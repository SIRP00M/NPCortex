"""Static integration check for the legacy oak-leaf caller after API signature update.

This catches a known signature regression but is NOT a full Forge compilation test.
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]


class LeafApiCompatibilityTest(unittest.TestCase):
    def test_legacy_gather_calls_use_creative_reach(self):
        gather = (ROOT / "forge-mod/src/main/java/io/github/sirp00m/npcortex/GatherTaskController.java").read_text(encoding="utf-8")
        actions = (ROOT / "forge-mod/src/main/java/io/github/sirp00m/npcortex/WorldBlockActions.java").read_text(encoding="utf-8")
        self.assertRegex(actions, r"public static BlockPos blockingNaturalOakLeaf\(CompanionEntity npc, BlockPos log,\s*boolean creative\)")
        expected = re.findall(
            r"WorldBlockActions\.blockingNaturalOakLeaf\(npc,\s*(?:log|pos),\s*owner\.isCreative\(\)\)",
            gather,
        )
        self.assertEqual(2, len(expected), "Legacy Gather controller must pass owner creative mode for both leaf checks")
        all_calls = re.findall(r"WorldBlockActions\.blockingNaturalOakLeaf\(", gather)
        self.assertEqual(2, len(all_calls), "Review additional leaf call sites for signature compatibility")


if __name__ == "__main__":
    unittest.main()
