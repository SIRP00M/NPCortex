"""Deterministic v0.8.5 API / tool grounding / feedback-driven replanning tests.
The Minecraft-side physics remains subject to the Forge build and in-game tests.
"""
import copy
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import Mock

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "backend" / "src"))
from npcortex_backend.brain import (validate_decision, validate_state,
                                   schema_for_observation, MAX_CALLS, MAX_FAILURES)
from npcortex_backend.service import ChatService
from npcortex_backend.session import SessionStore
from npcortex_backend.server import make_handler
from npcortex_backend.ollama import ModelError
from http.server import ThreadingHTTPServer
from urllib.request import urlopen, Request
from urllib.error import HTTPError
from threading import Thread


def state():
    return {"goal_item": "minecraft:oak_log", "goal_count": 6, "collected": 0,
            "mined": 1, "calls": 0, "failures": 0, "same_failures": 0, "stalls": 0,
            "last_result": "[Task accepted by server.]", "scaffold": 0, "paused": False,
            "equipment": {"holding": "minecraft:air", "bag": [
                {"id": "minecraft:dirt", "count": 8}], "can_scaffold": True},
            "surroundings": {"below": "minecraft:grass_block", "feet": "minecraft:air",
                             "ahead": "minecraft:air"}, "owner_distance": 3.0,
            "tree": {"active": False, "remaining": 0, "size": 0, "completed": 0, "terrain_edits": 0},
            "targets": [{"ref": "b0", "id": "minecraft:oak_log", "dx": 2,
                         "dy": 1, "dz": 0, "visible": True, "reachable": True,
                         "obstruction": "none", "leaf_clearable": False, "ground_stand_possible": True, "soft_clearable": False},
                        {"ref": "b1", "id": "minecraft:oak_log", "dx": 2,
                         "dy": 5, "dz": 0, "visible": False, "reachable": False,
                         "obstruction": "natural_tree_leaf", "leaf_clearable": True, "ground_stand_possible": True, "soft_clearable": False}],
            "drops": [{"ref": "d0", "id": "minecraft:oak_log", "distance": 2.5,
                       "uuid": "f2c31393-a4ce-4e7c-91d2-fdc946f38ce8",
                       "pickup_reachable": False}]}


def decision(tool, target="", reason="world-grounded choice"):
    return {"tool": tool, "target": target, "reason": reason}


class ValidationTests(unittest.TestCase):
    def test_valid_observation_and_genuine_ref(self):
        s = validate_state(state())
        self.assertEqual(validate_decision(decision("mine", "b0"), s)["target"], "b0")
        self.assertEqual(validate_decision(decision("approach_drop", "d0"), s)["tool"], "approach_drop")

    def test_pickup_visibility_is_server_observation_not_model_authority(self):
        s = state()
        self.assertFalse(validate_state(s)["drops"][0]["pickup_reachable"])
        s["drops"][0]["pickup_reachable"] = "true"
        with self.assertRaises(ValueError):
            validate_state(s)

    def test_owned_mined_drop_provenance_is_backward_compatible_and_strict(self):
        # Old Forge snapshots still omit npc_mined; Runtime Sprint 1 sends it.
        old = state()
        self.assertNotIn("npc_mined", validate_state(old)["drops"][0])
        for mined in (True, False):
            observed = state()
            observed["drops"][0]["npc_mined"] = mined
            accepted = validate_state(observed)
            self.assertIs(accepted["drops"][0]["npc_mined"], mined)
            # Provenance alone cannot grant new executor authority.
            self.assertEqual(
                schema_for_observation(accepted)["properties"]["target"]["enum"],
                schema_for_observation(validate_state(old))["properties"]["target"]["enum"])
        for invalid in ("true", 1, None, [], {}):
            observed = state()
            observed["drops"][0]["npc_mined"] = invalid
            with self.subTest(invalid=invalid), self.assertRaisesRegex(
                    ValueError, "Invalid mined-drop provenance"):
                validate_state(observed)
        observed = state()
        observed["drops"][0].update({"npc_mined": True, "unknown": False})
        with self.assertRaisesRegex(ValueError, "Unexpected drop observation fields"):
            validate_state(observed)

    def test_reject_invented_ref_and_wrong_kind(self):
        s = validate_state(state())
        for tool, target in [("mine", "b9"), ("mine", "d0"), ("approach_drop", "b0"),
                             ("mine", "2,64,4"), ("op_java", "b0")]:
            with self.subTest(tool=tool, target=target), self.assertRaises(ValueError):
                validate_decision(decision(tool, target), s)

    def test_targetless_tool_canonicalization_does_not_grant_authority(self):
        s = validate_state(state())
        for tool in ("wait", "pause"):
            for extra in ("b0", "d0", "xyz"):
                with self.subTest(tool=tool, extra=extra):
                    original = decision(tool, extra, "Model chose this skill")
                    verified = validate_decision(original, s)
                    self.assertEqual(verified, decision(tool, "", "Model chose this skill"))
                    self.assertEqual(original["target"], extra)
        # Pickup and finish are normalized only when their preconditions exist.
        s["drops"][0]["pickup_reachable"] = True
        self.assertEqual(validate_decision(decision("pickup", "d0"), s)["target"], "")
        s["collected"] = s["goal_count"]
        s["mined"] = s["goal_count"]
        s["tree"]["completed"] = 1
        self.assertEqual(validate_decision(decision("finish", "b0"), s)["target"], "")
        # Only the unused argument is normalized. Invented actionable targets,
        # extra keys and overlength strings are still rejected.
        for invalid in (decision("mine", "d0"),
                        decision("approach_drop", "b0"),
                        decision("pickup", "hack")):
            with self.assertRaises(ValueError):
                validate_decision(invalid, s)
        limited = state()
        limited["calls"] = MAX_CALLS
        with self.assertRaises(ValueError):
            validate_decision(decision("finish", "b0"), validate_state(limited))

    def test_reject_tool_payload_and_fake_counters(self):
        s = state()
        s["goal_count"] = True
        with self.assertRaises(ValueError):
            validate_state(s)
        s = state()
        s["targets"][0]["ref"] = "b7"
        with self.assertRaises(ValueError):
            validate_state(s)
        s = state()
        s["calls"] = MAX_CALLS + 1
        with self.assertRaises(ValueError):
            validate_state(s)
        with self.assertRaises(ValueError):
            validate_decision({"tool": "mine", "target": "b0", "reason": "x", "code": "op()"}, state())
        with self.assertRaises(ValueError):
            validate_decision({"tool": ["mine"], "target": "b0", "reason": "x"}, state())

    def test_real_server_drop_radius_passes_validation(self):
        s = state()
        # Forge searches an expanded AABB, not a 16-block spherical radius.
        # Drops at this distance previously failed with HTTP 400 before Qwen ran.
        s["drops"][0]["distance"] = 19.7
        self.assertEqual(validate_state(s)["drops"][0]["distance"], 19.7)
        s["drops"][0]["distance"] = 35
        with self.assertRaises(ValueError):
            validate_state(s)

    def test_schema_enums_use_only_current_server_refs(self):
        s = validate_state(state())
        schema = schema_for_observation(s)
        self.assertEqual(schema["properties"]["target"]["enum"], ["", "b0", "b1", "d0"])
        self.assertIn("mine", schema["properties"]["tool"]["enum"])
        self.assertIn("approach_drop", schema["properties"]["tool"]["enum"])
        self.assertEqual(validate_decision(decision("mine", "b0"), s)["tool"], "mine")
        # Schema is only decoding guidance; forged references still fail closed.
        with self.assertRaises(ValueError):
            validate_decision(decision("mine", "b5"), s)

    def test_schema_excludes_tools_without_targets(self):
        s = state()
        s["targets"] = []
        s["drops"] = []
        schema = schema_for_observation(validate_state(s))
        self.assertEqual(schema["properties"]["target"]["enum"], [""])
        self.assertNotIn("mine", schema["properties"]["tool"]["enum"])
        self.assertNotIn("approach_drop", schema["properties"]["tool"]["enum"])
        self.assertIn("pause", schema["properties"]["tool"]["enum"])

    def test_stagnation_budget_fails_closed_and_is_visible_to_qwen(self):
        s = state()
        s["stalls"] = 4
        self.assertEqual(validate_state(s)["stalls"], 4)
        s["last_result"] = "[Action unchanged: block already visible and in reach.]"
        self.assertEqual(validate_decision(decision("mine", "b0"), validate_state(s))["tool"], "mine")
        s["stalls"] = 5
        with self.assertRaisesRegex(ValueError, "Task budget exceeded"):
            validate_decision(decision("approach", "b0"), validate_state(s))
        self.assertEqual(validate_decision(decision("pause"), validate_state(s))["tool"], "pause")
        s["stalls"] = 6
        with self.assertRaises(ValueError):
            validate_state(s)

    def test_minimum_met_does_not_interrupt_committed_tree(self):
        s = state()
        s["goal_count"] = 1
        s["mined"] = 1
        s["collected"] = 1
        s["tree"] = {"active": True, "remaining": 3, "size": 4,
                     "completed": 0, "terrain_edits": 0}
        tools = schema_for_observation(validate_state(s))["properties"]["tool"]["enum"]
        self.assertIn("mine", tools)
        self.assertIn("approach", tools)
        self.assertNotIn("finish", tools)
        self.assertEqual(validate_decision(decision("mine", "b0"), validate_state(s))["tool"], "mine")

    def test_new_tree_denied_after_minimum_and_cleanup(self):
        s = state()
        s["goal_count"] = 1
        s["mined"] = 1
        s["collected"] = 1
        s["tree"]["completed"] = 1
        tools = schema_for_observation(validate_state(s))["properties"]["tool"]["enum"]
        for tool in ("mine", "approach", "clear_leaf", "climb", "clear_soft"):
            self.assertNotIn(tool, tools)
        self.assertIn("finish", tools)

    def test_grounded_reversible_soil_edit(self):
        s = state()
        s["tree"] = {"active": True, "remaining": 2, "size": 3,
                     "completed": 0, "terrain_edits": 1}
        s["targets"][0]["soft_clearable"] = True
        tools = schema_for_observation(validate_state(s))["properties"]["tool"]["enum"]
        self.assertIn("clear_soft", tools)
        self.assertIn("restore", tools)
        self.assertNotIn("finish", tools)
        self.assertEqual(validate_decision(decision("clear_soft", "b0"), validate_state(s))["tool"], "clear_soft")
        with self.assertRaises(ValueError):
            validate_decision(decision("clear_soft", "b1"), validate_state(s))
        s["tree"]["active"] = False
        s["tree"]["size"] = 0
        s["tree"]["remaining"] = 0
        with self.assertRaises(ValueError):
            validate_state(s)

    def test_collect_affordances_keep_ai_choice_without_auto_actions(self):
        s = state()
        s["mined"] = s["goal_count"]
        s["drops"][0]["pickup_reachable"] = True
        available = schema_for_observation(validate_state(s))["properties"]["tool"]["enum"]
        self.assertIn("pickup", available)
        self.assertNotIn("approach_drop", available)
        self.assertNotIn("finish", available)
        with self.assertRaises(ValueError):
            validate_decision(decision("finish"), s)
        s["collected"] = s["goal_count"]
        s["tree"]["completed"] = 1
        available = schema_for_observation(validate_state(s))["properties"]["tool"]["enum"]
        self.assertIn("finish", available)
        self.assertEqual(validate_decision(decision("finish"), s)["tool"], "finish")

    def test_missing_drops_does_not_claim_bag_success(self):
        s = state()
        s["goal_count"] = 1
        s["mined"] = 1
        s["targets"] = []
        s["drops"] = []  # player could have picked it up; goal is not met
        allowed = schema_for_observation(validate_state(s))["properties"]["tool"]["enum"]
        self.assertIn("pause", allowed)
        self.assertIn("wait", allowed)
        self.assertNotIn("finish", allowed)
        self.assertEqual(s["collected"], 0)

    def test_agent_prompt_explains_no_op_and_effective_replanning(self):
        self.service = None  # unrelated to pure schema validation
        from npcortex_backend.brain import BRAIN_POLICY
        self.assertIn("visible=true and reachable=true", BRAIN_POLICY)
        self.assertIn("Action unchanged", BRAIN_POLICY)
        self.assertIn("You choose the alternative", BRAIN_POLICY)

    def test_budget_fails_closed(self):
        s = state()
        s["calls"] = MAX_CALLS
        with self.assertRaises(ValueError):
            validate_decision(decision("mine", "b0"), validate_state(s))
        self.assertEqual(validate_decision(decision("pause"), validate_state(s))["tool"], "pause")
        s = state()
        s["failures"] = MAX_FAILURES
        with self.assertRaises(ValueError):
            validate_decision(decision("wait"), validate_state(s))


class AgentCycleTests(unittest.TestCase):
    def setUp(self):
        self.provider = Mock(spec=["chat_structured"])
        self.service = ChatService(ROOT / "character-packs", "qwen3:8b", self.provider,
                                   SessionStore())

    def step(self, observation):
        return self.service.brain_step("yui", "owner1", "Yui, chop 6 oak logs.",
                                       "npc1", observation)

    def test_redundant_approach_is_rejected_before_wasting_an_execution_cycle(self):
        self.provider.chat_structured.side_effect = [
            json.dumps(decision("approach", "b0", "Check the block")),
            json.dumps(decision("mine", "b0", "Already visible and reachable")),
        ]
        s = state()
        with self.assertRaisesRegex(ModelError, "no executable affordance"):
            self.step(s)
        s["calls"] = 1
        s["stalls"] = 1
        s["last_result"] = "[Action unchanged: block already visible and in reach; no movement or task progress.]"
        self.assertEqual(self.step(s)["tool"], "mine")
        messages = self.provider.chat_structured.call_args.args[0]
        self.assertIn('"stalls": 1', messages[-1]["content"])
        self.assertIn('"mine": ["b0"]', messages[-1]["content"])
        self.assertIn('"approach": ["b1"]', messages[-1]["content"])
        self.assertEqual(self.provider.chat_structured.call_count, 2)

    def test_feedback_driven_replan_is_new_model_decision_each_cycle(self):
        self.provider.chat_structured.side_effect = [
            json.dumps(decision("mine", "b0", "Try lower trunk")),
            json.dumps(decision("approach", "b1", "Out of reach")),
            json.dumps(decision("clear_leaf", "b1", "Foliage obstructs")),
            json.dumps(decision("pause", "", "Need owner assistance")),
        ]
        s = state()
        outputs = []
        for i, feedback in enumerate([
                "[Task accepted]", "[Action failed: block out of reach]",
                "[Action failed: no safe visible standing location]",
                "[Action blocked: leaf not verified]"]):
            s["last_result"] = feedback
            s["calls"] = i
            s["failures"] = min(i, 3)
            outputs.append(self.step(s)["tool"])
        self.assertEqual(outputs, ["mine", "approach", "clear_leaf", "pause"])
        self.assertEqual(self.provider.chat_structured.call_count, 4)
        prompts = [call.args[0][-1]["content"] for call in self.provider.chat_structured.call_args_list]
        self.assertIn("out of reach", prompts[1])
        self.assertIn("no safe visible", prompts[2])
        self.assertIn("Yui", self.provider.chat_structured.call_args.args[0][0]["content"])
        self.assertEqual(self.service.sessions.get("owner1", "yui:npc1"), [])  # no fabricated success memory

    def test_two_npcs_have_distinct_scoped_character_prompts(self):
        self.provider.chat_structured.return_value = json.dumps(decision("wait"))
        self.service.brain_step("guide", "owner1", "collect logs", "npc2", state())
        system = self.provider.chat_structured.call_args.args[0][0]["content"]
        self.assertIn("Guide", system)
        self.assertNotIn("You are the NPC named \"Yui\"", system)

    def test_reject_fake_success_and_no_model_side_effects(self):
        self.provider.chat_structured.return_value = json.dumps(decision("finish"))
        s = state()
        with self.assertRaises(ModelError):
            self.step(s)
        self.assertEqual(s["collected"], 0)
        self.assertEqual(self.service.sessions.get("owner1", "yui:npc1"), [])

    def test_agent_step_sends_scoped_tool_schema_not_static_global_schema(self):
        self.provider.chat_structured.return_value = json.dumps(decision("pause"))
        s = state()
        s["targets"] = []
        s["drops"] = []
        self.assertEqual(self.step(s)["tool"], "pause")
        used_schema = self.provider.chat_structured.call_args.args[1]
        self.assertEqual(used_schema["properties"]["choice"]["enum"], ["pause", "wait"])
        self.assertEqual(self.provider.chat_structured.call_count, 1)

    def test_validation_failure_reports_reason_without_dumping_character_memory(self):
        self.provider.chat_structured.return_value = json.dumps(decision("mine", "b15"))
        with self.assertLogs("npcortex_backend.service", "WARNING") as captured:
            with self.assertRaisesRegex(ModelError, "Unobserved block reference"):
                self.step(state())
        self.assertIn("tool='mine'", captured.output[0])
        self.assertIn("target='b15'", captured.output[0])

    def test_bounded_server_side_error(self):
        self.provider.chat_structured.return_value = json.dumps({"tool": "execute_command", "target": "", "reason": "cheat"})
        with self.assertRaises(ModelError):
            self.step(state())


class BrainEndpointTests(unittest.TestCase):
    def setUp(self):
        self.provider = Mock(spec=["chat_structured"])
        self.provider.chat_structured.return_value = json.dumps(decision("mine", "b0"))
        self.svc = ChatService(ROOT / "character-packs", "qwen3:8b", self.provider,
                               SessionStore())
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(self.svc))
        self.thread = Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)

    def call(self, world):
        payload = {"character_id": "yui", "player_id": "owner1", "session_id": "npc1",
                   "message": "Yui, chop 6 oak logs.", "world": world}
        req = Request(f"http://127.0.0.1:{self.server.server_port}/v1/brain/step",
                      data=json.dumps(payload).encode(),
                      headers={"Content-Type": "application/json"}, method="POST")
        try:
            with urlopen(req, timeout=3) as response:
                return response.status, json.load(response)
        except HTTPError as exc:
            return exc.code, json.load(exc)

    def test_http_endpoint_returns_grounded_single_tool(self):
        status, answer = self.call(state())
        self.assertEqual(status, 200)
        self.assertEqual(answer, decision("mine", "b0"))

    def test_http_normalizes_qwen_spurious_target_for_targetless_skill(self):
        self.provider.chat_structured.return_value = json.dumps(decision("pickup", "d0"))
        s = state()
        s["drops"][0]["pickup_reachable"] = True
        status, reply = self.call(s)
        self.assertEqual(status, 200)
        self.assertEqual(reply, decision("pickup"))
        self.assertEqual(self.provider.chat_structured.call_count, 1)

    def test_http_accepts_drop_at_edge_of_java_search_area(self):
        s = state()
        s["drops"][0]["distance"] = 19.7
        status, reply = self.call(s)
        self.assertEqual(status, 200)
        self.assertEqual(reply["tool"], "mine")
        self.assertEqual(self.provider.chat_structured.call_count, 1)

    def test_http_rejects_invalid_observation(self):
        bad = state()
        bad["goal_count"] = 999
        status, reply = self.call(bad)
        self.assertEqual(status, 400)
        self.assertIn("error", reply)
        self.assertEqual(self.provider.chat_structured.call_count, 0)

    def test_http_rejects_untrusted_tool_output(self):
        self.provider.chat_structured.return_value = json.dumps(decision("mine", "b15"))
        status, reply = self.call(state())
        self.assertEqual(status, 502)
        self.assertIn("error", reply)


if __name__ == "__main__":
    unittest.main()
