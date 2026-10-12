import io.github.sirp00m.npcortex.WorldInteractionPolicy;

public final class WorldInteractionPolicyTest {
    private static int assertions = 0;
    private static void check(boolean ok, String description) {
        assertions++;
        if (!ok) throw new AssertionError(description);
    }
    public static void main(String[] args) {
        check(WorldInteractionPolicy.breakable("minecraft:oak_log"), "can mine oak log");
        check(WorldInteractionPolicy.breakable("minecraft:stone"), "can mine stone with right tool");
        check(!WorldInteractionPolicy.breakable("minecraft:bedrock"), "no bedrock");
        check(!WorldInteractionPolicy.breakable("minecraft:chest"), "no chests");
        check(!WorldInteractionPolicy.breakable("minecraft:diamond_ore"), "no ores by default");
        check(WorldInteractionPolicy.placeable("minecraft:oak_planks"), "can place planks");
        check(!WorldInteractionPolicy.placeable("minecraft:tnt"), "no TNT");
        check(WorldInteractionPolicy.recipe("minecraft:oak_planks").output() == 4, "plank yield");
        check(WorldInteractionPolicy.recipe("minecraft:oak_planks").ingredients().get("minecraft:oak_log") == 1, "planks require log");
        check(WorldInteractionPolicy.recipe("minecraft:stick").ingredients().get("minecraft:oak_planks") == 2, "stick recipe");
        check(WorldInteractionPolicy.recipe("minecraft:torch").ingredients().size() == 2, "torch recipe");
        check(WorldInteractionPolicy.recipe("minecraft:diamond_sword") == null, "no unsupported recipes");
        System.out.println("WorldInteractionPolicy: " + assertions + " assertions passed");
    }
}
