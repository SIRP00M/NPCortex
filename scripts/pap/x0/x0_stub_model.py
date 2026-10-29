"""X0 TEST-ONLY stand-in for the local model server (Ollama /api/chat). It is NOT a model: for a C0 decision request it echoes the one
move_to the goal itself already states (criterion position), so the real C2/C0 pipeline can run without any Qwen call. Loopback only."""
import json, sys
from http.server import BaseHTTPRequestHandler, HTTPServer
CALLS = [0]
class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def do_GET(self):
        self.send_response(200); self.end_headers(); self.wfile.write(b'{"models":[]}')
    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))) or b"{}")
        CALLS[0] += 1
        reply = {"action": "blocked", "reason": "stub: no goal position found"}
        try:
            user = next(m for m in reversed(body["messages"]) if m["role"] == "user")
            task = json.loads(user["content"])["task"]
            crit = next(c for c in task["success_criteria"] if c.get("kind") == "position_near")
            reply = {"action": "use_skill", "skill_id": "move_to", "args": {"position": crit["position"]}}
        except Exception:
            pass
        out = json.dumps({"message": {"role": "assistant", "content": json.dumps(reply)}}).encode()
        self.send_response(200); self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(out))); self.end_headers(); self.wfile.write(out)
if __name__ == "__main__":
    HTTPServer(("127.0.0.1", int(sys.argv[1])), H).serve_forever()
