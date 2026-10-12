import io.github.sirp00m.npcortex.TreeHarvestPolicy;
import io.github.sirp00m.npcortex.GatherTaskIntent;

public class TreeHarvestPolicyTest {
    private static int n=0;
    private static void check(boolean condition) {
        n++; if (!condition) throw new AssertionError("TreeHarvestPolicy check " + n);
    }
    public static void main(String[] args) {
        check(Double.isInfinite(TreeHarvestPolicy.priority(false,false,false,5,2,10)));
        check(TreeHarvestPolicy.priority(true,false,false,2,2,10)
                < TreeHarvestPolicy.priority(false,true,false,2,2,10));
        check(TreeHarvestPolicy.priority(false,true,false,2,2,10)
                < TreeHarvestPolicy.priority(false,false,true,2,2,10));
        check(TreeHarvestPolicy.priority(true,false,false,2,2,10)
                < TreeHarvestPolicy.priority(true,false,false,4,2,10)); // bottom up from ground
        check(TreeHarvestPolicy.priority(true,false,false,4,6,10)
                < TreeHarvestPolicy.priority(true,false,false,2,6,10)); // top down if above
        check(TreeHarvestPolicy.MAX_LEAF_CLEAR > 0 && TreeHarvestPolicy.MAX_LEAF_CLEAR <= 8);
        check(TreeHarvestPolicy.MAX_CANDIDATES <= 40);
        check(GatherTaskIntent.parse("Yui, chop 6 oak logs") != null);
        check(GatherTaskIntent.parse("Yui, cut down 4 oak logs") != null);
        check(GatherTaskIntent.parse("Yui, mine 6 sand blocks") != null);
        check(GatherTaskIntent.parse("Yui, do not chop 6 oak logs") == null);
        check(GatherTaskIntent.parse("Chop 100 oak logs") == null);
        System.out.println("TreeHarvestPolicy + commands: " + n + " assertions passed");
    }
}
