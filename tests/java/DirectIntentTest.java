import io.github.sirp00m.npcortex.DirectIntent;
import io.github.sirp00m.npcortex.ActionCatalog;

/** Run without Forge libraries: javac --release 17. */
public final class DirectIntentTest {
    private static int checks;
    private static void eq(String expected, String observed, String label) {
        checks++;
        if (expected == null ? observed != null : !expected.equals(observed)) {
            throw new AssertionError(label + ": expected " + expected + ", got " + observed);
        }
    }
    private static void check(boolean result, String label) {
        checks++;
        if (!result) throw new AssertionError(label);
    }
    public static void main(String[] args) {
        eq("stay", DirectIntent.action("Yui, stay here!", "yui", "Yui"), "existing simple stay");
        eq("stay", DirectIntent.action("Yui, wait for me here.", "yui", "Yui"), "reported bug");
        eq("stay", DirectIntent.action("Wait here for me, please!", "yui", "Yui"), "postposition and polite");
        eq("stay", DirectIntent.action("Yui, please wait right here", "yui", "Yui"), "polite named request");
        eq("stay", DirectIntent.action("Hey Yui, could you please wait for me here?", "yui", "Yui"), "greeting + question + polite");
        eq("stay", DirectIntent.action("Yui, I want you to stay at this spot", "yui", "Yui"), "I want you to");
        eq("stay", DirectIntent.action("Yui, hold your position", "yui", "Yui"), "hold position");
        eq("stay", DirectIntent.action("Yui, remain where you are", "yui", "Yui"), "remain in place");
        eq("stay", DirectIntent.action("Yui, don't follow me for now", "yui", "Yui"), "negative follow");
        eq("stay", DirectIntent.action("Yui, stop following me", "yui", "Yui"), "interrupt follow");
        eq("stay", DirectIntent.action("Yui, don't move", "yui", "Yui"), "do not move");
        eq("stay", DirectIntent.action("Wait here until I come back", "yui", "Yui"), "return guard");
        eq("stay", DirectIntent.action("Forest Guard, wait right here.", "forest_guard", "Forest Guard"), "custom character name");
        eq("follow", DirectIntent.action("Yui, follow me!", "yui", "Yui"), "existing follow");
        eq("follow", DirectIntent.action("Hey Yui, would you please come with me?", "yui", "Yui"), "polite follow");
        eq("follow", DirectIntent.action("Please walk with me", "yui", "Yui"), "walk with me");
        eq("look_at_player", DirectIntent.action("Yui, look at me", "yui", "Yui"), "look natural intent");
        eq("look_at_player", DirectIntent.action("Yui, turn to face me", "yui", "Yui"), "face synonym");
        eq("move_to_player", DirectIntent.action("Yui, please come over here", "yui", "Yui"), "come here");
        eq("move_to_player", DirectIntent.action("Could you please walk over to me?", "yui", "Yui"), "polite approach");
        eq("wander", DirectIntent.action("Yui, wander around", "yui", "Yui"), "wander around");
        eq("wander", DirectIntent.action("Yui, take a short walk", "yui", "Yui"), "short walk");
        eq("jump", DirectIntent.action("Yui, jump once", "yui", "Yui"), "jump");
        eq("jump", DirectIntent.action("Yui, hop", "yui", "Yui"), "hop synonym");
        eq(null, DirectIntent.action("Yui, come here and look at me", "yui", "Yui"), "compound move and look");
        eq(null, DirectIntent.action("Don't jump!", "yui", "Yui"), "negative jump");
        eq(null, DirectIntent.action("What does look at me mean?", "yui", "Yui"), "discuss look phrase");
        eq(null, DirectIntent.action("Remember when I asked you to follow me?", "yui", "Yui"), "remembered follow");
        eq(null, DirectIntent.action("Yui, stay here and drop the sword", "yui", "Yui"), "compound stay -> planner");
        eq(null, DirectIntent.action("Yui, wait here, then follow me", "yui", "Yui"), "compound sequence -> planner");
        eq(null, DirectIntent.action("Yui, wait here but follow me later", "yui", "Yui"), "contradictory -> planner");
        eq(null, DirectIntent.action("Don't stay here", "yui", "Yui"), "negated stay");
        eq(null, DirectIntent.action("Don't wait for me here", "yui", "Yui"), "negated wait");
        eq(null, DirectIntent.action("Yui, do not stop following me", "yui", "Yui"), "negated stop");
        eq(null, DirectIntent.action("What does wait here mean?", "yui", "Yui"), "discussion, not command");
        eq(null, DirectIntent.action("Yui, do you remember when I said wait here?", "yui", "Yui"), "remembered quote");
        eq(null, DirectIntent.action("Yui, tell me what 'wait here' means", "yui", "Yui"), "quoted instruction");
        eq(null, DirectIntent.action("Could you wait for me to grab that sword and then follow me", "yui", "Yui"), "compound with wait");
        eq(null, DirectIntent.action("The sword is in my hand", "yui", "Yui"), "no arbitrary commands");
        check(ActionCatalog.validTarget("drop_stored_item", "minecraft:iron_sword"), "target item validated");
        check(!ActionCatalog.validTarget("stay", "minecraft:iron_sword"), "stay rejects item target");
        System.out.println("NPCortex v0.5.6 DirectIntent/ActionCatalog tests: " + checks + " assertions passed.");
    }
}
