package io.github.sirp00m.npcortex.entity;

import java.util.EnumSet;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

/** Higher priority than FollowOwnerGoal; stops after bounded retreat ticks. */
final class RetreatFromThreatGoal extends Goal {
    private final CompanionEntity npc;
    private int repath;

    RetreatFromThreatGoal(CompanionEntity npc) {
        this.npc = npc;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override public boolean canUse() { return npc.isRetreating(); }
    @Override public boolean canContinueToUse() {
        Vec3 target = npc.retreatDestination();
        return target != null && npc.isRetreating()
                && npc.position().distanceToSqr(target) > 2.25D
                && !npc.getNavigation().isDone();
    }
    @Override public void start() {
        repath = 0;
        tick();
    }
    @Override public void tick() {
        Vec3 target = npc.retreatDestination();
        if (target == null) return;
        if (repath-- <= 0) {
            repath = 12;
            npc.getNavigation().moveTo(target.x, target.y, target.z, 1.2D);
        }
    }
    @Override public void stop() {
        // An explicit owner command may already have cancelled retreat and started
        // a new path. Do not accidentally stop that newer player-directed path.
        if (npc.isRetreating()) npc.clearRetreat();
    }
}
