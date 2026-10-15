package io.github.sirp00m.npcortex;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Client-only two-phase witness. Registration/presence is checked on the render thread. */
@Mod.EventBusSubscriber(modid = NPCortex.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class C1P0ClientSync {
    private static final Logger LOG = LogUtils.getLogger();
    private static final int PROFILE_WAIT_TICKS = 100;
    private static final int ENTITY_WAIT_TICKS = 15;
    private static final Map<UUID, Pending> PENDING = new HashMap<>();
    // Retained after entity verification to authorize event-driven angle updates.
    // A new Prepare token or world change invalidates the previous session.
    private static final Map<UUID, RotationSession> ROTATION_SESSIONS = new HashMap<>();
    private static final Map<UUID, PendingDeath> DEATH_REPORTS = new HashMap<>();
    private static ClientLevel sessionLevel;

    private static final class RotationSession {
        final int entityId;
        final UUID token;
        int revision;
        RotationSession(int entityId, UUID token) {
            this.entityId = entityId;
            this.token = token;
        }
        boolean matches(int id, UUID value) { return entityId == id && token.equals(value); }
    }

    private static final class PendingDeath {
        final int entityId;
        final UUID token;
        int ticks;
        PendingDeath(int entityId, UUID token) {
            this.entityId = entityId;
            this.token = token;
        }
    }

    private static final class Pending {
        final UUID npc;
        final int entityId;
        final UUID token;
        int wait;
        boolean ready;
        boolean verifying;
        Pending(UUID npc, int entityId, UUID token) {
            this.npc = npc; this.entityId = entityId; this.token = token;
        }
        boolean matches(UUID uuid, int id, UUID nonce) {
            return npc.equals(uuid) && entityId == id && token.equals(nonce);
        }
    }

    private C1P0ClientSync() {}

    static void onPrepare(C1P0Network.Prepare packet) {
        // The first prepare can arrive before a local ClientLevel is installed.
        // Bind to the current world without dropping this newly received packet.
        Minecraft client = Minecraft.getInstance();
        if (client.level != sessionLevel) {
            PENDING.clear();
            ROTATION_SESSIONS.clear();
            DEATH_REPORTS.clear();
            sessionLevel = client.level;
        }
        Pending old = PENDING.get(packet.npcId());
        if (old != null && old.matches(packet.npcId(),packet.entityId(),packet.token())) return;
        PENDING.put(packet.npcId(), new Pending(packet.npcId(),packet.entityId(),packet.token()));
        ROTATION_SESSIONS.put(packet.npcId(), new RotationSession(packet.entityId(), packet.token()));
        DEATH_REPORTS.remove(packet.npcId());
        LOG.info("NPCortex C1-P0 CLIENT_PREPARE npc={} entityId={}", packet.npcId(), packet.entityId());
    }

    static void onVerify(C1P0Network.Verify packet) {
        Pending current = PENDING.get(packet.npcId());
        if (current == null || !current.matches(packet.npcId(),packet.entityId(),packet.token())) return;
        current.wait = 0;
        current.verifying = true;
        LOG.info("NPCortex C1-P0 CLIENT_VERIFY npc={} entityId={}", packet.npcId(), packet.entityId());
    }

    static void onRotationSnapshot(C1P0Network.RotationSnapshot packet) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.level != sessionLevel) return;
        RotationSession session = ROTATION_SESSIONS.get(packet.npcId());
        if (session == null || !session.matches(packet.entityId(), packet.token())
                || packet.revision() <= session.revision || packet.revision() <= 0
                || packet.rotation() == null || !packet.rotation().valid()) return;
        Entity entity = client.level.getEntity(packet.entityId());
        if (!(entity instanceof RemotePlayer player) || !entity.getUUID().equals(packet.npcId())) return;
        C1RotationState rotation = packet.rotation();
        player.setYRot(rotation.yaw());
        player.setXRot(rotation.pitch());
        player.setYHeadRot(rotation.headYaw());
        player.setYBodyRot(rotation.bodyYaw());
        // Reset the render interpolation endpoints on a one-off initial restore.
        player.yRotO = rotation.yaw();
        player.xRotO = rotation.pitch();
        player.yHeadRotO = rotation.headYaw();
        player.yBodyRotO = rotation.bodyYaw();
        session.revision = packet.revision();
        C1P0Network.rotationApplied(new C1P0Network.RotationApplied(
                packet.npcId(), packet.entityId(), packet.token(), packet.revision()));
        LOG.info("NPCortex C1-P1 CLIENT_ROTATION_APPLIED npc={} entityId={} revision={} yaw={} head={} body={}",
                packet.npcId(), packet.entityId(), packet.revision(),
                rotation.yaw(), rotation.headYaw(), rotation.bodyYaw());
    }

    static void onDeathPresentation(C1P0Network.DeathPresentation packet) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.level != sessionLevel) return;
        RotationSession session = ROTATION_SESSIONS.get(packet.npcId());
        if (session == null || !session.matches(packet.entityId(), packet.token())) return;
        Entity entity = client.level.getEntity(packet.entityId());
        if (!(entity instanceof RemotePlayer player) || !entity.getUUID().equals(packet.npcId())) return;
        // Vanilla PlayerRenderer uses LivingEntity's deathTime, driven by client
        // ticks when RemotePlayer health is zero. No custom renderer/pose.
        player.setHealth(0.0F);
        DEATH_REPORTS.put(packet.npcId(), new PendingDeath(packet.entityId(), packet.token()));
        LOG.info("NPCortex C1-R1 CLIENT_DEATH_STATE_ENTERED npc={} entityId={}",
                packet.npcId(), packet.entityId());
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft client = Minecraft.getInstance();
        // Re-login/world switch must not leak stale ACKs into a different session.
        if (client.level != sessionLevel) {
            // null -> a new level may be the continuation of login; keep its
            // initial profile prepare. Any old world is invalidated.
            if (sessionLevel != null) {
                PENDING.clear();
                ROTATION_SESSIONS.clear();
                DEATH_REPORTS.clear();
            }
            sessionLevel = client.level;
        }
        if (client.level == null || client.player == null || client.getConnection() == null) return;
        var deaths = DEATH_REPORTS.entrySet().iterator();
        while (deaths.hasNext()) {
            var item = deaths.next();
            PendingDeath death = item.getValue();
            Entity entity = client.level.getEntity(death.entityId);
            RotationSession session = ROTATION_SESSIONS.get(item.getKey());
            if (session == null || !session.matches(death.entityId, death.token)
                    || !(entity instanceof RemotePlayer player) || !entity.getUUID().equals(item.getKey())) {
                deaths.remove();
            } else if (++death.ticks >= 2 && player.isDeadOrDying() && player.deathTime > 0) {
                C1P0Network.deathReady(new C1P0Network.DeathReady(item.getKey(), death.entityId, death.token));
                LOG.info("NPCortex C1-R1 CLIENT_DEATH_ANIMATION_TICK npc={} entityId={}",
                        item.getKey(), death.entityId);
                deaths.remove();
            } else if (death.ticks >= 10) {
                LOG.warn("NPCortex C1-R1 CLIENT_DEATH_STATE_TIMEOUT npc={}", item.getKey());
                deaths.remove();
            }
        }
        var iterator = PENDING.entrySet().iterator();
        while (iterator.hasNext()) {
            Pending p = iterator.next().getValue();
            if (!p.ready) {
                if (client.getConnection().getPlayerInfo(p.npc) != null) {
                    C1P0Network.ready(new C1P0Network.ProfileReady(p.npc, p.entityId, p.token));
                    p.ready = true;
                    p.wait = 0;
                    LOG.info("NPCortex C1-P0 CLIENT_PROFILE_READY npc={}", p.npc);
                } else if (++p.wait >= PROFILE_WAIT_TICKS) {
                    LOG.warn("NPCortex C1-P0 CLIENT_PROFILE_TIMEOUT npc={}",p.npc);
                    iterator.remove();
                }
                continue;
            }
            if (!p.verifying) continue;
            Entity entity = client.level.getEntity(p.entityId);
            if (entity instanceof RemotePlayer && entity.getUUID().equals(p.npc)) {
                C1P0Network.observation(new C1P0Network.Observation(p.npc,p.entityId,p.token,true));
                LOG.info("NPCortex C1-P0 CLIENT_ENTITY_PRESENT npc={} id={}",p.npc,p.entityId);
                iterator.remove();
            } else if (++p.wait >= ENTITY_WAIT_TICKS) {
                C1P0Network.observation(new C1P0Network.Observation(p.npc,p.entityId,p.token,false));
                p.verifying = false;
                p.wait = 0;
                LOG.warn("NPCortex C1-P0 CLIENT_ENTITY_MISSING npc={} id={}",p.npc,p.entityId);
            }
        }
    }
}
