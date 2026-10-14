"""A2 regression tests: sessions, concurrency, backpressure, API, configuration."""
from concurrent.futures import ThreadPoolExecutor
import json
import os
from pathlib import Path
import sys
from threading import Event, Thread
import time
import unittest
from unittest.mock import Mock, patch
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "backend" / "src"))

from npcortex_backend.config import Settings
from npcortex_backend.ollama import ModelError, OllamaProvider
from npcortex_backend.server import make_handler, make_http_server
from npcortex_backend.service import ChatService, ModelCapacityError
from npcortex_backend.session import SessionStore, SessionBusyError, SessionCapacityError

PACKS = ROOT / "character-packs"


def chat_service(provider=None, sessions=None, **kwargs):
    if provider is None:
        provider = Mock()
        provider.chat.return_value = "Hi!"
    return ChatService(PACKS, "qwen3:8b", provider, sessions or SessionStore(), **kwargs)


class SessionLifecycleTest(unittest.TestCase):
    def test_expiration_clears_idle_context(self):
        clock = [0.0]
        sessions = SessionStore(idle_ttl_seconds=30, clock=lambda: clock[0])
        sessions.add_exchange("one", "yui", "hello", "hi")
        clock[0] = 29.0
        self.assertEqual(len(sessions.get("one", "yui")), 2)
        clock[0] = 60.0
        self.assertEqual(sessions.get("one", "yui"), [])

    def test_lru_evicts_only_inactive_sessions(self):
        clock = [0.0]
        sessions = SessionStore(max_sessions=2, clock=lambda: clock[0])
        sessions.add_exchange("old", "yui", "one", "reply")
        clock[0] = 1.0
        sessions.add_exchange("new", "yui", "two", "reply")
        clock[0] = 2.0
        sessions.add_exchange("latest", "yui", "three", "reply")
        self.assertEqual(sessions.get("old", "yui"), [])
        self.assertEqual(len(sessions.get("new", "yui")), 2)
        self.assertEqual(len(sessions.get("latest", "yui")), 2)

    def test_active_session_cannot_expire_or_be_evicted(self):
        clock = [0.0]
        sessions = SessionStore(max_sessions=1, idle_ttl_seconds=1, clock=lambda: clock[0])
        with sessions.conversation("one", "yui") as turn:
            turn.add_exchange("a", "b")
            clock[0] = 300
            with self.assertRaises(SessionCapacityError):
                with sessions.conversation("two", "yui"):
                    pass
            self.assertEqual(len(turn.history()), 2)
        with sessions.conversation("two", "yui"):
            pass
        self.assertEqual(sessions.get("one", "yui"), [])

    def test_same_session_rejects_excess_requests(self):
        sessions = SessionStore(max_requests_per_session=1, wait_timeout_seconds=0)
        with sessions.conversation("one", "yui"):
            with self.assertRaises(SessionBusyError):
                with sessions.conversation("one", "yui"):
                    pass

    def test_failed_turn_does_not_poison_future_turns(self):
        provider = Mock()
        provider.chat.side_effect = [ModelError("offline"), "recovered"]
        sessions = SessionStore()
        service = chat_service(provider, sessions)
        with self.assertRaises(ModelError):
            service.chat("yui", "player-1", "failed")
        self.assertEqual(sessions.get("player-1", "yui"), [])
        self.assertEqual(service.chat("yui", "player-1", "success")["reply"], "recovered")
        self.assertEqual([m["content"] for m in provider.chat.call_args.args[0][1:]], ["success"])

    def test_same_session_requests_are_serialized_in_order(self):
        started = Event()
        release = Event()
        calls = []

        def provider_call(messages):
            calls.append(messages)
            if len(calls) == 1:
                started.set()
                self.assertTrue(release.wait(3))
            return f"answer-{len(calls)}"

        provider = Mock()
        provider.chat.side_effect = provider_call
        service = chat_service(provider)
        with ThreadPoolExecutor(max_workers=2) as pool:
            one = pool.submit(service.chat, "yui", "same", "first")
            self.assertTrue(started.wait(2))
            two = pool.submit(service.chat, "yui", "same", "second")
            # Ensure second request reaches the session lease before releasing first.
            until = time.monotonic() + 2
            while service.sessions._sessions[("same", "yui")].requests != 2 and time.monotonic() < until:
                time.sleep(0.005)
            self.assertEqual(service.sessions._sessions[("same", "yui")].requests, 2)
            release.set()
            self.assertEqual(one.result(timeout=3)["reply"], "answer-1")
            self.assertEqual(two.result(timeout=3)["reply"], "answer-2")
        self.assertEqual([m["content"] for m in calls[1][1:]], ["first", "answer-1", "second"])

    def test_waiter_timeout_is_bounded_and_state_recovers(self):
        sessions = SessionStore(max_requests_per_session=2, wait_timeout_seconds=0)
        started = Event()
        release = Event()
        provider = Mock()

        def slow(messages):
            started.set()
            if not release.wait(3):
                raise AssertionError("provider stalled")
            return "done"

        provider.chat.side_effect = slow
        service = chat_service(provider, sessions)
        with ThreadPoolExecutor(max_workers=1) as pool:
            first = pool.submit(service.chat, "yui", "one", "first")
            self.assertTrue(started.wait(2))
            with self.assertRaises(SessionBusyError):
                service.chat("yui", "one", "second")
            release.set()
            self.assertEqual(first.result(timeout=3)["reply"], "done")
        self.assertEqual(sessions._sessions[("one", "yui")].requests, 0)

    def test_model_slots_are_bounded_and_release_on_failure(self):
        started = Event()
        release = Event()
        provider = Mock()

        def slow(messages):
            started.set()
            if not release.wait(3):
                raise AssertionError("provider stalled")
            raise ModelError("offline")

        provider.chat.side_effect = slow
        service = chat_service(provider, max_model_requests=1, model_wait_seconds=0)
        with ThreadPoolExecutor(max_workers=1) as pool:
            first = pool.submit(service.chat, "yui", "one", "first")
            self.assertTrue(started.wait(2))
            with self.assertRaises(ModelCapacityError):
                service.chat("yui", "two", "second")
            release.set()
            with self.assertRaises(ModelError):
                first.result(timeout=3)
        provider.chat.side_effect = None
        provider.chat.return_value = "back online"
        self.assertEqual(service.chat("yui", "two", "retry")["reply"], "back online")


class ConfigTest(unittest.TestCase):
    def test_defaults_are_valid(self):
        with patch.dict(os.environ, {}, clear=True):
            settings = Settings.from_environment()
        self.assertEqual(settings.model, "qwen3:8b")
        self.assertEqual(settings.port, 8765)
        self.assertEqual(settings.max_sessions, 128)

    def test_invalid_environment_is_rejected(self):
        cases = [
            ("NPCORTEX_PORT", "oops"),
            ("NPCORTEX_PORT", "70000"),
            ("NPCORTEX_MAX_SESSIONS", "0"),
            ("NPCORTEX_SESSION_TTL_SECONDS", "-1"),
            ("NPCORTEX_MAX_SESSION_REQUESTS", "0"),
            ("NPCORTEX_MODEL_WAIT_SECONDS", "-1"),
            ("NPCORTEX_OLLAMA_URL", "file:///etc/passwd"),
            ("NPCORTEX_OLLAMA_URL", "http://127.0.0.1:11434/path"),
            ("NPCORTEX_MODEL", " "),
            ("NPCORTEX_PACKS_DIR", str(ROOT / "missing-directory")),
        ]
        for key, value in cases:
            with self.subTest(key=key, value=value), patch.dict(os.environ, {key: value}, clear=True):
                with self.assertRaises(ValueError):
                    Settings.from_environment()


class HttpTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.provider = Mock()
        cls.provider.chat.return_value = "Mock response"
        cls.service = chat_service(cls.provider)
        cls.httpd = make_http_server(cls.service)
        cls.httpd.daemon_threads = True
        cls.thread = Thread(target=cls.httpd.serve_forever, daemon=True)
        cls.thread.start()
        cls.base = f"http://127.0.0.1:{cls.httpd.server_port}"

    @classmethod
    def tearDownClass(cls):
        cls.httpd.shutdown()
        cls.httpd.server_close()
        cls.thread.join(timeout=2)

    def request(self, path="/v1/chat", data=None, raw=None, method="POST", headers=None):
        if data is None:
            data = {"character_id": "yui", "player_id": "http-player", "message": "hello"}
        if raw is None and method == "POST":
            raw = json.dumps(data).encode("utf-8")
        req = Request(self.base + path, data=raw, headers=headers or {}, method=method)
        try:
            with urlopen(req, timeout=3) as response:
                return response.status, json.load(response)
        except HTTPError as error:
            return error.code, json.load(error)

    def test_unchanged_success_contract_and_health(self):
        status, health = self.request(path="/health", method="GET")
        self.assertEqual(status, 200)
        self.assertEqual(health["status"], "ok")
        status, chat = self.request()
        self.assertEqual(status, 200)
        self.assertEqual(set(chat), {"character_id", "character_name", "reply", "model"})
        self.assertEqual(chat["reply"], "Mock response")

    def test_validation_errors_keep_error_field_and_add_code(self):
        cases = [
            ("/nope", None, 404, "not_found"),
            ("/v1/chat", {"character_id": "unknown", "player_id": "p", "message": "hi"}, 400, "invalid_character"),
            ("/v1/chat", {"character_id": "yui", "player_id": "bad!", "message": "hi"}, 400, "invalid_request"),
            ("/v1/chat", {"character_id": "yui", "player_id": "p", "message": " "}, 400, "invalid_request"),
        ]
        for path, data, expected_status, expected_code in cases:
            with self.subTest(path=path, data=data):
                status, body = self.request(path=path, data=data)
                self.assertEqual((status, body["code"]), (expected_status, expected_code))
                self.assertIsInstance(body["error"], str)

    def test_malformed_and_large_json(self):
        for raw, expected_code, expected_status in [
            (b'{"bad":', "invalid_request", 400),
            (b'[]', "invalid_request", 400),
            (b'\xff', "invalid_request", 400),
            (b'x' * 8193, "request_too_large", 413),
        ]:
            with self.subTest(raw=raw[:10]):
                status, body = self.request(raw=raw)
                self.assertEqual((status, body["code"]), (expected_status, expected_code))

    def test_provider_failure_is_502_and_does_not_leak_detail(self):
        with patch.object(self.provider, "chat", side_effect=ModelError("private api token")):
            status, body = self.request(data={"character_id": "guide", "player_id": "provider-test", "message": "test"})
        self.assertEqual(status, 502)
        self.assertEqual(body["code"], "provider_unavailable")
        self.assertNotIn("private", json.dumps(body))

    def test_busy_capacity_http_mapping(self):
        with patch.object(self.service, "chat", side_effect=SessionBusyError("busy")):
            status, body = self.request()
            self.assertEqual((status, body["code"]), (429, "session_busy"))
        with patch.object(self.service, "chat", side_effect=SessionCapacityError("busy")):
            status, body = self.request()
            self.assertEqual((status, body["code"]), (503, "session_capacity"))
        with patch.object(self.service, "chat", side_effect=ModelCapacityError("busy")):
            status, body = self.request()
            self.assertEqual((status, body["code"]), (503, "model_busy"))


class OllamaTransportTest(unittest.TestCase):
    def test_actual_local_http_transport_payload(self):
        captured = []

        class ProviderHandler(BaseHTTPRequestHandler):
            def do_POST(self):
                body = self.rfile.read(int(self.headers["Content-Length"]))
                captured.append((self.path, json.loads(body)))
                response = json.dumps({"message": {"content": "mock llama reply"}}).encode()
                self.send_response(200)
                self.send_header("Content-Length", str(len(response)))
                self.end_headers()
                self.wfile.write(response)

            def log_message(self, *_):
                pass

        server = ThreadingHTTPServer(("127.0.0.1", 0), ProviderHandler)
        thread = Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            adapter = OllamaProvider(f"http://127.0.0.1:{server.server_port}", "qwen3:8b")
            reply = adapter.chat([{"role": "user", "content": "hello"}])
            self.assertEqual(reply, "mock llama reply")
            path, sent = captured[0]
            self.assertEqual(path, "/api/chat")
            self.assertEqual(sent["model"], "qwen3:8b")
            self.assertFalse(sent["stream"])
            self.assertFalse(sent["think"])
            self.assertEqual(sent["messages"][0]["content"], "hello")
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)

    def test_provider_rejects_invalid_json_response(self):
        class BrokenHandler(BaseHTTPRequestHandler):
            def do_POST(self):
                self.send_response(200)
                self.send_header("Content-Length", "5")
                self.end_headers()
                self.wfile.write(b"error")

            def log_message(self, *_):
                pass

        server = ThreadingHTTPServer(("127.0.0.1", 0), BrokenHandler)
        thread = Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            with self.assertRaises(ModelError):
                OllamaProvider(f"http://127.0.0.1:{server.server_port}", "qwen3:8b").chat([])
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)


if __name__ == "__main__":
    unittest.main()
