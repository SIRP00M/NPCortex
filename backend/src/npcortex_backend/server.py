"""Local, dependency-free HTTP API; not an internet-facing service."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import logging
import os
import hmac
import ipaddress
from .cplus_f1_bridge import DurableBridge, BridgeConflict
from .cplus_e_graph import ReadOnlyGraph, GraphConflict
from .cplus_e_coordinator import ExtendedCoordinator
from .cplus_e2_dispatcher import ReadOnlyDispatcher
from .cplus_e3_preview import MovePreviewStore
from .cplus_e3_g2_motion import GuardedMotionStore
from .cplus_local_auth import read_secret
import re
import sqlite3
import time
import threading
from .characters import CharacterError
from .config import is_loopback_host
from .identity import IdentityError, PLAYER_PATTERN
from .ollama import ModelError
from .agent_runtime import PlannerDecisionError
from .service import ModelCapacityError
from .session import SessionBusyError, SessionCapacityError
from .world_store import RegistryNotFoundError, RegistryConflictError

LOG = logging.getLogger(__name__)
MAX_BODY = 8192
# Invalid/rejected POST bodies are discarded in small pieces to avoid a TCP
# reset on Windows when sending a JSON 4xx response with unread bytes.
# Never buffer the rejected payload or accept an unbounded drain workload.
MAX_REJECT_DRAIN = 1024 * 1024
REJECT_DRAIN_TIMEOUT_SECONDS = 2
ACTOR_GET = re.compile(r"^/v1/actors/([^/]+)$")
CONVERSATION_GET = re.compile(r"^/v1/conversations/([^/]+)$")
DIALOGUE_GET = re.compile(r"^/v1/dialogues/([^/]+)$")
DIALOGUE_TURNS = re.compile(r"^/v1/dialogues/([^/]+)/turns$")
DIALOGUE_CLOSE = re.compile(r"^/v1/dialogues/([^/]+)/close$")
SOCIAL_GET = re.compile(r"^/v1/social-runs/([^/]+)$")
SOCIAL_STEP = re.compile(r"^/v1/social-runs/([^/]+)/step$")
SOCIAL_STOP = re.compile(r"^/v1/social-runs/([^/]+)/stop$")
AGENT_GOAL_GET = re.compile(r"^/v1/agent/goals/([^/]+)$")
AGENT_OBSERVE = re.compile(r"^/v1/agent/goals/([^/]+)/observe$")
AGENT_DECIDE = re.compile(r"^/v1/agent/goals/([^/]+)/decide$")
AGENT_CANCEL = re.compile(r"^/v1/agent/goals/([^/]+)/cancel$")
AGENT_RESULT = re.compile(r"^/v1/agent/actions/([^/]+)/result$")


def _host_header_allowed(raw):
    """Reject DNS-rebinding requests: browsers cannot forge Host, so it must name loopback."""
    if not raw or len(raw) > 255:
        return False
    if raw.startswith("["):
        end = raw.find("]")
        host = raw[1:end] if end > 0 else ""
    else:
        host = raw.rsplit(":", 1)[0] if raw.count(":") == 1 else raw
    return bool(host) and is_loopback_host(host)


def _is_json_content_type(raw):
    return (raw or "").split(";", 1)[0].strip().lower() == "application/json"


def make_handler(service):
    # Construct only if the bridge is configured; legacy HTTP routes remain unchanged.
    bridge_holder = {}
    initialization_lock = threading.RLock()
    def cplus_bridge():
        with initialization_lock:
            if 'instance' not in bridge_holder:
                bridge_holder['instance'] = DurableBridge(service._require_world_store().db_path)
            return bridge_holder['instance']

    def cplus_e_graph():
        with initialization_lock:
            if "e" not in bridge_holder:
                bridge_holder["e"] = ReadOnlyGraph(service._require_world_store().db_path)
            return bridge_holder["e"]

    def cplus_e_coordinator():
        with initialization_lock:
            if "e1" not in bridge_holder:
                bridge_holder["e1"] = ReadOnlyDispatcher(service._require_world_store().db_path)
            return bridge_holder["e1"]

    def cplus_e3_preview():
        with initialization_lock:
            if "e3_g1" not in bridge_holder:
                bridge_holder["e3_g1"] = MovePreviewStore(service._require_world_store().db_path)
            return bridge_holder["e3_g1"]

    def cplus_e3_motion():
        with initialization_lock:
            if "e3_g2" not in bridge_holder:
                bridge_holder["e3_g2"] = GuardedMotionStore(service._require_world_store().db_path)
            return bridge_holder["e3_g2"]

    class Handler(BaseHTTPRequestHandler):
        def _json(self, status: int, payload: dict):
            raw = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(raw)))
            self.send_header("Cache-Control", "no-store")
            if self.close_connection:
                self.send_header("Connection", "close")
            self.end_headers()
            try:
                self.wfile.write(raw)
            except (BrokenPipeError, ConnectionResetError):
                LOG.info("Client disconnected before response could be delivered")

        def _error(self, status: int, code: str, message: str):
            # Preserve A2's human-readable `error` for existing clients.
            self._json(status, {"error": message, "code": code})

        def _discard_rejected_body(self, length: int):
            """Drain a rejected Content-Length body without allocating it in RAM.

            Windows can reset a connection when a server responds then closes
            the socket while incoming request bytes remain unread. The drain is
            bounded by *bytes*, *time* and *buffer size*: oversized/untrusted
            bodies must never be allowed to monopolize a server thread.
            """
            if length <= 0:
                if length < 0:
                    self.close_connection = True
                return
            if length > MAX_REJECT_DRAIN:
                self.close_connection = True
                return
            # This server uses HTTP/1.0 response semantics (one request per
            # connection), so the timeout is scoped to this request's socket.
            deadline = time.monotonic() + REJECT_DRAIN_TIMEOUT_SECONDS
            try:
                remaining = length
                while remaining:
                    remaining_time = deadline - time.monotonic()
                    if remaining_time <= 0:
                        self.close_connection = True
                        return
                    self.connection.settimeout(remaining_time)
                    chunk = self.rfile.read(min(8192, remaining))
                    if not chunk:
                        self.close_connection = True
                        return
                    remaining -= len(chunk)
            except (TimeoutError, OSError):
                self.close_connection = True

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
            if isinstance(exc, PlannerDecisionError):
                # A semantically invalid model response is NOT an Ollama transport outage.
                LOG.warning("C0 planner validation exhausted: %s", exc)
                return self._error(502, "planner_invalid_decision", "AI returned no valid skill decision; check backend logs")
            if isinstance(exc, ModelError):
                LOG.exception("Ollama request failed")
                return self._error(502, "provider_unavailable", "Local AI provider unavailable; check Ollama logs")
            if isinstance(exc, sqlite3.Error):
                LOG.exception("SQLite operation failed")
                return self._error(503, "storage_unavailable", "Local storage unavailable; check backend logs")
            LOG.exception("Unhandled backend error")
            return self._error(500, "internal_error", "Internal server error")

        def _host_ok(self):
            if _host_header_allowed(self.headers.get("Host")):
                return True
            self.close_connection = True
            self._error(403, "forbidden_host", "Host header must name a loopback address")
            return False

        def do_GET(self):
            if not self._host_ok():
                return
            if self.path == "/health":
                return self._json(200, {"status": "ok", "service": "npcortex-backend", "model": service.model})
            try:
                agent_goal = AGENT_GOAL_GET.fullmatch(self.path)
                if agent_goal:
                    return self._json(200, service.get_agent_goal(agent_goal.group(1)))
                actor = ACTOR_GET.fullmatch(self.path)
                if actor:
                    return self._json(200, service._require_world_store().get_actor(actor.group(1)))
                social = SOCIAL_GET.fullmatch(self.path)
                if social:
                    return self._json(200, service.get_social_run(social.group(1)))
                dialogue = DIALOGUE_GET.fullmatch(self.path)
                if dialogue:
                    return self._json(200, service.get_dialogue(dialogue.group(1)))
                conversation = CONVERSATION_GET.fullmatch(self.path)
                if conversation:
                    return self._json(200, service._require_world_store().get_conversation(conversation.group(1)))
            except Exception as exc:
                return self._handle_error(exc)
            return self._error(404, "not_found", "Not found")

        def do_POST(self):
            if not self._host_ok():
                return
            known_route = (self.path in (
                "/v1/chat", "/v1/worlds", "/v1/actors", "/v1/actor-chat",
                "/v1/dialogues", "/v1/social-runs", "/v1/agent/goals",
                "/v1/cplus/f1/events", "/v1/cplus/f1/status", "/v1/cplus/f1/confirm", "/v1/cplus/f1/recovery",
                "/v1/cplus/e/preview", "/v1/cplus/e/status",
                "/v1/cplus/e1/submit", "/v1/cplus/e1/status", "/v1/cplus/e1/queue", "/v1/cplus/e1/cancel",
                "/v1/cplus/e2/dispatch", "/v1/cplus/e2/verify", "/v1/cplus/e3/preview",
                "/v1/cplus/e3/reserve", "/v1/cplus/e3/report", "/v1/cplus/e3/status",
                "/v1/cplus/e3/abandon", "/v1/cplus/e3/finalize", "/v1/cplus/e3/cancel-begin", "/v1/cplus/e3/cancel-ack")
                or DIALOGUE_TURNS.fullmatch(self.path)
                or DIALOGUE_CLOSE.fullmatch(self.path)
                or SOCIAL_STEP.fullmatch(self.path)
                or SOCIAL_STOP.fullmatch(self.path)
                or AGENT_OBSERVE.fullmatch(self.path)
                or AGENT_DECIDE.fullmatch(self.path)
                or AGENT_CANCEL.fullmatch(self.path)
                or AGENT_RESULT.fullmatch(self.path))

            # The server does not support request transfer codings. Never try
            # to drain bytes whose framing is unknown; close this connection.
            if self.headers.get("Transfer-Encoding"):
                self.close_connection = True
                if not known_route:
                    return self._error(404, "not_found", "Not found")
                return self._error(400, "invalid_request", "Chunked request bodies are not supported")

            lengths = self.headers.get_all("Content-Length", [])
            if not known_route:
                # Preserve the original 404 contract. Consume framed bodies
                # so Windows clients can reliably read the JSON error.
                if len(lengths) == 1:
                    try:
                        self._discard_rejected_body(int(lengths[0]))
                    except ValueError:
                        self.close_connection = True
                elif lengths:
                    self.close_connection = True
                return self._error(404, "not_found", "Not found")

            if len(lengths) != 1:
                self.close_connection = True
                return self._error(400, "invalid_request", "Exactly one Content-Length header is required")
            try:
                length = int(lengths[0])
            except ValueError:
                self.close_connection = True
                return self._error(400, "invalid_request", "Invalid Content-Length")
            max_body = 24000 if self.path in ("/v1/cplus/e/preview", "/v1/cplus/e1/submit", "/v1/cplus/e2/dispatch", "/v1/cplus/e2/verify", "/v1/cplus/e3/preview",
                "/v1/cplus/e3/reserve", "/v1/cplus/e3/report", "/v1/cplus/e3/status",
                "/v1/cplus/e3/abandon", "/v1/cplus/e3/finalize", "/v1/cplus/e3/cancel-begin", "/v1/cplus/e3/cancel-ack") else MAX_BODY
            if length < 1 or length > max_body:
                self._discard_rejected_body(length)
                return self._error(413, "request_too_large", f"Request size must be 1–{max_body} bytes")
            if not _is_json_content_type(self.headers.get("Content-Type")):
                self._discard_rejected_body(length)
                return self._error(415, "unsupported_media_type", "Content-Type must be application/json")
            self.connection.settimeout(10)
            try:
                data = json.loads(self.rfile.read(length).decode("utf-8"))
            except (ValueError, UnicodeDecodeError, TimeoutError, OSError):
                return self._error(400, "invalid_request", "Invalid JSON or incomplete request body")
            if not isinstance(data, dict):
                return self._error(400, "invalid_request", "Expected JSON object")
            try:
                if self.path in ("/v1/cplus/f1/events", "/v1/cplus/f1/status", "/v1/cplus/f1/confirm", "/v1/cplus/f1/recovery",
                "/v1/cplus/e/preview", "/v1/cplus/e/status",
                "/v1/cplus/e1/submit", "/v1/cplus/e1/status", "/v1/cplus/e1/queue", "/v1/cplus/e1/cancel",
                "/v1/cplus/e2/dispatch", "/v1/cplus/e2/verify", "/v1/cplus/e3/preview",
                "/v1/cplus/e3/reserve", "/v1/cplus/e3/report", "/v1/cplus/e3/status",
                "/v1/cplus/e3/abandon", "/v1/cplus/e3/finalize", "/v1/cplus/e3/cancel-begin", "/v1/cplus/e3/cancel-ack"):
                    # Diagnostic-only, opt-in, local-only, shared secret required.
                    secret = read_secret()
                    supplied = self.headers.get("X-NPCortex-CPlus-Token", "")
                    try:
                        local = ipaddress.ip_address(self.client_address[0]).is_loopback
                    except ValueError:
                        local = False
                    if not local or len(secret) < 24 or len(supplied) > 512 or not hmac.compare_digest(secret, supplied):
                        return self._error(403, "forbidden", "C+ local diagnostic bridge not authorized")
                    if self.path in ("/v1/cplus/e3/reserve", "/v1/cplus/e3/report", "/v1/cplus/e3/status",
                "/v1/cplus/e3/abandon", "/v1/cplus/e3/finalize", "/v1/cplus/e3/cancel-begin", "/v1/cplus/e3/cancel-ack"):
                        try:
                            motion=cplus_e3_motion()
                            if self.path == "/v1/cplus/e3/abandon":
                                return self._json(200,motion.abandon(data))
                            if self.path == "/v1/cplus/e3/reserve":
                                return self._json(200,motion.reserve(data))
                            if self.path == "/v1/cplus/e3/report":
                                return self._json(200,motion.report(data))
                            if self.path == "/v1/cplus/e3/finalize":
                                return self._json(200,motion.finalize(data))
                            if self.path == "/v1/cplus/e3/cancel-begin":
                                return self._json(200,motion.cancel_begin(data))
                            if self.path == "/v1/cplus/e3/cancel-ack":
                                return self._json(200,motion.cancel_ack(data))
                            if set(data)!={"world_id","actor_id"}:
                                return self._error(400,"invalid_request","world/actor IDs required")
                            return self._json(200,motion.status(data["world_id"],data["actor_id"]))
                        except GraphConflict as exc:
                            return self._error(409,"e3_g2_conflict",str(exc))
                    if self.path == "/v1/cplus/e3/preview":
                        try:
                            return self._json(200, cplus_e3_preview().preview(data))
                        except GraphConflict as exc:
                            return self._error(409, "e3_g1_preview_conflict", str(exc))
                    if self.path.startswith("/v1/cplus/e1/") or self.path.startswith("/v1/cplus/e2/"):
                        try:
                            coord = cplus_e_coordinator()
                            if self.path == "/v1/cplus/e2/dispatch":
                                if set(data) != {"snapshot"}:
                                    return self._error(400,"invalid_request","server read-only snapshot required")
                                return self._json(200,coord.dispatch(data["snapshot"]))
                            if self.path == "/v1/cplus/e2/verify":
                                if set(data) != {"task_id","lease_id","generation","snapshot"}:
                                    return self._error(400,"invalid_request","fenced read-only verification required")
                                return self._json(200,coord.verify(data["task_id"],data["lease_id"],
                                    data["generation"],data["snapshot"]))
                            if self.path == "/v1/cplus/e1/submit":
                                if set(data) != {"request_id", "requester_id", "snapshot", "plan"}:
                                    return self._error(400,"invalid_request","typed E1 submission required")
                                return self._json(200,coord.submit_task(data["request_id"],
                                    data["requester_id"],data["snapshot"],data["plan"]))
                            if self.path == "/v1/cplus/e1/status":
                                if set(data) != {"task_id"}:
                                    return self._error(400,"invalid_request","task_id required")
                                return self._json(200,coord.task_status(data["task_id"]))
                            if self.path == "/v1/cplus/e1/queue":
                                if set(data) != {"world_id", "actor_id"}:
                                    return self._error(400,"invalid_request","world_id and actor_id required")
                                return self._json(200,coord.task_queue(data["world_id"],data["actor_id"]))
                            if self.path == "/v1/cplus/e1/cancel":
                                if set(data) != {"task_id", "requester_id"}:
                                    return self._error(400,"invalid_request","requester_id required")
                                return self._json(200,coord.cancel_task(data["task_id"],data["requester_id"]))
                        except GraphConflict as exc:
                            return self._error(409,"e1_conflict",str(exc))
                    if self.path in ("/v1/cplus/e/preview", "/v1/cplus/e/status"):
                        try:
                            if self.path == "/v1/cplus/e/status":
                                if set(data) != {"task_id"}:
                                    return self._error(400, "invalid_request", "task_id required")
                                return self._json(200, cplus_e_graph().status(data["task_id"]))
                            if set(data) != {"request_id", "snapshot"}:
                                return self._error(400, "invalid_request", "request_id and snapshot required")
                            queued = cplus_e_graph().submit(data["request_id"], data["snapshot"])
                            return self._json(200, cplus_e_graph().advance(queued["task_id"]))
                        except GraphConflict as exc:
                            return self._error(409, "e_preview_conflict", str(exc))
                    if self.path == "/v1/cplus/f1/events":
                        try:
                            return self._json(200, cplus_bridge().receive(data))
                        except BridgeConflict as ex:
                            return self._json(409, {"code": "bridge_conflict", "error": str(ex),
                                                    "expected_sequence": ex.expected})
                    if self.path == "/v1/cplus/f1/confirm":
                        try:
                            return self._json(200, cplus_bridge().confirm(data))
                        except BridgeConflict as ex:
                            return self._error(409, "bridge_conflict", str(ex))
                    if set(data) != {"world_id"}:
                        return self._error(400, "invalid_request", "Expected world_id only")
                    try:
                        if self.path == "/v1/cplus/f1/recovery":
                            return self._json(200, cplus_bridge().recovery(data["world_id"]))
                        return self._json(200, cplus_bridge().status(data["world_id"]))
                    except BridgeConflict as ex:
                        return self._error(400, "invalid_request", str(ex))
                if self.path == "/v1/agent/goals":
                    required = {"world_id", "dimension_id", "actor_id", "objective", "success_criteria"}
                    if not required.issubset(data) or not set(data).issubset(required | {"max_decisions", "goal_id"}):
                        return self._error(400, "invalid_request", "Invalid agent goal fields")
                    return self._json(201, service.create_agent_goal(
                        world_id=data["world_id"], dimension=data["dimension_id"],
                        actor_id=data["actor_id"], objective=data["objective"],
                        success_criteria=data["success_criteria"],
                        max_decisions=data.get("max_decisions", 8),goal_id=data.get("goal_id")))
                agent_observe = AGENT_OBSERVE.fullmatch(self.path)
                if agent_observe:
                    if set(data) != {"observation_id", "snapshot"}:
                        return self._error(400,"invalid_request","Observe requires observation_id and snapshot")
                    return self._json(200, service.agent_observe(agent_observe.group(1),data["observation_id"],data["snapshot"]))
                agent_decide = AGENT_DECIDE.fullmatch(self.path)
                if agent_decide:
                    if set(data) != {"request_id"}:
                        return self._error(400,"invalid_request","Decide requires request_id")
                    return self._json(200, service.agent_decide(agent_decide.group(1),data["request_id"]))
                agent_cancel = AGENT_CANCEL.fullmatch(self.path)
                if agent_cancel:
                    if not {"world_id", "actor_id"}.issubset(data) or not set(data).issubset({"world_id", "actor_id", "action_id"}):
                        return self._error(400, "invalid_request", "Cancel requires world_id and actor_id with optional action_id")
                    return self._json(200, service.cancel_agent_goal(agent_cancel.group(1),
                        data["world_id"], data["actor_id"], data.get("action_id")))
                agent_result = AGENT_RESULT.fullmatch(self.path)
                if agent_result:
                    if set(data) != {"result_id","observation_id","status","snapshot"}:
                        return self._error(400,"invalid_request","Result requires result_id, observation_id, status, snapshot")
                    return self._json(200, service.agent_result(agent_result.group(1),
                        data["result_id"],data["status"],data["observation_id"],data["snapshot"]))
                if self.path == "/v1/social-runs":
                    if "dialogue_id" not in data:
                        return self._error(400, "invalid_request", "Missing dialogue_id")
                    return self._json(201, service.create_social_run(data["dialogue_id"], data.get("max_steps", 6)))
                step_match = SOCIAL_STEP.fullmatch(self.path)
                if step_match:
                    if set(data) != {"request_id"}:
                        return self._error(400, "invalid_request", "Step requires request_id UUID only")
                    return self._json(200, service.social_step(step_match.group(1), data["request_id"]))
                stop_match = SOCIAL_STOP.fullmatch(self.path)
                if stop_match:
                    if data:
                        return self._error(400, "invalid_request", "Stop body must be an empty object")
                    return self._json(200, service.stop_social_run(stop_match.group(1)))
                if self.path == "/v1/dialogues":
                    if not all(k in data for k in ("world_id", "dimension_id", "actor_ids", "topic")):
                        return self._error(400, "invalid_request", "Missing dialogue fields")
                    return self._json(201, service.create_dialogue(
                        data["world_id"], data["dimension_id"], data["actor_ids"],
                        data.get("player_ids", []), data["topic"], data.get("max_turns", 24)))
                turn_match = DIALOGUE_TURNS.fullmatch(self.path)
                if turn_match:
                    if not all(k in data for k in ("speaker_type", "speaker_id")):
                        return self._error(400, "invalid_request", "Missing dialogue speaker fields")
                    return self._json(200, service.dialogue_turn(turn_match.group(1),
                        data["speaker_type"], data["speaker_id"], data.get("message"), data.get("request_id")))
                close_match = DIALOGUE_CLOSE.fullmatch(self.path)
                if close_match:
                    if data:
                        return self._error(400, "invalid_request", "Close body must be an empty object")
                    return self._json(200, service.close_dialogue(close_match.group(1)))
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


def make_http_server(service, host="127.0.0.1", port=0):
    """Same threaded server configuration for production and HTTP tests."""
    if not is_loopback_host(host):
        raise ValueError("Backend must bind a loopback address; LAN binding is not supported without authentication")
    httpd = ThreadingHTTPServer((host, port), make_handler(service))
    httpd.daemon_threads = True
    return httpd


def serve(service, host="127.0.0.1", port=8765):
    logging.basicConfig(level=logging.INFO, format="%(levelname)s: %(message)s")
    with make_http_server(service, host, port) as httpd:
        print(f"NPCortex backend listening at http://{host}:{port}", flush=True)
        print("Local prototype: legacy chat routes are unauthenticated; do not expose publicly. C-F1 diagnostic bridge requires a local credential.", flush=True)
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            pass
