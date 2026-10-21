package io.github.sirp00m.npcortex;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/** Server-thread-only player-volume environmental-risk overlay.
 * The actual physical clearance remains Vanilla level.noCollision(npc, AABB).
 * Optional datapack block tags let packs classify modded risks without a block-ID
 * whitelist for passability. Unknown modded effects cannot be inferred automatically.
 */
final class C2BlockTraversalWorld {
    private C2BlockTraversalWorld() { }
    private static final TagKey<Block> FORBIDDEN = TagKey.create(Registries.BLOCK,
            new ResourceLocation("npcortex","traversal_forbidden"));
    private static final TagKey<Block> COSTLY = TagKey.create(Registries.BLOCK,
            new ResourceLocation("npcortex","traversal_costly"));

    /** False/unknown stops a candidate. Never accesses unloaded chunk contents. */
    static C2TraversalCost.Assessment assess(ServerLevel level, AABB volume) {
        if (volume == null || !Double.isFinite(volume.minX) || !Double.isFinite(volume.maxX)
                || !Double.isFinite(volume.minY) || !Double.isFinite(volume.maxY)
                || !Double.isFinite(volume.minZ) || !Double.isFinite(volume.maxZ))
            return C2TraversalCost.Assessment.blocked();
        int minX = net.minecraft.util.Mth.floor(volume.minX);
        int maxX = net.minecraft.util.Mth.floor(volume.maxX - 1e-7);
        int minY = net.minecraft.util.Mth.floor(volume.minY);
        int maxY = net.minecraft.util.Mth.floor(volume.maxY - 1e-7);
        int minZ = net.minecraft.util.Mth.floor(volume.minZ);
        int maxZ = net.minecraft.util.Mth.floor(volume.maxZ - 1e-7);
        if (maxX < minX || maxY < minY || maxZ < minZ
                || maxX-minX > 16 || maxY-minY > 8 || maxZ-minZ > 16
                || minY < level.getMinBuildHeight() || maxY >= level.getMaxBuildHeight())
            return C2TraversalCost.Assessment.blocked();
        double penalty=0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x=minX;x<=maxX;x++) for (int z=minZ;z<=maxZ;z++) {
            pos.set(x,minY,z);
            if (!level.hasChunkAt(pos) || !level.getWorldBorder().isWithinBounds(pos))
                return C2TraversalCost.Assessment.blocked();
            for (int y=minY;y<=maxY;y++) {
                pos.set(x,y,z);
                BlockState state=level.getBlockState(pos);
                if (!state.getFluidState().isEmpty() || state.is(FORBIDDEN)
                        || state.is(Blocks.CACTUS) || state.is(Blocks.MAGMA_BLOCK)
                        || state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE)
                        || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)
                        || state.is(Blocks.POWDER_SNOW) || state.is(Blocks.WITHER_ROSE))
                    return C2TraversalCost.Assessment.blocked();
                if (state.is(Blocks.SWEET_BERRY_BUSH))
                    penalty=Math.max(penalty,C2TraversalCost.DAMAGING_PENALTY);
                else if (state.is(COSTLY) || state.is(Blocks.COBWEB)
                        || state.is(Blocks.SOUL_SAND) || state.is(Blocks.HONEY_BLOCK))
                    penalty=Math.max(penalty,C2TraversalCost.SLOWING_PENALTY);
            }
        }
        return new C2TraversalCost.Assessment(true,penalty);
    }
}
