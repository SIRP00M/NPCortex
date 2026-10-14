"""Vanilla overworld tree groundwork. Static and backend schema checks; NOT Forge physics tests."""
from pathlib import Path
import unittest
from npcortex_backend.brain import validate_state, schema_for_observation, validate_decision
from test_agentic_brain_v085 import state

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "forge-mod/src/main/java/io/github/sirp00m/npcortex"


class TreeSpeciesGroundworkTests(unittest.TestCase):
    def test_branchy_tree_observation_can_represent_high_targets(self):
        s = state()
        s["goal_item"] = "minecraft:cherry_log"
        for b in s["targets"]:
            b["id"] = s["goal_item"]
        for d in s["drops"]:
            d["id"] = s["goal_item"]
        s["targets"][1]["dy"] = 21
        s["targets"][1]["ground_stand_possible"] = False  # floating branch has no supported ground standing position
        s["tree"] = {"active": True, "remaining": 52, "size": 58,
                      "completed": 0, "terrain_edits": 0}
        s["mined"] = 6
        valid = validate_state(s)
        self.assertEqual(valid["tree"]["remaining"], 52)
        self.assertIn("climb", schema_for_observation(valid)["properties"]["tool"]["enum"])
        with self.assertRaises(ValueError):
            validate_decision({"tool":"approach","target":"b1","reason":"no ground stand"}, valid)
        self.assertEqual("climb", validate_decision({"tool":"climb","target":"b1","reason":"need vertical access"}, valid)["tool"])

    def test_tree_grounding_uses_species_and_connected_logs(self):
        source = (JAVA / "WholeTreeTracker.java").read_text()
        self.assertIn("TreeSpecies.profile(logId)", source)
        self.assertIn("groundedRoots(roots, species, seen)", source)
        self.assertIn("for (int dx = -1; dx <= 1; dx++)", source)
        self.assertIn("if (!level.isLoaded(next)) return null", source)
        self.assertIn("species.isFoliage", source)
        self.assertNotIn("Blocks.OAK_LOG", source)

    def test_only_registered_species_can_start_ai_task(self):
        source = (JAVA / "AgenticTaskController.java").read_text() + (JAVA / "HarvestGoalSkills.java").read_text()
        self.assertIn("TreeSpecies.supportedLog(item)", source)
        self.assertIn("WholeTreeTracker.commit(t, proposedTree)", source)
        self.assertIn("WholeTreeTracker.pending(owner.serverLevel(), t)", source)

    def test_unknown_modded_wood_cannot_edit_world(self):
        registry = (JAVA / "TreeSpecies.java").read_text()
        self.assertIn('minecraft:dark_oak_log', registry)
        self.assertIn('minecraft:mangrove_log', registry)
        self.assertIn('minecraft:cherry_log', registry)
        self.assertIn('minecraft:birch_log', registry)
        self.assertNotIn('minecraft:crimson_stem", new Profile', registry)


if __name__ == "__main__":
    unittest.main()
