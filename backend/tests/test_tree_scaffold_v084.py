"""Static integration checks for Forge-only tree/scaffold wiring.
These do not replace a real Forge build or game physics test.
"""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "forge-mod/src/main/java/io/github/sirp00m/npcortex"

class TreeScaffoldWiringTest(unittest.TestCase):
    def test_tree_lock_persists_before_each_mine(self):
        s = (JAVA / 'GatherTaskController.java').read_text()
        self.assertIn('t.putBoolean("treeLocked", true)', s)
        self.assertIn('ScaffoldPolicy.sameTrunk(', s)
        self.assertIn('TreeScaffold.treeHasLogs(level, t)', s)
        self.assertIn('mineAt(owner, npc, item, target.block())', s)

    def test_pending_drops_are_not_fake_progress(self):
        s = (JAVA / 'GatherTaskController.java').read_text()
        self.assertIn('t.putInt("pendingLogs", t.getInt("pendingLogs") + 1)', s)
        self.assertIn('count + pending >= goal', s)
        self.assertIn('TreeScaffold.size(t) > 0', s)
        self.assertIn('"waiting for " + pending + " mined drops', s)

    def test_cleanup_on_cancel_and_finish(self):
        s = (JAVA / 'GatherTaskController.java').read_text()
        self.assertIn('t.putBoolean("cancelRequested", true)', s)
        self.assertIn('change(npc, t, CLEANUP, now)', s)
        self.assertIn('TreeScaffold.dismantle(level, npc, t)', s)

    def test_scaffolds_are_persisted_and_returned(self):
        s = (JAVA / 'TreeScaffold.java').read_text()
        self.assertIn('task.put(LIST, placed)', s)
        self.assertIn('placed.getCompound(placed.size()-1)', s)
        self.assertIn('npc.bag().addItem(new ItemStack(item, 1))', s)
        self.assertIn('npc.getJumpControl().jump()', s)

    def test_only_safe_natural_leaf_cleanup(self):
        s = (JAVA / 'TreeScaffold.java').read_text()
        self.assertIn('LeavesBlock.PERSISTENT', s)
        self.assertIn('MAX_LEAF_CLEAR', s)

if __name__ == '__main__':
    unittest.main()
