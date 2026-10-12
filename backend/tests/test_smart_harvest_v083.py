"""Source guardrails for NPCortex v0.8.3-alpha. Not in-game integration tests."""
from pathlib import Path
import unittest

JAVA = Path(__file__).resolve().parents[2] / 'forge-mod/src/main/java/io/github/sirp00m/npcortex'

class SmartHarvestContract(unittest.TestCase):
    def text(self, f): return (JAVA / f).read_text(encoding='utf-8')

    def test_no_automatic_gather_delivery(self):
        source = self.text('GatherTaskController.java')
        self.assertNotIn('WorldActions.deliverGathered(', source)
        self.assertIn('inventory retained by NPC', source)
        self.assertIn('npc.setFollowing(follow)', source)

    def test_old_saved_return_state_does_not_autodeliver(self):
        source = self.text('GatherTaskController.java')
        self.assertIn('RETURN.equals(state)', source)
        self.assertIn('stored in NPC inventory', source)

    def test_mine_exact_selected_block(self):
        source = self.text('GatherTaskController.java')
        self.assertIn('WorldBlockActions.mineAt(owner, npc, item, target.block())', source)
        self.assertNotIn('WorldBlockActions.mine(owner, npc, item)', source)
        world = self.text('WorldBlockActions.java')
        self.assertIn('BuiltInRegistries.BLOCK.getKey(', world)
        self.assertIn('!visible(npc, pos)', world)

    def test_only_natural_leaves_and_bounded(self):
        source = self.text('WorldBlockActions.java')
        self.assertIn('Blocks.OAK_LEAVES', source)
        self.assertIn('LeavesBlock.PERSISTENT', source)
        self.assertIn('!state.getValue(LeavesBlock.PERSISTENT)', source)
        self.assertIn('!leaf.equals(blockingNaturalOakLeaf(npc, log))', source)
        controller = self.text('GatherTaskController.java')
        self.assertIn('MAX_LEAF_CLEAR', controller)
        self.assertIn('level.isLoaded(', controller)

    def test_pathfinding_visible_stand_and_no_teleport(self):
        source = self.text('GatherTaskController.java')
        self.assertIn('findVisibleStand(', source)
        self.assertIn('WorldBlockActions.visibleFrom(', source)
        self.assertNotIn('teleportTo(', source)
        self.assertNotIn('setPos(', source)

if __name__ == '__main__': unittest.main()
