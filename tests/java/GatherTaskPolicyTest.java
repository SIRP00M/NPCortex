import io.github.sirp00m.npcortex.GatherTaskIntent;
import io.github.sirp00m.npcortex.GatherTaskPolicy;

public final class GatherTaskPolicyTest {
    private static int checks = 0;
    private static void check(boolean ok) {
        checks++;
        if (!ok) throw new AssertionError("Gather task check " + checks + " failed");
    }
    public static void main(String[] args) {
        check(GatherTaskPolicy.valid("minecraft:oak_log", 10));
        check(GatherTaskPolicy.valid("minecraft:cobblestone", 16));
        check(!GatherTaskPolicy.valid("minecraft:oak_log", 17));
        check(!GatherTaskPolicy.valid("minecraft:oak_log", 0));
        check(!GatherTaskPolicy.valid("minecraft:stone", 10));
        check(!GatherTaskPolicy.valid("minecraft:diamond_ore", 2));
        check(GatherTaskPolicy.remaining(10, 7) == 3);
        check(GatherTaskPolicy.remaining(10, 12) == 0);
        check(GatherTaskPolicy.withinHome(400));
        check(!GatherTaskPolicy.withinHome(400.01));
        check(GatherTaskPolicy.ownerPresent(1024));
        check(!GatherTaskPolicy.ownerPresent(1025));
        check(GatherTaskIntent.parse("Yui, collect 10 oak logs and bring them back to me!") != null);
        check("minecraft:oak_log".equals(GatherTaskIntent.parse("Gather 10 oak logs").itemId()));
        check(GatherTaskIntent.parse("Collect 17 oak logs") == null);
        check(GatherTaskIntent.parse("Do not collect 10 oak logs") == null);
        check(GatherTaskIntent.parse("\"collect 10 oak logs\"") == null);
        check(GatherTaskIntent.parse("Would you collect 10 oak logs?") != null);
        check(GatherTaskIntent.parse("Collect 10 diamond ore") == null);
        check(GatherTaskIntent.parse("Collect 3 minecraft:oak_planks") != null);
        check(GatherTaskIntent.isCancel("Yui cancel the task"));
        check(!GatherTaskIntent.isCancel("don't cancel the task"));
        System.out.println("GatherTaskPolicy + intent: " + checks + " assertions passed");
    }
}
