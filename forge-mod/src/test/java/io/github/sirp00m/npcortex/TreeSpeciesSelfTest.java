package io.github.sirp00m.npcortex;

public final class TreeSpeciesSelfTest {
    private static int assertions;
    private static void check(boolean value, String msg) {
        if (!value) throw new AssertionError(msg);
        assertions++;
    }
    public static void main(String[] args) {
        check(TreeSpecies.supportedLogs().size() == 8, "Vanilla 1.20.1 overworld tree profiles");
        for (String wood : TreeSpecies.supportedLogs()) {
            check(TreeSpecies.supportedLog(wood), "supported " + wood);
            check(TreeSpecies.profile(wood).foliage().size() > 0, "has foliage " + wood);
            check(!TreeSpecies.matchingFoliage(wood, "minecraft:stone"), "no arbitrary rock clearance " + wood);
        }
        check(TreeSpecies.profile("minecraft:dark_oak_log").permitsWideRoot(), "dark oak 2x2");
        check(TreeSpecies.profile("minecraft:jungle_log").permitsWideRoot(), "giant jungle 2x2");
        check(TreeSpecies.profile("minecraft:spruce_log").permitsWideRoot(), "giant spruce 2x2");
        check(TreeSpecies.matchingFoliage("minecraft:oak_log", "minecraft:flowering_azalea_leaves"), "Azalea canopy with oak wood");
        check(!TreeSpecies.supportedLog("minecraft:stripped_oak_log"), "do not destroy player structures");
        check(!TreeSpecies.supportedLog("minecraft:crimson_stem"), "Nether fungi require distinct topology");
        System.out.println("TreeSpecies: " + assertions + " assertions passed");
    }
}
