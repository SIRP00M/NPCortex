"""v0.8.2 schema/planner regressions. Mocked Qwen; not an in-game physics test."""
import json
import unittest
from pathlib import Path
from unittest.mock import Mock
from npcortex_backend.brain import (validate_state, validate_decision,
                                    schema_for_observation, BRAIN_POLICY)
from npcortex_backend.service import ChatService
from npcortex_backend.session import SessionStore

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / 'forge-mod/src/main/java/io/github/sirp00m/npcortex'


def observation(active=False, remaining=0, collected=0, mined=0, completed=0,
                soil=False, terrain_edits=0):
    return {
        "goal_item": "minecraft:oak_log", "goal_count": 6, "collected": collected,
        "mined": mined, "calls": 2, "failures": 0, "same_failures": 0,
        "stalls": 0, "last_result": "[Server confirmed previous action.]",
        "scaffold": 0, "paused": False, "owner_distance": 2.0,
        "tree": {"active": active, "remaining": remaining,
                 "size": max(remaining + mined, 1) if active else 0,
                 "completed": completed, "terrain_edits": terrain_edits},
        "equipment": {"holding": "minecraft:air", "bag": [], "can_scaffold": True},
        "surroundings": {"below": "minecraft:grass_block", "feet": "minecraft:air",
                         "ahead": "minecraft:air"},
        "targets": [{"ref": "b0", "id": "minecraft:oak_log", "dx": 2,
                     "dy": 1, "dz": 0, "visible": True, "reachable": True,
                     "obstruction": "other_block" if soil else "none",
                     "leaf_clearable": False, "ground_stand_possible": True, "soft_clearable": soil}],
        "drops": []
    }


class WholeTreeContractTests(unittest.TestCase):
    def test_qwen_may_mine_past_requested_quantity_while_tree_active(self):
        s = observation(active=True, remaining=2, collected=6, mined=6)
        tools = schema_for_observation(validate_state(s))["properties"]["tool"]["enum"]
        self.assertIn('mine', tools)
        self.assertNotIn('finish', tools)
        self.assertEqual(validate_decision({"tool": "mine", "target": "b0", "reason": "tree unfinished"},
                                           validate_state(s))["tool"], 'mine')

    def test_extra_wood_stays_in_bag_without_starting_another_tree(self):
        s = observation(collected=9, mined=9, completed=1)
        tools = schema_for_observation(validate_state(s))["properties"]["tool"]["enum"]
        self.assertIn('finish', tools)
        self.assertNotIn('mine', tools)

    def test_cannot_complete_when_mined_drop_is_not_stored(self):
        s = observation(collected=6, mined=8, completed=1)
        self.assertNotIn('finish', schema_for_observation(validate_state(s))['properties']['tool']['enum'])

    def test_no_unauthorized_dirt_edits_or_coordinate_injection(self):
        s = observation(active=True, remaining=2, mined=2)
        with self.assertRaises(ValueError):
            validate_decision({"tool": "clear_soft", "target": "b0", "reason": "dig"}, validate_state(s))
        s['targets'][0]['soft_clearable'] = True
        self.assertEqual(validate_decision({"tool": "clear_soft", "target": "b0", "reason": "dig"},
                                           validate_state(s))['tool'], 'clear_soft')
        for bad in ('0 64 0', 'b12', 'd0'):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                validate_decision({"tool": "clear_soft", "target": bad, "reason": "dig"}, validate_state(s))

    def test_restoration_blocks_completion(self):
        s = observation(collected=7, mined=7, completed=1, terrain_edits=1)
        tools = schema_for_observation(validate_state(s))['properties']['tool']['enum']
        self.assertIn('restore', tools)
        self.assertNotIn('finish', tools)
        self.assertEqual(validate_decision({"tool": "restore", "target": "", "reason": "cleanup"},
                                           validate_state(s))['tool'], 'restore')

    def test_invalid_tree_ledger_rejected(self):
        s = observation(active=True, remaining=2, mined=1)
        for invalid in (-1, 97, True):
            with self.subTest(invalid=invalid):
                s['tree']['remaining'] = invalid
                with self.assertRaises(ValueError):
                    validate_state(s)

    def test_model_selects_recovery_action_from_feedback(self):
        provider = Mock(spec=['chat_structured'])
        provider.chat_structured.return_value = json.dumps({
            "tool": "clear_soft", "target": "b0", "reason": "approach failed; dirt blocks sightline"})
        service = ChatService(ROOT / 'character-packs', 'qwen3:8b', provider, SessionStore())
        s = observation(active=True, remaining=3, mined=1, soil=True)
        s['last_result'] = '[Action failed: no safe visible standing position.]'
        result = service.brain_step('yui', 'owner', 'collect oak logs', 'npc', s)
        self.assertEqual(result['tool'], 'clear_soft')
        self.assertIn('no safe visible standing', provider.chat_structured.call_args.args[0][-1]['content'])

    def test_no_implied_claim_of_world_test(self):
        self.assertIn('natural', BRAIN_POLICY)
        controller = (JAVA / 'AgenticTaskController.java').read_text() + (JAVA / 'HarvestGoalSkills.java').read_text()
        self.assertIn('WholeTreeTracker.commit(t, proposedTree)', controller)
        self.assertIn('WholeTreeTracker.releaseCompleted(t)', controller)
        self.assertIn('WorldActions.pickupForGather(owner, npc', controller)
        self.assertIn('WorldTerrainEdits.restore(level, npc, owner, t)', controller)


if __name__ == '__main__':
    unittest.main()
