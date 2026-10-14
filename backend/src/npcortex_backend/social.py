"""B3: durable, bounded HEADLESS social-run state. No world/sensor/actions.

A caller advances a run with explicit step requests. Qwen selects the next
speaker or stops; model inference is never performed inside SQLite transactions.
"""
from uuid import uuid4

from .identity import canonical_uuid, IdentityError
from .world_store import RegistryConflictError, RegistryNotFoundError

MAX_SOCIAL_STEPS = 24
MAX_FAILURES = 3


class SocialStore:
    def __init__(self, world_store):
        self.world_store = world_store

    def create(self, dialogue: dict, max_steps: int) -> dict:
        if type(max_steps) is not int or not 2 <= max_steps <= MAX_SOCIAL_STEPS:
            raise IdentityError("max_steps must be an integer from 2 to 24")
        did = canonical_uuid(dialogue["dialogue_id"], "dialogue_id")
        if dialogue["status"] != "open" or dialogue["turns"]:
            raise RegistryConflictError("Social run requires a fresh open dialogue")
        participants = dialogue["participants"]
        if len(participants) < 2 or any(p["speaker_type"] != "actor" for p in participants):
            raise RegistryConflictError("Headless social run requires at least two NPC actors and no players")
        if max_steps > dialogue["max_turns"]:
            raise IdentityError("max_steps cannot exceed dialogue max_turns")
        run_id = str(uuid4())
        with self.world_store.database.write() as db:
            # Revalidate in the transaction; only one B3 run owns a dialogue.
            current = db.execute("SELECT status FROM dialogues WHERE dialogue_id=?", (did,)).fetchone()
            has_turns = db.execute("SELECT 1 FROM dialogue_turns WHERE dialogue_id=? LIMIT 1", (did,)).fetchone()
            if current is None or current["status"] != "open" or has_turns:
                raise RegistryConflictError("Social dialogue changed before run registration")
            if db.execute("SELECT 1 FROM social_runs WHERE dialogue_id=?", (did,)).fetchone():
                raise RegistryConflictError("Dialogue already has a social run")
            db.execute("INSERT INTO social_runs(run_id,dialogue_id,max_steps) VALUES (?,?,?)",
                       (run_id, did, max_steps))
        return self.get(run_id)

    def get(self, run_id: str) -> dict:
        rid = canonical_uuid(run_id, "run_id")
        with self.world_store.database.read() as db:
            row = db.execute("SELECT * FROM social_runs WHERE run_id=?", (rid,)).fetchone()
            if row is None:
                raise RegistryNotFoundError("Social run not found")
            steps = db.execute("SELECT request_id,action,speaker_id,dialogue_seq,reason "
                               "FROM social_steps WHERE run_id=? ORDER BY rowid", (rid,)).fetchall()
        recorded = [dict(s) for s in steps]
        return {**dict(row), "steps": recorded,
                "steps_completed": sum(s["action"] == "speak" for s in recorded),
                "steps_remaining": row["max_steps"] - sum(s["action"] == "speak" for s in recorded)}

    def active_for_dialogue(self, dialogue_id: str) -> bool:
        did = canonical_uuid(dialogue_id, "dialogue_id")
        with self.world_store.database.read() as db:
            return db.execute("SELECT 1 FROM social_runs WHERE dialogue_id=? AND status='active'",
                              (did,)).fetchone() is not None

    def get_step(self, run_id: str, request_id: str):
        rid = canonical_uuid(run_id, "run_id")
        req = canonical_uuid(request_id, "request_id")
        with self.world_store.database.read() as db:
            row = db.execute("SELECT request_id,action,speaker_id,dialogue_seq,reason FROM social_steps "
                             "WHERE run_id=? AND request_id=?", (rid, req)).fetchone()
        return dict(row) if row else None

    def finish(self, run_id: str, request_id: str, *, action: str, speaker_id: str | None = None,
               dialogue_seq: int | None = None, reason: str = "", status: str | None = None) -> None:
        rid = canonical_uuid(run_id, "run_id")
        req = canonical_uuid(request_id, "request_id")
        if action not in ("speak", "stop"):
            raise ValueError("Invalid social action")
        with self.world_store.database.write() as db:
            existing = db.execute("SELECT 1 FROM social_steps WHERE run_id=? AND request_id=?",
                                  (rid, req)).fetchone()
            if existing:
                return
            db.execute("INSERT INTO social_steps(run_id,request_id,action,speaker_id,dialogue_seq,reason) "
                       "VALUES (?,?,?,?,?,?)", (rid, req, action, speaker_id, dialogue_seq, reason))
            if status is None:
                db.execute("UPDATE social_runs SET consecutive_failures=0 WHERE run_id=?", (rid,))
            else:
                db.execute("UPDATE social_runs SET status=?,stop_reason=?,consecutive_failures=0 "
                           "WHERE run_id=?", (status, reason, rid))
                db.execute("UPDATE dialogues SET status='closed' WHERE dialogue_id="
                           "(SELECT dialogue_id FROM social_runs WHERE run_id=?)", (rid,))

    def fail(self, run_id: str) -> dict:
        rid = canonical_uuid(run_id, "run_id")
        with self.world_store.database.write() as db:
            db.execute("UPDATE social_runs SET consecutive_failures=consecutive_failures+1 "
                       "WHERE run_id=? AND status='active'", (rid,))
            db.execute("UPDATE social_runs SET status='failed', stop_reason='model_failure_budget' "
                       "WHERE run_id=? AND status='active' AND consecutive_failures>=?", (rid, MAX_FAILURES))
            ended = db.execute("SELECT status FROM social_runs WHERE run_id=?", (rid,)).fetchone()
            if ended and ended["status"] == "failed":
                db.execute("UPDATE dialogues SET status='closed' WHERE dialogue_id="
                           "(SELECT dialogue_id FROM social_runs WHERE run_id=?)", (rid,))
        return self.get(rid)

    def stop(self, run_id: str, reason="operator_stop") -> dict:
        rid = canonical_uuid(run_id, "run_id")
        with self.world_store.database.write() as db:
            result = db.execute("UPDATE social_runs SET status='stopped', stop_reason=? "
                                "WHERE run_id=? AND status='active'", (reason, rid))
            if result.rowcount:
                db.execute("UPDATE dialogues SET status='closed' WHERE dialogue_id="
                           "(SELECT dialogue_id FROM social_runs WHERE run_id=?)", (rid,))
        return self.get(rid)
