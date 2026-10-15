package io.github.sirp00m.npcortex;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.ForgeHooks;
import org.slf4j.Logger;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import java.util.Map;
import java.util.HashMap;
import java.util.Iterator;
import java.util.UUID;

/**
 * P1-C SELECTIVE-VANILLA feasibility: real player hurt/health and Forge death
 * cancellation, but controlled teardown/recreation because ServerPlayer.die,
 * PlayerList.respawn and changeDimension require genuine player connections.
 * All mutations are operator/server-thread only. No automatic portal AI.
 */
final class C1P1CLifecycleRuntime {
    private static final Logger LOG = LogUtils.getLogger();
    private record PendingDeath(C1PlayerAvatar avatar, boolean keepInventory) {}
    private static final Map<UUID, PendingDeath> PENDING = new HashMap<>();
    private record Presentation(C1PlayerAvatar avatar, long deadlineTick) {}
    // Volatile-only rendering state: DEAD/Inventory are already persisted before this begins.
    private static final Map<UUID, Presentation> PRESENTING = new HashMap<>();

    static boolean isPresenting(UUID profile) { return PRESENTING.containsKey(profile); }
    static int presentationTicksLeft(MinecraftServer server, UUID profile) {
        Presentation p = PRESENTING.get(profile);
        return p == null ? 0 : (int) Math.max(0L, p.deadlineTick() - server.getTickCount());
    }

    private C1P1CLifecycleRuntime() {}

    static boolean mayRestore(MinecraftServer server, java.util.UUID profile) {
        C1LifecycleData data = C1LifecycleData.get(server);
        return !data.blocked(profile) && data.state(profile).phase().canRestoreAutomatically();
    }

    static void onAvatarConstructed(MinecraftServer server, C1PlayerAvatar avatar) {
        C1LifecycleData data = C1LifecycleData.get(server);
        if (!mayRestore(server, avatar.getUUID())) throw new IllegalStateException("lifecycle restore is not ALIVE");
        float saved = data.state(avatar.getUUID()).health();
        avatar.setHealth(Math.min(avatar.getMaxHealth(), Math.max(1.0f, saved)));
    }

    static void onHealthChanged(C1PlayerAvatar avatar) {
        MinecraftServer server = avatar.getServer();
        if (server == null || avatar.isRemoved() || C1P0Runtime.liveAvatar(avatar.getUUID()) != avatar) return;
        C1LifecycleData data = C1LifecycleData.get(server);
        if (data.state(avatar.getUUID()).phase() == C1LifecyclePhase.ALIVE)
            data.update(avatar.getUUID(), C1LifecyclePhase.ALIVE, Math.max(0, avatar.getHealth()));
    }

    static void onFatalDamage(C1PlayerAvatar avatar, DamageSource source) {
        MinecraftServer server = avatar.getServer();
        if (server == null || avatar.isRemoved() || C1P0Runtime.liveAvatar(avatar.getUUID()) != avatar) return;
        C1LifecycleData states = C1LifecycleData.get(server);
        if (states.state(avatar.getUUID()).phase() != C1LifecyclePhase.ALIVE) return;
        // Vanilla ServerPlayer.die would operate on PlayerList statistics, client
        // death screen and real Network Connection. Only the Forge cancellable
        // living-death hook is dispatched deliberately in this selective path.
        if (ForgeHooks.onLivingDeath(avatar, source)) {
            avatar.setHealth(1.0F);
            onHealthChanged(avatar);
            LOG.info("NPCortex C1-P1-C DEATH_CANCELLED npc={}", avatar.getUUID());
            return;
        }
        boolean keep = avatar.serverLevel().getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY);
        C1InventoryData inventory = C1InventoryData.get(server);
        if (inventory.blockage(avatar.getUUID()) != null) {
            LOG.error("NPCortex C1-P1-C death inventory corrupt; will fail closed at next server tick npc={}", avatar.getUUID());
        }
        // Defer world mutation/removal until server tick END, after the native
        // ServerPlayer.hurt call stack has unwound. Before that, TRANSITION is
        // persisted as a fail-closed crash marker: never respawn with ambiguous items.
        states.update(avatar.getUUID(), C1LifecyclePhase.TRANSITION, 0);
        C1P0Runtime.persistPosition(server, avatar);
        C1P1MotionRuntime.beforeDespawn(server, avatar);
        PENDING.put(avatar.getUUID(), new PendingDeath(avatar, keep));
        LOG.info("NPCortex C1-P1-C DEATH_QUEUED npc={} keepInventory={}", avatar.getUUID(), keep);
    }

    static void onTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        // Presentation completes independently of whether any new deaths are queued.
        var showing = PRESENTING.entrySet().iterator();
        while (showing.hasNext()) {
            var item = showing.next();
            Presentation p = item.getValue();
            boolean sameAvatar = C1P0Runtime.liveAvatar(item.getKey()) == p.avatar();
            boolean removed = p.avatar().isRemoved();
            long nowTick = server.getTickCount();
            if (C1DeathPresentationPolicy.shouldDetach(nowTick, p.deadlineTick(), sameAvatar, removed)) {
                // R1-A: a single client-visible mob-like poof on NORMAL end of the
                // Vanilla player fallover. Never emit for a stale/removed avatar or
                // an interrupted/inconsistent lifecycle transition. The existing
                // presentation-map removal prevents repeat bursts on future ticks.
                boolean deadPersisted = C1LifecycleData.get(server).state(item.getKey()).phase()
                        == C1LifecyclePhase.DEAD;
                if (C1DeathPresentationPolicy.shouldEmitVanishPoof(
                        nowTick, p.deadlineTick(), sameAvatar, removed, deadPersisted)) {
                    emitVanishPoof(p.avatar());
                    LOG.info("NPCortex C1-R1-A DEATH_DESPAWN_POOF npc={} count={}",
                            item.getKey(), C1DeathPresentationPolicy.VANISH_POOF_COUNT
                                    + C1DeathPresentationPolicy.VANISH_CLOUD_COUNT);
                }
                if (sameAvatar) C1P0Runtime.detachForLifecycle(server, item.getKey());
                LOG.info("NPCortex C1-R1 DEATH_PRESENTATION_END npc={}", item.getKey());
                showing.remove();
            }
        }
        if (PENDING.isEmpty()) return;
        var iterator = PENDING.entrySet().iterator();
        while (iterator.hasNext()) {
            var next = iterator.next();
            C1PlayerAvatar avatar = next.getValue().avatar();
            if (C1P0Runtime.liveAvatar(avatar.getUUID()) != avatar || avatar.isRemoved()) {
                iterator.remove(); continue;
            }
            try {
                C1InventoryData inventory = C1InventoryData.get(server);
                if (inventory.blockage(avatar.getUUID()) != null) throw new IllegalStateException("corrupt inventory");
                if (!next.getValue().keepInventory()) avatar.getInventory().dropAll();
                inventory.snapshotForLifecycle(avatar.getUUID(), avatar.getInventory());
                C1LifecycleData.get(server).update(avatar.getUUID(), C1LifecyclePhase.DEAD, 0);
                // Do not discard immediately: let RemotePlayer's Vanilla death
                // renderer animate the health=0 state before tracking removal.
                avatar.setHealth(0.0f);
                PRESENTING.put(avatar.getUUID(), new Presentation(avatar,
                        server.getTickCount() + C1DeathPresentationPolicy.PRESENTATION_TICKS));
                C1P0Runtime.syncDeathPresentation(avatar);
                LOG.info("NPCortex C1-R1 DEATH_PRESENTATION_BEGIN npc={} ticks={} keepInventory={}",
                        avatar.getUUID(), C1DeathPresentationPolicy.PRESENTATION_TICKS,
                        next.getValue().keepInventory());
                LOG.info("NPCortex C1-P1-C DEAD npc={} keepInventory={}", avatar.getUUID(), next.getValue().keepInventory());
            } catch (RuntimeException ex) {
                // Retain TRANSITION and reject automatic restoration/respawn.
                LOG.error("NPCortex C1-P1-C DEATH_INDETERMINATE npc={} manual audit required", avatar.getUUID(), ex);
            } finally {
                // On any failure, retain TRANSITION and remove the avatar now;
                // never display a potentially inconsistent inventory/death.
                if (!PRESENTING.containsKey(avatar.getUUID())) {
                    C1P0Runtime.detachForLifecycle(server, avatar.getUUID());
                }
                iterator.remove();
            }
        }
    }

    /** Vanilla particles are dimension-scoped and distributed by ServerLevel only
     * to nearby viewers; particles are visual only and never affect death state.
     */
    private static void emitVanishPoof(C1PlayerAvatar avatar) {
        ServerLevel level = avatar.serverLevel();
        double x = avatar.getX();
        double y = avatar.getY() + avatar.getBbHeight() * 0.5;
        double z = avatar.getZ();
        double horizontal = avatar.getBbWidth() * 0.42;
        double vertical = avatar.getBbHeight() * 0.38;
        level.sendParticles(ParticleTypes.POOF, x, y, z,
                C1DeathPresentationPolicy.VANISH_POOF_COUNT,
                horizontal, vertical, horizontal, 0.045);
        level.sendParticles(ParticleTypes.CLOUD, x, y, z,
                C1DeathPresentationPolicy.VANISH_CLOUD_COUNT,
                horizontal * 0.7, vertical * 0.7, horizontal * 0.7, 0.025);
    }

    static void onServerStopping(ServerStoppingEvent event) {
        if (!PENDING.isEmpty()) {
            LOG.warn("NPCortex C1-P1-C server stopping with {} pending deaths; persisted TRANSITION blocks unsafe restore", PENDING.size());
            PENDING.clear();
        }
        // Presentation is ephemeral. The DEAD state and items have already been
        // committed in SavedData in memory; server shutdown saves them normally.
        PRESENTING.clear();
    }

    static String status(MinecraftServer server, String name) {
        C1IdentityData.Entry entry = C1IdentityData.get(server).find(name);
        if (entry == null) return "Unknown NPC: " + name;
        C1LifecycleData data = C1LifecycleData.get(server);
        C1PlayerAvatar live = C1P0Runtime.activeAvatar(server, name);
        C1LifecycleData.State state = data.state(entry.profileId());
        return name + " phase=" + state.phase() + " health=" + (live == null ? state.health() : live.getHealth())
                + " revision=" + state.revision() + " live=" + (live != null)
                + " deathPresentationTicks=" + presentationTicksLeft(server, entry.profileId())
                + " keepInventory=" + (live == null ? "(no live actor)" :
                    live.serverLevel().getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY))
                + " blocked=" + data.blocked(entry.profileId())
                + (live == null ? "" : " gameMode=" + live.gameMode.getGameModeForPlayer()
                    + " abilityInvulnerable=" + live.getAbilities().invulnerable
                    + " flying=" + live.getAbilities().flying
                    + " noGravity=" + live.isNoGravity());
    }

    static String damage(MinecraftServer server, String name, float amount) {
        if (!Float.isFinite(amount) || amount <= 0 || amount > 1000) return "Damage must be (0,1000]";
        C1PlayerAvatar npc = C1P0Runtime.activeAvatar(server, name);
        if (npc == null || !mayRestore(server, npc.getUUID())) return "NPC not ALIVE: " + name;
        float before = npc.getHealth();
        boolean accepted = npc.hurt(npc.damageSources().generic(), amount);
        onHealthChanged(npc);
        return name + " damageAccepted=" + accepted + " before=" + before
                + " lifecycle=" + C1LifecycleData.get(server).state(npc.getUUID()).phase()
                + " after=" + (npc.isRemoved() ? "DEAD/removed" : npc.getHealth());
    }

    static String kill(MinecraftServer server, String name) {
        C1PlayerAvatar npc = C1P0Runtime.activeAvatar(server, name);
        if (npc == null || !mayRestore(server, npc.getUUID())) return "NPC not ALIVE: " + name;
        // Bypass hurt cooldown for a deterministic fatal-damage fixture.
        npc.setHealth(0.0f);
        npc.die(npc.damageSources().genericKill());
        return "FATAL_DAMAGE_REQUESTED " + name + "; inspect lifecycle next server tick";
    }

    static String respawn(MinecraftServer server, String name) {
        C1IdentityData.Entry entry = C1IdentityData.get(server).find(name);
        if (entry == null || !entry.active()) return "No saved active NPC: " + name;
        C1LifecycleData states = C1LifecycleData.get(server);
        if (states.blocked(entry.profileId()) || !states.state(entry.profileId()).phase().canRespawn())
            return "Respawn requires DEAD (TRANSITION/corrupt requires audit)";
        if (isPresenting(entry.profileId())) return "WAIT death animation before respawn ("
                + presentationTicksLeft(server, entry.profileId()) + " ticks)";
        if (C1P0Runtime.liveAvatar(entry.profileId()) != null) return "BLOCKED duplicate live avatar";
        ResourceLocation id = ResourceLocation.tryParse(entry.dimension());
        if (id == null) return "Invalid dimension";
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
        if (level == null || !level.hasChunkAt(BlockPos.containing(entry.x(), entry.y(), entry.z())))
            return "BLOCKED respawn destination unloaded; no forced chunk loading";
        // The feasibility fixture respawns at last saved location; bed anchors,
        // world spawn fallbacks, XP and player clone events are not yet proven.
        states.update(entry.profileId(), C1LifecyclePhase.ALIVE, 20);
        try {
            C1P0Runtime.spawnExisting(server, entry);
            C1PlayerAvatar npc = C1P0Runtime.liveAvatar(entry.profileId());
            if (npc == null) throw new IllegalStateException("no live avatar after respawn");
            states.update(entry.profileId(), C1LifecyclePhase.ALIVE, npc.getHealth());
            LOG.info("NPCortex C1-P1-C RESPAWN npc={} dim={}", entry.profileId(), entry.dimension());
            return "RESPAWNED " + name + " profile=" + entry.profileId();
        } catch (RuntimeException failure) {
            states.update(entry.profileId(), C1LifecyclePhase.DEAD, 0);
            LOG.error("NPCortex C1-P1-C RESPAWN_FAILED npc={}", entry.profileId(), failure);
            return "RESPAWN_FAILED " + failure.getClass().getSimpleName() + ": " + failure.getMessage();
        }
    }

    /** Controlled dimension proof: an operator MUST already stand in the destination dimension.
     * This avoids portal search, new chunk loading and unbounded teleport computation. */
    static String dimension(MinecraftServer server, ServerPlayer operator, String name, String targetName) {
        C1IdentityData identities = C1IdentityData.get(server);
        C1IdentityData.Entry entry = identities.find(name);
        if (entry == null || !entry.active()) return "Unknown or inactive NPC: " + name;
        C1PlayerAvatar old = C1P0Runtime.activeAvatar(server, name);
        if (old == null || !mayRestore(server, entry.profileId())) return "NPC must be ALIVE and live";
        ResourceLocation id = ResourceLocation.tryParse(targetName);
        if (id == null) return "Invalid dimension id";
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, id);
        ServerLevel target = server.getLevel(key);
        if (target == null) return "Dimension not loaded: " + targetName;
        if (old.serverLevel() == target) return "Already in dimension " + targetName;
        if (operator.serverLevel() != target) return "Operator must stand in target dimension for safe arrival fixture";
        double x = operator.getX() + 1.5;
        double y = operator.getY();
        double z = operator.getZ() + 1.5;
        if (!target.hasChunkAt(BlockPos.containing(x, y, z))) return "Destination chunk not loaded";
        if (!target.getWorldBorder().isWithinBounds(BlockPos.containing(x, y, z))) return "Outside destination world border";
        var destinationBox = old.getBoundingBox().move(x - old.getX(), y - old.getY(), z - old.getZ());
        if (!target.noCollision(old, destinationBox)) return "Destination obstructed: no teleport";
        C1LifecycleData states = C1LifecycleData.get(server);
        if (C1InventoryData.get(server).blockage(entry.profileId()) != null) return "BLOCKED corrupt inventory";
        C1RotationState rotation = new C1RotationState(old.getYRot(), old.getXRot(), old.getYHeadRot(), old.yBodyRot);
        // Mark an interrupted transition as fail-closed on next server start.
        states.update(entry.profileId(), C1LifecyclePhase.TRANSITION, old.getHealth());
        C1InventoryData.get(server).snapshotForLifecycle(entry.profileId(), old.getInventory());
        C1P0Runtime.detachForLifecycle(server, entry.profileId());
        identities.relocate(entry.profileId(), key.location().toString(), x, y, z, rotation, true);
        states.update(entry.profileId(), C1LifecyclePhase.ALIVE, Math.max(1f, old.getHealth()));
        try {
            C1P0Runtime.spawnExisting(server, identities.find(name));
            LOG.info("NPCortex C1-P1-C DIMENSION_TRANSFER npc={} from={} to={}",
                    entry.profileId(), entry.dimension(), key.location());
            return "TRANSFERRED " + name + " to=" + key.location() + " profile=" + entry.profileId();
        } catch (RuntimeException failure) {
            // Do not force rollback spawns or overwrite persisted inventory; require manual audit
            // if a failure occurs between removing an old avatar and creating the new one.
            states.update(entry.profileId(), C1LifecyclePhase.TRANSITION, Math.max(0, old.getHealth()));
            LOG.error("NPCortex C1-P1-C TRANSFER_INDETERMINATE npc={}", entry.profileId(), failure);
            return "TRANSFER_INDETERMINATE (blocked until manual recovery): " + failure.getMessage();
        }
    }
}
