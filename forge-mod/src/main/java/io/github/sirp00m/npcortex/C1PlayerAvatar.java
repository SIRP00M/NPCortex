package io.github.sirp00m.npcortex;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.phys.Vec3;

/**
 * C1 P0/P1: a real ServerPlayer subclass with a passive synthetic listener.
 * This is NOT a logged-in player or a full client/connection contract.
 * P1-C Fix #1 enables vanilla ServerPlayer.tick() (world-side phase one),
 * which owns the spawn-invulnerability countdown and player-level updates.
 * Vanilla doTick() (connection-driven phase two) remains disabled: this avatar
 * does not have a real logged-in session. Motion keeps its single owner in
 * the bounded P1-A controller, using Player.travel/jumpFromGround.
 * R2 delegates real knockback calculation to LivingEntity and hands its
 * resulting impulse to that same travel owner without opening doTick().
 */
final class C1PlayerAvatar extends ServerPlayer {
    C1PlayerAvatar(MinecraftServer server, ServerLevel level, GameProfile profile) {
        super(server, level, profile);
        this.connection = new C1PassivePacketListener(server, this);
        setNoGravity(false); // P1-A applies travel only when a motion/settling session is active.
        // Damage enabled for P1-C; initial vanilla spawn protection now counts down
        // naturally in world-side ServerPlayer.tick() (NOT in passive doTick()).
        // Full ServerPlayer death/respawn remains unsafe
        // with our passive connection. NPCortex intercepts die() below.
        setInvulnerable(false);
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        boolean applied = super.hurt(source, amount);
        C1VanillaLifecycleCoordinator.onHealthChanged(this);
        return applied;
    }

    @Override
    public void knockback(double strength, double x, double z) {
        // Let LivingEntity/Forge choose the actual impulse, including resistance,
        // enchantments and attack-specific strength. Never invent an extra push.
        Vec3 before = getDeltaMovement();
        super.knockback(strength, x, z);
        C1VanillaLifecycleCoordinator.onVanillaKnockback(this, before);
    }

    @Override
    public void die(DamageSource source) {
        // Vanilla ServerPlayer.die expects a real PlayerList/Connection session.
        // Forge's cancellable LivingDeathEvent is fired by the controlled handler.
        C1VanillaLifecycleCoordinator.onFatalDamage(this, source);
    }

    @Override
    public boolean allowsListing() {
        // Keep PlayerInfo for skin/model lookup without occupying the vanilla tab list.
        return false;
    }

    @Override
    public void tick() {
        // Vanilla server WORLD phase: decrements ServerPlayer.spawnInvulnerableTime
        // and runs game-mode/container/advancement housekeeping. A passive packet
        // listener discards any packets addressed to this synthetic player.
        // Deliberately do not call Player.tick() or doTick(): the latter uses
        // connection-phase player simulation and would duplicate P1-A motion.
        if (!C1VanillaLifecycleCoordinator.mayRunWorldTick(this)) return;
        super.tick();
        C1VanillaLifecycleCoordinator.afterWorldTick(this);
    }

    @Override
    public void doTick() {
        // Deliberate separation: ServerPlayer.doTick() expects a real client
        // connection and performs Player.tick() in the network/movement phase.
        // This feasibility build retains P1-A as the only travel owner.
    }
}
