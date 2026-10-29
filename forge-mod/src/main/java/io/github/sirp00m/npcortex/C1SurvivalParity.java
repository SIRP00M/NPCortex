package io.github.sirp00m.npcortex;

import net.minecraftforge.event.entity.player.EntityItemPickupEvent;

/**
 * Parity P1 switches. The avatar now runs the vanilla connection-phase tick (ServerPlayer.doTick -> Player.tick -> LivingEntity.tick:
 * fire, lava, drowning, effects, hunger, regeneration, item use, cooldowns) with Player.travel suppressed inside that phase, so the
 * P1-A motion controller stays the single physics owner (see C1PlayerAvatar).
 */
final class C1SurvivalParity {
    /** Kill switch for the vanilla survival phase; tests flip it to compare against the pre-parity behaviour. */
    static volatile boolean survivalTick = true;
    /**
     * Vanilla players pick items up by walking over them (Player.aiStep). Owner decision D1 (2026-10-29): on by default; the claim
     * arbiter check in onItemPickup keeps it away from actors that own a native/PAP attempt. Switch off only to compare in tests.
     */
    static volatile boolean autoPickup = true;

    /**
     * X0: a spawned avatar owns a full vanilla player chunk ring, but ChunkMap only learns about a new section when something calls
     * ServerChunkCache.move(player) (vanilla: the movement-packet handler). We have no packet handler, so call it on a section change.
     */
    static volatile boolean chunkTracking = true;

    private C1SurvivalParity() {}

    /** One cheap section compare per tick per live avatar; move() runs only when the section changed. */
    /**
     * Runs from the server tick, NOT from the avatar's own tick: an avatar displaced outside its ring (teleport, knockback, rebuild) is no
     * longer entity-ticked, so a tick()-driven notifier could never bring the ring back to it (found by the T19 GameTest).
     */
    static void onServerTick(net.minecraftforge.event.TickEvent.ServerTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END || !chunkTracking) return;
        for (C1PlayerAvatar av : C1P0Runtime.liveAvatars()) {
            if (av.isRemoved()) continue;
            if (!net.minecraft.core.SectionPos.of(av).equals(av.getLastSectionPos())) av.serverLevel().getChunkSource().move(av);
        }
    }

    /**
     * D1: vanilla touch pickup (delay, owner field, inventory capacity, Forge events are all vanilla's) except while a native/PAP attempt
     * owns the actor: an unclaimed pickup there would eat the drop its verifier is about to count. The explicit ledgered pickup passes.
     */
    static void onItemPickup(EntityItemPickupEvent event) {
        if (!(event.getEntity() instanceof C1PlayerAvatar av) || NativeEffectRuntime.pickupInProgress()) return;
        if (!autoPickup || claimed(av)) event.setCanceled(true);
    }

    /** Fail closed: an avatar without identity or server is treated as claimed. */
    static boolean claimed(C1PlayerAvatar av) {
        net.minecraft.server.MinecraftServer server = av.getServer();
        if (server == null) return true;
        for (C1IdentityData.Entry e : C1IdentityData.get(server).all())
            if (e.profileId().equals(av.getUUID())) return NativeEffectRuntime.pending(server, e.actorId());
        return true;
    }
}
