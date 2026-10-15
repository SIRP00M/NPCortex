package io.github.sirp00m.npcortex;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;

/**
 * R0 coordination boundary between Vanilla ServerPlayer hooks and NPCortex.
 * Preserves the proven P0 -> P1-A -> P1-C END-tick order, with one owner per
 * phase. Does not register the synthetic player in PlayerList, emulate a real
 * connection, run ServerPlayer.doTick(), or create a second physics loop.
 */
final class C1VanillaLifecycleCoordinator {
    private C1VanillaLifecycleCoordinator() {}

    static boolean mayRunWorldTick(C1PlayerAvatar avatar) {
        MinecraftServer server = avatar.getServer();
        if (server == null || avatar.isRemoved()) return false;
        boolean registered = C1P0Runtime.liveAvatar(avatar.getUUID()) == avatar;
        // The P1-C persisted lifecycle state remains authoritative; TRANSITION
        // is deliberately fail-closed until manual reconciliation.
        boolean alive = registered && C1P1CLifecycleRuntime.mayRestore(server, avatar.getUUID());
        return C1VanillaLifecyclePolicy.mayRunWorldTick(registered, avatar.isRemoved(), alive);
    }

    static void afterWorldTick(C1PlayerAvatar avatar) {
        // This callback runs only after Vanilla ServerPlayer.tick() succeeded.
        // P1-A retains exclusive ownership of actual Player.travel/jump.
        // The P1-A motion runtime already guards the 4-tick support probe,
        // live identity and chunk availability. Do not duplicate SavedData
        // lookups or execute another motion loop here.
        if (!avatar.isRemoved()) C1P1MotionRuntime.onVanillaWorldTick(avatar);
    }

    static void onHealthChanged(C1PlayerAvatar avatar) {
        C1P1CLifecycleRuntime.onHealthChanged(avatar);
    }

    static void onVanillaKnockback(C1PlayerAvatar avatar, Vec3 previousVelocity) {
        C1P1MotionRuntime.onVanillaKnockback(avatar, previousVelocity);
    }

    static void onFatalDamage(C1PlayerAvatar avatar, DamageSource source) {
        C1P1CLifecycleRuntime.onFatalDamage(avatar, source);
    }

    static void beforeSpawn(MinecraftServer server, C1PlayerAvatar avatar) {
        // Same pre-tracking order as P1-B/P1-C: restore equipment and health
        // before ServerLevel.addNewPlayer can send the spawn packet.
        C1P1InventoryRuntime.restore(server, avatar);
        C1P1CLifecycleRuntime.onAvatarConstructed(server, avatar);
    }

    static void afterSpawn(C1PlayerAvatar avatar) {
        C1P1MotionRuntime.afterSpawn(avatar);
    }

    static void beforeDetach(MinecraftServer server, C1PlayerAvatar avatar) {
        C1P1MotionRuntime.beforeDespawn(server, avatar);
    }

    static void onServerTick(TickEvent.ServerTickEvent event) {
        // Registration order is intentionally identical to the P1-C baseline.
        C1P0Runtime.onServerTick(event);
        C1P1MotionRuntime.onTick(event);
        C1P1CLifecycleRuntime.onTick(event);
    }

    static void onServerStopping(ServerStoppingEvent event) {
        // Persist motion before clearing live identity; record interrupted
        // deaths as TRANSITION without auto-recreating an ambiguous player.
        C1P0Runtime.onServerStopping(event);
        C1P1CLifecycleRuntime.onServerStopping(event);
    }
}
