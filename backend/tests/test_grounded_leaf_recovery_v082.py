"""Agentic v0.8.2 recovery: world-grounded skill/ref choices.

Backend tests simulate model decisions. Java-source contract checks are not Forge
physics tests; physical behavior must be confirmed by Minecraft.
"""
from pathlib import Path
import json
import unittest
from unittest.mock import Mock
from npcortex_backend.brain import (validate_state, validate_decision,
                                    available_tools, eligible_tool_refs, schema_for_observation)
from npcortex_backend.service import ChatService
from npcortex_backend.session import SessionStore
from test_agentic_brain_v085 import state as base_state

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / 'forge-mod/src/main/java/io/github/sirp00m/npcortex'


class GroundedLeafRecoveryTests(unittest.TestCase):
    def test_visible_log_and_blocked_log_offer_distinct_skills(self):
        s = validate_state(base_state())
        options = eligible_tool_refs(s)
        self.assertEqual(options['mine'], ['b0'])
        self.assertEqual(options['approach'], ['b1'])
        self.assertEqual(options['clear_leaf'], ['b1'])
        self.assertEqual(options['climb'], [])  # no committed tree
        self.assertEqual(options['approach_drop'], ['d0'])
        for tool, ref in [('clear_leaf','b0'),('approach','b0'),('mine','b1')]:
            with self.subTest(tool=tool, ref=ref), self.assertRaisesRegex(ValueError,'no executable affordance'):
                validate_decision({'tool':tool,'target':ref,'reason':'attempt'}, s)
        self.assertEqual(validate_decision({'tool':'clear_leaf','target':'b1','reason':'ray blocked'},s)['tool'],'clear_leaf')

    def test_hypothetically_occluding_leaf_does_not_override_visible_log(self):
        s = base_state()
        # Defensive filtering even if a stale Java observation contains inconsistent flags.
        s['targets'][0]['leaf_clearable'] = True
        s['targets'][0]['obstruction'] = 'natural_tree_leaf'
        options = eligible_tool_refs(validate_state(s))
        self.assertNotIn('b0', options['clear_leaf'])
        self.assertIn('b1', options['clear_leaf'])

    def test_tall_tree_can_offer_climb_without_forced_java_sequence(self):
        s = base_state()
        s['tree'] = {'active':True,'remaining':3,'size':4,'completed':0,'terrain_edits':0}
        s['goal_count'] = 1
        s['collected'] = 1
        s['mined'] = 1
        options = eligible_tool_refs(validate_state(s))
        self.assertEqual(options['climb'], ['b1'])
        self.assertIn('mine', available_tools(s))
        self.assertIn('climb', available_tools(s))
        s['equipment']['can_scaffold'] = False
        self.assertEqual(eligible_tool_refs(validate_state(s))['climb'], [])
        self.assertNotIn('climb', available_tools(s))

    def test_disabled_soft_edit_does_not_leak_target_reference(self):
        s = base_state()
        s['targets'][1]['soft_clearable'] = True
        with self.assertRaises(ValueError):
            validate_state(s)  # Server must never offer terrain edits before a commitment.
        s['tree'] = {'active':True,'remaining':2,'size':3,'completed':0,'terrain_edits':1}
        self.assertEqual(eligible_tool_refs(validate_state(s))['clear_soft'], ['b1'])
        s['tree']['terrain_edits'] = 2
        self.assertEqual(eligible_tool_refs(validate_state(s))['clear_soft'], [])

    def test_qwen_receives_grounded_skill_target_map_without_automatic_selection(self):
        service = ChatService(ROOT/'character-packs','qwen3:8b',Mock(spec=['chat_structured']),SessionStore())
        service.provider.chat_structured.return_value = json.dumps({'tool':'mine','target':'b0','reason':'reachable'})
        result=service.brain_step('yui','owner','get logs','npc',base_state())
        self.assertEqual(result['tool'],'mine')
        prompt=json.loads(service.provider.chat_structured.call_args.args[0][-1]['content'].removeprefix('READ-ONLY DATA: '))
        self.assertEqual(prompt['candidate_refs_by_skill']['clear_leaf'],['b1'])
        self.assertEqual(prompt['candidate_refs_by_skill']['mine'],['b0'])
        self.assertEqual(prompt['candidate_refs_by_skill']['approach'],['b1'])

    def test_floating_branch_ground_route_hidden_but_climb_remains_ai_choice(self):
        s = base_state()
        s["tree"] = {"active": True, "remaining": 15, "size": 18,
                     "completed": 0, "terrain_edits": 0}
        s["targets"][0]["visible"] = False
        s["targets"][0]["reachable"] = False
        s["targets"][0]["ground_stand_possible"] = False
        s["targets"][0]["leaf_clearable"] = False
        s["targets"][0]["obstruction"] = "none"
        normalized = validate_state(s)
        choices = eligible_tool_refs(normalized)
        self.assertNotIn("b0", choices["approach"])
        self.assertIn("b0", choices["climb"])
        with self.assertRaises(ValueError):
            validate_decision({"tool": "approach", "target": "b0", "reason": "walk to floating branch"}, normalized)
        self.assertEqual(validate_decision({"tool": "climb", "target": "b0", "reason": "use safe scaffold"}, normalized)["tool"], "climb")

    def test_missing_or_malformed_ground_stand_flag_fails_closed(self):
        s = base_state()
        del s["targets"][0]["ground_stand_possible"]
        with self.assertRaises(ValueError):
            validate_state(s)
        s["targets"][0]["ground_stand_possible"] = "false"
        with self.assertRaises(ValueError):
            validate_state(s)

    def test_java_observation_uses_same_ground_stand_probe_as_executor(self):
        java = ROOT / "forge-mod/src/main/java/io/github/sirp00m/npcortex"
        brain = (java / "BrainSkills.java").read_text()
        observe = (java / "BrainObservation.java").read_text()
        self.assertIn("groundStandPossible(ServerPlayer owner", brain)
        self.assertIn("safeVisibleStands(owner, npc, target, task,", brain)
        self.assertIn("safeVisibleStands(owner, npc, anchor, task,", brain)
        self.assertIn("BrainSkills.groundStandPossible(owner, npc, p, task)", observe)

    def test_java_reports_obstruction_from_current_eyes_and_skips_reachable_target(self):
        source = (JAVA/'WorldBlockActions.java').read_text()
        self.assertIn('CURRENT NPC sightline', source)
        self.assertIn('BlockPos direct = rayBlockingLeaf(npc, npc.getEyePosition(), log);', source)
        self.assertIn('return new OakObstruction("none", false);',source)
        self.assertNotIn('Sample at most 27 nearby real standing positions',source)
        skill = (JAVA/'BrainSkills.java').read_text()
        self.assertIn('target log already visible and reachable',skill)
        self.assertIn('WorldBlockActions.clearNaturalOakLeaf(owner, npc, leaf, log)',skill)


if __name__ == '__main__':
    unittest.main()
