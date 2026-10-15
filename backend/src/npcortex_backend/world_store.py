"""Durable world/actor registry and B1 player-to-actor transcripts.

B2-C: all operational SQL runs through explicit short-lived SQLite units of
work; B1 tables and the additive B2 schema remain unchanged.
"""
from pathlib import Path
import sqlite3
from .persistence import SQLiteDatabase
from uuid import uuid4, uuid5, UUID
from .identity import canonical_uuid, dimension_id, player_id, world_name

# Namespace is a fixed stable value; changing it would break derived conversation IDs.
CONVERSATION_NAMESPACE = UUID("4bc93e61-ac2d-4c7a-94cf-4cfc4a3a59e1")
SCHEMA_VERSION = 4


class RegistryNotFoundError(LookupError):
    pass


class RegistryConflictError(ValueError):
    pass


class SchemaVersionError(RuntimeError):
    pass


class WorldStore:
    def __init__(self, db_path: Path, max_transcript_messages: int = 200):
        if (not isinstance(max_transcript_messages, int) or isinstance(max_transcript_messages, bool)
                or max_transcript_messages < 2 or max_transcript_messages % 2):
            raise ValueError("max_transcript_messages must be an even integer >= 2")
        self.db_path = Path(db_path)
        self.max_transcript_messages = max_transcript_messages
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        self.database = SQLiteDatabase(self.db_path)
        with self.database.maintenance() as db:
            # SQLite WAL supports concurrent readers and one serialized writer.
            db.execute("PRAGMA journal_mode=WAL")
            version = db.execute("PRAGMA user_version").fetchone()[0]
            if version not in (0, 1, 2, 3, SCHEMA_VERSION):
                raise SchemaVersionError(
                    f"Unsupported SQLite schema version {version}; expected {SCHEMA_VERSION}. "
                    "Do not downgrade or delete a database containing player data."
                )
            if version == 0:
                # Atomic initial schema migration, including PRAGMA user_version.
                db.executescript("""
                    BEGIN IMMEDIATE;
                    CREATE TABLE worlds (
                        world_id TEXT PRIMARY KEY,
                        name TEXT NOT NULL
                    );
                    CREATE TABLE actors (
                        actor_id TEXT PRIMARY KEY,
                        world_id TEXT NOT NULL REFERENCES worlds(world_id),
                        dimension_id TEXT NOT NULL,
                        character_id TEXT NOT NULL
                    );
                    CREATE INDEX idx_actors_world ON actors(world_id, dimension_id);
                    CREATE TABLE conversations (
                        conversation_id TEXT PRIMARY KEY,
                        world_id TEXT NOT NULL REFERENCES worlds(world_id),
                        actor_id TEXT NOT NULL REFERENCES actors(actor_id),
                        player_id TEXT NOT NULL,
                        UNIQUE(world_id, actor_id, player_id)
                    );
                    CREATE TABLE transcript (
                        conversation_id TEXT NOT NULL REFERENCES conversations(conversation_id),
                        seq INTEGER NOT NULL,
                        role TEXT NOT NULL CHECK(role IN ('user', 'assistant')),
                        content TEXT NOT NULL,
                        PRIMARY KEY(conversation_id, seq)
                    );
                    PRAGMA user_version=1;
                    COMMIT;
                """)
            # Validate B1 schema before applying B2. A corrupted B1 database must
            # never be marked as successfully migrated.
            required_b1 = {
                "worlds": {"world_id", "name"},
                "actors": {"actor_id", "world_id", "dimension_id", "character_id"},
                "conversations": {"conversation_id", "world_id", "actor_id", "player_id"},
                "transcript": {"conversation_id", "seq", "role", "content"},
            }
            for table, columns in required_b1.items():
                db.execute(f"SELECT * FROM {table} LIMIT 0")
                actual = {row[1] for row in db.execute(f"PRAGMA table_info({table})")}
                if not columns.issubset(actual):
                    raise sqlite3.DatabaseError(f"Invalid B1 schema: {table} missing columns")
            if version in (0, 1):
                # Additive, atomic upgrade: no B1 table or transcript is rewritten.
                # An interrupted migration rolls back to a usable v1 database.
                db.executescript("""
                    BEGIN IMMEDIATE;
                    CREATE TABLE dialogues (
                        dialogue_id TEXT PRIMARY KEY,
                        world_id TEXT NOT NULL REFERENCES worlds(world_id),
                        dimension_id TEXT NOT NULL,
                        topic TEXT NOT NULL,
                        max_turns INTEGER NOT NULL CHECK(max_turns BETWEEN 2 AND 64),
                        status TEXT NOT NULL DEFAULT 'open' CHECK(status IN ('open', 'closed'))
                    );
                    CREATE TABLE dialogue_participants (
                        dialogue_id TEXT NOT NULL REFERENCES dialogues(dialogue_id),
                        speaker_type TEXT NOT NULL CHECK(speaker_type IN ('actor', 'player')),
                        speaker_id TEXT NOT NULL,
                        PRIMARY KEY(dialogue_id, speaker_type, speaker_id)
                    );
                    CREATE TABLE dialogue_turns (
                        dialogue_id TEXT NOT NULL REFERENCES dialogues(dialogue_id),
                        seq INTEGER NOT NULL,
                        request_id TEXT NOT NULL,
                        speaker_type TEXT NOT NULL CHECK(speaker_type IN ('actor', 'player')),
                        speaker_id TEXT NOT NULL,
                        content TEXT NOT NULL,
                        PRIMARY KEY(dialogue_id, seq),
                        UNIQUE(dialogue_id, request_id),
                        FOREIGN KEY(dialogue_id, speaker_type, speaker_id)
                            REFERENCES dialogue_participants(dialogue_id, speaker_type, speaker_id)
                    );
                    CREATE INDEX idx_dialogue_turns ON dialogue_turns(dialogue_id, seq);
                    PRAGMA user_version=2;
                    COMMIT;
                """)
            required_b2 = {
                "dialogues": {"dialogue_id", "world_id", "dimension_id", "topic", "max_turns", "status"},
                "dialogue_participants": {"dialogue_id", "speaker_type", "speaker_id"},
                "dialogue_turns": {"dialogue_id", "seq", "request_id", "speaker_type", "speaker_id", "content"},
            }
            for table, columns in required_b2.items():
                db.execute(f"SELECT * FROM {table} LIMIT 0")
                actual = {row[1] for row in db.execute(f"PRAGMA table_info({table})")}
                if not columns.issubset(actual):
                    raise sqlite3.DatabaseError(f"Invalid B2 schema: {table} missing columns")
            if version in (0, 1, 2):
                # B3 extends B2 without rewriting worlds, actors, legacy transcripts
                # or any existing dialogue/turn. An interrupted migration rolls back.
                db.executescript("""
                    BEGIN IMMEDIATE;
                    CREATE TABLE social_runs (
                        run_id TEXT PRIMARY KEY,
                        dialogue_id TEXT NOT NULL UNIQUE REFERENCES dialogues(dialogue_id),
                        max_steps INTEGER NOT NULL CHECK(max_steps BETWEEN 2 AND 24),
                        status TEXT NOT NULL DEFAULT 'active'
                            CHECK(status IN ('active','completed','stopped','failed')),
                        consecutive_failures INTEGER NOT NULL DEFAULT 0,
                        stop_reason TEXT NOT NULL DEFAULT ''
                    );
                    CREATE TABLE social_steps (
                        run_id TEXT NOT NULL REFERENCES social_runs(run_id),
                        request_id TEXT NOT NULL,
                        action TEXT NOT NULL CHECK(action IN ('speak','stop')),
                        speaker_id TEXT,
                        dialogue_seq INTEGER,
                        reason TEXT NOT NULL DEFAULT '',
                        PRIMARY KEY(run_id, request_id),
                        CHECK((action='speak' AND speaker_id IS NOT NULL AND dialogue_seq IS NOT NULL)
                          OR (action='stop' AND speaker_id IS NULL AND dialogue_seq IS NULL))
                    );
                    CREATE UNIQUE INDEX idx_social_step_seq
                        ON social_steps(run_id, dialogue_seq) WHERE dialogue_seq IS NOT NULL;
                    PRAGMA user_version=3;
                    COMMIT;
                """)
            required_b3 = {
                "social_runs": {"run_id", "dialogue_id", "max_steps", "status", "consecutive_failures", "stop_reason"},
                "social_steps": {"run_id", "request_id", "action", "speaker_id", "dialogue_seq", "reason"},
            }
            for table, columns in required_b3.items():
                db.execute(f"SELECT * FROM {table} LIMIT 0")
                actual = {row[1] for row in db.execute(f"PRAGMA table_info({table})")}
                if not columns.issubset(actual):
                    raise sqlite3.DatabaseError(f"Invalid B3 schema: {table} missing columns")
            if version in (0, 1, 2, 3):
                # C0 is additive. Every legacy B1/B2/B3 table and row remains intact.
                db.executescript("""
                    BEGIN IMMEDIATE;
                    CREATE TABLE agent_goals (
                        goal_id TEXT PRIMARY KEY,
                        world_id TEXT NOT NULL REFERENCES worlds(world_id),
                        dimension_id TEXT NOT NULL,
                        actor_id TEXT NOT NULL REFERENCES actors(actor_id),
                        objective TEXT NOT NULL,
                        criteria_json TEXT NOT NULL,
                        max_decisions INTEGER NOT NULL CHECK(max_decisions BETWEEN 1 AND 24),
                        decisions_made INTEGER NOT NULL DEFAULT 0,
                        planner_failures INTEGER NOT NULL DEFAULT 0,
                        status TEXT NOT NULL DEFAULT 'pending'
                            CHECK(status IN ('pending','active','blocked','completed','failed')),
                        pending_action_id TEXT,
                        snapshot_seq INTEGER NOT NULL DEFAULT 0,
                        snapshot_json TEXT,
                        last_error TEXT NOT NULL DEFAULT ''
                    );
                    CREATE INDEX idx_agent_goals_actor ON agent_goals(actor_id,status);
                    CREATE TABLE agent_observations (
                        goal_id TEXT NOT NULL REFERENCES agent_goals(goal_id),
                        observation_id TEXT NOT NULL,
                        seq INTEGER NOT NULL,
                        snapshot_json TEXT NOT NULL,
                        PRIMARY KEY(goal_id,observation_id),
                        UNIQUE(goal_id,seq)
                    );
                    CREATE TABLE agent_decisions (
                        goal_id TEXT NOT NULL REFERENCES agent_goals(goal_id),
                        request_id TEXT NOT NULL,
                        decision_json TEXT NOT NULL,
                        action_id TEXT,
                        PRIMARY KEY(goal_id,request_id)
                    );
                    CREATE TABLE agent_actions (
                        action_id TEXT PRIMARY KEY,
                        goal_id TEXT NOT NULL REFERENCES agent_goals(goal_id),
                        request_id TEXT NOT NULL,
                        skill_id TEXT NOT NULL,
                        args_json TEXT NOT NULL,
                        expected_json TEXT NOT NULL,
                        before_json TEXT NOT NULL,
                        source_seq INTEGER NOT NULL,
                        status TEXT NOT NULL DEFAULT 'pending'
                            CHECK(status IN ('pending','succeeded','failed','unverified')),
                        result_id TEXT,
                        receipt_json TEXT,
                        verdict TEXT NOT NULL DEFAULT '',
                        UNIQUE(goal_id,request_id)
                    );
                    CREATE INDEX idx_agent_actions_goal ON agent_actions(goal_id);
                    PRAGMA user_version=4;
                    COMMIT;
                """)
            required_c0 = {
                "agent_goals": {"goal_id", "world_id", "dimension_id", "actor_id", "criteria_json", "pending_action_id", "snapshot_seq", "planner_failures"},
                "agent_observations": {"goal_id", "observation_id", "seq", "snapshot_json"},
                "agent_decisions": {"goal_id", "request_id", "decision_json", "action_id"},
                "agent_actions": {"action_id", "goal_id", "request_id", "skill_id", "source_seq", "receipt_json"},
            }
            for table, columns in required_c0.items():
                db.execute(f"SELECT * FROM {table} LIMIT 0")
                actual = {row[1] for row in db.execute(f"PRAGMA table_info({table})")}
                if not columns.issubset(actual):
                    raise sqlite3.DatabaseError(f"Invalid C0 schema: {table} missing columns")

    def register_world(self, name: str, world_id: str | None = None) -> dict:
        name = world_name(name)
        wid = canonical_uuid(world_id, "world_id") if world_id is not None else str(uuid4())
        with self.database.write() as db:
            db.execute("INSERT OR IGNORE INTO worlds(world_id,name) VALUES (?,?)", (wid, name))
            record = db.execute("SELECT name FROM worlds WHERE world_id=?", (wid,)).fetchone()
            if record["name"] != name:
                raise RegistryConflictError("world_id already registered with a different name")
        return {"world_id": wid, "name": name}

    def register_actor(self, world_id: str, dimension: str, character_id: str,
                       actor_id: str | None = None) -> dict:
        wid = canonical_uuid(world_id, "world_id")
        dim = dimension_id(dimension)
        aid = canonical_uuid(actor_id, "actor_id") if actor_id is not None else str(uuid4())
        with self.database.write() as db:
            if db.execute("SELECT 1 FROM worlds WHERE world_id=?", (wid,)).fetchone() is None:
                raise RegistryNotFoundError("World is not registered")
            db.execute("INSERT OR IGNORE INTO actors(actor_id,world_id,dimension_id,character_id) VALUES (?,?,?,?)",
                       (aid, wid, dim, character_id))
            record = db.execute("SELECT * FROM actors WHERE actor_id=?", (aid,)).fetchone()
            if (record["world_id"], record["dimension_id"], record["character_id"]) != (wid, dim, character_id):
                raise RegistryConflictError("actor_id already registered with different identity")
        return {"actor_id": aid, "world_id": wid, "dimension_id": dim,
                "character_id": character_id}

    def get_actor(self, actor_id: str) -> dict:
        aid = canonical_uuid(actor_id, "actor_id")
        with self.database.read() as db:
            record = db.execute("SELECT * FROM actors WHERE actor_id=?", (aid,)).fetchone()
        if record is None:
            raise RegistryNotFoundError("Actor not registered")
        return dict(record)

    @staticmethod
    def conversation_id(world_id: str, actor_id: str, participant: str) -> str:
        return str(uuid5(CONVERSATION_NAMESPACE, f"{world_id}/{actor_id}/{participant}"))

    def conversation_for(self, world_id: str, actor_id: str, participant: str,
                         dimension: str) -> str:
        """Bind the player/actor dialogue to exactly one world and dimension."""
        wid = canonical_uuid(world_id, "world_id")
        aid = canonical_uuid(actor_id, "actor_id")
        pid = player_id(participant)
        dim = dimension_id(dimension)
        cid = self.conversation_id(wid, aid, pid)
        with self.database.write() as db:
            actor = db.execute("SELECT world_id,dimension_id FROM actors WHERE actor_id=?", (aid,)).fetchone()
            if actor is None:
                raise RegistryNotFoundError("Actor not registered")
            if actor["world_id"] != wid or actor["dimension_id"] != dim:
                raise RegistryConflictError("Actor does not belong to the requested world/dimension")
            db.execute("INSERT OR IGNORE INTO conversations(conversation_id,world_id,actor_id,player_id) "
                       "VALUES (?,?,?,?)", (cid, wid, aid, pid))
        return cid

    def history(self, conversation_id: str, max_messages: int = 12) -> list[dict[str, str]]:
        cid = canonical_uuid(conversation_id, "conversation_id")
        with self.database.read() as db:
            rows = db.execute("SELECT role,content FROM transcript WHERE conversation_id=? "
                              "ORDER BY seq DESC LIMIT ?", (cid, max_messages)).fetchall()
        return [dict(row) for row in reversed(rows)]

    def append_exchange(self, conversation_id: str, user_text: str, assistant_text: str) -> None:
        cid = canonical_uuid(conversation_id, "conversation_id")
        with self.database.write() as db:
            # Ensure sequence allocation + both messages + pruning happen atomically.
            row = db.execute("SELECT 1 FROM conversations WHERE conversation_id=?", (cid,)).fetchone()
            if row is None:
                raise RegistryNotFoundError("Conversation does not exist")
            seq = db.execute("SELECT COALESCE(MAX(seq),0) FROM transcript WHERE conversation_id=?",
                             (cid,)).fetchone()[0]
            db.executemany("INSERT INTO transcript(conversation_id,seq,role,content) VALUES (?,?,?,?)", [
                (cid, seq + 1, "user", user_text), (cid, seq + 2, "assistant", assistant_text)
            ])
            cutoff = seq + 2 - self.max_transcript_messages
            if cutoff > 0:
                db.execute("DELETE FROM transcript WHERE conversation_id=? AND seq<=?", (cid, cutoff))

    def get_conversation(self, conversation_id: str) -> dict:
        cid = canonical_uuid(conversation_id, "conversation_id")
        with self.database.read() as db:
            row = db.execute("SELECT * FROM conversations WHERE conversation_id=?", (cid,)).fetchone()
            if row is None:
                raise RegistryNotFoundError("Conversation does not exist")
            messages = db.execute(
                "SELECT role,content FROM transcript WHERE conversation_id=? "
                "ORDER BY seq DESC LIMIT ?", (cid, self.max_transcript_messages)
            ).fetchall()
            return {**dict(row), "messages": [dict(m) for m in reversed(messages)]}
