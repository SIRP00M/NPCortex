"""D1: a rejected POST must never lose its response to a connection reset.

Windows resets a TCP connection when a server closes a socket that still has unread request bytes. The 404/413/415
branches already drain the rejected body; the Host rejection used to answer 403 and close first (flaky WinError
10053 in test_http_security). These tests pin the drain on every rejection branch, the byte/time bounds that keep a
hostile client from holding a handler thread, and that authentication / Host / status codes are unchanged."""
import http.client
import json
import socket
import sys
import time
import unittest
from pathlib import Path
from threading import Thread
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "backend" / "src"))
from npcortex_backend.server import MAX_REJECT_DRAIN, REJECT_DRAIN_TIMEOUT_SECONDS, make_http_server
from npcortex_backend.service import ChatService
from npcortex_backend.session import SessionStore

CHAT = json.dumps({"character_id": "yui", "player_id": "sec", "message": "hello"}).encode()
TOKEN = "t" * 32
JSON = {"Content-Type": "application/json"}
BAD_HOSTS = ("evil.example", "evil.example:8765", "192.168.1.5", "localhost.evil.example", "0.0.0.0", "", None)


class RejectionReliability(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        provider = Mock()
        provider.chat.return_value = "Hello from Yui"
        cls.httpd = make_http_server(ChatService(ROOT / "character-packs", "fake", provider, SessionStore()))
        cls.thread = Thread(target=cls.httpd.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.httpd.shutdown()
        cls.httpd.server_close()
        cls.thread.join(timeout=3)

    def send(self, method, path, body=None, headers=None, host="default"):
        conn = http.client.HTTPConnection("127.0.0.1", self.httpd.server_port, timeout=6)
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

    def healthy(self):
        self.assertEqual(self.send("GET", "/health")[0], 200)

    # ---- Host rejection (the defect) ------------------------------------------------------------------------
    def test_host_rejected_post_always_delivers_its_403(self):
        # The original flake: 14 requests (7 hosts x GET/POST) failed ~1 run in 3. 20 rounds make it near-certain.
        for round_ in range(20):
            for host in BAD_HOSTS:
                with self.subTest(round=round_, host=host):
                    self.assertEqual(self.send("POST", "/v1/chat", CHAT, JSON, host=host)[1]["code"], "forbidden_host")

    def test_host_rejected_post_with_large_bodies_up_to_the_drain_limit(self):
        for size in (4096, 65536, 600_000):
            for i in range(10):
                with self.subTest(size=size, i=i):
                    status, body = self.send("POST", "/v1/chat", b"x" * size, JSON, host="evil.example")
                    self.assertEqual((status, body["code"]), (403, "forbidden_host"))
        self.healthy()

    def test_host_rejection_applies_to_every_post_route_class(self):
        for path in ("/v1/chat", "/v1/actors", "/v1/cplus/e3/finalize", "/v1/no-such-route"):
            with self.subTest(path=path):
                status, body = self.send("POST", path, CHAT, JSON, host="evil.example")
                self.assertEqual((status, body["code"]), (403, "forbidden_host"))

    def test_host_check_still_runs_before_anything_else(self):
        # A bad Host with an oversized or non-JSON body is still forbidden_host, not 413/415.
        self.assertEqual(self.send("POST", "/v1/chat", b"x" * 200_000, {}, host="evil.example")[1]["code"], "forbidden_host")
        self.assertEqual(self.send("GET", "/health", host="evil.example")[1]["code"], "forbidden_host")

    # ---- bounds ---------------------------------------------------------------------------------------------
    def test_declared_length_above_the_drain_limit_is_not_read(self):
        started = time.monotonic()
        try:
            # Declares far more than MAX_REJECT_DRAIN but sends little: the server must not wait for it.
            status, body = self.send_raw(declared=MAX_REJECT_DRAIN * 5, sent=b"x" * 1024)
            self.assertEqual(status, 403)
        except (ConnectionError, socket.timeout, OSError):
            pass          # a reset is acceptable here; hanging is not
        self.assertLess(time.monotonic() - started, REJECT_DRAIN_TIMEOUT_SECONDS + 3)
        self.healthy()

    def test_slow_body_cannot_hold_the_handler_beyond_the_drain_timeout(self):
        started = time.monotonic()
        try:
            status, body = self.send_raw(declared=1000, sent=b"x" * 10, linger=REJECT_DRAIN_TIMEOUT_SECONDS + 4)
            self.assertEqual(status, 403)
        except (ConnectionError, socket.timeout, OSError):
            pass
        elapsed = time.monotonic() - started
        self.assertLess(elapsed, REJECT_DRAIN_TIMEOUT_SECONDS + 3, "the drain is bounded by time")
        self.healthy()

    def test_chunked_body_is_never_drained_and_still_rejected(self):
        started = time.monotonic()
        try:
            status, body = self.send_raw(declared=None, sent=b"5\r\nhello\r\n0\r\n\r\n", chunked=True)
            self.assertEqual(status, 403)
        except (ConnectionError, socket.timeout, OSError):
            pass
        self.assertLess(time.monotonic() - started, 3)
        self.healthy()

    def send_raw(self, declared, sent, linger=0, chunked=False):
        sock = socket.create_connection(("127.0.0.1", self.httpd.server_port), timeout=REJECT_DRAIN_TIMEOUT_SECONDS + 6)
        try:
            lines = ["POST /v1/chat HTTP/1.1", "Host: evil.example", "Content-Type: application/json"]
            lines.append("Transfer-Encoding: chunked" if chunked else f"Content-Length: {declared}")
            sock.sendall(("\r\n".join(lines) + "\r\n\r\n").encode() + sent)
            data = b""
            while b"\r\n\r\n" not in data or not data.rstrip().endswith(b"}"):
                chunk = sock.recv(4096)
                if not chunk:
                    break
                data += chunk
            head, _, body = data.partition(b"\r\n\r\n")
            return int(head.split()[1]), json.loads(body or b"{}")
        finally:
            sock.close()

    # ---- token rejections: body already consumed, behaviour unchanged ---------------------------------------
    def test_profile_routes_always_need_the_token_and_deliver_the_403(self):
        route = "/v1/actors/some-actor/profile/revisions"
        with patch("npcortex_backend.server.read_secret", return_value=TOKEN):
            for i in range(15):
                with self.subTest(i=i):
                    status, body = self.send("POST", route, b"{" + b" " * 20_000 + b"}", JSON)
                    self.assertEqual((status, body["code"]), (403, "forbidden"))
                    status, body = self.send("POST", route, b"{}", {**JSON, "X-NPCortex-CPlus-Token": "w" * 32})
                    self.assertEqual((status, body["code"]), (403, "forbidden"))
            status, body = self.send("POST", route, b"{}", {**JSON, "X-NPCortex-CPlus-Token": TOKEN})
            self.assertEqual((status, body["code"]), (400, "invalid_request"),
                             "the valid token passes authentication; the empty body is then rejected on its merits")

    def test_legacy_route_token_policy_unchanged(self):
        with patch("npcortex_backend.server.read_secret", return_value=TOKEN):
            with patch.dict("os.environ", {"NPCORTEX_REQUIRE_TOKEN": "1"}):
                for i in range(10):
                    with self.subTest(i=i):
                        self.assertEqual(self.send("POST", "/v1/chat", CHAT, JSON)[0], 403)
                        self.assertEqual(self.send("POST", "/v1/chat", CHAT, {**JSON, "X-NPCortex-CPlus-Token": "w" * 32})[0], 403)
                self.assertEqual(self.send("POST", "/v1/chat", CHAT, {**JSON, "X-NPCortex-CPlus-Token": TOKEN})[0], 200)
            # default policy untouched: no token needed when the opt-in is off
            self.assertEqual(self.send("POST", "/v1/chat", CHAT, JSON)[0], 200)

    def test_cplus_bridge_rejects_without_token(self):
        with patch("npcortex_backend.server.read_secret", return_value=TOKEN):
            for i in range(10):
                status, body = self.send("POST", "/v1/cplus/e3/finalize", b"{}", JSON)
                self.assertIn(status, (403, 404, 503))

    # ---- other rejection branches keep draining -------------------------------------------------------------
    def test_existing_rejections_still_deliver(self):
        for i in range(10):
            with self.subTest(i=i):
                self.assertEqual(self.send("POST", "/v1/chat", b"x" * 300_000, JSON)[0], 413)
                self.assertEqual(self.send("POST", "/v1/chat", CHAT, {"Content-Type": "text/plain"})[0], 415)
                self.assertEqual(self.send("POST", "/nope", CHAT, JSON)[0], 404)
        self.healthy()


if __name__ == "__main__":
    unittest.main()
