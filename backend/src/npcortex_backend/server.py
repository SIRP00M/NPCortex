"""Local, dependency-free HTTP API; not an internet-facing service."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import logging
import re
import sqlite3
from .characters import CharacterError
from .identity import IdentityError, PLAYER_PATTERN
from .ollama import ModelError
from .service import ModelCapacityError
from .session import SessionBusyError, SessionCapacityError
from .world_store import RegistryNotFoundError, RegistryConflictError

LOG = logging.getLogger(__name__)
MAX_BODY = 8192
ACTOR_GET = re.compile(r"^/v1/actors/([^/]+)$")
CONVERSATION_GET = re.compile(r"^/v1/conversations/([^/]+)$")


def make_handler(service):
    class Handler(BaseHTTPRequestHandler):
        def _json(self, status: int, payload: dict):
            raw = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(raw)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            try:
                self.wfile.write(raw)
            except (BrokenPipeError, ConnectionResetError):
                LOG.info("Client disconnected before response could be delivered")

        def _error(self, status: int, code: str, message: str):
            # Preserve A2's human-readable `error` for existing clients.
            self._json(status, {"error": message, "code": code})

        def _handle_error(self, exc):
            if isinstance(exc, CharacterError):
                return self._error(400, "invalid_character", str(exc))
            if isinstance(exc, IdentityError):
                return self._error(400, "invalid_identity", str(exc))
            if isinstance(exc, RegistryNotFoundError):
                return self._error(404, "registry_not_found", str(exc))
            if isinstance(exc, RegistryConflictError):
                return self._error(409, "identity_conflict", str(exc))
            if isinstance(exc, SessionBusyError):
                return self._error(429, "session_busy", "This conversation is busy; retry after its previous reply")
            if isinstance(exc, SessionCapacityError):
                return self._error(503, "session_capacity", "Conversation capacity reached; retry shortly")
            if isinstance(exc, ModelCapacityError):
                return self._error(503, "model_busy", "Local AI model is busy; retry shortly")
            if isinstance(exc, ModelError):
                LOG.exception("Ollama request failed")
                return self._error(502, "provider_unavailable", "Local AI provider unavailable; check Ollama logs")
            if isinstance(exc, sqlite3.Error):
                LOG.exception("SQLite operation failed")
                return self._error(503, "storage_unavailable", "Local storage unavailable; check backend logs")
            LOG.exception("Unhandled backend error")
            return self._error(500, "internal_error", "Internal server error")

        def do_GET(self):
            if self.path == "/health":
                return self._json(200, {"status": "ok", "service": "npcortex-backend", "model": service.model})
            try:
                actor = ACTOR_GET.fullmatch(self.path)
                if actor:
                    return self._json(200, service._require_world_store().get_actor(actor.group(1)))
                conversation = CONVERSATION_GET.fullmatch(self.path)
                if conversation:
                    return self._json(200, service._require_world_store().get_conversation(conversation.group(1)))
            except Exception as exc:
                return self._handle_error(exc)
            return self._error(404, "not_found", "Not found")

        def do_POST(self):
            if self.path not in ("/v1/chat", "/v1/worlds", "/v1/actors", "/v1/actor-chat"):
                return self._error(404, "not_found", "Not found")
            if self.headers.get("Transfer-Encoding"):
                return self._error(400, "invalid_request", "Chunked request bodies are not supported")
            lengths = self.headers.get_all("Content-Length", [])
            if len(lengths) != 1:
                return self._error(400, "invalid_request", "Exactly one Content-Length header is required")
            try:
                length = int(lengths[0])
            except ValueError:
                return self._error(400, "invalid_request", "Invalid Content-Length")
            if length < 1 or length > MAX_BODY:
                return self._error(413, "request_too_large", "Request size must be 1–8192 bytes")
            self.connection.settimeout(10)
            try:
                data = json.loads(self.rfile.read(length).decode("utf-8"))
            except (ValueError, UnicodeDecodeError, TimeoutError, OSError):
                return self._error(400, "invalid_request", "Invalid JSON or incomplete request body")
            if not isinstance(data, dict):
                return self._error(400, "invalid_request", "Expected JSON object")
            try:
                if self.path == "/v1/worlds":
                    if "name" not in data:
                        return self._error(400, "invalid_request", "Missing world name")
                    return self._json(201, service.register_world(data["name"], data.get("world_id")))
                if self.path == "/v1/actors":
                    if not all(key in data for key in ("world_id", "dimension_id", "character_id")):
                        return self._error(400, "invalid_request", "Missing actor identity fields")
                    return self._json(201, service.register_actor(
                        data["world_id"], data["dimension_id"], data["character_id"], data.get("actor_id")))
                if self.path == "/v1/chat":
                    character_id, participant, message = (data.get("character_id"), data.get("player_id"), data.get("message"))
                    if (not isinstance(character_id, str) or not isinstance(participant, str)
                            or not PLAYER_PATTERN.fullmatch(participant)):
                        return self._error(400, "invalid_request", "Invalid character_id or player_id")
                    if not isinstance(message, str) or not 1 <= len(message.strip()) <= 2000:
                        return self._error(400, "invalid_request", "Message must contain 1–2000 characters")
                    return self._json(200, service.chat(character_id, participant, message.strip()))
                # B1 only: player -> specific actor; NPC -> NPC orchestration belongs to B2.
                if not all(key in data for key in ("world_id", "dimension_id", "actor_id", "player_id", "message")):
                    return self._error(400, "invalid_request", "Missing actor-chat fields")
                message = data["message"]
                if not isinstance(message, str) or not 1 <= len(message.strip()) <= 2000:
                    return self._error(400, "invalid_request", "Message must contain 1–2000 characters")
                return self._json(200, service.chat_actor(data["world_id"], data["dimension_id"],
                                                         data["actor_id"], data["player_id"], message.strip()))
            except Exception as exc:
                return self._handle_error(exc)

        def log_message(self, format, *args):
            LOG.info("%s - %s", self.address_string(), format % args)

    return Handler


def serve(service, host="127.0.0.1", port=8765):
    logging.basicConfig(level=logging.INFO, format="%(levelname)s: %(message)s")
    httpd = ThreadingHTTPServer((host, port), make_handler(service))
    httpd.daemon_threads = True
    print(f"NPCortex backend listening at http://{host}:{port}", flush=True)
    print("Local prototype: no authentication; do not expose publicly.", flush=True)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        httpd.server_close()
