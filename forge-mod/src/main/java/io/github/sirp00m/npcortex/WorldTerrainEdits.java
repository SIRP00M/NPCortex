package io.github.sirp00m.npcortex;

import io.github.sirp00m.npcortex.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Strictly limited, reversible excavation of dirt obstructing a selected tree log.
 *  Never lets the model pick a position or change arbitrary blocks.
 */
public final class WorldTerrainEdits {
    private static final String KEY = "npcortexRestorableSoil";
    private WorldTerrainEdits() {}

    public static int size(CompoundTag task) { return task.getList(KEY, Tag.TAG_COMPOUND).size(); }

    public static BlockPos eligible(ServerPlayer owner, CompanionEntity npc, BlockPos log,
            CompoundTag task) {
        if (owner == null || npc == null || log == null || task == null
                || !BrainSkills.authorized(owner, npc) || !owner.serverLevel().isLoaded(log)
                || size(task) >= WholeTreePolicy.MAX_EXTRA_SOFT_EDITS) return null;
        BlockHitResult hit = owner.serverLevel().clip(new ClipContext(npc.getEyePosition(),
                Vec3.atCenterOf(log), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, npc));
        if (hit.getType() != HitResult.Type.BLOCK || hit.getBlockPos().equals(log)) return null;
        BlockPos soil = hit.getBlockPos();
        if (!owner.serverLevel().isLoaded(soil) || owner.serverLevel().getBlockEntity(soil) != null
                || !owner.serverLevel().getFluidState(soil).isEmpty()
                || npc.getEyePosition().distanceToSqr(Vec3.atCenterOf(soil))
                    > InteractionReach.squared(owner.isCreative())
                || Math.abs(soil.getX()-log.getX()) > 3 || Math.abs(soil.getZ()-log.getZ()) > 3
                || Math.abs(soil.getY()-log.getY()) > 3
                || soil.equals(npc.blockPosition().below())
                || soil.equals(owner.blockPosition().below())) return null;
        var state = owner.serverLevel().getBlockState(soil);
        if (!state.is(Blocks.DIRT) && !state.is(Blocks.GRASS_BLOCK)) return null;
        for (int i = 0; i < size(task); i++)
            if (task.getList(KEY, Tag.TAG_COMPOUND).getCompound(i).getLong("p") == soil.asLong())
                return null;
        return soil.immutable();
    }

    public static String clear(ServerPlayer owner, CompanionEntity npc, BlockPos log,
            CompoundTag task) {
        BlockPos soil = eligible(owner, npc, log, task);
        if (soil == null) return "[Action failed: no safely restorable dirt obstruction in reach.]";
        ServerLevel level = owner.serverLevel();
        boolean grass = level.getBlockState(soil).is(Blocks.GRASS_BLOCK);
        // No drops: the original block is recorded for restoration, not looted.
        if (!level.destroyBlock(soil, false, npc))
            return "[Action failed: protected dirt obstruction could not be opened.]";
        CompoundTag edit = new CompoundTag();
        edit.putLong("p", soil.asLong());
        edit.putString("id", grass ? "minecraft:grass_block" : "minecraft:dirt");
        ListTag edits = task.getList(KEY, Tag.TAG_COMPOUND);
        edits.add(edit);
        task.put(KEY, edits);
        return "[Action confirmed: opened one recorded temporary dirt obstruction; restoration required.]";
    }

    /** Restore only recorded holes if safe and unchanged; never overwrite a player edit. */
    public static String restore(ServerLevel level, CompanionEntity npc, ServerPlayer owner,
            CompoundTag task) {
        if (!BrainSkills.authorized(owner, npc)) return "[Action blocked: cleanup authority lost.]";
        ListTag edits = task.getList(KEY, Tag.TAG_COMPOUND);
        for (int i = edits.size()-1; i >= 0; i--) {
            CompoundTag edit = edits.getCompound(i);
            BlockPos p = BlockPos.of(edit.getLong("p"));
            if (!level.isLoaded(p) || !level.getBlockState(p).isAir()
                    || npc.getBoundingBox().intersects(new AABB(p))
                    || owner.getBoundingBox().intersects(new AABB(p))
                    || !level.getEntities((net.minecraft.world.entity.Entity)null, new AABB(p),
                        e -> e.isAlive()).isEmpty())
                return "[Action blocked: soil restoration unsafe or block changed; manual recovery required.]";
            var state = "minecraft:grass_block".equals(edit.getString("id"))
                    ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.DIRT.defaultBlockState();
            if (!level.setBlock(p, state, 3))
                return "[Action failed: Minecraft refused recorded soil restoration.]";
            edits.remove(i);
            task.put(KEY, edits);
        }
        return "[Action confirmed: restored all recorded terrain edits.]";
    }
}
