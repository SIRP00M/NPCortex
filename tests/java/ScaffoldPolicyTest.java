import io.github.sirp00m.npcortex.ScaffoldPolicy;

public class ScaffoldPolicyTest {
    private static int count;
    private static void check(boolean value) {
        count++;
        if (!value) throw new AssertionError("ScaffoldPolicy #" + count);
    }
    public static void main(String[] args) {
        check(ScaffoldPolicy.mayBuild(12,true,true));
        check(ScaffoldPolicy.mayBuild(80,true,true));
        check(ScaffoldPolicy.MAX_TREE_HEIGHT >= 12 && ScaffoldPolicy.MAX_TREE_HEIGHT <= 20);
        check(ScaffoldPolicy.validMaterial("minecraft:oak_log"));
        check(ScaffoldPolicy.validMaterial("minecraft:cobblestone"));
        check(ScaffoldPolicy.validMaterial("minecraft:dirt"));
        check(!ScaffoldPolicy.validMaterial("minecraft:sand"));
        check(!ScaffoldPolicy.validMaterial("minecraft:tnt"));
        check(!ScaffoldPolicy.validMaterial("minecraft:diamond_block"));
        check(ScaffoldPolicy.sameTrunk(2, 3, 2, 3));
        check(!ScaffoldPolicy.sameTrunk(3, 3, 2, 3));
        check(ScaffoldPolicy.mayBuild(0, true, true));
        check(ScaffoldPolicy.mayBuild(5, true, true));
        check(ScaffoldPolicy.mayBuild(6, true, true));
        check(!ScaffoldPolicy.mayBuild(ScaffoldPolicy.MAX_HEIGHT, true, true));
        check(!ScaffoldPolicy.mayBuild(1, false, true));
        check(!ScaffoldPolicy.mayBuild(1, true, false));
        check(!ScaffoldPolicy.jumpClearance(65.99, 65, 0.0));
        check(ScaffoldPolicy.jumpClearance(66.2, 65, 0.1));
        check(!ScaffoldPolicy.jumpClearance(66.2, 65, 0.6));
        check(ScaffoldPolicy.safeDisassembly(65, 66, 0.2, true, true, true));
        check(!ScaffoldPolicy.safeDisassembly(65, 68, 0.2, true, true, true));
        check(!ScaffoldPolicy.safeDisassembly(65, 66, 0.7, true, true, true));
        check(!ScaffoldPolicy.safeDisassembly(65, 66, 0.2, false, true, true));
        check(!ScaffoldPolicy.safeDisassembly(65, 66, 0.2, true, false, true));
        check(!ScaffoldPolicy.safeDisassembly(65, 66, 0.2, true, true, false));
        System.out.println("ScaffoldPolicy: " + count + " assertions passed");
    }
}
