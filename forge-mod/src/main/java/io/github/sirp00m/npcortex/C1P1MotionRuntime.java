package io.github.sirp00m.npcortex;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.MoverType;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * P1-A operator-controlled player physics experiment, NOT navigation or AI.
 * Exactly one bounded movement intent per avatar. Uses vanilla Player.travel
 * and Player.jumpFromGround, never moveTo/teleport for movement.
 *
 * P1-C Fix #1 checks immediate support from the VANILLA world tick in bounded
 * intervals; once unsupported, the same P1-A travel loop handles the fall.
 * R2 routes Vanilla's actual combat impulse into this SAME travel owner;
 * Fix #2 explicitly owns velocity across attack/world/END-tick boundaries;
 * verified viewer velocity uses ordinary Vanilla motion packets. No Qwen polling.
 */
final class C1P1MotionRuntime {
    private static final Logger LOG = LogUtils.getLogger();
    private static final Map<UUID, Session> ACTIVE = new HashMap<>();
    private static final Map<UUID, String> LAST_RESULT = new HashMap<>();
    // A fallback for externally-set velocity (e.g. explosions). Avoid repeatedly
    // scheduling an impulse while the vanilla tracker still owns hasImpulse.
    private static final Map<UUID, Long> LAST_EXTERNAL_IMPULSE = new HashMap<>();
    private static final int SAVE_INTERVAL = 20;
    // At most one support probe every four world ticks while idle: 10 NPCs are
    // at most ~50 lightweight probes/second at 20 TPS, without chunk loading.
    private static final int IDLE_SUPPORT_INTERVAL = 4;
    private record FallStep(C1PlayerAvatar avatar, Vec3 displacement, boolean onGround) {}

    private static final class Session {
        final C1PlayerAvatar avatar;
        final C1P1MotionPlan plan;  // null for single jump or gravity settling
        int ticks;
        int settleTicks;
        boolean jumping;
        boolean leftGround;
        int combatTicksRemaining;
        int combatAge;
        int combatFallbackSteps;
        int combatTravelSteps;
        // Explicit single-owner bridge from Vanilla attack phase to END-tick physics.
        final C1VanillaMotionOwnership combatVelocity = new C1VanillaMotionOwnership();
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

    /** Called only by C1PlayerAvatar.tick() on the server world thread.
     *  We do not call Vanilla Player.tick/doTick or travel for an idle grounded
     *  actor. If the supporting block disappears, use the EXISTING bounded
     *  motion loop, so gravity has precisely one owner per avatar.
     */
    static void onVanillaWorldTick(C1PlayerAvatar avatar) {
        if (avatar.isRemoved()) return;
        // Real ServerPlayer#doTick normally integrates unsolicited combat motion.
        // Synthetic players have no connection phase, so capture Vanilla's
        // externally-applied impulses and hand travel to our ONE physics owner.
        // Do not activate on normal P1-A controlled walking/jumping.
        if (!ACTIVE.containsKey(avatar.getUUID()) && avatar.hasImpulse
                && C1CombatTickPolicy.flaggedExternalImpulse(true,
                    avatar.getDeltaMovement().x, avatar.getDeltaMovement().y,
                    avatar.getDeltaMovement().z)) {
            long tick = avatar.getServer().getTickCount();
            long last = LAST_EXTERNAL_IMPULSE.getOrDefault(avatar.getUUID(), Long.MIN_VALUE / 2);
            if (tick - last >= 8 && integrateCombatImpulse(avatar, "EXTERNAL_VANILLA")) {
                LAST_EXTERNAL_IMPULSE.put(avatar.getUUID(), tick);
            }
        }
        if (ACTIVE.containsKey(avatar.getUUID())) return;
        // World tickCount advances even with the synthetic connection-phase
        // doTick() disabled; Entity.tickCount may not, so never use it here.
        if (avatar.getServer().getTickCount() % IDLE_SUPPORT_INTERVAL != 0) return;
        if (C1P0Runtime.liveAvatar(avatar.getUUID()) != avatar
                || !C1P1CLifecycleRuntime.mayRestore(avatar.getServer(), avatar.getUUID())) return;
        if (!avatar.serverLevel().hasChunkAt(avatar.blockPosition())) return;
        // A loaded chunk + Vanilla noCollision are necessary: never force a
        // chunk load merely to discover missing floor support.
        boolean supported = !avatar.serverLevel().noCollision(
                avatar, avatar.getBoundingBox().move(0.0, -0.08, 0.0));
        if (!C1P1VanillaTickPolicy.needsSettling(true, false, true,
                avatar.onGround(), supported)) return;
        avatar.setOnGround(false);
        preparePhysics(avatar);
        ACTIVE.put(avatar.getUUID(), new Session(avatar, null));
        LAST_RESULT.put(avatar.getUUID(), "AUTO_SETTLING");
        LOG.debug("NPCortex C1-P1-C AUTO_SETTLE npc={} pos=({},{},{})",
                avatar.getUUID(), avatar.getX(), avatar.getY(), avatar.getZ());
    }

    /** Called just AFTER Vanilla LivingEntity.knockback() has applied its real
     *  impulse. Resistant actors produce zero delta and are left untouched.
     *  Knockback during a planned move temporarily interrupts player input;
     *  the same travel loop continues the plan after the impulse dissipates.
     */
    static void onVanillaKnockback(C1PlayerAvatar avatar, Vec3 previousVelocity) {
        Vec3 after = avatar.getDeltaMovement();
        if (!C1CombatTickPolicy.actualImpulse(previousVelocity.x, previousVelocity.y,
                previousVelocity.z, after.x, after.y, after.z)) return;
        integrateCombatImpulse(avatar, "VANILLA_KNOCKBACK");
    }

    private static boolean integrateCombatImpulse(C1PlayerAvatar avatar, String reason) {
        MinecraftServer server = avatar.getServer();
        if (server == null) return false;
        boolean registered = C1P0Runtime.liveAvatar(avatar.getUUID()) == avatar;
        boolean alive = registered && C1P1CLifecycleRuntime.mayRestore(server, avatar.getUUID());
        boolean loaded = avatar.serverLevel().hasChunkAt(avatar.blockPosition());
        if (!C1CombatTickPolicy.mayIntegrate(registered, alive, avatar.isRemoved(), loaded)) return false;
        // Vanilla already computed the vector: NEVER scale/multiply it again.
        // In particular do not call preparePhysics(), which resets movement speed.
        Session session = ACTIVE.computeIfAbsent(avatar.getUUID(), id -> new Session(avatar, null));
        session.combatTicksRemaining = C1CombatTickPolicy.MAX_COMBAT_TICKS;
        session.combatAge = 0;
        session.combatFallbackSteps = 0;
        session.combatTravelSteps = 0;
        // Capture the latest Vanilla vector *at emission time*. The synthetic
        // ServerPlayer can lose it in its world tick before END-tick travel.
        // Do not sum multiple callbacks; the last vector already contains the
        // Vanilla-calculated enchantment/attack/attribute effects.
        session.combatVelocity.captureVanilla(toOwned(avatar.getDeltaMovement()));
        LAST_RESULT.put(avatar.getUUID(), reason);
        C1P0Runtime.forEachPresentViewer(avatar,
                viewer -> viewer.connection.send(new ClientboundSetEntityMotionPacket(avatar)));
        LOG.info("NPCortex C1-R2 COMBAT_IMPULSE npc={} reason={} velocity={} motionPlanned={}",
                avatar.getUUID(), reason, avatar.getDeltaMovement(), session.plan != null);
        return true;
    }

    /** Late viewers need the authoritative velocity after two-phase repair. */
    static void syncCombatVelocityForViewer(ServerPlayer viewer, C1PlayerAvatar avatar) {
        Session session = ACTIVE.get(avatar.getUUID());
        if (session != null && session.combatTicksRemaining > 0)
            viewer.connection.send(new ClientboundSetEntityMotionPacket(avatar));
    }

    static String combatStatus(MinecraftServer server, String name) {
        C1PlayerAvatar avatar = resolve(server, name);
        if (avatar == null) return "NPC not live: " + name;
        Session session = ACTIVE.get(avatar.getUUID());
        return name + " combat=" + (session != null && session.combatTicksRemaining > 0 ? "IMPULSE" : "IDLE")
                + " ticksRemaining=" + (session == null ? 0 : session.combatTicksRemaining)
                + " collisionFallbackSteps=" + (session == null ? 0 : session.combatFallbackSteps)
                + " travelOnlySteps=" + (session == null ? 0 : session.combatTravelSteps)
                + " vanillaHurtTime=" + avatar.hurtTime
                + " resistance=" + fmt(avatar.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE))
                + " velocity=" + avatar.getDeltaMovement();
    }

    /** Operator-only fixture: delegates direction and magnitude to Vanilla knockback. */
    static String testKnockback(MinecraftServer server, ServerPlayer commander,
                                String name, double strength) {
        C1PlayerAvatar avatar = resolve(server, name);
        if (avatar == null) return "NPC not live: " + name;
        if (!Double.isFinite(strength) || strength < 0.1 || strength > 2.0) return "Strength must be 0.1..2.0";
        if (avatar.serverLevel() != commander.serverLevel()) return "Operator must be in NPC's dimension";
        double dx = commander.getX() - avatar.getX();
        double dz = commander.getZ() - avatar.getZ();
        if (dx * dx + dz * dz < 1.0e-6) dx = 1.0;
        Vec3 before = avatar.getDeltaMovement();
        avatar.knockback(strength, dx, dz);
        return name + " vanillaKnockback=" + strength + " before=" + before
                + " after=" + avatar.getDeltaMovement()
                + " (may be resisted; verify with /npcortex c1 combat)";
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
        return moveInternal(server,name,directionText,distance,false);
    }

    /** Called by C2 move_to only, on the server thread; reuses the same Vanilla motion session. */
    static String moveC2Precision(MinecraftServer server, String name, String directionText, double distance) {
        return moveInternal(server,name,directionText,distance,true);
    }

    private static String moveInternal(MinecraftServer server, String name, String directionText,
                                       double distance, boolean c2Precision) {
        C1PlayerAvatar avatar = resolve(server, name);
        if (avatar == null) return "NPC not live: " + name;
        C1P1MotionPlan.Direction direction;
        try {
            direction = C1P1MotionPlan.Direction.valueOf(directionText.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return "Direction must be forward, back, left or right";
        }
        double minimum = c2Precision ? C1P1MotionPlan.C2_PRECISION_MIN_DISTANCE
                : C1P1MotionPlan.MIN_DISTANCE;
        if (!Double.isFinite(distance) || distance < minimum || distance > C1P1MotionPlan.MAX_DISTANCE) {
            return c2Precision ? "C2 distance must be 0.10 to 8 blocks" : "Distance must be 0.5 to 8 blocks";
        }
        refreshGroundContact(avatar);
        if (ACTIVE.containsKey(avatar.getUUID())) return "Movement busy; use /npcortex c1 stop " + name;
        if (!avatar.onGround()) return "NPC must be on ground before movement (use c1 settle to test gravity)";
        C1P1MotionPlan plan = new C1P1MotionPlan(direction, distance, avatar.getYRot(),
                avatar.getX(), avatar.getZ(), c2Precision);
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
        if (C1CombatTickPolicy.canClearHorizontalVelocity(session.combatTicksRemaining))
            stopHorizontalVelocity(avatar);
        if (avatar.onGround() && session.combatTicksRemaining <= 0) finish(server, session);
        else session.settleTicks = 1; // allow gravity to finish safely
        return "Cancelled motion for " + name + "; preserving gravity until landed";
    }

    static String status(MinecraftServer server, String name) {
        C1PlayerAvatar avatar = resolve(server, name);
        if (avatar == null) return "NPC not live: " + name;
        Session session = ACTIVE.get(avatar.getUUID());
        String state = session == null ? LAST_RESULT.getOrDefault(avatar.getUUID(), "IDLE")
                : session.combatTicksRemaining > 0 ? "COMBAT_IMPULSE ticks=" + session.combatTicksRemaining
                : session.plan != null && session.plan.moving()
                    ? "MOVING remaining=" + String.format(java.util.Locale.ROOT, "%.2f", session.plan.remaining())
                    : session.jumping ? "JUMPING/SETTLING" : "SETTLING";
        return name + " motion=" + state + " pos=" + fmt(avatar.getX()) + "," + fmt(avatar.getY()) + "," + fmt(avatar.getZ())
                + " grounded=" + avatar.onGround() + " horizontalCollision=" + avatar.horizontalCollision
                + " velocity=" + fmt(avatar.getDeltaMovement().length());
    }

    /** Read-only action receipt, after C1 has completed its one authoritative physics tick. */
    record MotionReceipt(boolean active, String phase) {}

    static MotionReceipt c2Receipt(UUID profileId) {
        Session session = ACTIVE.get(profileId);
        if (session != null) return new MotionReceipt(true, "ACTIVE");
        return new MotionReceipt(false, LAST_RESULT.getOrDefault(profileId, "UNKNOWN"));
    }

    private static C1VanillaMotionOwnership.Velocity toOwned(Vec3 v) {
        return new C1VanillaMotionOwnership.Velocity(v.x, v.y, v.z);
    }

    private static Vec3 fromOwned(C1VanillaMotionOwnership.Velocity v) {
        return new Vec3(v.x(), v.y(), v.z());
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
        // Do not call damage/death code while iterating ACTIVE: a fatal fall can
        // invoke beforeDespawn(), which removes sessions from this map.
        List<FallStep> fallChecks = new ArrayList<>();
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
            Vec3 previousPosition = avatar.position();
            // Exactly one motion owner per END-tick: combat > commanded > settle.
            // Never execute a commanded travel() in the same tick as an impulse.
            C1PlayerMotionPhase phase = C1PlayerMotionPhase.choose(
                    session.combatTicksRemaining, session.plan != null && session.plan.moving());
            if (phase == C1PlayerMotionPhase.COMBAT) {
                // Connection-independent Vanilla combat phase. Always ask
                // Player.travel to consume Vanilla's real knockback first.
                // A ServerPlayer normally receives client movement packets;
                // our passive ServerPlayer may leave position unchanged even
                // when Vanilla knockback provides a non-zero velocity. In that
                // precise case, use Vanilla Entity.move collision resolution
                // ONCE for the unconsumed component, not teleport/setPos or
                // an invented knockback formula. Never move twice on an axis
                // which travel already advanced.
                Vec3 liveVelocity = avatar.getDeltaMovement();
                C1VanillaMotionOwnership.Selection source = session.combatVelocity.begin(
                        toOwned(liveVelocity), avatar.onGround());
                Vec3 incomingVelocity = fromOwned(source.velocity());
                if (!incomingVelocity.equals(liveVelocity)) {
                    // One-time ownership handoff, not a second knockback call.
                    avatar.setDeltaMovement(incomingVelocity);
                }
                avatar.travel(Vec3.ZERO);
                Vec3 travelDelta = avatar.position().subtract(previousPosition);
                boolean horizontalFallback = C1CombatTickPolicy.needsHorizontalCollisionFallback(
                        incomingVelocity.x, incomingVelocity.z, travelDelta.x, travelDelta.z,
                        avatar.horizontalCollision);
                boolean verticalFallback = C1CombatTickPolicy.needsVerticalCollisionFallback(
                        incomingVelocity.y, travelDelta.y, avatar.onGround(), avatar.verticalCollision);
                Vec3 fallback = new Vec3(horizontalFallback ? incomingVelocity.x : 0.0,
                        verticalFallback ? incomingVelocity.y : 0.0,
                        horizontalFallback ? incomingVelocity.z : 0.0);
                if (fallback.lengthSqr() > 0.0) {
                    // This calls Minecraft's ordinary AABB/step collision and
                    // updates onGround; it cannot phase through loaded blocks.
                    avatar.move(MoverType.SELF, fallback);
                    session.combatFallbackSteps++;
                } else {
                    session.combatTravelSteps++;
                }
                session.combatAge++;
                session.combatTicksRemaining--;
                Vec3 velocity = avatar.getDeltaMovement();
                // The next authoritative frame uses Vanilla's *post-travel*
                // friction/gravity, not the original impulse. Collision always
                // invalidates carried motion on the blocked axes.
                session.combatVelocity.completed(toOwned(velocity), avatar.onGround(),
                        avatar.horizontalCollision, avatar.verticalCollision);
                // Bounded INFO instrumentation only for the opening frames,
                // where an impulse may otherwise silently fail. No per-tick
                // log spam through the remainder of the combat session.
                if (session.combatAge <= 3) {
                    LOG.info("NPCortex C1-R2-FIX2 COMBAT_STEP npc={} age={} ownership={} liveBefore={} incoming={} travelDelta={} "
                                    + "fallback={} actualDelta={} velocityAfter={} grounded={} "
                                    + "horizontalCollision={} verticalCollision={}",
                            avatar.getUUID(), session.combatAge, source.source(), liveVelocity,
                            incomingVelocity, travelDelta, fallback, avatar.position().subtract(previousPosition), velocity,
                            avatar.onGround(), avatar.horizontalCollision, avatar.verticalCollision);
                }
                if (C1CombatTickPolicy.shouldEndImpulse(session.combatAge, avatar.onGround(),
                        velocity.x, velocity.y, velocity.z)) {
                    session.combatTicksRemaining = 0;
                    session.combatVelocity.clear();
                }
                session.settleTicks++;
            } else if (phase == C1PlayerMotionPhase.COMMANDED) {
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
                    if (C1CombatTickPolicy.canClearHorizontalVelocity(session.combatTicksRemaining))
                        stopHorizontalVelocity(avatar);
                }
            } else {
                // Decelerated / jumping / cancelled movement still needs gravity
                // until the avatar is safely grounded.
                avatar.travel(Vec3.ZERO);
                session.settleTicks++;
                if (session.jumping && !avatar.onGround()) session.leftGround = true;
            }
            // Vanilla ServerPlayer.checkFallDamage is intentionally empty:
            // a real client's movement packets call doCheckFallDamage instead.
            // Our synthetic player has no client movement packets, so call the
            // SAME vanilla method once after each authoritative travel step.
            // Never call it outside this single motion controller.
            Vec3 displacement = avatar.position().subtract(previousPosition);
            fallChecks.add(new FallStep(avatar, displacement, avatar.onGround()));
            if (session.ticks % SAVE_INTERVAL == 0) C1P0Runtime.persistPosition(server, avatar);
            if (session.combatTicksRemaining > 0) {
                // Never finish, clear horizontal speed, or resume command input
                // while the Vanilla impulse is still moving the actor.
            } else if (session.jumping && session.leftGround && avatar.onGround()) {
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
        // Vanilla player fall checks normally arrive through the real player's
        // movement packet handler; synthetic avatars have no such packet path.
        // Apply once per authoritative travel step, AFTER the ACTIVE iteration.
        for (FallStep step : fallChecks) {
            C1PlayerAvatar avatar = step.avatar();
            if (avatar.isRemoved() || C1P0Runtime.liveAvatar(avatar.getUUID()) != avatar
                    || !C1P1CLifecycleRuntime.mayRestore(server, avatar.getUUID())) continue;
            Vec3 d = step.displacement();
            avatar.doCheckFallDamage(d.x, d.y, d.z, step.onGround());
        }
    }

    private static void finishIter(MinecraftServer server, Session session, String result,
                                   Iterator<Map.Entry<UUID, Session>> iterator) {
        if (C1CombatTickPolicy.canClearHorizontalVelocity(session.combatTicksRemaining))
            stopHorizontalVelocity(session.avatar);
        C1P0Runtime.persistPosition(server, session.avatar);
        session.combatVelocity.clear();
        LAST_RESULT.put(session.avatar.getUUID(), result);
        LOG.info("NPCortex C1-P1 motion_done npc={} result={} ticks={} pos=({},{},{})",
                session.avatar.getUUID(), result, session.ticks,
                session.avatar.getX(), session.avatar.getY(), session.avatar.getZ());
        iterator.remove();
    }

    private static void finish(MinecraftServer server, Session session) {
        ACTIVE.remove(session.avatar.getUUID());
        if (C1CombatTickPolicy.canClearHorizontalVelocity(session.combatTicksRemaining))
            stopHorizontalVelocity(session.avatar);
        C1P0Runtime.persistPosition(server, session.avatar);
        session.combatVelocity.clear();
        LAST_RESULT.put(session.avatar.getUUID(), session.completion.isEmpty() ? "CANCELLED" : session.completion);
    }

    static void beforeDespawn(MinecraftServer server, C1PlayerAvatar avatar) {
        if (avatar == null) return;
        ACTIVE.remove(avatar.getUUID());
        LAST_RESULT.remove(avatar.getUUID());
        LAST_EXTERNAL_IMPULSE.remove(avatar.getUUID());
        C1P0Runtime.persistPosition(server, avatar);
    }

    static void beforeServerStop(MinecraftServer server) {
        for (Session session : ACTIVE.values()) {
            C1P0Runtime.persistPosition(server, session.avatar);
        }
        ACTIVE.clear();
        LAST_RESULT.clear();
        LAST_EXTERNAL_IMPULSE.clear();
    }
}
