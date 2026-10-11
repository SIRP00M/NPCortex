"""Local-only HTTP backend; not safe to expose on the public internet."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import logging
import re
from .characters import CharacterError
from .ollama import ModelError

LOG = logging.getLogger(__name__)
ID_PATTERN = re.compile(r"^[a-zA-Z0-9_-]{1,64}$")
MAX_BODY = 8192


def make_handler(service):
    class Handler(BaseHTTPRequestHandler):
        def _json(self, status: int, payload):
            raw = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(raw)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(raw)

        def do_GET(self):
            if self.path == "/health":
                return self._json(200, {"status": "ok", "service": "npcortex-backend", "model": service.model})
            if self.path == "/v1/characters":
                return self._json(200, {"characters": service.characters()})
            if self.path.startswith("/v1/characters/"):
                character_id = self.path[len("/v1/characters/"):]
                try:
                    return self._json(200, service.character(character_id))
                except CharacterError as exc:
                    return self._json(404, {"error": str(exc)})
            return self._json(404, {"error": "Not found"})

        def do_POST(self):
            if self.path not in ("/v1/chat", "/v1/agent"):
                return self._json(404, {"error": "Not found"})
            try:
                length = int(self.headers.get("Content-Length", "0"))
            except ValueError:
                return self._json(400, {"error": "Invalid Content-Length"})
            if length < 1 or length > MAX_BODY:
                return self._json(413, {"error": "Request size must be 1–8192 bytes"})
            try:
                data = json.loads(self.rfile.read(length))
            except (ValueError, UnicodeDecodeError):
                return self._json(400, {"error": "Invalid JSON"})
            if not isinstance(data, dict):
                return self._json(400, {"error": "Expected JSON object"})
            character_id = data.get("character_id")
            player_id = data.get("player_id")
            message = data.get("message")
            session_id = data.get("session_id")
            if not isinstance(character_id, str) or not isinstance(player_id, str) or not ID_PATTERN.fullmatch(player_id):
                return self._json(400, {"error": "Invalid character_id or player_id"})
            if session_id is not None and (not isinstance(session_id, str) or not ID_PATTERN.fullmatch(session_id)):
                return self._json(400, {"error": "Invalid session_id"})
            if not isinstance(message, str) or not 1 <= len(message.strip()) <= 2000:
                return self._json(400, {"error": "Message must contain 1–2000 characters"})
            try:
                if self.path == "/v1/agent":
                    if session_id is None:
                        return self._json(400, {"error": "session_id required for agent requests"})
                    try:
                        from .agent import sanitize_observation
                        world = sanitize_observation(data.get("world"))
                    except ValueError as exc:
                        return self._json(400, {"error": str(exc)})
                    return self._json(200, service.agent(character_id, player_id,
                                                         message.strip(), session_id, world))
                return self._json(200, service.chat(character_id, player_id, message.strip(), session_id))
            except CharacterError as exc:
                return self._json(400, {"error": str(exc)})
            except ModelError:
                LOG.exception("Ollama request failed")
                return self._json(502, {"error": "Local AI provider unavailable; check Ollama logs"})
            except Exception:
                LOG.exception("Unhandled backend error")
                return self._json(500, {"error": "Internal server error"})

        def log_message(self, format, *args):
            LOG.info("%s - %s", self.address_string(), format % args)

    return Handler


def serve(service, host="127.0.0.1", port=8765):
    logging.basicConfig(level=logging.INFO, format="%(levelname)s: %(message)s")
    httpd = ThreadingHTTPServer((host, port), make_handler(service))
    print(f"NPCortex backend listening at http://{host}:{port}", flush=True)
    print("Local prototype: no authentication; do not expose publicly.", flush=True)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        httpd.server_close()
