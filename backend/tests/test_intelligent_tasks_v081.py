"""v0.8.1 source contract checks (not a substitute for Forge in-game tests)."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "forge-mod/src/main/java/io/github/sirp00m/npcortex"


def source(name):
    return (JAVA / name).read_text(encoding="utf-8")


class IntelligentTasksSourceTests(unittest.TestCase):
    def test_server_scheduler_registered(self):
        self.assertIn("GatherTaskController::tick", source("NPCortex.java"))

    def test_commands(self):
        text = source("NPCortex.java")
        self.assertIn('Commands.literal("gather")', text)
        for cmd in ("status", "cancel", "resume"):
            self.assertIn(f'Commands.literal("{cmd}")', text)

    def test_dialogue_fast_path_with_owner_checks(self):
        text = source("network/DialogueRequestPacket.java")
        self.assertLess(text.index("GatherTaskIntent.parse(text)"), text.index("ChatBridge.agent("))
        self.assertLess(text.index('player.getUUID().equals(npc.getOwnerUUID())'),
                        text.index("GatherTaskIntent.parse(text)"))

    def test_task_uses_persistent_npc_data(self):
        text = source("GatherTaskController.java")
        self.assertIn("npc.getPersistentData().put(TAG, data)", text)
        self.assertIn("npc.getPersistentData().getCompound(TAG)", text)

    def test_no_chunk_load_or_teleport(self):
        text = source("GatherTaskController.java")
        self.assertIn("level.isLoaded", text)
        self.assertNotIn("setPos(", text)
        self.assertNotIn("teleportTo", text)

    def test_task_separate_from_eight_step_plan(self):
        self.assertIn("GatherTaskController.isBusy", source("ActionPlanRunner.java"))
        self.assertIn("GatherTaskController.isBusy", source("WorldActions.java"))

    def test_bag_capacity_guard(self):
        self.assertIn("canStoreGather", source("WorldActions.java"))
        self.assertIn("!WorldActions.canStoreGather", source("GatherTaskController.java"))

    def test_owner_in_distance_and_server_permissions(self):
        text = source("GatherTaskController.java")
        self.assertIn("owner.getUUID().equals(npc.getOwnerUUID())", text)
        self.assertIn("NPCortexConfig.WORLD_EDITS_ENABLED.get()", text)
        self.assertIn("owner.hasPermissions(2)", text)
        self.assertIn("owner.getServer().isSingleplayer()", text)

    def test_progress_and_cancel(self):
        text = source("GatherTaskController.java")
        for term in ('"goal"', '"collected"', '"state"', '"previousFollow"',
                     'public static String cancel', 'public static String resume'):
            self.assertIn(term, text)

    def test_return_requires_inventory_capacity(self):
        text = source("WorldActions.java")
        self.assertIn("if (capacity < count) return 0", text)
        self.assertIn("if (available < count", text)


if __name__ == "__main__":
    unittest.main()
