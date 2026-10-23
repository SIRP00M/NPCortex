"""C-F1 D+ staging-only SQLite projection/outbox contract.

NOT mounted on HTTP routes or live agent_runtime. Does not modify the v4
world_store schema version, legacy action rows, or Minecraft world storage.
SQLite commit is not a distributed atomic commit with a Forge world save.
"""
from pathlib import Path
from contextlib import contextmanager
import sqlite3
from uuid import UUID


class CPlusF1Conflict(ValueError):
    pass


class CPlusF1Projection:
    def __init__(self, database_path):
        self.path = Path(database_path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self.connection() as db:
            db.executescript("""
                CREATE TABLE IF NOT EXISTS cplus_f1_events (
                    event_id TEXT PRIMARY KEY,
                    world_id TEXT NOT NULL,
                    actor_id TEXT NOT NULL,
                    task_id TEXT NOT NULL,
                    action_id TEXT NOT NULL,
                    attempt_id TEXT NOT NULL,
                    parent_event_id TEXT,
                    epoch INTEGER NOT NULL CHECK(epoch > 0),
                    stage TEXT NOT NULL,
                    fingerprint TEXT NOT NULL,
                    evidence_id TEXT NOT NULL DEFAULT ''
                );
                CREATE INDEX IF NOT EXISTS cplus_f1_actor_action
                    ON cplus_f1_events(world_id, actor_id, action_id);
                CREATE TABLE IF NOT EXISTS cplus_f1_outbox (
                    event_id TEXT PRIMARY KEY REFERENCES cplus_f1_events(event_id),
                    state TEXT NOT NULL DEFAULT 'PENDING'
                        CHECK(state IN ('PENDING','ACKED')),
                    receipt_id TEXT NOT NULL DEFAULT ''
                );
            """)

    @contextmanager
    def connection(self):
        db = sqlite3.connect(self.path, timeout=5.0)
        db.execute("PRAGMA foreign_keys=ON")
        db.execute("PRAGMA busy_timeout=5000")
        db.execute("PRAGMA journal_mode=WAL")
        db.execute("PRAGMA synchronous=FULL")
        try:
            with db:
                yield db
        finally:
            db.close()

    @staticmethod
    def _validate(event):
        required = {"event_id", "world_id", "actor_id", "task_id", "action_id",
                    "attempt_id", "parent_event_id", "epoch", "stage", "fingerprint", "evidence_id"}
        if not isinstance(event, dict) or set(event) != required:
            raise CPlusF1Conflict("unrecognized C-F1 event schema")
        for field in ("event_id", "world_id", "actor_id", "task_id", "action_id", "attempt_id"):
            try:
                if str(UUID(event[field])) != event[field] or UUID(event[field]).int == 0:
                    raise ValueError()
            except (ValueError, TypeError, AttributeError):
                raise CPlusF1Conflict(f"invalid {field}") from None
        parent = event["parent_event_id"]
        if parent is not None:
            try:
                if str(UUID(parent)) != parent or UUID(parent).int == 0:
                    raise ValueError()
            except (ValueError, TypeError, AttributeError):
                raise CPlusF1Conflict("invalid parent event id") from None
        if event["action_id"] == event["attempt_id"]:
            raise CPlusF1Conflict("action/attempt collision")
        if type(event["epoch"]) is not int or event["epoch"] < 1:
            raise CPlusF1Conflict("invalid epoch")
        import re
        if not isinstance(event["fingerprint"], str) or not re.fullmatch(r"[0-9a-f]{64}", event["fingerprint"]):
            raise CPlusF1Conflict("invalid fingerprint")
        stages = {"FENCE", "INTENT", "EXECUTION_STARTED", "OBSERVED_PARTIAL", "OBSERVED_EXTERNAL",
                  "IN_DOUBT", "RECONCILED", "VERIFIED", "CANCELLED"}
        if event["stage"] not in stages or not isinstance(event["evidence_id"], str) or len(event["evidence_id"]) > 160:
            raise CPlusF1Conflict("invalid stage/evidence")
        if event["stage"] in {"OBSERVED_PARTIAL", "OBSERVED_EXTERNAL", "RECONCILED", "VERIFIED", "CANCELLED"} and not event["evidence_id"].strip():
            raise CPlusF1Conflict("proof identifier required")
        if event["stage"] == "FENCE" and event["evidence_id"]:
            raise CPlusF1Conflict("fence does not prove an effect")

    def record(self, event):
        """Idempotent event projection; 1st insert outbox row in SAME SQLite transaction.

        No request delivered from Forge is allowed to execute a world effect here.
        """
        self._validate(event)
        fields = ("event_id", "world_id", "actor_id", "task_id", "action_id", "attempt_id",
                  "parent_event_id", "epoch", "stage", "fingerprint", "evidence_id")
        with self.connection() as db:
            db.execute("BEGIN IMMEDIATE")
            existing = db.execute("SELECT " + ",".join(fields) + " FROM cplus_f1_events WHERE event_id=?",
                                  (event["event_id"],)).fetchone()
            current = tuple(event[k] for k in fields)
            if existing is not None:
                if tuple(existing) != current:
                    raise CPlusF1Conflict("event id reused with different content")
                return False
            epoch_row = db.execute("SELECT MAX(epoch) FROM cplus_f1_events WHERE actor_id=?",
                                   (event["actor_id"],)).fetchone()
            latest_epoch = epoch_row[0]
            if event["stage"] == "FENCE":
                if latest_epoch is not None and event["epoch"] <= latest_epoch:
                    raise CPlusF1Conflict("stale session fence")
            elif latest_epoch is None or event["epoch"] != latest_epoch:
                raise CPlusF1Conflict("missing/stale session fence")
            previous = db.execute("SELECT stage,world_id,actor_id,task_id,attempt_id,fingerprint,epoch "
                                  "FROM cplus_f1_events WHERE action_id=? AND stage!='FENCE' "
                                  "ORDER BY rowid DESC LIMIT 1", (event["action_id"],)).fetchone()
            if event["stage"] == "INTENT" and previous is not None:
                raise CPlusF1Conflict("reused action ID")
            if event["stage"] not in ("FENCE", "INTENT"):
                if previous is None:
                    raise CPlusF1Conflict("effect without durable intent")
                prior = previous[0]
                if prior in ("VERIFIED", "CANCELLED"):
                    raise CPlusF1Conflict("terminal history immutable")
                if event["stage"] == "EXECUTION_STARTED" and (prior != "INTENT" or previous[6] != event["epoch"]):
                    raise CPlusF1Conflict("execution restart rejected")
                if event["stage"] == "CANCELLED" and prior != "INTENT":
                    raise CPlusF1Conflict("cancel is not undo")
                if event["stage"] == "RECONCILED" and prior not in ("IN_DOUBT", "OBSERVED_PARTIAL", "OBSERVED_EXTERNAL"):
                    raise CPlusF1Conflict("reconciliation without uncertain observation")
                if event["stage"] == "VERIFIED" and prior not in ("RECONCILED", "OBSERVED_PARTIAL", "OBSERVED_EXTERNAL"):
                    raise CPlusF1Conflict("unverified world success")
            collisions = db.execute("SELECT world_id,actor_id,task_id,attempt_id,fingerprint "
                                    "FROM cplus_f1_events WHERE action_id=? AND stage!='FENCE' LIMIT 1",
                                    (event["action_id"],)).fetchone()
            identity = tuple(event[k] for k in ("world_id", "actor_id", "task_id", "attempt_id", "fingerprint"))
            if collisions is not None and tuple(collisions) != identity:
                raise CPlusF1Conflict("action ID owner/fingerprint conflict")
            db.execute("INSERT INTO cplus_f1_events(" + ",".join(fields) + ") VALUES (" + ",".join("?" for _ in fields) + ")", current)
            db.execute("INSERT INTO cplus_f1_outbox(event_id) VALUES (?)", (event["event_id"],))
        return True

    def pending_outbox(self, limit=100):
        if type(limit) is not int or not 1 <= limit <= 1000:
            raise ValueError("bounded outbox read required")
        with self.connection() as db:
            return [r[0] for r in db.execute("SELECT event_id FROM cplus_f1_outbox WHERE state='PENDING' ORDER BY rowid LIMIT ?", (limit,))]

    def acknowledge(self, event_id, receipt_id):
        if not isinstance(receipt_id, str) or not receipt_id.strip() or len(receipt_id) > 160:
            raise CPlusF1Conflict("invalid durable ACK reference")
        with self.connection() as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute("SELECT state,receipt_id FROM cplus_f1_outbox WHERE event_id=?", (event_id,)).fetchone()
            if row is None:
                raise CPlusF1Conflict("unknown ACK event")
            if row[0] == "ACKED":
                if row[1] != receipt_id:
                    raise CPlusF1Conflict("contradictory ACK")
                return False
            db.execute("UPDATE cplus_f1_outbox SET state='ACKED',receipt_id=? WHERE event_id=?", (receipt_id,event_id))
            return True
