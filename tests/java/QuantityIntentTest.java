import io.github.sirp00m.npcortex.GatherTaskIntent;
import io.github.sirp00m.npcortex.BulkTransferIntent;

/** Pure-Java command parsing tests (Forge-independent). */
public final class QuantityIntentTest {
    private static int checks;
    private static void check(boolean value) {
        if (!value) throw new AssertionError("Quantity Intent assertion " + (++checks) + " failed");
        checks++;
    }
    private static void gather(String request, String item, int count) {
        GatherTaskIntent.Request parsed = GatherTaskIntent.parse(request);
        check(parsed != null);
        check(item.equals(parsed.itemId()));
        check(count == parsed.count());
    }
    private static void bulk(String request, boolean give, boolean all, int count, String item) {
        BulkTransferIntent.Request parsed = BulkTransferIntent.parse(request);
        check(parsed != null);
        check(parsed.give() == give);
        check(parsed.all() == all);
        check(parsed.count() == count);
        check(parsed.itemId().equals(item));
    }
    public static void main(String[] args) {
        gather("Yui, collect 6 sand blocks!", "minecraft:sand", 6);
        gather("Yui, mine 6 blocks of sand", "minecraft:sand", 6);
        gather("Can you gather 3 oak logs and bring them back to me?", "minecraft:oak_log", 3);
        gather("Please get me 4 dirt blocks", "minecraft:dirt", 4);
        gather("Yui, dig 6 sand blocks for me", "minecraft:sand", 6);
        gather("I need you to collect 10 oak logs and bring them back to me", "minecraft:oak_log", 10);
        gather("Collect 16 minecraft:sand", "minecraft:sand", 16);
        check(GatherTaskIntent.parse("collect 17 sand") == null);
        check(GatherTaskIntent.rejection("collect 17 sand") != null);
        check(GatherTaskIntent.rejection("collect 4 minecraft:diamond_ore") != null);
        check(GatherTaskIntent.parse("Don't collect 6 sand") == null);
        check(GatherTaskIntent.parse("How do you collect 6 sand?") == null);
        check(GatherTaskIntent.parse("\"collect 6 sand\"") == null);
        check(GatherTaskIntent.parse("Collect 6 sand and place it in my chest") == null);
        bulk("Yui, drop all", false, true, BulkTransferIntent.MAX_ITEMS, "");
        bulk("drop everything", false, true, BulkTransferIntent.MAX_ITEMS, "");
        bulk("Yui, give me all", true, true, BulkTransferIntent.MAX_ITEMS, "");
        bulk("Give me everything", true, true, BulkTransferIntent.MAX_ITEMS, "");
        bulk("Give me all your diamonds", true, true, BulkTransferIntent.MAX_ITEMS, "minecraft:diamond");
        bulk("Drop all the sand blocks", false, true, BulkTransferIntent.MAX_ITEMS, "minecraft:sand");
        bulk("Drop 6 sand blocks", false, false, 6, "minecraft:sand");
        bulk("Yui, give me 6 sand blocks", true, false, 6, "minecraft:sand");
        bulk("Give me 2 diamond swords", true, false, 2, "minecraft:diamond_sword");
        bulk("Drop all minecraft:oak_log", false, true, BulkTransferIntent.MAX_ITEMS, "minecraft:oak_log");
        check(BulkTransferIntent.parse("Drop all except my sword") == null);
        check(BulkTransferIntent.parse("Don't drop all") == null);
        check(BulkTransferIntent.parse("How do I drop all") == null);
        check(BulkTransferIntent.parse("Drop 6") == null);
        check(BulkTransferIntent.parse("\"Drop all\"") == null);
        check(BulkTransferIntent.parse("Drop all and follow me") == null);
        System.out.println("QuantityIntentTest: " + checks + " assertions passed");
    }
}
