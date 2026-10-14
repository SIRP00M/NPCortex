"""v0.8.2 recovery regression: planner affordances and limited low-level skills.

These tests do not simulate Forge physics or verify live Qwen output.
"""
import unittest
from pathlib import Path
from npcortex_backend.brain import validate_state, schema_for_observation, validate_decision
from test_whole_tree_v082 import observation

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "forge-mod/src/main/java/io/github/sirp00m/npcortex"


class TreeRecoveryRegression(unittest.TestCase):
    def test_active_tree_never_offers_idle_wait_while_logs_are_observed(self):
        s = observation(active=True, remaining=1, collected=4, mined=4)
        s["targets"][0].update(visible=False, reachable=False,
                                 obstruction="natural_tree_leaf", leaf_clearable=True)
        tools = schema_for_observation(validate_state(s))["properties"]["tool"]["enum"]
        self.assertNotIn("wait", tools)
        self.assertIn("clear_leaf", tools)
        self.assertIn("approach", tools)
        self.assertIn("climb", tools)  # NPC bag says material available
        with self.assertRaises(ValueError):
            validate_decision({"tool":"wait","target":"","reason":"stall"}, validate_state(s))

    def test_wait_still_available_when_no_targets_or_drops_exist(self):
        s = observation(active=False, remaining=0, mined=1, collected=0)
        s["targets"] = []
        tools = schema_for_observation(validate_state(s))["properties"]["tool"]["enum"]
        self.assertIn("wait", tools)

    def test_climb_denied_without_materials_but_other_skills_still_open(self):
        s = observation(active=True, remaining=12, mined=2)
        s["equipment"]["can_scaffold"] = False
        s["targets"][0].update(visible=False, reachable=False)
        tools = schema_for_observation(validate_state(s))["properties"]["tool"]["enum"]
        self.assertNotIn("climb", tools)
        self.assertIn("approach", tools)

    def test_java_climb_anchors_to_committed_root_not_branch(self):
        c = (JAVA/"AgenticTaskController.java").read_text() + (JAVA/"HarvestGoalSkills.java").read_text()
        tracker = (JAVA/"WholeTreeTracker.java").read_text()
        self.assertIn("WholeTreeTracker.verifiedRoot(owner.serverLevel(), proposedTree)", c)
        self.assertIn('t.putInt("treeX", t.getInt("wholeTreeRootX"))', c)
        self.assertIn('t.putInt("treeBaseY", t.getInt("wholeTreeRootY"))', c)
        self.assertIn("WholeTreeTracker.contains(t, pos)", c)
        self.assertIn("public static BlockPos verifiedRoot(ServerLevel level, long[] tree)", tracker)
        self.assertIn('task.remove("wholeTreeRootX")', tracker)

    def test_leaf_skill_bounded_and_server_revalidates_each_leaf(self):
        s = (JAVA/"BrainSkills.java").read_text()
        self.assertIn("attempt < 3", s)
        self.assertIn("WorldBlockActions.blockingNaturalOakLeaf(npc, log, owner.isCreative())", s)
        self.assertIn("WorldBlockActions.clearNaturalOakLeaf(owner, npc, leaf, log)", s)
        self.assertIn('if (!last.startsWith("[Action confirmed:")) break;', s)


if __name__ == "__main__":
    unittest.main()
