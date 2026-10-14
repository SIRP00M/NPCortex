package io.github.sirp00m.npcortex;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

/** Travel has no task-home radius. Actual world height, border and loaded chunks
 * remain authoritative; observing/planning never force-loads terrain. */
public final class TaskWorldBounds {
    private TaskWorldBounds() {}
    public static boolean allows(ServerPlayer owner, BlockPos p) {
        return p != null && !owner.serverLevel().isOutsideBuildHeight(p)
                && owner.serverLevel().isLoaded(p)
                && owner.serverLevel().getWorldBorder().isWithinBounds(p);
    }
}
