package io.github.sirp00m.npcortex;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Server-verifiable, bounded connected wood component. Qwen chooses actions.
 * This scanner refuses ambiguous connected trunks, missing chunks and oversized
 * trees rather than truncating and claiming successful whole-tree harvesting.
 */
public final class WholeTreeTracker {
    private static final String KEY = "wholeTreePositions";
    private WholeTreeTracker() {}

    public static boolean isCommitted(CompoundTag task) { return task.contains(KEY); }
    private static String id(ServerLevel level, BlockPos p) {
        return BuiltInRegistries.BLOCK.getKey(level.getBlockState(p).getBlock()).toString();
    }
    public static List<BlockPos> remaining(ServerLevel level, CompoundTag task) {
        List<BlockPos> left = new ArrayList<>();
        if (!isCommitted(task)) return left;
        String expected = task.getString("item");
        for (long packed : task.getLongArray(KEY)) {
            BlockPos p = BlockPos.of(packed);
            if (!level.isLoaded(p) || (expected.equals(id(level,p))
                    && !TreeScaffold.ownsPlacedBlock(task,p)
                    && !Navigation3DAccess.ownsPlacedBlock(task,p))) left.add(p);
        }
        return left;
    }
    public static int pending(ServerLevel level, CompoundTag task) { return remaining(level, task).size(); }
    public static boolean contains(CompoundTag task, BlockPos p) {
        if (!isCommitted(task) || p == null) return false;
        for (long n : task.getLongArray(KEY)) if (n == p.asLong()) return true;
        return false;
    }
    private static boolean ground(BlockState b) {
        return b.is(Blocks.GRASS_BLOCK) || b.is(Blocks.DIRT) || b.is(Blocks.PODZOL)
                || b.is(Blocks.COARSE_DIRT) || b.is(Blocks.ROOTED_DIRT)
                || b.is(Blocks.MOSS_BLOCK) || b.is(Blocks.MUD) || b.is(Blocks.MYCELIUM);
    }
    private static boolean groundedRoots(List<BlockPos> roots, TreeSpecies.Profile species,
            Set<BlockPos> visited) {
        if (roots.isEmpty()) return false;
        if (roots.size() == 1) {
            BlockPos root = roots.get(0);
            return visited.contains(root.above()) && visited.contains(root.above(2));
        }
        if (!species.permitsWideRoot() || roots.size() != 4) return false;
        int minX = roots.stream().mapToInt(BlockPos::getX).min().orElse(0);
        int minZ = roots.stream().mapToInt(BlockPos::getZ).min().orElse(0);
        int y = roots.get(0).getY();
        for (int x = minX; x <= minX + 1; x++) for (int z = minZ; z <= minZ + 1; z++) {
            BlockPos p = new BlockPos(x, y, z);
            if (!roots.contains(p) || !visited.contains(p.above())) return false;
        }
        return true;
    }

    /** Conservative shape heuristic; natural origin cannot be proven from blocks alone. */
    public static long[] inspect(ServerLevel level, BlockPos chosen, BlockPos home) {
        if (chosen == null || home == null || !level.isLoaded(chosen)) return null;
        String logId = id(level, chosen);
        TreeSpecies.Profile species = TreeSpecies.profile(logId);
        if (species == null) return null;
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(chosen.immutable());
        List<BlockPos> roots = new ArrayList<>();
        while (!queue.isEmpty()) {
            BlockPos p = queue.removeFirst();
            if (!seen.add(p)) continue;
            // Never silently truncate a connected tree or force-load chunks.
            if (seen.size() > WholeTreePolicy.MAX_TREE_LOGS || !level.isLoaded(p)
                    || !logId.equals(id(level, p))
                    || Math.abs(p.getX() - home.getX()) > WholeTreePolicy.MAX_HOME_HORIZONTAL
                    || Math.abs(p.getZ() - home.getZ()) > WholeTreePolicy.MAX_HOME_HORIZONTAL
                    || Math.abs(p.getX() - chosen.getX()) > WholeTreePolicy.MAX_TREE_HORIZONTAL
                    || Math.abs(p.getZ() - chosen.getZ()) > WholeTreePolicy.MAX_TREE_HORIZONTAL
                    || level.isOutsideBuildHeight(p)
                    || !level.getWorldBorder().isWithinBounds(p)) return null;
            if (!level.isLoaded(p.below())) return null;
            if (ground(level.getBlockState(p.below()))) roots.add(p);
            // 26-neighbour connectivity supports diagonal oak/cherry/acacia branches.
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++)
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    BlockPos next = p.offset(dx, dy, dz);
                    if (!level.isLoaded(next)) return null;
                    if (logId.equals(id(level, next)) && !seen.contains(next)) queue.add(next.immutable());
                }
        }
        if (seen.size() < 3 || !groundedRoots(roots, species, seen)) return null;
        int highest = seen.stream().mapToInt(BlockPos::getY).max().orElse(chosen.getY());
        boolean canopy = false;
        for (BlockPos log : seen) {
            if (log.getY() < highest - 4) continue;
            for (int dx = -3; dx <= 3 && !canopy; dx++)
                for (int dz = -3; dz <= 3 && !canopy; dz++)
                    for (int dy = -2; dy <= 3; dy++) {
                        BlockPos p = log.offset(dx, dy, dz);
                        if (!level.isLoaded(p)) continue;
                        BlockState b = level.getBlockState(p);
                        if (species.isFoliage(id(level, p)) && b.hasProperty(LeavesBlock.PERSISTENT)
                                && !b.getValue(LeavesBlock.PERSISTENT)) {
                            canopy = true;
                            break;
                        }
                    }
        }
        if (!canopy) return null;
        return seen.stream().sorted(Comparator.comparingLong(BlockPos::asLong))
                .mapToLong(BlockPos::asLong).toArray();
    }
    public static BlockPos verifiedRoot(ServerLevel level, long[] tree) {
        if (tree == null || tree.length == 0) return null;
        // For 2x2 roots choose one stable, physical corner as scaffold reference.
        return java.util.Arrays.stream(tree)
                .mapToObj(packed -> BlockPos.of(packed))
                .filter(p -> level.isLoaded(p.below()) && ground(level.getBlockState(p.below())))
                .min(Comparator.<BlockPos>comparingInt(p -> p.getY())
                        .thenComparingInt(p -> p.getX())
                        .thenComparingInt(p -> p.getZ()))
                .orElse(null);
    }
    public static void commit(CompoundTag task, long[] tree) {
        if (tree == null || tree.length == 0 || tree.length > WholeTreePolicy.MAX_TREE_LOGS)
            throw new IllegalArgumentException("Unsafe whole-tree commitment");
        task.putLongArray(KEY, tree);
        task.putInt("committedTreeSize", tree.length);
    }
    public static void releaseCompleted(CompoundTag task) {
        task.remove(KEY);
        task.putInt("committedTreeSize", 0);
        task.remove("wholeTreeRootX");
        task.remove("wholeTreeRootY");
        task.remove("wholeTreeRootZ");
        task.putInt("completedTrees", task.getInt("completedTrees") + 1);
    }
}
