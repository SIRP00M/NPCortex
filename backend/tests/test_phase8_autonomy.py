"""Autonomous speech is cosmetic, validated, and does not modify memory or execute actions."""
import http.client
from http.server import ThreadingHTTPServer
import json
from pathlib import Path
import sys
import tempfile
import threading
import unittest
from unittest.mock import Mock

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "backend" / "src"))
from npcortex_backend.memory import MemoryStore
from npcortex_backend.service import ChatService
from npcortex_backend.session import SessionStore
from npcortex_backend.server import make_handler

class AutonomyBackendTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.store = MemoryStore(Path(self.tmp.name) / "memory.sqlite3")
        self.provider = Mock()
        self.provider.chat.return_value = "Careful! That creeper looks dangerous!"
        self.service = ChatService(ROOT / "character-packs", "qwen3:8b",
                                   self.provider, SessionStore(), self.store)
    def tearDown(self):
        self.store.close()
        self.tmp.cleanup()

    def test_character_reacts_without_storing_fake_player_conversation(self):
        self.store.add_note("player1", "yui:npc1", "My name is Kenji")
        reaction = self.service.autonomy_reply("yui", "player1", "npc1", "hostile_nearby",
                                                "minecraft:creeper", "warned_owner")
        self.assertIn("creeper", reaction["reply"])
        self.assertEqual(self.store.snapshot("player1", "yui:npc1")["chats"], 0)
        messages = self.provider.chat.call_args.args[0]
        self.assertIn('NPC named "Yui"', messages[0]["content"])
        self.assertIn('PLAYER NOTE: "My name is Kenji"', messages[0]["content"])
        self.assertIn('successful escape', messages[0]["content"])
        self.assertIn('minecraft:creeper', messages[-1]["content"])

    def test_dangerous_unexpected_events_rejected_before_llm(self):
        bad = [("execute_command", "minecraft:creeper", "warned_owner"),
               ("hostile_nearby", "creeper", "warned_owner"),
               ("hostile_nearby", "minecraft:creeper", "teleport_to_target")]
        for event, mob, status in bad:
            with self.subTest(event=event, mob=mob):
                with self.assertRaises(ValueError):
                    self.service.autonomy_reply("yui", "p", "npc", event, mob, status)
        self.provider.chat.assert_not_called()

    def test_http_route_validation_and_success(self):
        httpd = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(self.service))
        t = threading.Thread(target=httpd.serve_forever, daemon=True)
        t.start()
        base = dict(character_id="yui", player_id="p1", session_id="npc1",
                    message="Hostile mob nearby", event="hostile_nearby",
                    threat_type="minecraft:zombie", result="retreat_started")
        try:
            def send(body):
                c = http.client.HTTPConnection("127.0.0.1", httpd.server_port, timeout=3)
                try:
                    c.request("POST", "/v1/autonomy-reply", json.dumps(body),
                              {"Content-Type": "application/json"})
                    reply = c.getresponse()
                    return reply.status, json.loads(reply.read())
                finally: c.close()
            code, result = send(base)
            self.assertEqual(code, 200)
            self.assertIn("reply", result)
            for invalid in [dict(base, result="attack_player"),
                            dict(base, session_id=""), dict(base, threat_type="INVALID TYPE")]:
                code, _ = send(invalid)
                self.assertEqual(code, 400)
        finally:
            httpd.shutdown();httpd.server_close();t.join(timeout=3)

    def test_speech_is_short_and_one_line(self):
        self.provider.chat.return_value = "Hi!\n" + "z" * 400
        text = self.service.autonomy_reply("yui", "p", "npc", "hostile_nearby",
                                            "minecraft:skeleton", "warned_owner")["reply"]
        self.assertLessEqual(len(text), 220)
        self.assertNotIn("\n", text)

if __name__ == "__main__": unittest.main()
