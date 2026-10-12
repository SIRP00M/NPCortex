package io.github.sirp00m.npcortex;

import io.github.sirp00m.npcortex.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Server-owned temporary straight pillars. Only edits blocks this task recorded itself.
 *  Pillars are dismantled one level at a time while the NPC stands on them.
 *  Failures leave explicit paused work; never blindly delete terrain on cancel.
 */
public final class TreeScaffold {
    public static final String WAIT = "WAIT";
    public static final String DONE = "DONE";
    public static final String BLOCKED = "BLOCKED:";
    private static final String LIST = "npcortexScaffold";

    private TreeScaffold() {}

    public static int size(CompoundTag task) {
        return task.getList(LIST, Tag.TAG_COMPOUND).size();
    }

    public static boolean treeHasLogs(ServerLevel level, CompoundTag task) {
        if (!task.getBoolean("treeLocked")) return false;
        int x = task.getInt("treeX"), z = task.getInt("treeZ");
        int base = task.getInt("treeBaseY");
        for (int y = base - 1; y <= base + ScaffoldPolicy.MAX_TREE_HEIGHT; y++) {
            BlockPos pos = new BlockPos(x, y, z);
            if (!level.isLoaded(pos)) return true; // unknown != empty
            if (level.getBlockState(pos).is(net.minecraft.world.level.block.Blocks.OAK_LOG)) return true;
        }
        return false;
    }

    private static String material(CompanionEntity npc) {
        for (int slot = 0; slot < npc.bag().getContainerSize(); slot++) {
            ItemStack s = npc.bag().getItem(slot);
            if (s.isEmpty() || !(s.getItem() instanceof BlockItem)) continue;
            String id = BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
            if (ScaffoldPolicy.validMaterial(id)) return id;
        }
        return "";
    }

    private static double offset(CompanionEntity npc, int x, int z) {
        double dx = npc.getX() - x - 0.5D, dz = npc.getZ() - z - 0.5D;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Request one pillar level, or move to a safe adjacent base first. */
    public static String ascend(ServerLevel level, ServerPlayer owner, CompanionEntity npc,
            CompoundTag task, long now) {
        if (!task.getBoolean("treeLocked")) return BLOCKED + "no locked tree";
        if (size(task) >= ScaffoldPolicy.MAX_HEIGHT)
            return BLOCKED + "maximum safe pillar height reached";
        if (material(npc).isBlank())
            return BLOCKED + "NPC needs a solid block (dirt/stone/cobble/planks/log) in bag for a temporary pillar";
        if (!task.contains("pillarX")) {
            BlockPos best = null;
            double nearest = Double.POSITIVE_INFINITY;
            int x = task.getInt("treeX"), z = task.getInt("treeZ");
            int baseY = task.getInt("treeBaseY");
            int[][] sides = {{1,0},{-1,0},{0,1},{0,-1}};
            for (int[] s : sides) for (int dy = -1; dy <= 1; dy++) {
                BlockPos pos = new BlockPos(x + s[0], baseY + dy, z + s[1]);
                if (!safeStanding(level, pos)) continue;
                double sq = npc.distanceToSqr(Vec3.atCenterOf(pos));
                if (sq < nearest) { best = pos; nearest = sq; }
            }
            if (best == null) return BLOCKED + "no safe pillar base next to trunk";
            task.putInt("pillarX", best.getX());
            task.putInt("pillarY", best.getY());
            task.putInt("pillarZ", best.getZ());
            task.putLong("pillarApproachAt", now);
        }
        int x = task.getInt("pillarX"), z = task.getInt("pillarZ");
        int feetY = task.getInt("pillarY") + size(task);
        BlockPos foot = new BlockPos(x, feetY, z);
        if (!level.isLoaded(foot) || !level.isLoaded(foot.above(2)))
            return BLOCKED + "pillar chunk not loaded";
        if (offset(npc, x, z) > 0.40D || !npc.onGround()
                || npc.blockPosition().getY() != feetY) {
            if (size(task) != 0) return BLOCKED + "NPC left the pillar; return safely before cleanup";
            if (now - task.getLong("pillarApproachAt") > GatherTaskPolicy.MAX_WAIT_TICKS)
                return BLOCKED + "cannot reach the pillar base";
            if (npc.getNavigation().isDone()) {
                npc.getNavigation().moveTo(x + 0.5D, feetY, z + 0.5D, 1.0D);
                // Vanilla navigation can finish slightly off-centre; adjust using
                // ordinary movement control instead of teleporting the NPC.
                if (npc.onGround() && offset(npc, x, z) < 1.0D)
                    npc.getMoveControl().setWantedPosition(x + 0.5D, feetY, z + 0.5D, 0.8D);
            }
            return WAIT;
        }
        boolean overheadClear = level.getBlockState(foot.above()).isAir()
                && level.getBlockState(foot.above(2)).isAir();
        if (!overheadClear && clearOverheadNaturalLeaves(level, owner, npc, task, foot))
            return WAIT;
        if (!ScaffoldPolicy.mayBuild(size(task), npc.onGround(), overheadClear))
            return BLOCKED + "not enough overhead room to jump safely";
        if (!level.getBlockState(foot).isAir() || !level.getBlockState(foot.below()).isSolidRender(level, foot.below()))
            return BLOCKED + "scaffold placement is obstructed or lacks support";
        npc.getNavigation().stop();
        task.putInt("jumpBaseY", feetY);
        task.putLong("jumpStarted", now);
        task.putBoolean("jumpPlaced", false);
        npc.getJumpControl().jump();
        return DONE; // Jump initiated; caller must enter BUILD state
    }

    private static boolean clearOverheadNaturalLeaves(ServerLevel level, ServerPlayer owner,
            CompanionEntity npc, CompoundTag task, BlockPos foot) {
        if (task.getInt("leavesCleared") >= TreeHarvestPolicy.MAX_LEAF_CLEAR) return false;
        for (int dy = 1; dy <= 2; dy++) {
            BlockPos leaf = foot.above(dy);
            if (level.getBlockState(leaf).isAir()) continue;
            BlockState state = level.getBlockState(leaf);
            if (!state.is(net.minecraft.world.level.block.Blocks.OAK_LEAVES)
                    || !state.hasProperty(net.minecraft.world.level.block.LeavesBlock.PERSISTENT)
                    || state.getValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT)
                    || Math.abs(leaf.getX() - task.getInt("treeX")) > 2
                    || Math.abs(leaf.getZ() - task.getInt("treeZ")) > 2
                    || npc.getEyePosition().distanceToSqr(Vec3.atCenterOf(leaf))
                         > InteractionReach.squared(owner.isCreative())) return false;
            if (!level.destroyBlock(leaf, true, npc)) return false;
            task.putInt("leavesCleared", task.getInt("leavesCleared") + 1);
            return true;
        }
        return false;
    }

    private static boolean safeStanding(ServerLevel level, BlockPos pos) {
        return level.isLoaded(pos) && level.isLoaded(pos.below())
                && level.isLoaded(pos.above(2))
                && level.getBlockState(pos).isAir()
                && (level.getBlockState(pos.above()).isAir()
                   || (level.getBlockState(pos.above()).is(net.minecraft.world.level.block.Blocks.OAK_LEAVES)
                       && !level.getBlockState(pos.above()).getValue(
                              net.minecraft.world.level.block.LeavesBlock.PERSISTENT)))
                && (level.getBlockState(pos.above(2)).isAir()
                   || (level.getBlockState(pos.above(2)).is(net.minecraft.world.level.block.Blocks.OAK_LEAVES)
                       && !level.getBlockState(pos.above(2)).getValue(
                              net.minecraft.world.level.block.LeavesBlock.PERSISTENT)))
                && level.getBlockState(pos.below()).isSolidRender(level, pos.below());
    }

    /** Must run every server tick while jumping to catch the jump apex. */
    public static String tickJump(ServerLevel level, ServerPlayer owner, CompanionEntity npc,
            CompoundTag task, long now) {
        int x = task.getInt("pillarX"), z = task.getInt("pillarZ");
        int y = task.getInt("jumpBaseY");
        if (now - task.getLong("jumpStarted") > ScaffoldPolicy.JUMP_TIMEOUT_TICKS)
            return BLOCKED + "pillar jump timed out; scaffold remains recorded";
        if (task.getBoolean("jumpPlaced")) {
            if (npc.onGround() && npc.blockPosition().getY() == y + 1
                    && offset(npc, x, z) <= 0.4D) return DONE;
            return WAIT;
        }
        if (offset(npc, x, z) > 0.4D)
            return BLOCKED + "NPC moved during pillar jump";
        if (!ScaffoldPolicy.jumpClearance(npc.getBoundingBox().minY, y, offset(npc, x, z)))
            return WAIT;
        BlockPos pos = new BlockPos(x, y, z);
        String blockId = material(npc);
        if (blockId.isBlank()) return BLOCKED + "scaffold material ran out";
        if (!level.isLoaded(pos) || !level.getBlockState(pos).isAir()
                || !level.getBlockState(pos.below()).isSolidRender(level, pos.below()))
            return BLOCKED + "pillar position changed while jumping";
        ItemStack source = ItemStack.EMPTY;
        for (int i = 0; i < npc.bag().getContainerSize(); i++) {
            ItemStack s = npc.bag().getItem(i);
            if (!s.isEmpty() && BuiltInRegistries.ITEM.getKey(s.getItem()).toString().equals(blockId)) {
                source = s; break;
            }
        }
        if (source.isEmpty() || !(source.getItem() instanceof BlockItem blockItem))
            return BLOCKED + "scaffold item missing";
        BlockState state = blockItem.getBlock().defaultBlockState();
        if (!state.canSurvive(level, pos) || !state.isSolidRender(level, pos)
                || !level.setBlock(pos, state, 3))
            return BLOCKED + "Minecraft rejected safe pillar block";
        source.shrink(1);
        CompoundTag record = new CompoundTag();
        record.putLong("pos", pos.asLong());
        record.putString("block", blockId);
        ListTag placed = task.getList(LIST, Tag.TAG_COMPOUND);
        placed.add(record);
        task.put(LIST, placed);
        task.putBoolean("jumpPlaced", true);
        return WAIT;
    }

    /** Break only task-owned placed blocks, top down. One-block controlled drops. */
    public static String dismantle(ServerLevel level, CompanionEntity npc, CompoundTag task) {
        ListTag placed = task.getList(LIST, Tag.TAG_COMPOUND);
        if (placed.isEmpty()) return DONE;
        CompoundTag top = placed.getCompound(placed.size()-1);
        BlockPos pos = BlockPos.of(top.getLong("pos"));
        String expected = top.getString("block");
        if (!level.isLoaded(pos) || !level.isLoaded(pos.below()))
            return BLOCKED + "scaffold chunk unloaded; no blocks were removed";
        if (!BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString().equals(expected))
            return BLOCKED + "scaffold was changed externally; do not remove another player's block";
        if (!npc.onGround()) return WAIT; // descent from the previous block is in progress
        if (!ScaffoldPolicy.safeDisassembly(pos.getY(), npc.blockPosition().getY(),
                offset(npc, pos.getX(), pos.getZ()), npc.onGround(), true,
                level.getBlockState(pos.below()).isSolidRender(level, pos.below())))
            return BLOCKED + "NPC is not safely standing on its top scaffold block";
        npc.getNavigation().stop();
        // Air replaces only a recorded task-owned block. Return material to NPC inventory.
        if (!level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3))
            return BLOCKED + "could not dismantle scaffold block";
        net.minecraft.world.item.Item item = BuiltInRegistries.ITEM.get(
                new net.minecraft.resources.ResourceLocation(expected));
        ItemStack leftover = npc.bag().addItem(new ItemStack(item, 1));
        if (!leftover.isEmpty()) Block.popResource(level, pos, leftover);
        placed.remove(placed.size()-1);
        task.put(LIST, placed);
        return placed.isEmpty() ? DONE : WAIT;
    }
}
