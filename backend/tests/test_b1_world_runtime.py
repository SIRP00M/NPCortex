"""B1 regression: stable world/actor identity, SQLite persistence, HTTP, recovery."""
from concurrent.futures import ThreadPoolExecutor
from http.server import ThreadingHTTPServer
import json
from pathlib import Path
import os
import sqlite3
import sys
import tempfile
from threading import Event, Thread
import unittest
from sqlite_test_utils import sqlite_test_connection
from unittest.mock import Mock, patch
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "backend" / "src"))
from npcortex_backend.characters import CharacterError
from npcortex_backend.config import Settings
from npcortex_backend.identity import IdentityError
from npcortex_backend.ollama import ModelError
from npcortex_backend.server import make_handler, make_http_server
from npcortex_backend.service import ChatService
from npcortex_backend.session import SessionStore
from npcortex_backend.world_store import (
    WorldStore, RegistryConflictError, RegistryNotFoundError, SchemaVersionError, SCHEMA_VERSION,
)


def make_service(db_path: Path, provider=None, **kwargs):
    if provider is None:
        provider = Mock()
        provider.chat.return_value = "Test reply"
    store = WorldStore(db_path, kwargs.pop("max_transcript_messages", 200))
    svc = ChatService(ROOT / "character-packs", "qwen3:8b", provider, SessionStore(),
                      world_store=store, **kwargs)
    return svc, provider


class B1StorageTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.db_path = Path(self.tmp.name) / "nested" / "npcortex.sqlite3"
        self.svc, self.provider = make_service(self.db_path)
        self.world = self.svc.register_world("Test World")
        self.world_id = self.world["world_id"]

    def actor(self, character="yui", world_id=None, dimension="minecraft:overworld", actor_id=None):
        return self.svc.register_actor(world_id or self.world_id, dimension, character, actor_id)

    def chat(self, actor, text="hello", player="player-a", world_id=None, dimension="minecraft:overworld"):
        return self.svc.chat_actor(world_id or self.world_id, dimension,
                                   actor["actor_id"], player, text)

    def test_schema_created_with_version_and_relationships(self):
        with sqlite_test_connection(self.db_path) as db:
            version = db.execute("PRAGMA user_version").fetchone()[0]
            integrity = db.execute("PRAGMA integrity_check").fetchone()[0]
            foreign_keys = db.execute("PRAGMA foreign_key_list(transcript)").fetchall()
        self.assertEqual(version, SCHEMA_VERSION)
        self.assertEqual(integrity, "ok")
        self.assertTrue(foreign_keys)

    def test_same_character_two_actors_have_separate_histories(self):
        first, second = self.actor(), self.actor()
        self.assertNotEqual(first["actor_id"], second["actor_id"])
        reply_a = self.chat(first, "My name is Alice")
        reply_b = self.chat(second, "My name is Bob")
        self.assertNotEqual(reply_a["conversation_id"], reply_b["conversation_id"])
        self.assertEqual(self.svc.world_store.history(reply_a["conversation_id"])[0]["content"], "My name is Alice")
        self.assertEqual(self.svc.world_store.history(reply_b["conversation_id"])[0]["content"], "My name is Bob")
        self.chat(second, "Do you remember?", player="player-b")
        self.assertEqual([m["content"] for m in self.provider.chat.call_args.args[0][1:]],
                         ["Do you remember?"])

    def test_actor_history_survives_restart_with_same_conversation_id(self):
        actor = self.actor()
        initial = self.chat(actor, "Please remember this")
        new_service, provider = make_service(self.db_path)
        later = new_service.chat_actor(self.world_id, "minecraft:overworld", actor["actor_id"],
                                       "player-a", "What did I say?")
        self.assertEqual(initial["conversation_id"], later["conversation_id"])
        self.assertEqual([m["content"] for m in provider.chat.call_args.args[0][1:]],
                         ["Please remember this", "Test reply", "What did I say?"])
        self.assertEqual(len(new_service.world_store.get_conversation(later["conversation_id"])["messages"]), 4)

    def test_world_dimension_and_player_isolation(self):
        another_world = self.svc.register_world("Another World")
        a = self.actor()
        b = self.actor(world_id=another_world["world_id"])
        c = self.actor(dimension="minecraft:the_nether")
        self.assertNotEqual(self.chat(a)["conversation_id"],
                            self.chat(b, world_id=another_world["world_id"])["conversation_id"])
        self.assertNotEqual(self.chat(a, player="player-b")["conversation_id"],
                            self.chat(a, player="player-a")["conversation_id"])
        with self.assertRaises(RegistryConflictError):
            self.chat(c, dimension="minecraft:overworld")
        with self.assertRaises(RegistryConflictError):
            self.chat(a, world_id=another_world["world_id"])

    def test_register_world_and_actor_support_idempotent_ids(self):
        named = str(uuid4())
        world = self.svc.register_world("Another", named)
        self.assertEqual(world, self.svc.register_world("Another", named))
        with self.assertRaises(RegistryConflictError):
            self.svc.register_world("Changed", named)
        aid = str(uuid4())
        actor = self.actor(actor_id=aid)
        self.assertEqual(actor, self.actor(actor_id=aid))
        self.assertEqual(self.svc.world_store.get_actor(aid), actor)
        with self.assertRaises(RegistryConflictError):
            self.actor("guide", actor_id=aid)

    def test_invalid_identity_or_pack_does_not_persist(self):
        with self.assertRaises(IdentityError):
            self.actor(actor_id="../../../data")
        with self.assertRaises(IdentityError):
            self.actor(dimension="overworld")
        with self.assertRaises(CharacterError):
            self.actor(character="missing")
        with self.assertRaises(RegistryNotFoundError):
            self.actor(world_id=str(uuid4()))
        with self.assertRaises(IdentityError):
            self.chat(self.actor(), player="invalid player")
        with sqlite_test_connection(self.db_path) as db:
            self.assertEqual(db.execute("SELECT COUNT(*) FROM actors").fetchone()[0], 1)
            self.assertEqual(db.execute("SELECT COUNT(*) FROM conversations").fetchone()[0], 0)

    def test_provider_failure_keeps_transcript_clean_and_recovers(self):
        actor = self.actor()
        self.provider.chat.side_effect = [ModelError("failed"), "Recovered"]
        with self.assertRaises(ModelError):
            self.chat(actor, "Failed prompt")
        answer = self.chat(actor, "Recovered prompt")
        self.assertEqual([m["content"] for m in self.svc.world_store.history(answer["conversation_id"])],
                         ["Recovered prompt", "Recovered"])

    def test_bounded_transcript_retains_only_complete_exchanges(self):
        svc, provider = make_service(self.db_path, max_transcript_messages=4)
        actor = svc.register_actor(self.world_id, "minecraft:overworld", "yui")
        for n in range(5):
            svc.chat_actor(self.world_id, "minecraft:overworld", actor["actor_id"], "player-a", f"prompt {n}")
        cid = WorldStore.conversation_id(self.world_id, actor["actor_id"], "player-a")
        messages = svc.world_store.history(cid, 12)
        self.assertEqual([m["content"] for m in messages],
                         ["prompt 3", "Test reply", "prompt 4", "Test reply"])

    def test_same_actor_requests_serialized_and_history_consistent(self):
        actor = self.actor()
        started, release = Event(), Event()
        seen = []
        def slow(messages):
            seen.append(messages)
            if len(seen) == 1:
                started.set()
                self.assertTrue(release.wait(3))
            return f"reply-{len(seen)}"
        self.provider.chat.side_effect = slow
        with ThreadPoolExecutor(max_workers=2) as pool:
            one = pool.submit(self.chat, actor, "first")
            self.assertTrue(started.wait(2))
            two = pool.submit(self.chat, actor, "second")
            # Both leases share one A2 session key; once queued, release first.
            import time
            deadline = time.monotonic() + 2
            key = (f"{self.world_id}/player-a", f"actor:{actor['actor_id']}")
            while self.svc.sessions._sessions[key].requests != 2 and time.monotonic() < deadline:
                time.sleep(.005)
            self.assertEqual(self.svc.sessions._sessions[key].requests, 2)
            release.set()
            one_result = one.result(timeout=4)
            two_result = two.result(timeout=4)
        self.assertEqual(one_result["conversation_id"], two_result["conversation_id"])
        self.assertEqual([m["content"] for m in seen[1][1:]], ["first", "reply-1", "second"])

    def test_legacy_chat_remains_ephemeral_and_unchanged(self):
        result = self.svc.chat("yui", "p1", "hello")
        self.assertEqual(set(result), {"character_id", "character_name", "reply", "model"})
        with sqlite_test_connection(self.db_path) as db:
            self.assertEqual(db.execute("SELECT COUNT(*) FROM conversations").fetchone()[0], 0)

    def test_registrations_remain_idempotent_under_concurrency(self):
        wid, aid = str(uuid4()), str(uuid4())
        with ThreadPoolExecutor(max_workers=8) as pool:
            worlds = list(pool.map(lambda _: self.svc.register_world("Concurrent", wid), range(12)))
        self.assertTrue(all(item == worlds[0] for item in worlds))
        with ThreadPoolExecutor(max_workers=8) as pool:
            actors = list(pool.map(lambda _: self.svc.register_actor(wid, "minecraft:overworld", "yui", aid),
                                   range(12)))
        self.assertTrue(all(item == actors[0] for item in actors))
        with sqlite_test_connection(self.db_path) as db:
            self.assertEqual(db.execute("SELECT COUNT(*) FROM actors WHERE actor_id=?", (aid,)).fetchone()[0], 1)

    def test_failed_sqlite_exchange_rolls_back_both_messages(self):
        actor = self.actor()
        cid = self.svc.world_store.conversation_for(self.world_id, actor["actor_id"], "p1", "minecraft:overworld")
        with sqlite_test_connection(self.db_path) as db:
            db.execute("""CREATE TRIGGER abort_assistant BEFORE INSERT ON transcript
                          WHEN NEW.role='assistant' BEGIN SELECT RAISE(ABORT, 'disk failure'); END""")
        with self.assertRaises(sqlite3.DatabaseError):
            self.svc.world_store.append_exchange(cid, "should not persist", "assistant reply")
        self.assertEqual(self.svc.world_store.history(cid), [])

    def test_session_eviction_does_not_erase_sqlite_transcript(self):
        actor = self.actor()
        first = self.chat(actor, "persistent")
        self.svc.sessions = SessionStore(max_sessions=1)
        self.svc.sessions.add_exchange("another", "guide", "eviction", "happened")
        self.chat(actor, "after eviction")
        self.assertEqual([m["content"] for m in self.provider.chat.call_args.args[0][1:]],
                         ["persistent", "Test reply", "after eviction"])
        self.assertEqual(len(self.svc.world_store.get_conversation(first["conversation_id"])["messages"]), 4)

    def test_corrupt_schema_claiming_v1_is_rejected_at_startup(self):
        with sqlite_test_connection(self.db_path) as db:
            db.execute("DROP TABLE transcript")
        with self.assertRaises(sqlite3.DatabaseError):
            WorldStore(self.db_path)

    def test_b1_environment_configuration_validation(self):
        from npcortex_backend.config import Settings
        with patch.dict(os.environ, {"NPCORTEX_DB_PATH": str(self.db_path),
                                  "NPCORTEX_MAX_TRANSCRIPT_MESSAGES": "100"}):
            settings = Settings.from_environment()
            self.assertEqual(settings.db_path, self.db_path)
            self.assertEqual(settings.max_transcript_messages, 100)
        for value in ("1", "3", "2001", "bad"):
            with self.subTest(value=value), patch.dict(os.environ, {"NPCORTEX_MAX_TRANSCRIPT_MESSAGES": value}):
                with self.assertRaises(ValueError):
                    Settings.from_environment()

    def test_schema_upgrade_refuses_future_versions_without_mutation(self):
        with sqlite_test_connection(self.db_path) as db:
            db.execute("PRAGMA user_version=999")
        with self.assertRaises(SchemaVersionError):
            WorldStore(self.db_path)
        with sqlite_test_connection(self.db_path) as db:
            self.assertEqual(db.execute("PRAGMA user_version").fetchone()[0], 999)


class B1HTTPTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.db_path = Path(self.tmp.name) / "db.sqlite3"
        self.svc, self.provider = make_service(self.db_path)
        self.httpd = make_http_server(self.svc)
        self.thread = Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()
        self.base = f"http://127.0.0.1:{self.httpd.server_port}"
        self.addCleanup(self.stop)

    def stop(self):
        self.httpd.shutdown()
        self.httpd.server_close()
        self.thread.join(timeout=2)

    def call(self, path, data=None, method="POST"):
        request = Request(self.base + path, method=method,
                          data=json.dumps(data).encode("utf-8") if method == "POST" else None,
                          headers={"Content-Type": "application/json"})
        try:
            with urlopen(request, timeout=4) as response:
                return response.status, json.load(response)
        except HTTPError as e:
            return e.code, json.load(e)

    def test_real_http_actor_lifecycle_and_persistence(self):
        status, world = self.call("/v1/worlds", {"name": "Single Player"})
        self.assertEqual(status, 201)
        status, actor = self.call("/v1/actors", {"world_id": world["world_id"],
                                                   "dimension_id": "minecraft:overworld", "character_id": "yui"})
        self.assertEqual(status, 201)
        status, found = self.call(f"/v1/actors/{actor['actor_id']}", method="GET")
        self.assertEqual((status, found), (200, actor))
        post = {"world_id": world["world_id"], "actor_id": actor["actor_id"],
                "dimension_id": "minecraft:overworld", "player_id": "player1", "message": "Hi"}
        status, result = self.call("/v1/actor-chat", post)
        self.assertEqual(status, 200)
        self.assertEqual(result["character_id"], "yui")
        status, conversation = self.call(f"/v1/conversations/{result['conversation_id']}", method="GET")
        self.assertEqual(status, 200)
        self.assertEqual([m["content"] for m in conversation["messages"]], ["Hi", "Test reply"])
        self.assertEqual(self.call("/health", method="GET")[0], 200)
        legacy, response = self.call("/v1/chat", {"character_id": "yui", "player_id": "p1", "message": "Hi"})
        self.assertEqual(legacy, 200)
        self.assertEqual(set(response), {"character_id", "character_name", "reply", "model"})

    def test_storage_failure_maps_to_503_without_leaking_database_error(self):
        world_status, world = self.call("/v1/worlds", {"name": "World"})
        actor_status, actor = self.call("/v1/actors", {"world_id": world["world_id"],
                                                        "dimension_id": "minecraft:overworld", "character_id": "yui"})
        with sqlite_test_connection(self.db_path) as db:
            db.execute("""CREATE TRIGGER fail_append BEFORE INSERT ON transcript
                          BEGIN SELECT RAISE(ABORT, 'private sqlite error'); END""")
        _, payload = self.call("/v1/actor-chat", {"world_id": world["world_id"],
                                                  "dimension_id": "minecraft:overworld",
                                                  "actor_id": actor["actor_id"],
                                                  "player_id": "p1", "message": "Hello"})
        self.assertEqual(payload["code"], "storage_unavailable")
        self.assertNotIn("private", json.dumps(payload))

    def test_new_api_errors_are_structured(self):
        status, result = self.call("/v1/worlds", {"name": " "})
        self.assertEqual((status, result["code"]), (400, "invalid_identity"))
        status, result = self.call("/v1/actors", {"world_id": str(uuid4()), "dimension_id": "minecraft:overworld",
                                                     "character_id": "yui"})
        self.assertEqual((status, result["code"]), (404, "registry_not_found"))
        status, result = self.call("/v1/actors/not-a-uuid", method="GET")
        self.assertEqual((status, result["code"]), (400, "invalid_identity"))
        status, result = self.call("/v1/actor-chat", {"message": "hi"})
        self.assertEqual((status, result["code"]), (400, "invalid_request"))
        status, result = self.call("/v1/conversations/" + str(uuid4()), method="GET")
        self.assertEqual((status, result["code"]), (404, "registry_not_found"))


if __name__ == "__main__":
    unittest.main()
