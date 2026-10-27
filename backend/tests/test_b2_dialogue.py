"""B2 actual SQLite v1 migration, multi-party dialogue, retry and HTTP regression."""
from concurrent.futures import ThreadPoolExecutor
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
import json
from pathlib import Path
import sqlite3
import sys
import tempfile
from threading import Event, Thread
import time
import unittest
from sqlite_test_utils import sqlite_test_connection
from unittest.mock import Mock
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "backend" / "src"))
from npcortex_backend.dialogues import DialogueStore
from npcortex_backend.identity import IdentityError
from npcortex_backend.ollama import ModelError, OllamaProvider
from npcortex_backend.server import make_handler, make_http_server
from npcortex_backend.service import ChatService
from npcortex_backend.session import SessionBusyError, SessionStore
from npcortex_backend.world_store import WorldStore, RegistryConflictError, RegistryNotFoundError


def make_service(path, provider=None, sessions=None):
    if provider is None:
        provider = Mock()
        provider.chat.return_value = "A generated line."
    svc = ChatService(ROOT / "character-packs", "qwen3:8b", provider,
                      sessions or SessionStore(), world_store=WorldStore(path))
    return svc, provider


class B2Tests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.path = Path(temp.name) / "npcortex.sqlite3"
        self.svc, self.provider = make_service(self.path)
        self.world = self.svc.register_world("World")
        self.yui = self.svc.register_actor(self.world["world_id"], "minecraft:overworld", "yui")
        self.yui2 = self.svc.register_actor(self.world["world_id"], "minecraft:overworld", "yui")
        self.guide = self.svc.register_actor(self.world["world_id"], "minecraft:overworld", "guide")

    def create(self, actors=None, players=None, max_turns=24, topic="Let's talk about a village"):
        return self.svc.create_dialogue(self.world["world_id"], "minecraft:overworld",
                                         [a["actor_id"] for a in (actors if actors is not None else [self.yui, self.guide])],
                                         players if players is not None else [], topic, max_turns)

    def actor_turn(self, dialogue, actor, request_id=None):
        return self.svc.dialogue_turn(dialogue["dialogue_id"], "actor", actor["actor_id"], request_id=request_id)

    def player_turn(self, dialogue, player, content, request_id=None):
        return self.svc.dialogue_turn(dialogue["dialogue_id"], "player", player, content, request_id)

    def test_three_actors_share_only_one_dialogue_and_keep_speakers(self):
        d = self.create([self.yui, self.yui2, self.guide])
        for actor in [self.yui, self.guide, self.yui2]:
            self.actor_turn(d, actor)
        turns = self.svc.get_dialogue(d["dialogue_id"])["turns"]
        self.assertEqual([t["seq"] for t in turns], [1, 2, 3])
        self.assertEqual([t["speaker_id"] for t in turns],
                         [self.yui["actor_id"], self.guide["actor_id"], self.yui2["actor_id"]])
        self.assertEqual(len(self.svc.get_dialogue(d["dialogue_id"])["participants"]), 3)
        self.assertEqual(self.provider.chat.call_count, 3)
        context = json.loads(self.provider.chat.call_args.args[0][-1]["content"])
        self.assertEqual([t["speaker_id"] for t in context["previous_turns"]],
                         [self.yui["actor_id"], self.guide["actor_id"]])
        self.assertEqual(context["next_speaker_actor_id"], self.yui2["actor_id"])

    def test_player_and_actor_turns_and_explicit_close(self):
        d = self.create([self.yui], ["p1"])
        player = self.player_turn(d, "p1", "Hello Yui")
        self.assertEqual(player["turn"]["speaker_type"], "player")
        self.assertEqual(self.provider.chat.call_count, 0)
        actor = self.actor_turn(d, self.yui)
        self.assertEqual(actor["turn"]["speaker_type"], "actor")
        self.assertEqual(actor["turn"]["content"], "A generated line.")
        closed = self.svc.close_dialogue(d["dialogue_id"])
        self.assertEqual(closed["status"], "closed")
        with self.assertRaises(RegistryConflictError):
            self.player_turn(d, "p1", "Are you there?")
        self.assertEqual(self.svc.close_dialogue(d["dialogue_id"])["status"], "closed")

    def test_same_speaker_denied_without_calling_ai(self):
        d = self.create()
        self.actor_turn(d, self.yui)
        with self.assertRaises(RegistryConflictError):
            self.actor_turn(d, self.yui)
        self.assertEqual(self.provider.chat.call_count, 1)
        self.assertEqual(len(self.svc.get_dialogue(d["dialogue_id"])["turns"]), 1)

    def test_turn_limit_auto_closes_and_retries_work_when_closed(self):
        d = self.create(max_turns=2)
        rid = str(uuid4())
        first = self.actor_turn(d, self.yui, rid)
        second = self.actor_turn(d, self.guide)
        self.assertEqual(second["status"], "closed")
        self.assertEqual(second["turns_remaining"], 0)
        replay = self.actor_turn(d, self.yui, rid)
        self.assertTrue(replay["replayed"])
        self.assertEqual(replay["turn"], first["turn"])
        self.assertEqual(self.provider.chat.call_count, 2)
        with self.assertRaises(RegistryConflictError):
            self.actor_turn(d, self.yui)

    def test_idempotency_across_backend_restart(self):
        d = self.create()
        rid = str(uuid4())
        original = self.actor_turn(d, self.yui, rid)
        restarted, provider = make_service(self.path)
        replay = restarted.dialogue_turn(d["dialogue_id"], "actor", self.yui["actor_id"], request_id=rid)
        self.assertTrue(replay["replayed"])
        self.assertEqual(replay["turn"], original["turn"])
        self.assertEqual(provider.chat.call_count, 0)
        self.assertEqual(len(restarted.get_dialogue(d["dialogue_id"])["turns"]), 1)

    def test_request_id_conflict_rejected(self):
        d = self.create([self.yui], ["p1", "p2"])
        rid = str(uuid4())
        self.player_turn(d, "p1", "Hi", rid)
        self.assertTrue(self.player_turn(d, "p1", "Hi", rid)["replayed"])
        with self.assertRaises(RegistryConflictError):
            self.player_turn(d, "p1", "Modified", rid)
        with self.assertRaises(RegistryConflictError):
            self.player_turn(d, "p2", "Hi", rid)

    def test_context_isolation_between_two_dialogues_and_b1(self):
        self.svc.chat_actor(self.world["world_id"], "minecraft:overworld", self.yui["actor_id"],
                            "p1", "PRIVATE_B1_SECRET")
        one = self.create()
        two = self.create()
        self.player = self.svc.create_dialogue(self.world["world_id"], "minecraft:overworld",
                        [self.yui["actor_id"]], ["p1"], "Private test")
        self.player_turn(self.player, "p1", "THIS_DIALOGUE_ONLY")
        self.actor_turn(one, self.yui)
        self.actor_turn(two, self.yui)
        context = json.loads(self.provider.chat.call_args.args[0][-1]["content"])
        self.assertNotIn("PRIVATE_B1_SECRET", json.dumps(context))
        self.assertNotIn("THIS_DIALOGUE_ONLY", json.dumps(context))
        self.assertEqual(context["previous_turns"], [])

    def test_bad_participants_and_dimension_rejected_without_write(self):
        other_world = self.svc.register_world("Different")
        other = self.svc.register_actor(other_world["world_id"], "minecraft:overworld", "yui")
        nether = self.svc.register_actor(self.world["world_id"], "minecraft:the_nether", "yui")
        for actors, players in [([self.yui], []), ([self.yui, self.yui], []),
                                ([], ["p1", "p2"]), ([self.yui, other], []),
                                ([self.yui, nether], []), ([self.yui], ["illegal player"]),
                                ([self.yui] * 9, [])]:
            with self.subTest(actors=actors, players=players), self.assertRaises((IdentityError, RegistryConflictError)):
                self.create(actors, players)
        with sqlite_test_connection(self.path) as db:
            self.assertEqual(db.execute("SELECT COUNT(*) FROM dialogues").fetchone()[0], 0)

    def test_player_or_actor_not_in_dialogue_rejected(self):
        d = self.create()
        with self.assertRaises(RegistryConflictError):
            self.player_turn(d, "p1", "Hello")
        with self.assertRaises(RegistryConflictError):
            self.actor_turn(d, self.yui2)
        with self.assertRaises(IdentityError):
            self.svc.dialogue_turn(d["dialogue_id"], "actor", self.yui["actor_id"], "forged AI speech")

    def test_optimistic_sequence_guard_rejects_stale_writer(self):
        d = self.create()
        store = DialogueStore(self.svc.world_store)
        first = store.append(d["dialogue_id"], str(uuid4()), "actor", self.yui["actor_id"], "Hello", 0)
        self.assertEqual(first["seq"], 1)
        with self.assertRaises(RegistryConflictError):
            store.append(d["dialogue_id"], str(uuid4()), "actor", self.guide["actor_id"], "Hi", 0)
        self.assertEqual(len(store.get(d["dialogue_id"])["turns"]), 1)

    def test_context_limit_sends_only_recent_dialogue_turns(self):
        d = self.create(max_turns=20)
        store = DialogueStore(self.svc.world_store)
        for index in range(14):
            aid = self.yui["actor_id"] if index % 2 == 0 else self.guide["actor_id"]
            store.append(d["dialogue_id"], str(uuid4()), "actor", aid, f"turn-{index}", index)
        self.actor_turn(d, self.yui)
        prompt = json.loads(self.provider.chat.call_args.args[0][-1]["content"])
        self.assertEqual(len(prompt["previous_turns"]), self.svc.sessions.max_messages)
        self.assertEqual(prompt["previous_turns"][0]["content"], "turn-2")
        self.assertEqual(len(self.svc.get_dialogue(d["dialogue_id"])["turns"]), 15)

    def test_invalid_limits_and_topic(self):
        for limit in (1, 65, "3", 0, True):
            with self.subTest(limit=limit), self.assertRaises(IdentityError):
                self.create(max_turns=limit)
        with self.assertRaises(IdentityError):
            self.create(topic=" ")
        with self.assertRaises(IdentityError):
            self.create(topic="x" * 201)

    def test_failed_model_does_not_commit_and_can_retry(self):
        d = self.create()
        self.provider.chat.side_effect = [ModelError("provider down"), "Recovered"]
        with self.assertRaises(ModelError):
            self.actor_turn(d, self.yui)
        self.assertEqual(self.svc.get_dialogue(d["dialogue_id"])["turns"], [])
        result = self.actor_turn(d, self.yui)
        self.assertEqual(result["turn"]["seq"], 1)
        self.assertEqual(result["turn"]["content"], "Recovered")

    def test_sqlite_insert_failure_rolls_back_and_recovers(self):
        d = self.create()
        with sqlite_test_connection(self.path) as db:
            db.execute("""CREATE TRIGGER fail_b2 BEFORE INSERT ON dialogue_turns
                       BEGIN SELECT RAISE(ABORT, 'write failed'); END""")
        with self.assertRaises(sqlite3.Error):
            self.actor_turn(d, self.yui)
        self.assertEqual(self.svc.get_dialogue(d["dialogue_id"])["turns"], [])
        with sqlite_test_connection(self.path) as db:
            db.execute("DROP TRIGGER fail_b2")
        self.assertEqual(self.actor_turn(d, self.yui)["turn"]["seq"], 1)

    def test_concurrent_turns_serialized(self):
        d = self.create()
        started, release = Event(), Event()
        seen = []
        def slow(messages):
            seen.append(messages)
            if len(seen) == 1:
                started.set()
                self.assertTrue(release.wait(4))
            return f"Response-{len(seen)}"
        self.provider.chat.side_effect = slow
        with ThreadPoolExecutor(max_workers=2) as pool:
            first = pool.submit(self.actor_turn, d, self.yui)
            self.assertTrue(started.wait(2))
            second = pool.submit(self.actor_turn, d, self.guide)
            time.sleep(.05)
            self.assertEqual(self.provider.chat.call_count, 1)
            release.set()
            first.result(timeout=3)
            second.result(timeout=3)
        self.assertEqual([t["seq"] for t in self.svc.get_dialogue(d["dialogue_id"])["turns"]], [1, 2])
        context = json.loads(seen[1][-1]["content"])
        self.assertEqual(len(context["previous_turns"]), 1)

    def test_session_busy_does_not_corrupt_state(self):
        self.svc.sessions = SessionStore(max_requests_per_session=1)
        d = self.create()
        started, release = Event(), Event()
        def slow(_):
            started.set()
            self.assertTrue(release.wait(4))
            return "done"
        self.provider.chat.side_effect = slow
        with ThreadPoolExecutor(max_workers=2) as pool:
            first = pool.submit(self.actor_turn, d, self.yui)
            self.assertTrue(started.wait(2))
            with self.assertRaises(SessionBusyError):
                self.actor_turn(d, self.guide)
            release.set()
            first.result(timeout=3)
        self.assertEqual(len(self.svc.get_dialogue(d["dialogue_id"])["turns"]), 1)

    def test_backward_compatibility_v1_actor_and_legacy(self):
        b1 = self.svc.chat_actor(self.world["world_id"], "minecraft:overworld",
                                 self.yui["actor_id"], "p1", "B1 input")
        self.assertEqual([m["content"] for m in self.svc.world_store.get_conversation(b1["conversation_id"])["messages"]],
                         ["B1 input", "A generated line."])
        response = self.svc.chat("yui", "p1", "legacy input")
        self.assertEqual(set(response), {"character_id", "character_name", "reply", "model"})
        self.assertEqual(self.svc.world_store.get_conversation(b1["conversation_id"])["messages"][0]["content"], "B1 input")


class B2OllamaTransportIntegrationTest(unittest.TestCase):
    def test_b2_actor_turn_through_actual_local_fake_ollama_http(self):
        captured = []
        class FakeOllama(BaseHTTPRequestHandler):
            def do_POST(self):
                payload = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                captured.append((self.path, payload))
                body = json.dumps({"message": {"content": "Hello from Qwen stub"}}).encode()
                self.send_response(200)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
            def log_message(self, *_):
                pass
        httpd = ThreadingHTTPServer(("127.0.0.1", 0), FakeOllama)
        thread = Thread(target=httpd.serve_forever, daemon=True)
        thread.start()
        try:
            with tempfile.TemporaryDirectory() as tmp:
                provider = OllamaProvider(f"http://127.0.0.1:{httpd.server_port}", "qwen3:8b")
                svc, _ = make_service(Path(tmp) / "db.sqlite3", provider)
                world = svc.register_world("Fake provider")
                first = svc.register_actor(world["world_id"], "minecraft:overworld", "yui")
                second = svc.register_actor(world["world_id"], "minecraft:overworld", "guide")
                d = svc.create_dialogue(world["world_id"], "minecraft:overworld",
                                        [first["actor_id"], second["actor_id"]], [], "Welcome")
                result = svc.dialogue_turn(d["dialogue_id"], "actor", first["actor_id"])
                self.assertEqual(result["turn"]["content"], "Hello from Qwen stub")
                self.assertEqual(len(svc.get_dialogue(d["dialogue_id"])["turns"]), 1)
                endpoint, sent = captured[0]
                self.assertEqual(endpoint, "/api/chat")
                self.assertEqual(sent["model"], "qwen3:8b")
                self.assertFalse(sent["think"])
                self.assertFalse(sent["stream"])
                self.assertEqual(json.loads(sent["messages"][-1]["content"])["next_speaker_actor_id"], first["actor_id"])
        finally:
            httpd.shutdown()
            httpd.server_close()
            thread.join(timeout=2)


class B2MigrationTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = Path(self.tmp.name) / "original_b1.db"

    def create_v1(self):
        # Exact v1 DDL copied from the shipped B1 WorldStore, before any v2 code runs.
        with sqlite_test_connection(self.path) as db:
            db.executescript("""
                CREATE TABLE worlds (world_id TEXT PRIMARY KEY, name TEXT NOT NULL);
                CREATE TABLE actors (actor_id TEXT PRIMARY KEY,
                    world_id TEXT NOT NULL REFERENCES worlds(world_id),
                    dimension_id TEXT NOT NULL, character_id TEXT NOT NULL);
                CREATE INDEX idx_actors_world ON actors(world_id, dimension_id);
                CREATE TABLE conversations (conversation_id TEXT PRIMARY KEY,
                    world_id TEXT NOT NULL REFERENCES worlds(world_id),
                    actor_id TEXT NOT NULL REFERENCES actors(actor_id),
                    player_id TEXT NOT NULL, UNIQUE(world_id,actor_id,player_id));
                CREATE TABLE transcript (conversation_id TEXT NOT NULL REFERENCES conversations(conversation_id),
                    seq INTEGER NOT NULL, role TEXT NOT NULL CHECK(role IN ('user','assistant')),
                    content TEXT NOT NULL, PRIMARY KEY(conversation_id,seq));
                PRAGMA user_version=1;
            """)
            wid, aid, cid = str(uuid4()), str(uuid4()), str(uuid4())
            db.execute("INSERT INTO worlds VALUES(?,?)", (wid, "Older B1 World"))
            db.execute("INSERT INTO actors VALUES(?,?,?,?)", (aid, wid, "minecraft:overworld", "yui"))
            db.execute("INSERT INTO conversations VALUES(?,?,?,?)", (cid, wid, aid, "p1"))
            db.execute("INSERT INTO transcript VALUES(?,?,?,?)", (cid, 1, "user", "B1 original secret"))
            db.execute("INSERT INTO transcript VALUES(?,?,?,?)", (cid, 2, "assistant", "B1 original answer"))
        return wid, aid, cid

    def test_migration_from_real_v1_schema_preserves_all_data_and_is_idempotent(self):
        wid, aid, cid = self.create_v1()
        for _ in range(2):
            store = WorldStore(self.path, allow_schema_upgrade=True)
            self.assertEqual(store.get_actor(aid)["world_id"], wid)
            self.assertEqual(store.get_conversation(cid)["messages"],
                             [{"role": "user", "content": "B1 original secret"},
                              {"role": "assistant", "content": "B1 original answer"}])
        with sqlite_test_connection(self.path) as db:
            self.assertEqual(db.execute("PRAGMA user_version").fetchone()[0], 5)
            self.assertEqual(db.execute("PRAGMA integrity_check").fetchone()[0], "ok")
            self.assertEqual(db.execute("SELECT COUNT(*) FROM transcript").fetchone()[0], 2)
            self.assertEqual(db.execute("SELECT COUNT(*) FROM dialogue_turns").fetchone()[0], 0)

    def test_corrupt_v1_columns_not_promoted(self):
        self.create_v1()
        with sqlite_test_connection(self.path) as db:
            db.execute("ALTER TABLE actors RENAME COLUMN character_id TO missing")
        with self.assertRaises(sqlite3.DatabaseError):
            WorldStore(self.path, allow_schema_upgrade=True)
        with sqlite_test_connection(self.path) as db:
            self.assertEqual(db.execute("PRAGMA user_version").fetchone()[0], 1)

    def test_corrupt_v1_db_not_promoted(self):
        self.create_v1()
        with sqlite_test_connection(self.path) as db:
            db.execute("DROP TABLE transcript")
        with self.assertRaises(sqlite3.Error):
            WorldStore(self.path, allow_schema_upgrade=True)
        with sqlite_test_connection(self.path) as db:
            self.assertEqual(db.execute("PRAGMA user_version").fetchone()[0], 1)
            self.assertFalse(db.execute("SELECT name FROM sqlite_master WHERE name='dialogues'").fetchone())


class B2HTTPTests(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        path = Path(tmp.name) / "db.sqlite3"
        self.svc, self.provider = make_service(path)
        self.httpd = make_http_server(self.svc)
        self.thread = Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()
        self.base = f"http://127.0.0.1:{self.httpd.server_port}"
        self.addCleanup(self.stop)

    def stop(self):
        self.httpd.shutdown()
        self.httpd.server_close()
        self.thread.join(timeout=2)

    def request(self, endpoint, data=None, method="POST"):
        req = Request(self.base + endpoint,
                      data=json.dumps(data).encode("utf-8") if method == "POST" else None,
                      method=method, headers={"Content-Type": "application/json"})
        try:
            with urlopen(req, timeout=6) as response:
                return response.status, json.load(response)
        except HTTPError as exc:
            return exc.code, json.load(exc)

    def test_full_multi_actor_http_flow_and_legacy(self):
        _, w = self.request("/v1/worlds", {"name": "HTTP B2"})
        actors = [self.request("/v1/actors", {"world_id": w["world_id"], "dimension_id": "minecraft:overworld",
                                                    "character_id": c})[1] for c in ("yui", "guide", "yui")]
        status, d = self.request("/v1/dialogues", {"world_id": w["world_id"], "dimension_id": "minecraft:overworld",
                              "actor_ids": [a["actor_id"] for a in actors], "topic": "Say hello", "max_turns": 4})
        self.assertEqual(status, 201)
        self.assertEqual(len(d["participants"]), 3)
        rid = str(uuid4())
        status, first = self.request(f"/v1/dialogues/{d['dialogue_id']}/turns",
                                     {"speaker_type": "actor", "speaker_id": actors[0]["actor_id"], "request_id": rid})
        self.assertEqual(status, 200)
        status, replay = self.request(f"/v1/dialogues/{d['dialogue_id']}/turns",
                                     {"speaker_type": "actor", "speaker_id": actors[0]["actor_id"], "request_id": rid})
        self.assertEqual((status, replay["replayed"]), (200, True))
        status, second = self.request(f"/v1/dialogues/{d['dialogue_id']}/turns",
                                     {"speaker_type": "actor", "speaker_id": actors[1]["actor_id"]})
        self.assertEqual(status, 200)
        status, found = self.request(f"/v1/dialogues/{d['dialogue_id']}", method="GET")
        self.assertEqual((status, len(found["turns"])), (200, 2))
        self.assertEqual(found["turns"][0]["speaker_id"], actors[0]["actor_id"])
        self.assertEqual(self.request("/health", method="GET")[0], 200)
        self.assertEqual(self.request("/v1/chat", {"character_id": "yui", "player_id": "p1", "message": "hi"})[0], 200)
        status, closed = self.request(f"/v1/dialogues/{d['dialogue_id']}/close", {})
        self.assertEqual((status, closed["status"]), (200, "closed"))

    def test_errors_are_structured_and_dont_crash(self):
        status, payload = self.request("/v1/dialogues", {"wrong": "fields"})
        self.assertEqual((status, payload["code"]), (400, "invalid_request"))
        status, payload = self.request(f"/v1/dialogues/{uuid4()}", method="GET")
        self.assertEqual((status, payload["code"]), (404, "registry_not_found"))
        status, payload = self.request(f"/v1/dialogues/{uuid4()}/turns", {"speaker_type": "actor", "speaker_id": "bad"})
        self.assertEqual((status, payload["code"]), (400, "invalid_identity"))
        self.assertEqual(self.request("/health", method="GET")[0], 200)


if __name__ == "__main__":
    unittest.main()
