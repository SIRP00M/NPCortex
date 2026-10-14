package io.github.sirp00m.npcortex;

import io.github.sirp00m.npcortex.entity.CompanionEntity;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;

/** Mark actual block-break ItemEntity spawns with their NPC and task provenance.
 * No radius-only claim on unrelated ground items, and no forced item teleportation.
 * Forge fires EntityJoinLevelEvent synchronously during vanilla destroyBlock drops.
 */
public final class CompanionMinedDrops {
    private static final String MINER = "NPCortexMinedBy";
    private static final String TASK = "NPCortexMinedTask";
    private static final ThreadLocal<BreakContext> BREAK = new ThreadLocal<>();
    private record BreakContext(ServerLevel level, UUID npc, UUID task, BlockPos pos) {}
    private CompanionMinedDrops() {}

    public static boolean destroy(ServerLevel level, BlockPos pos, CompanionEntity npc) {
        UUID task = AgenticTaskController.currentTaskId(npc);
        // Called from the Minecraft server thread; never retains a mining context.
        BreakContext previous = BREAK.get();
        BREAK.set(new BreakContext(level, npc.getUUID(), task, pos.immutable()));
        try {
            return level.destroyBlock(pos, true, npc);
        } finally {
            if (previous == null) BREAK.remove();
            else BREAK.set(previous);
        }
    }

    public static void onEntityJoin(EntityJoinLevelEvent event) {
        BreakContext context = BREAK.get();
        if (context == null || event.getLevel() != context.level
                || !(event.getEntity() instanceof ItemEntity item)) return;
        if (item.distanceToSqr(context.pos.getX() + 0.5D,
                context.pos.getY() + 0.5D, context.pos.getZ() + 0.5D) > 9.0D) return;
        CompoundTag data = item.getPersistentData();
        data.putUUID(MINER, context.npc);
        if (context.task != null) data.putUUID(TASK, context.task);
        data.putLong("NPCortexMinedAt", context.pos.asLong());
    }

    public static boolean ownedBy(CompanionEntity npc, ItemEntity item) {
        CompoundTag data = item.getPersistentData();
        return data.hasUUID(MINER) && npc.getUUID().equals(data.getUUID(MINER));
    }

    public static boolean fromTask(CompanionEntity npc, UUID taskId, ItemEntity item) {
        if (taskId == null || !ownedBy(npc, item)) return false;
        CompoundTag data = item.getPersistentData();
        return data.hasUUID(TASK) && taskId.equals(data.getUUID(TASK));
    }
}
