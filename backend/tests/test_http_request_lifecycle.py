"""B3 HTTP hardening regression, especially Windows oversized POST reset."""
import json
from pathlib import Path
from threading import Thread
from unittest.mock import Mock
import sys
import unittest
from urllib.error import HTTPError
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "backend" / "src"))
from npcortex_backend.server import MAX_BODY, MAX_REJECT_DRAIN, make_http_server
from npcortex_backend.service import ChatService
from npcortex_backend.session import SessionStore


class HTTPRequestLifecycleTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        provider = Mock()
        provider.chat.return_value = "Hello from Yui"
        cls.service = ChatService(ROOT / "character-packs", "fake", provider, SessionStore())
        cls.httpd = make_http_server(cls.service)
        cls.thread = Thread(target=cls.httpd.serve_forever, daemon=True)
        cls.thread.start()
        cls.base = f"http://127.0.0.1:{cls.httpd.server_port}"

    @classmethod
    def tearDownClass(cls):
        cls.httpd.shutdown()
        cls.httpd.server_close()
        cls.thread.join(timeout=3)

    def post(self, path, payload, headers=None):
        req = Request(self.base + path, data=payload, method="POST", headers=headers or {})
        try:
            with urlopen(req, timeout=4) as response:
                return response.status, json.load(response)
        except HTTPError as error:
            with error:
                return error.code, json.load(error)

    def check_chat_after_rejection(self):
        body = json.dumps({"character_id": "yui", "player_id": "http-recover",
                           "message": "hello"}).encode()
        status, data = self.post("/v1/chat", body)
        self.assertEqual(status, 200)
        self.assertEqual(data["reply"], "Hello from Yui")

    def test_oversized_body_413_repeated_without_abort(self):
        # Windows previously saw WinError 10053 at MAX_BODY + 1. Exercise
        # multiple request sizes over many independent TCP connections.
        for length in [MAX_BODY + 1, 16_384, 65_536, MAX_REJECT_DRAIN]:
            for iteration in range(12 if length <= 65_536 else 2):
                with self.subTest(length=length, iteration=iteration):
                    status, response = self.post("/v1/chat", b"x" * length)
                    self.assertEqual(status, 413)
                    self.assertEqual(response["code"], "request_too_large")
                    self.assertIn("error", response)
        self.check_chat_after_rejection()

    def test_unrecognized_route_discards_body_before_404(self):
        for length in (2, MAX_BODY + 1, 24_576):
            for iteration in range(12):
                with self.subTest(length=length, iteration=iteration):
                    status, body = self.post("/not-an-endpoint", b"z" * length)
                    self.assertEqual((status, body["code"]), (404, "not_found"))
        self.check_chat_after_rejection()

    def test_bad_json_400_repeated_and_recover(self):
        for payload in (b'{"bad":', b'[]', b'\xff'):
            for iteration in range(15):
                with self.subTest(payload=payload, iteration=iteration):
                    status, body = self.post("/v1/chat", payload)
                    self.assertEqual((status, body["code"]), (400, "invalid_request"))
        self.check_chat_after_rejection()

    def test_unsupported_transfer_encoding_rejected_and_server_recovers(self):
        # Verify the unsupported framing header without racing a client that
        # streams an unbounded chunked body after the server has rejected it.
        import http.client
        c = http.client.HTTPConnection('127.0.0.1', self.httpd.server_port, timeout=3)
        try:
            c.putrequest('POST', '/v1/chat')
            c.putheader('Transfer-Encoding', 'chunked')
            c.endheaders()
            response = c.getresponse()
            self.assertEqual(response.status, 400)
            self.assertEqual(json.loads(response.read())['code'], 'invalid_request')
        finally:
            c.close()
        self.check_chat_after_rejection()

    def test_headerless_post_is_rejected(self):
        # A raw socket request without Content-Length exercises the otherwise
        # untested 400 response path. Nothing is left pending to drain.
        import http.client
        c = http.client.HTTPConnection('127.0.0.1', self.httpd.server_port, timeout=3)
        try:
            c.putrequest('POST', '/v1/chat')
            c.endheaders()
            response = c.getresponse()
            self.assertEqual(response.status, 400)
            self.assertEqual(json.loads(response.read())['code'], 'invalid_request')
        finally:
            c.close()
        self.check_chat_after_rejection()


if __name__ == '__main__':
    unittest.main()
