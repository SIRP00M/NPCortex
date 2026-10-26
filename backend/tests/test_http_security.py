"""G2 browser/network hardening: Host and Content-Type checks, loopback-only binding, 413 message."""
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
from npcortex_backend.config import Settings, is_loopback_host
from npcortex_backend.server import MAX_BODY, make_http_server
from npcortex_backend.service import ChatService
from npcortex_backend.session import SessionStore

CHAT = json.dumps({"character_id": "yui", "player_id": "sec", "message": "hello"}).encode()


class HttpSecurityTests(unittest.TestCase):
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

    def send(self, method, path, body=None, headers=None, host="default"):
        conn = http.client.HTTPConnection("127.0.0.1", self.httpd.server_port, timeout=4)
        try:
            conn.putrequest(method, path, skip_host=host != "default")
            if host not in ("default", None):
                conn.putheader("Host", host)
            for key, value in (headers or {}).items():
                conn.putheader(key, value)
            if body is not None:
                conn.putheader("Content-Length", str(len(body)))
            conn.endheaders(body)
            response = conn.getresponse()
            return response.status, json.loads(response.read())
        finally:
            conn.close()

    def chat(self, **kwargs):
        headers = kwargs.pop("headers", {"Content-Type": "application/json"})
        return self.send("POST", "/v1/chat", CHAT, headers, **kwargs)

    # Compatibility: what the Java HttpClient and PowerShell/urllib send.
    def test_loopback_hosts_and_json_variants_accepted(self):
        for host in ("default", "127.0.0.1:8765", "localhost", "LOCALHOST:1", "[::1]:8765", "127.0.0.2"):
            with self.subTest(host=host):
                status, body = self.chat(host=host)
                self.assertEqual((status, body.get("reply")), (200, "Hello from Yui"))
        for ctype in ("application/json", "Application/JSON; charset=utf-8"):
            with self.subTest(ctype=ctype):
                self.assertEqual(self.chat(headers={"Content-Type": ctype})[0], 200)

    def test_health_get_still_works(self):
        self.assertEqual(self.send("GET", "/health")[0], 200)

    # Negative: DNS rebinding and cross-origin form POSTs.
    def test_foreign_or_missing_host_rejected_on_get_and_post(self):
        for host in ("evil.example", "evil.example:8765", "192.168.1.5", "localhost.evil.example", "0.0.0.0", "", None):
            with self.subTest(host=host):
                for status, body in (self.chat(host=host), self.send("GET", "/health", host=host)):
                    self.assertEqual((status, body["code"]), (403, "forbidden_host"))

    def test_non_json_content_type_rejected(self):
        for headers in ({}, {"Content-Type": "text/plain"}, {"Content-Type": "application/x-www-form-urlencoded"},
                        {"Content-Type": "multipart/form-data; boundary=x"}, {"Content-Type": "application/jsonx"}):
            with self.subTest(headers=headers):
                status, body = self.chat(headers=headers)
                self.assertEqual((status, body["code"]), (415, "unsupported_media_type"))

    def test_rejections_do_not_break_server(self):
        self.chat(host="evil.example")
        self.chat(headers={"Content-Type": "text/plain"})
        self.assertEqual(self.chat()[0], 200)

    def test_413_message_reports_actual_limit(self):
        status, body = self.send("POST", "/v1/chat", b"x" * (MAX_BODY + 1), {"Content-Type": "application/json"})
        self.assertEqual(status, 413)
        self.assertIn(f"1–{MAX_BODY} bytes", body["error"])
        status, body = self.send("POST", "/v1/cplus/e3/reserve", b"x" * 24001, {"Content-Type": "application/json"})
        self.assertEqual(status, 413)
        self.assertIn("1–24000 bytes", body["error"])


class LoopbackBindingTests(unittest.TestCase):
    def test_helper(self):
        for host in ("127.0.0.1", "127.0.0.9", "::1", "[::1]", "localhost", "LocalHost"):
            self.assertTrue(is_loopback_host(host), host)
        for host in ("0.0.0.0", "::", "192.168.0.2", "10.0.0.1", "example.com", "", "localhost.evil.com"):
            self.assertFalse(is_loopback_host(host), host)

    def test_server_refuses_non_loopback_without_any_override(self):
        for host in ("0.0.0.0", "::", "192.168.0.2"):
            with self.subTest(host=host), self.assertRaises(ValueError):
                make_http_server(Mock(), host)

    def test_settings_refuse_non_loopback_host(self):
        with patch.dict(os.environ, {"NPCORTEX_HOST": "0.0.0.0"}):
            with self.assertRaises(ValueError):
                Settings.from_environment()
        with patch.dict(os.environ, {"NPCORTEX_HOST": "127.0.0.1"}):
            self.assertEqual(Settings.from_environment().host, "127.0.0.1")


if __name__ == "__main__":
    unittest.main()
