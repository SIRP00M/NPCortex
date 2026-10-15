package io.github.sirp00m.npcortex;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * C1 P0/P1: a real ServerPlayer subclass with a passive synthetic listener.
 * This is NOT a logged-in player or a full client/connection contract.
 * Never call vanilla ServerPlayer.tick/doTick until verified and implemented.
 * P1-A injects bounded Vanilla Player.travel/jumpFromGround from an external
 * server-only motion controller, not from the PlayerList login pipeline.
 */
final class C1PlayerAvatar extends ServerPlayer {
    C1PlayerAvatar(MinecraftServer server, ServerLevel level, GameProfile profile) {
        super(server, level, profile);
        this.connection = new C1PassivePacketListener(server, this);
        setNoGravity(false); // P1-A only advances physics while a bounded intent is active.
        setInvulnerable(true);
    }

    @Override
    public boolean allowsListing() {
        // Keep PlayerInfo for skin/model lookup without occupying the vanilla tab list.
        return false;
    }

    @Override
    public void tick() {
        // Synthetic player: passive connection cannot service client ticks.
        // Controlled motion is driven separately by C1P1MotionRuntime.
    }

    @Override
    public void doTick() {
        // Avoid network-dependent PlayerList lifecycle in P1-A.
    }
}
