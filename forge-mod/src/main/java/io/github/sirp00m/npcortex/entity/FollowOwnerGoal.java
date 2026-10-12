package io.github.sirp00m.npcortex.entity;

import java.util.EnumSet;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;

/** Basic pathfinding; does not teleport or execute any LLM-generated actions. */
final class FollowOwnerGoal extends Goal {
    private final CompanionEntity npc;
    private final PathNavigation navigation;
    private ServerPlayer owner;
    private int repathTicks;

    FollowOwnerGoal(CompanionEntity npc) {
        this.npc = npc;
        this.navigation = npc.getNavigation();
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!npc.isFollowing() || npc.isRetreating()
                || !(npc.level() instanceof ServerLevel world)) return false;
        UUID id = npc.getOwnerUUID();
        if (id == null) return false;
        owner = world.getServer().getPlayerList().getPlayer(id);
        return owner != null && owner.level() == npc.level()
                && !owner.isSpectator() && npc.distanceToSqr(owner) > 9.0D
                && npc.distanceToSqr(owner) < 32.0D * 32.0D;
    }

    @Override
    public boolean canContinueToUse() {
        return npc.isFollowing() && !npc.isRetreating() && owner != null && owner.isAlive()
                && owner.level() == npc.level() && npc.distanceToSqr(owner) > 4.0D;
    }

    @Override
    public void start() { repathTicks = 0; }

    @Override
    public void stop() {
        owner = null;
        navigation.stop();
    }

    @Override
    public void tick() {
        if (owner == null) return;
        npc.getLookControl().setLookAt(owner, 10.0F, npc.getMaxHeadXRot());
        if (--repathTicks <= 0) {
            repathTicks = 10;
            navigation.moveTo(owner, 1.1D);
        }
    }
}
