"""Regression: Qwen must not independently combine legal tools with illegal targets.

The new inference schema presents atomic action choices; Java remains the only
world executor and STILL revalidates every response. No model is called here.
"""
import json
import sys
import unittest
from pathlib import Path
from unittest.mock import Mock
from http.server import ThreadingHTTPServer
from threading import Thread
from urllib.request import Request, urlopen
from urllib.error import HTTPError

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "backend" / "src"))
sys.path.insert(0, str(ROOT / "backend" / "tests"))
from test_agentic_brain_v085 import state
from npcortex_backend.brain import (validate_state, allowed_brain_choices,
    choice_schema_for_observation, validate_brain_choice)
from npcortex_backend.service import ChatService
from npcortex_backend.server import make_handler
from npcortex_backend.session import SessionStore


def branch_snapshot():
    s = state()
    s["tree"] = {"active": True, "remaining": 8, "size": 20,
                 "completed": 0, "terrain_edits": 0}
    s["scaffold"] = 3
    s["mined"] = 13
    s["collected"] = 9
    s["goal_count"] = 15
    s["calls"] = 51
    s["targets"] = [
        {"ref": "b0", "id": "minecraft:oak_log", "dx": 0, "dy": 1,
         "dz": 1, "visible": True, "reachable": True,
         "ground_stand_possible": True, "obstruction": "none",
         "leaf_clearable": False, "soft_clearable": False},
        {"ref": "b1", "id": "minecraft:oak_log", "dx": 2, "dy": 4,
         "dz": 0, "visible": False, "reachable": False,
         "ground_stand_possible": False, "obstruction": "unknown",
         "leaf_clearable": False, "soft_clearable": False},
        {"ref": "b2", "id": "minecraft:oak_log", "dx": 2, "dy": 5,
         "dz": 1, "visible": False, "reachable": False,
         "ground_stand_possible": False, "obstruction": "none",
         "leaf_clearable": False, "soft_clearable": False},
    ]
    return validate_state(s)


class AtomicSchemaTests(unittest.TestCase):
    def test_unreachable_b2_cannot_pair_with_mine(self):
        s = branch_snapshot()
        opts = allowed_brain_choices(s)
        self.assertIn("mine:b0", opts)
        self.assertIn("climb:b2", opts)
        self.assertNotIn("mine:b2", opts)
        self.assertNotIn("approach:b2", opts)
        self.assertIn("pause", opts)
        self.assertNotIn("wait", opts)
        self.assertEqual(choice_schema_for_observation(s)["properties"]["choice"]["enum"], opts)

    def test_one_atomic_choice_returns_old_java_contract(self):
        s = branch_snapshot()
        before = json.dumps(s, sort_keys=True)
        self.assertEqual(validate_brain_choice({"choice": "climb:b2", "reason": "high branch"}, s),
                         {"tool": "climb", "target": "b2", "reason": "high branch"})
        self.assertEqual(json.dumps(s, sort_keys=True), before)

    def test_bad_pairs_and_injection_still_fail_closed(self):
        s = branch_snapshot()
        for item in [
            {"choice": "mine:b2", "reason": "unreachable log"},
            {"choice": "java.lang.Runtime", "reason": "attack"},
            {"choice": "climb:b99", "reason": "unknown block"},
            {"choice": "mine:b0", "reason": "ok", "command": "/give @p diamond"},
            {"tool": "mine", "target": "b2", "reason": "legacy bad pair"},
        ]:
            with self.subTest(item=item), self.assertRaises(ValueError):
                validate_brain_choice(item, s)

    def test_targetless_action_and_budget_are_still_grounded(self):
        s = branch_snapshot()
        self.assertEqual(validate_brain_choice({"choice": "pause", "reason": "unsafe"}, s)["target"], "")
        s["stalls"] = 5
        self.assertEqual(allowed_brain_choices(s), ["pause"])
        with self.assertRaises(ValueError):
            validate_brain_choice({"choice": "mine:b0", "reason": "budget"}, s)

    def test_snapshot_change_rejects_stale_ref(self):
        s = branch_snapshot()
        s["targets"][0]["visible"] = False
        s["targets"][0]["reachable"] = False
        with self.assertRaises(ValueError):
            validate_brain_choice({"choice": "mine:b0", "reason": "stale"}, s)


class EndpointAtomicSchemaTests(unittest.TestCase):
    def setUp(self):
        self.provider = Mock(spec=["chat_structured"])
        self.svc = ChatService(ROOT / "character-packs", "qwen3:8b", self.provider, SessionStore())
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(self.svc))
        self.thread = Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=3)

    def call(self, obs):
        body = {"character_id": "yui", "player_id": "owner1", "session_id": "npc1",
                "message": "Chop full tree", "world": obs}
        req = Request(f"http://127.0.0.1:{self.server.server_port}/v1/brain/step",
                      data=json.dumps(body).encode(),
                      headers={"Content-Type": "application/json"}, method="POST")
        try:
            with urlopen(req, timeout=5) as response:
                return response.status, json.load(response)
        except HTTPError as exc:
            return exc.code, json.load(exc)

    def test_valid_choice_returns_http_200_no_java_change(self):
        self.provider.chat_structured.return_value = '{"choice":"climb:b2","reason":"elevated log"}'
        code, reply = self.call(branch_snapshot())
        self.assertEqual(code, 200)
        self.assertEqual(reply, {"tool": "climb", "target": "b2", "reason": "elevated log"})
        self.assertEqual(self.provider.chat_structured.call_count, 1)
        schema = self.provider.chat_structured.call_args.args[1]
        self.assertIn("climb:b2", schema["properties"]["choice"]["enum"])
        self.assertNotIn("mine:b2", schema["properties"]["choice"]["enum"])
        prompt = self.provider.chat_structured.call_args.args[0][-1]["content"]
        self.assertIn('"allowed_choices"', prompt)

    def test_owned_mined_drop_snapshot_reaches_brain_endpoint(self):
        observed = branch_snapshot()
        observed["drops"][0]["npc_mined"] = True
        self.provider.chat_structured.return_value = '{"choice":"climb:b2","reason":"high log"}'
        code, reply = self.call(observed)
        self.assertEqual(code, 200)
        self.assertEqual(reply, {"tool": "climb", "target": "b2", "reason": "high log"})
        self.assertEqual(self.provider.chat_structured.call_count, 1)
        self.assertIn('"npc_mined": true', self.provider.chat_structured.call_args.args[0][-1]["content"])

    def test_invalid_mined_drop_provenance_is_http_400_before_model(self):
        observed = branch_snapshot()
        observed["drops"][0]["npc_mined"] = "true"
        code, reply = self.call(observed)
        self.assertEqual(code, 400)
        self.assertIn("Invalid mined-drop provenance", reply["error"])
        self.provider.chat_structured.assert_not_called()

    def test_malicious_or_invalid_choice_remains_http_502_without_retry(self):
        self.provider.chat_structured.return_value = '{"choice":"mine:b2","reason":"wrong"}'
        code, reply = self.call(branch_snapshot())
        self.assertEqual(code, 502)
        self.assertEqual(self.provider.chat_structured.call_count, 1)


if __name__ == "__main__":
    unittest.main()
