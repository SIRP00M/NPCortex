package io.github.sirp00m.npcortex;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * P1-A operator-controlled player physics experiment, NOT navigation or AI.
 * Exactly one bounded movement intent per avatar. Uses vanilla Player.travel
 * and Player.jumpFromGround, never moveTo/teleport for movement.
 *
 * No per-idle-NPC physics or Qwen polling. Only running / settling sessions
 * are advanced. The P0 passive listener and network tracking remain unchanged.
 */
final class C1P1MotionRuntime {
    private static final Logger LOG = LogUtils.getLogger();
    private static final Map<UUID, Session> ACTIVE = new HashMap<>();
    private static final Map<UUID, String> LAST_RESULT = new HashMap<>();
    private static final int SAVE_INTERVAL = 20;

    private static final class Session {
        final C1PlayerAvatar avatar;
        final C1P1MotionPlan plan;  // null for single jump or gravity settling
        int ticks;
        int settleTicks;
        boolean jumping;
        boolean leftGround;
        String completion = "";
        Session(C1PlayerAvatar avatar, C1P1MotionPlan plan) {
            this.avatar = avatar;
            this.plan = plan;
        }
    }

    private C1P1MotionRuntime() {}

    /** Determine initial grounded state because the P0 passive avatar has no vanilla tick. */
    private static void refreshGroundContact(C1PlayerAvatar avatar) {
        if (!avatar.serverLevel().hasChunkAt(avatar.blockPosition())) return;
        if (avatar.serverLevel().noCollision(avatar, avatar.getBoundingBox())
                && !avatar.serverLevel().noCollision(avatar, avatar.getBoundingBox().move(0.0, -0.08, 0.0))) {
            avatar.setOnGround(true);
        }
    }

    static void afterSpawn(C1PlayerAvatar avatar) {
        refreshGroundContact(avatar);
        // In a loaded chunk, settle a floating spawn with a bounded gravity run.
        // No perpetual physics work on inactive avatars.
        if (!avatar.onGround() && avatar.serverLevel().hasChunkAt(avatar.blockPosition())) {
            preparePhysics(avatar);
            ACTIVE.put(avatar.getUUID(), new Session(avatar, null));
        }
    }

    private static C1PlayerAvatar resolve(MinecraftServer server, String name) {
        return C1P0Runtime.activeAvatar(server, name);
    }

    static String move(MinecraftServer server, String name, String directionText, double distance) {
        C1PlayerAvatar avatar = resolve(server, name);
        if (avatar == null) return "NPC not live: " + name;
        C1P1MotionPlan.Direction direction;
        try {
            direction = C1P1MotionPlan.Direction.valueOf(directionText.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return "Direction must be forward, back, left or right";
        }
        if (!Double.isFinite(distance) || distance < C1P1MotionPlan.MIN_DISTANCE
                || distance > C1P1MotionPlan.MAX_DISTANCE) {
            return "Distance must be 0.5 to 8 blocks";
        }
        refreshGroundContact(avatar);
        if (ACTIVE.containsKey(avatar.getUUID())) return "Movement busy; use /npcortex c1 stop " + name;
        if (!avatar.onGround()) return "NPC must be on ground before movement (use c1 settle to test gravity)";
        C1P1MotionPlan plan = new C1P1MotionPlan(direction, distance, avatar.getYRot(),
                avatar.getX(), avatar.getZ());
        preparePhysics(avatar);
        ACTIVE.put(avatar.getUUID(), new Session(avatar, plan));
        LAST_RESULT.put(avatar.getUUID(), "MOVING " + directionText + " " + distance + " blocks");
        return "Started B+ motion: " + name + " " + directionText + " " + distance
                + " blocks (server-controlled, maximum 240 ticks)";
    }

    static String turn(MinecraftServer server, String name, float yaw) {
        C1PlayerAvatar avatar = resolve(server, name);
        if (avatar == null) return "NPC not live: " + name;
        if (!Float.isFinite(yaw) || yaw < -180 || yaw > 180) return "Yaw must be -180..180 degrees";
        if (ACTIVE.containsKey(avatar.getUUID())) return "Movement busy; stop before turning";
        avatar.setYRot(yaw);
        avatar.setYHeadRot(yaw);
        avatar.setYBodyRot(yaw);
        C1P0Runtime.persistPosition(server, avatar);
        C1P0Runtime.syncRotationForActiveViewers(avatar);
        LAST_RESULT.put(avatar.getUUID(), "TURN yaw=" + yaw);
        // Vanilla ChunkMap tracking synchronizes yaw/head rotation to clients.
        return "Turned " + name + " to yaw=" + yaw + " (verify client facing)";
    }

    static String jump(MinecraftServer server, String name) {
        C1PlayerAvatar avatar = resolve(server, name);
        if (avatar == null) return "NPC not live: " + name;
        refreshGroundContact(avatar);
        if (ACTIVE.containsKey(avatar.getUUID())) return "Movement busy; stop first";
        if (!avatar.onGround()) return "NPC must be on ground to jump";
        preparePhysics(avatar);
        Session session = new Session(avatar, null);
        session.jumping = true;
        ACTIVE.put(avatar.getUUID(), session);
        LAST_RESULT.put(avatar.getUUID(), "JUMPING");
        // Player implementation uses the game's jump velocity and effects.
        avatar.jumpFromGround();
        return "Jump requested: " + name + " (watch jump / landing in-game)";
    }

    static String settle(MinecraftServer server, String name) {
        C1PlayerAvatar avatar = resolve(server, name);
        if (avatar == null) return "NPC not live: " + name;
        if (ACTIVE.containsKey(avatar.getUUID())) return "Movement busy; stop first";
        preparePhysics(avatar);
        ACTIVE.put(avatar.getUUID(), new Session(avatar, null));
        LAST_RESULT.put(avatar.getUUID(), "SETTLING");
        return "Gravity/collision settling requested: " + name + " (max 100 ticks)";
    }

    static String stop(MinecraftServer server, String name) {
        C1PlayerAvatar avatar = resolve(server, name);
        if (avatar == null) return "NPC not live: " + name;
        Session session = ACTIVE.get(avatar.getUUID());
        if (session == null) return "No active movement for " + name;
        if (session.plan != null) session.plan.cancel();
        session.completion = "CANCELLED";
        stopHorizontalVelocity(avatar);
        if (avatar.onGround()) finish(server, session);
        else session.settleTicks = 1; // allow gravity to finish safely
        return "Cancelled motion for " + name + "; preserving gravity until landed";
    }

    static String status(MinecraftServer server, String name) {
        C1PlayerAvatar avatar = resolve(server, name);
        if (avatar == null) return "NPC not live: " + name;
        Session session = ACTIVE.get(avatar.getUUID());
        String state = session == null ? LAST_RESULT.getOrDefault(avatar.getUUID(), "IDLE")
                : session.plan != null && session.plan.moving()
                    ? "MOVING remaining=" + String.format(java.util.Locale.ROOT, "%.2f", session.plan.remaining())
                    : session.jumping ? "JUMPING/SETTLING" : "SETTLING";
        return name + " motion=" + state + " pos=" + fmt(avatar.getX()) + "," + fmt(avatar.getY()) + "," + fmt(avatar.getZ())
                + " grounded=" + avatar.onGround() + " horizontalCollision=" + avatar.horizontalCollision
                + " velocity=" + fmt(avatar.getDeltaMovement().length());
    }

    private static String fmt(double n) { return String.format(java.util.Locale.ROOT, "%.3f", n); }

    private static void preparePhysics(C1PlayerAvatar avatar) {
        avatar.setNoGravity(false);
        avatar.getAbilities().flying = false;
        avatar.setSpeed(0.1F); // Vanilla walking speed; no sprint or flying in P1-A.
    }

    private static void stopHorizontalVelocity(C1PlayerAvatar avatar) {
        Vec3 velocity = avatar.getDeltaMovement();
        avatar.setDeltaMovement(0, velocity.y, 0);
    }

    static void onTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || ACTIVE.isEmpty()) return;
        MinecraftServer server = event.getServer();
        // There is no AI, per-tick world scan or chunk loading here.
        for (Iterator<Map.Entry<UUID, Session>> it = ACTIVE.entrySet().iterator(); it.hasNext();) {
            Session session = it.next().getValue();
            C1PlayerAvatar avatar = session.avatar;
            if (avatar.isRemoved() || C1P0Runtime.liveAvatar(avatar.getUUID()) != avatar) {
                it.remove();
                continue;
            }
            if (!avatar.serverLevel().hasChunkAt(avatar.blockPosition())) {
                finishIter(server, session, "CHUNK_UNLOADED", it);
                continue;
            }
            session.ticks++;
            if (session.plan != null && session.plan.moving()) {
                // Check the next position without generating / loading chunks.
                BlockPos ahead = BlockPos.containing(avatar.getX() + session.plan.axisX,
                        avatar.getY(), avatar.getZ() + session.plan.axisZ);
                if (!avatar.serverLevel().hasChunkAt(ahead)) {
                    finishIter(server, session, "CHUNK_UNLOADED", it);
                    continue;
                }
                // Forward motion is applied in player-local axes. Player.travel
                // performs Vanilla friction, gravity and collision resolution.
                avatar.travel(new Vec3(session.plan.direction.strafe, 0, session.plan.direction.forward));
                session.plan.observe(avatar.getX(), avatar.getZ());
                if (session.plan.terminal()) {
                    session.completion = session.plan.phase.name();
                    stopHorizontalVelocity(avatar);
                }
            } else {
                // Decelerated / jumping / cancelled movement still needs gravity
                // until the avatar is safely grounded.
                avatar.travel(Vec3.ZERO);
                session.settleTicks++;
                if (session.jumping && !avatar.onGround()) session.leftGround = true;
            }
            if (session.ticks % SAVE_INTERVAL == 0) C1P0Runtime.persistPosition(server, avatar);
            if (session.jumping && session.leftGround && avatar.onGround()) {
                finishIter(server, session, "JUMP_COMPLETE", it);
            } else if (session.plan != null && session.plan.terminal() && avatar.onGround()) {
                finishIter(server, session, session.completion, it);
            } else if (session.plan == null && !session.jumping && avatar.onGround()
                    && session.settleTicks >= 2) {
                finishIter(server, session, session.completion.isEmpty() ? "SETTLED" : session.completion, it);
            } else if (session.settleTicks >= C1P1MotionPlan.MAX_SETTLE_TICKS
                    || session.ticks >= C1P1MotionPlan.MAX_TICKS + C1P1MotionPlan.MAX_SETTLE_TICKS) {
                finishIter(server, session, "SETTLE_TIMEOUT", it);
            }
        }
    }

    private static void finishIter(MinecraftServer server, Session session, String result,
                                   Iterator<Map.Entry<UUID, Session>> iterator) {
        stopHorizontalVelocity(session.avatar);
        C1P0Runtime.persistPosition(server, session.avatar);
        LAST_RESULT.put(session.avatar.getUUID(), result);
        LOG.info("NPCortex C1-P1 motion_done npc={} result={} ticks={} pos=({},{},{})",
                session.avatar.getUUID(), result, session.ticks,
                session.avatar.getX(), session.avatar.getY(), session.avatar.getZ());
        iterator.remove();
    }

    private static void finish(MinecraftServer server, Session session) {
        ACTIVE.remove(session.avatar.getUUID());
        stopHorizontalVelocity(session.avatar);
        C1P0Runtime.persistPosition(server, session.avatar);
        LAST_RESULT.put(session.avatar.getUUID(), session.completion.isEmpty() ? "CANCELLED" : session.completion);
    }

    static void beforeDespawn(MinecraftServer server, C1PlayerAvatar avatar) {
        if (avatar == null) return;
        ACTIVE.remove(avatar.getUUID());
        LAST_RESULT.remove(avatar.getUUID());
        C1P0Runtime.persistPosition(server, avatar);
    }

    static void beforeServerStop(MinecraftServer server) {
        for (Session session : ACTIVE.values()) {
            C1P0Runtime.persistPosition(server, session.avatar);
        }
        ACTIVE.clear();
        LAST_RESULT.clear();
    }
}
