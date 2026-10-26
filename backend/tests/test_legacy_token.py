"""G3 phase 1: opt-in token for legacy GET/POST routes (NPCORTEX_REQUIRE_TOKEN); default stays compatible."""
import http.client
import json
from pathlib import Path
import os
import sys
from threading import Thread
import unittest
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "backend" / "src"))
from npcortex_backend.server import make_http_server, token_required
from npcortex_backend.service import ChatService
from npcortex_backend.session import SessionStore

TOKEN = "t" * 40
CHAT = json.dumps({"character_id": "yui", "player_id": "tok", "message": "hello"}).encode()
UUID0 = "11111111-1111-4111-8111-111111111111"
# (method, path, body): every legacy family. With the token they must get past auth (status != 403).
LEGACY = [
    ("POST", "/v1/chat", CHAT), ("POST", "/v1/worlds", b"{}"), ("POST", "/v1/actors", b"{}"),
    ("POST", "/v1/actor-chat", b"{}"), ("POST", "/v1/dialogues", b"{}"), ("POST", "/v1/social-runs", b"{}"),
    ("POST", "/v1/agent/goals", b"{}"), ("POST", f"/v1/agent/goals/{UUID0}/decide", b"{}"),
    ("POST", f"/v1/agent/actions/{UUID0}/result", b"{}"),
    ("GET", f"/v1/agent/goals/{UUID0}", None), ("GET", f"/v1/actors/{UUID0}", None),
]


class LegacyTokenTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        provider = Mock()
        provider.chat.return_value = "Hello from Yui"
        service = ChatService(ROOT / "character-packs", "fake", provider, SessionStore())
        cls.httpd = make_http_server(service)
        cls.thread = Thread(target=cls.httpd.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.httpd.shutdown()
        cls.httpd.server_close()
        cls.thread.join(timeout=3)

    def call(self, method, path, body=None, token=None):
        conn = http.client.HTTPConnection("127.0.0.1", self.httpd.server_port, timeout=4)
        try:
            conn.putrequest(method, path)
            if body is not None:
                conn.putheader("Content-Type", "application/json")
                conn.putheader("Content-Length", str(len(body)))
            if token is not None:
                conn.putheader("X-NPCortex-CPlus-Token", token)
            conn.endheaders(body)
            response = conn.getresponse()
            raw = response.read()
            return response.status, raw
        finally:
            conn.close()

    def enabled(self):
        return patch.dict(os.environ, {"NPCORTEX_REQUIRE_TOKEN": "1", "NPCORTEX_CPLUS_F1_TOKEN": TOKEN})

    def test_flag_parsing_default_off(self):
        with patch.dict(os.environ, {}, clear=False):
            os.environ.pop("NPCORTEX_REQUIRE_TOKEN", None)
            self.assertFalse(token_required())
        for value, expected in (("1", True), ("true", True), ("YES", True), ("on", True),
                                ("0", False), ("false", False), ("", False), ("maybe", False)):
            with patch.dict(os.environ, {"NPCORTEX_REQUIRE_TOKEN": value}):
                self.assertEqual(token_required(), expected, value)

    def test_compat_mode_needs_no_token_on_legacy_routes(self):
        with patch.dict(os.environ, {"NPCORTEX_CPLUS_F1_TOKEN": TOKEN}):
            os.environ.pop("NPCORTEX_REQUIRE_TOKEN", None)
            self.assertEqual(self.call("POST", "/v1/chat", CHAT)[0], 200)
            self.assertNotEqual(self.call("GET", f"/v1/agent/goals/{UUID0}")[0], 403)

    def test_enabled_rejects_missing_wrong_and_nonascii_tokens_on_all_legacy_routes(self):
        with self.enabled():
            for method, path, body in LEGACY:
                for token in (None, "", "wrong" * 8, TOKEN[:-1], TOKEN + "x", "é" * 40):
                    with self.subTest(path=path, token=token):
                        status, raw = self.call(method, path, body, token)
                        self.assertEqual(status, 403)
                        self.assertEqual(json.loads(raw)["code"], "forbidden")
                        self.assertNotIn(TOKEN.encode(), raw)

    def test_enabled_accepts_correct_token_on_all_legacy_routes(self):
        with self.enabled():
            self.assertEqual(self.call("POST", "/v1/chat", CHAT, TOKEN)[0], 200)
            for method, path, body in LEGACY:
                with self.subTest(path=path):
                    self.assertNotEqual(self.call(method, path, body, TOKEN)[0], 403)

    def test_health_stays_open_when_enabled(self):
        with self.enabled():
            self.assertEqual(self.call("GET", "/health")[0], 200)

    def test_cplus_routes_always_require_token_regardless_of_flag(self):
        with patch.dict(os.environ, {"NPCORTEX_CPLUS_F1_TOKEN": TOKEN}):
            os.environ.pop("NPCORTEX_REQUIRE_TOKEN", None)
            status, raw = self.call("POST", "/v1/cplus/e1/status", b"{}")
            self.assertEqual((status, json.loads(raw)["code"]), (403, "forbidden"))
            self.assertEqual(self.call("POST", "/v1/cplus/e1/status", b"{}", "z" * 40)[0], 403)

    def test_fail_closed_when_no_secret_available(self):
        env = {"NPCORTEX_REQUIRE_TOKEN": "1", "NPCORTEX_CPLUS_F1_TOKEN": "",
               "NPCORTEX_CPLUS_F1_SECRET_FILE": str(ROOT / "backend" / "tests" / "does-not-exist.secret")}
        with patch.dict(os.environ, env):
            self.assertEqual(self.call("POST", "/v1/chat", CHAT, "")[0], 403)
            self.assertEqual(self.call("POST", "/v1/chat", CHAT, TOKEN)[0], 403)


if __name__ == "__main__":
    unittest.main()
