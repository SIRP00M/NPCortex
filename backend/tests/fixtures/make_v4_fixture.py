"""One-off generator for tests/fixtures/v4_baseline.sqlite3, run against the PRE-v5 code (G0).

Kept for provenance only: after the v5 change this script would produce a v5 database.
"""
import hashlib, json, sqlite3, sys
from pathlib import Path
from unittest.mock import Mock

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "backend" / "src"))
from npcortex_backend.service import ChatService
from npcortex_backend.session import SessionStore
from npcortex_backend.world_store import WorldStore

out = Path(__file__).with_name("v4_baseline.sqlite3")
for suffix in ("", "-wal", "-shm"):
    Path(str(out) + suffix).unlink(missing_ok=True)
provider = Mock(); provider.chat.return_value = "fixture reply"
store = WorldStore(out)
svc = ChatService(ROOT / "character-packs", "qwen3:8b", provider, SessionStore(), world_store=store)
FIXED = {"world": "11111111-1111-4111-8111-111111111111",
         "yui": "22222222-2222-4222-8222-222222222222",
         "guide": "33333333-3333-4333-8333-333333333333"}
svc.register_world("V4 Fixture World", FIXED["world"])
svc.register_actor(FIXED["world"], "minecraft:overworld", "yui", FIXED["yui"])
svc.register_actor(FIXED["world"], "minecraft:overworld", "guide", FIXED["guide"])
svc.chat_actor(FIXED["world"], "minecraft:overworld", FIXED["yui"], "player-1", "Hello Yui")
svc.create_dialogue(FIXED["world"], "minecraft:overworld", [FIXED["yui"], FIXED["guide"]], ["player-1"], "fixture topic")
db = sqlite3.connect(out); db.execute("PRAGMA wal_checkpoint(TRUNCATE)"); db.execute("PRAGMA journal_mode=DELETE")
print("user_version", db.execute("PRAGMA user_version").fetchone()[0]); db.close()
dump = "\n".join(sqlite3.connect(out).iterdump())
print("dump_sha256", hashlib.sha256(dump.encode()).hexdigest())
