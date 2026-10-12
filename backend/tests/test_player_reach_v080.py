"""Static integration regression tests for player-reach code and observation consistency."""
from pathlib import Path
import unittest

JAVA = (Path(__file__).resolve().parents[2] / 'forge-mod/src/main/java/io/github/sirp00m/npcortex')

class PlayerReachTest(unittest.TestCase):
    def test_reach_policy_is_central_and_mode_based(self):
        src = (JAVA / 'InteractionReach.java').read_text()
        self.assertIn('SURVIVAL_BLOCKS = 4.5D', src)
        self.assertIn('CREATIVE_BLOCKS = 5.0D', src)
        self.assertIn('public static double squared(boolean creative)', src)

    def test_mining_uses_same_reach_and_visibility(self):
        src = (JAVA / 'WorldBlockActions.java').read_text()
        self.assertIn('InteractionReach.squared(owner.isCreative())', src)
        self.assertIn('dist > reachSq', src)
        self.assertIn('!visible(npc, p)', src)
        self.assertIn('p.equals(center.below())', src)
        self.assertIn('level.isLoaded(candidate)', src)
        self.assertNotIn('closest = 10.0D', src)

    def test_drop_observation_and_pickup_match(self):
        act = (JAVA / 'WorldActions.java').read_text()
        obs = (JAVA / 'WorldObservation.java').read_text()
        self.assertIn('case "pickup_nearby" -> pickup(player, npc, targetItem)', act)
        for src in [act, obs]:
            self.assertIn('InteractionReach.blocks(', src)
            self.assertIn('InteractionReach.squared(', src)
            self.assertIn('inflate(reach)', src)
            self.assertIn('distanceToSqr(item) <= reachSq', src)
            self.assertNotIn('inflate(3.0D)', src)
        self.assertIn('ClipContext.Block.COLLIDER', act)
        self.assertIn('ClipContext.Block.COLLIDER', obs)

    def test_placement_is_directional_reach_bounded_and_safe(self):
        src = (JAVA / 'WorldBlockActions.java').read_text()
        self.assertIn('center.relative(npc.getDirection(), distance)', src)
        self.assertIn('distance >= 1', src)
        self.assertIn('distanceToSqr(Vec3.atCenterOf(candidate)) > reachSq', src)
        self.assertIn('new AABB(candidate)', src)
        self.assertIn('hit.getType() != HitResult.Type.MISS', src)
        self.assertIn('npc.bag().removeItem(slot, 1)', src)
        self.assertIn('enable', (JAVA / 'NPCortexConfig.java').read_text().lower())

if __name__ == '__main__':
    unittest.main()
