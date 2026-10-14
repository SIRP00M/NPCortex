"""B2 explicit multi-participant dialogue; no autonomous tick or world actions.

Each turn is human-requested. The model generates ONE requested actor's speech.
All speakers see the SAME dialogue-local transcript, never private B1 histories.
SQLite persists durable shared turns; caller-supplied request IDs permit safe retry.
"""
from uuid import uuid4

from .identity import canonical_uuid, dimension_id, player_id, IdentityError
from .world_store import RegistryConflictError, RegistryNotFoundError, WorldStore

MAX_PARTICIPANTS = 8
MIN_TURNS = 2
MAX_TURNS = 64


class DialogueStore:
    def __init__(self, world_store: WorldStore):
        self.store = world_store

    def create(self, world_id: str, dimension: str, actor_ids: list, player_ids: list,
               topic: str, max_turns: int = 24) -> dict:
        wid = canonical_uuid(world_id, "world_id")
        dim = dimension_id(dimension)
        if not isinstance(actor_ids, list) or not isinstance(player_ids, list):
            raise IdentityError("actor_ids and player_ids must be arrays")
        actors = [canonical_uuid(a, "actor_id") for a in actor_ids]
        players = [player_id(p) for p in player_ids]
        if (not actors or not 2 <= len(actors) + len(players) <= MAX_PARTICIPANTS
                or len(set(actors)) != len(actors) or len(set(players)) != len(players)):
            raise IdentityError("Dialogue needs 2-8 distinct participants with at least one actor")
        if not isinstance(topic, str) or not 1 <= len(topic.strip()) <= 200:
            raise IdentityError("topic must be 1-200 characters")
        if type(max_turns) is not int or not MIN_TURNS <= max_turns <= MAX_TURNS:
            raise IdentityError("max_turns must be an integer from 2 to 64")
        did = str(uuid4())
        with self.store.database.write() as db:
            if not db.execute("SELECT 1 FROM worlds WHERE world_id=?", (wid,)).fetchone():
                raise RegistryNotFoundError("World is not registered")
            # Every NPC belongs to this exact world and dimension.
            for aid in actors:
                record = db.execute("SELECT world_id,dimension_id FROM actors WHERE actor_id=?", (aid,)).fetchone()
                if record is None:
                    raise RegistryNotFoundError(f"Actor {aid} is not registered")
                if (record["world_id"], record["dimension_id"]) != (wid, dim):
                    raise RegistryConflictError("Dialogue actor belongs to a different world/dimension")
            db.execute("INSERT INTO dialogues(dialogue_id,world_id,dimension_id,topic,max_turns) "
                       "VALUES (?,?,?,?,?)", (did, wid, dim, topic.strip(), max_turns))
            db.executemany("INSERT INTO dialogue_participants(dialogue_id,speaker_type,speaker_id) "
                           "VALUES (?,?,?)", [(did, "actor", aid) for aid in actors] +
                           [(did, "player", pid) for pid in players])
        return self.get(did)

    def get(self, dialogue_id: str) -> dict:
        did = canonical_uuid(dialogue_id, "dialogue_id")
        with self.store.database.read() as db:
            row = db.execute("SELECT * FROM dialogues WHERE dialogue_id=?", (did,)).fetchone()
            if row is None:
                raise RegistryNotFoundError("Dialogue does not exist")
            participants = db.execute(
                "SELECT p.speaker_type, p.speaker_id, a.character_id "
                "FROM dialogue_participants p LEFT JOIN actors a "
                "ON p.speaker_type='actor' AND p.speaker_id=a.actor_id "
                "WHERE p.dialogue_id=? ORDER BY p.rowid", (did,)).fetchall()
            turns = db.execute("SELECT seq,request_id,speaker_type,speaker_id,content "
                               "FROM dialogue_turns WHERE dialogue_id=? ORDER BY seq", (did,)).fetchall()
        return {**dict(row), "participants": [dict(p) for p in participants],
                "turns": [dict(t) for t in turns], "turns_remaining": row["max_turns"] - len(turns)}

    def get_request(self, dialogue_id: str, request_id: str):
        did = canonical_uuid(dialogue_id, "dialogue_id")
        rid = canonical_uuid(request_id, "request_id")
        with self.store.database.read() as db:
            row = db.execute("SELECT seq,request_id,speaker_type,speaker_id,content "
                             "FROM dialogue_turns WHERE dialogue_id=? AND request_id=?", (did, rid)).fetchone()
        return dict(row) if row else None

    def append(self, dialogue_id: str, request_id: str, speaker_type: str,
               speaker_id: str, content: str, expected_seq: int) -> dict:
        did = canonical_uuid(dialogue_id, "dialogue_id")
        rid = canonical_uuid(request_id, "request_id")
        kind, sid = self._speaker(speaker_type, speaker_id)
        if not isinstance(content, str) or not 1 <= len(content.strip()) <= 2000:
            raise IdentityError("Turn content must be 1-2000 characters")
        with self.store.database.write() as db:
            duplicate = db.execute("SELECT seq,request_id,speaker_type,speaker_id,content "
                                   "FROM dialogue_turns WHERE dialogue_id=? AND request_id=?", (did, rid)).fetchone()
            if duplicate:
                if duplicate["speaker_type"] != kind or duplicate["speaker_id"] != sid or (kind == "player" and duplicate["content"] != content):
                    raise RegistryConflictError("request_id already used for a different turn")
                return dict(duplicate)
            meta = db.execute("SELECT status,max_turns FROM dialogues WHERE dialogue_id=?", (did,)).fetchone()
            if meta is None:
                raise RegistryNotFoundError("Dialogue does not exist")
            if meta["status"] != "open":
                raise RegistryConflictError("Dialogue is closed")
            member = db.execute("SELECT 1 FROM dialogue_participants WHERE dialogue_id=? AND "
                                "speaker_type=? AND speaker_id=?", (did, kind, sid)).fetchone()
            if not member:
                raise RegistryConflictError("Speaker is not a dialogue participant")
            last = db.execute("SELECT seq,speaker_type,speaker_id FROM dialogue_turns "
                              "WHERE dialogue_id=? ORDER BY seq DESC LIMIT 1", (did,)).fetchone()
            seq = last["seq"] if last else 0
            if seq != expected_seq:
                raise RegistryConflictError("Dialogue changed during turn; retry with a new request")
            if seq >= meta["max_turns"]:
                raise RegistryConflictError("Dialogue turn limit reached")
            if last is not None and (last["speaker_type"], last["speaker_id"]) == (kind, sid):
                raise RegistryConflictError("The same participant cannot speak in consecutive turns")
            db.execute("INSERT INTO dialogue_turns(dialogue_id,seq,request_id,speaker_type,speaker_id,content) "
                       "VALUES (?,?,?,?,?,?)", (did, seq + 1, rid, kind, sid, content.strip()))
            if seq + 1 >= meta["max_turns"]:
                db.execute("UPDATE dialogues SET status='closed' WHERE dialogue_id=?", (did,))
        return {"seq": seq + 1, "request_id": rid, "speaker_type": kind,
                "speaker_id": sid, "content": content.strip()}

    def close(self, dialogue_id: str) -> dict:
        did = canonical_uuid(dialogue_id, "dialogue_id")
        with self.store.database.write() as db:
            changed = db.execute("UPDATE dialogues SET status='closed' WHERE dialogue_id=?", (did,))
            if changed.rowcount == 0:
                raise RegistryNotFoundError("Dialogue does not exist")
        return self.get(did)

    @staticmethod
    def _speaker(speaker_type: object, speaker_id: object) -> tuple[str, str]:
        if speaker_type == "actor":
            return "actor", canonical_uuid(speaker_id, "speaker_id")
        if speaker_type == "player":
            return "player", player_id(speaker_id)
        raise IdentityError("speaker_type must be 'actor' or 'player'")
