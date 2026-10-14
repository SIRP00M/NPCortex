"""B1 local SQLite world/actor registry and bounded durable player-to-actor transcripts.

Single-process localhost backend only. No world simulation, behavior or NPC-NPC turns.
Every operation opens an independent SQLite connection for ThreadingHTTPServer safety.
"""
from contextlib import contextmanager
from pathlib import Path
import sqlite3
from uuid import uuid4, uuid5, UUID
from .identity import canonical_uuid, dimension_id, player_id, world_name

# Namespace is a fixed stable value; changing it would break derived conversation IDs.
CONVERSATION_NAMESPACE = UUID("4bc93e61-ac2d-4c7a-94cf-4cfc4a3a59e1")
SCHEMA_VERSION = 1


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
        with self._connect() as db:
            # SQLite WAL supports concurrent readers and one serialized writer.
            db.execute("PRAGMA journal_mode=WAL")
            version = db.execute("PRAGMA user_version").fetchone()[0]
            if version != 0 and version != SCHEMA_VERSION:
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
            # Reject DBs that claim v1 but do not actually contain the schema.
            for table in ("worlds", "actors", "conversations", "transcript"):
                db.execute(f"SELECT * FROM {table} LIMIT 0")

    @contextmanager
    def _connect(self):
        db = sqlite3.connect(str(self.db_path), timeout=5)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA foreign_keys=ON")
        db.execute("PRAGMA busy_timeout=5000")
        try:
            yield db
            db.commit()
        except BaseException:
            db.rollback()
            raise
        finally:
            db.close()

    def register_world(self, name: str, world_id: str | None = None) -> dict:
        name = world_name(name)
        wid = canonical_uuid(world_id, "world_id") if world_id is not None else str(uuid4())
        with self._connect() as db:
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
        with self._connect() as db:
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
        with self._connect() as db:
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
        with self._connect() as db:
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
        with self._connect() as db:
            rows = db.execute("SELECT role,content FROM transcript WHERE conversation_id=? "
                              "ORDER BY seq DESC LIMIT ?", (cid, max_messages)).fetchall()
        return [dict(row) for row in reversed(rows)]

    def append_exchange(self, conversation_id: str, user_text: str, assistant_text: str) -> None:
        cid = canonical_uuid(conversation_id, "conversation_id")
        with self._connect() as db:
            # Ensure sequence allocation + both messages + pruning happen atomically.
            db.execute("BEGIN IMMEDIATE")
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
        with self._connect() as db:
            row = db.execute("SELECT * FROM conversations WHERE conversation_id=?", (cid,)).fetchone()
        if row is None:
            raise RegistryNotFoundError("Conversation does not exist")
        return {**dict(row), "messages": self.history(cid, self.max_transcript_messages)}
