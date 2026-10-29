package io.github.sirp00m.npcortex;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.MoverType;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
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
    // Pose only: one small transient state per live avatar, never an extra physics owner.
    private static final Map<UUID, C1LocomotionPresentation> PRESENTATIONS = new HashMap<>();
    private static final Map<UUID, String> LAST_RESULT = new HashMap<>();
    private static final Map<UUID, UUID> LAST_SESSION_TOKEN = new HashMap<>();
    private static final Map<UUID, C2S4RecoveryPolicy.MotionOrigin> LAST_SESSION_ORIGIN = new HashMap<>();
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
        C1P1MotionPlan plan;  // mutable ONLY for same-token C-3D Follow stage handoffs
        final UUID token = UUID.randomUUID();
        C2S4RecoveryPolicy.MotionOrigin origin = C2S4RecoveryPolicy.MotionOrigin.OPERATOR_COMMAND;
        C1S2TraversalPlan traversal;
        C1F2ContinuousWalk continuous;
        C1Persistent3DChain mixed; // single C1 owner/token, bounded, Follow only
        boolean mixedTurning;
        float mixedDesiredYaw;
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
        // Idle turn/stop easing is visual-only. It cannot move the player or
        // create a C1 motion session; avoid leaking presentation after despawn.
        C1LocomotionPresentation idlePose = PRESENTATIONS.get(avatar.getUUID());
        if (idlePose != null && !idlePose.settled(avatar.getYRot()))
            updatePresentation(avatar, avatar.getYRot(), 0, false, false, false);
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
        Session gravity = new Session(avatar, null);
        gravity.origin = C2S4RecoveryPolicy.MotionOrigin.AUTO_SETTLING;
        ACTIVE.put(avatar.getUUID(), gravity);
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
        Session session = ACTIVE.computeIfAbsent(avatar.getUUID(), id -> {
            Session combat = new Session(avatar, null);
            combat.origin = C2S4RecoveryPolicy.MotionOrigin.AUTO_COMBAT;
            return combat;
        });
        session.combatTicksRemaining = C1CombatTickPolicy.MAX_COMBAT_TICKS;
        session.combatAge = 0;
        session.combatFallbackSteps = 0;
        session.combatTravelSteps = 0;
        // Combat owns this frame. A rolling Follow walk must NOT silently
        // resume an obsolete chain after knockback; require a fresh graph
        // proof and a new ticket through Follow's guarded replan path.
        if(session.continuous!=null && session.continuous.moving()) {
            session.continuous.cancel();
            session.completion="INTERRUPTED_COMBAT";
        }
        if (session.traversal != null) {
            session.traversal.combatInterrupted = true;
            if (session.plan != null) session.plan.cancel();
            session.completion = "TRAVERSAL_INTERRUPTED_COMBAT";
        }
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
            Session spawn = new Session(avatar, null);
            spawn.origin = C2S4RecoveryPolicy.MotionOrigin.SPAWN_SETTLING;
            ACTIVE.put(avatar.getUUID(), spawn);
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
        ServerThread.require(server, "C1 move");
        C1PlayerAvatar avatar = resolve(server, name);
        if (avatar == null) return "NPC not live: " + name;
        if (NativeEffectRuntime.pendingProfile(server, avatar.getUUID()))
            return "Native world action active; motion refused until it is settled (Rec. in the Manager)";
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
        Session movement = new Session(avatar, plan);
        movement.origin = c2Precision ? C2S4RecoveryPolicy.MotionOrigin.C2_COMMAND
                : C2S4RecoveryPolicy.MotionOrigin.OPERATOR_COMMAND;
        ACTIVE.put(avatar.getUUID(), movement);
        LAST_RESULT.put(avatar.getUUID(), "MOVING " + directionText + " " + distance + " blocks");
        return "Started B+ motion: " + name + " " + directionText + " " + distance
                + " blocks (server-controlled, maximum 240 ticks)";
    }

    /** F2 Fix #1 FOLLOW ONLY: one bounded C1-owned WALK session across
     * already verified flat graph segments. Never called by C0 navigation,
     * combat, operator move or vertical primitive execution. */
    static String moveC2ContinuousWalk(MinecraftServer server,String name,
            List<C1F2ContinuousWalk.Point> targets) {
        C1PlayerAvatar avatar=resolve(server,name);
        if(avatar==null) return "F2 actor not live";
        refreshGroundContact(avatar);
        if(ACTIVE.containsKey(avatar.getUUID()) || !avatar.onGround())
            return "F2 C1 motion busy/not grounded";
        final C1F2ContinuousWalk path;
        try {path=new C1F2ContinuousWalk(avatar.getX(),avatar.getY(),avatar.getZ(),targets);}
        catch(IllegalArgumentException bad) {return "F2 bounded walk rejected: "+bad.getMessage();}
        preparePhysics(avatar);
        Session session=new Session(avatar,null);
        session.origin=C2S4RecoveryPolicy.MotionOrigin.C2_COMMAND;
        session.continuous=path;
        ACTIVE.put(avatar.getUUID(),session);
        LAST_RESULT.put(avatar.getUUID(),"F2 CONTINUOUS_WALK");
        return "F2 continuous WALK started token="+session.token;
    }

    /** C-3D: one bounded, typed Follow lease, never a new physics owner.
     * Every stage is revalidated at start/grounded handoff. No C0 command,
     * no force loading, and no interpolation of unverified air movement. */
    static String moveC2Persistent3D(MinecraftServer server,String name,
            List<C1Persistent3DChain.Stage> stages) {
        if(server==null || !server.isSameThread()) return "C-3D server thread required";
        C1PlayerAvatar avatar=resolve(server,name);
        if(avatar==null || avatar.isRemoved() || ACTIVE.containsKey(avatar.getUUID())
                || !C1P1CLifecycleRuntime.mayRestore(server,avatar.getUUID()))
            return "C-3D actor busy/unavailable";
        refreshGroundContact(avatar);
        if(!avatar.onGround()) return "C-3D actor not grounded";
        final C1Persistent3DChain chain;
        try {chain=new C1Persistent3DChain(stages);}
        catch(IllegalArgumentException error) {return "C-3D mixed route rejected: "+error.getMessage();}
        Session session=new Session(avatar,null);
        session.mixed=chain;
        session.origin=C2S4RecoveryPolicy.MotionOrigin.C2_COMMAND;
        if(!armMixedStage(session)) return "C-3D first stage failed live terrain validation";
        preparePhysics(avatar);
        ACTIVE.put(avatar.getUUID(),session);
        LAST_RESULT.put(avatar.getUUID(),"C-3D MIXED_FOLLOW");
        return "C-3D mixed Follow started token="+session.token+" stages="+chain.count();
    }

    /** Re-probe at each actual landing, after previous stage is physically
     * verified. No target prediction is evidence of a valid directed edge. */
    private static boolean armMixedStage(Session session) {
        C1PlayerAvatar avatar=session.avatar;
        var stage=session.mixed.current();
        var from=stage.from();var to=stage.to();
        if(!avatar.onGround() || Mth.floor(avatar.getX())!=from.x()
                || Mth.floor(avatar.getZ())!=from.z()
                || Math.abs(avatar.getY()-from.footY())>.16) return false;
        if(!avatar.serverLevel().hasChunkAt(new BlockPos(to.x(),to.supportY(),to.z()))
                || !avatar.serverLevel().getWorldBorder().isWithinBounds(new BlockPos(to.x(),to.supportY(),to.z())))
            return false;
        var graph=C2GraphWorldProbe.captureWithSnapshot(avatar,C2NavGrid.MAX_RADIUS).graph();
        C2WaypointExecution witness;
        try {witness=C2WaypointExecution.followSmoothed(List.of(from,to));}
        catch(IllegalArgumentException invalid) {return false;}
        if(!witness.edgeAllowed(graph)) return false;
        double tx=to.x()+.5,tz=to.z()+.5,ty=to.footY();
        double dist=Math.hypot(tx-avatar.getX(),tz-avatar.getZ());
        if(!Double.isFinite(dist) || dist<.03) return false;
        if(stage.kind()==C2TraversabilityGraph.MoveKind.WALK) {
            if(Math.abs(ty-avatar.getY())>.02 || dist>4.5
                    || !C2WaypointBridge.sweptFollowCorridorClear(avatar,
                            avatar.getX(),avatar.getZ(),tx,ty,tz)) return false;
            C1F2ContinuousWalk candidate;
            try {candidate=new C1F2ContinuousWalk(avatar.getX(),avatar.getY(),avatar.getZ(),
                    List.of(new C1F2ContinuousWalk.Point(tx,ty,tz)));}
            catch(IllegalArgumentException invalid) {return false;}
            session.plan=null;
            session.traversal=null;
            session.continuous=candidate;
            session.jumping=false;
            session.mixedTurning=false;
            session.completion="";
            return true;
        }
        if(dist<C1S2TraversalPlan.MIN_DISTANCE || dist>C1S2TraversalPlan.MAX_DISTANCE
                || !loadedTraversalNeighborhood(avatar,BlockPos.containing(tx,ty,tz)))
            return false;
        // Typed 3D preflight uses the SAME Vanilla AABB/standing surface
        // conditions as C1S2 traversal, not a direct or interpolated teleport.
        var kind=C1S2TraversalPlan.Kind.valueOf(stage.kind().name());
        if(Math.abs(ty-(avatar.getY()+kind.height))>C1S2TraversalPlan.HEIGHT_TOLERANCE) return false;
        var landing=C2NavigationSensor.landingAt(avatar,to.x(),to.supportY(),to.z());
        if(landing==null || landing.risk()==C2TerrainObservation.Risk.HAZARD
                || landing.risk()==C2TerrainObservation.Risk.UNKNOWN
                || Math.abs(landing.cell().footY()-ty)>C1S2TraversalPlan.HEIGHT_TOLERANCE
                || (!C2NavGrid.plannable(landing.cell().condition())
                    && !(landing.cell().condition()==C2NavGrid.Condition.PARTIAL_SUPPORT
                         && landing.supportedFraction()>=.5))) return false;
        net.minecraft.world.phys.AABB landed=avatar.getBoundingBox().move(
                tx-avatar.getX(),ty-avatar.getY(),tz-avatar.getZ());
        if(!avatar.serverLevel().noCollision(avatar,landed)
                || (kind.jump && !avatar.serverLevel().noCollision(avatar,
                        avatar.getBoundingBox().move(0,.5,0)))) return false;
        C2MoveGeometry.Plan heading;
        try {heading=C2MoveGeometry.plan(avatar.getX(),avatar.getY(),avatar.getZ(),
                tx,avatar.getY(),tz);}
        catch(IllegalArgumentException invalid) {return false;}
        C1P1MotionPlan drive;
        C1S2TraversalPlan traverse;
        try {
            drive=new C1P1MotionPlan(C1P1MotionPlan.Direction.FORWARD,dist,heading.yaw(),
                    avatar.getX(),avatar.getZ(),true);
            traverse=new C1S2TraversalPlan(kind,avatar.getX(),avatar.getY(),avatar.getZ(),tx,tz,dist);
        } catch(IllegalArgumentException invalid) {return false;}
        session.mixedDesiredYaw=heading.yaw();
        session.mixedTurning=!C2F2Locomotion.aligned(avatar.getYRot(),heading.yaw());
        if(!session.mixedTurning) avatar.setYRot(heading.yaw());
        session.continuous=null;
        session.plan=drive;
        session.traversal=traverse;
        session.jumping=kind.jump;
        session.leftGround=false;
        session.completion="";
        if(kind.jump && !session.mixedTurning) avatar.jumpFromGround();
        return true;
    }

    /** F2 Fix #2 — read-only, token-fenced diagnostics for current Follow
     * owner. No motion mutation, backend access or chunk loading. */
    static C1F2WalkProgress c2WalkProgress(UUID profile, UUID token) {
        Session session=ACTIVE.get(profile);
        if(session==null || token==null || !session.token.equals(token)
                || session.mixed!=null || session.continuous==null || session.combatTicksRemaining>0
                || session.origin!=C2S4RecoveryPolicy.MotionOrigin.C2_COMMAND) return null;
        return new C1F2WalkProgress(session.continuous.consumed(),
                session.continuous.remaining(),session.continuous.queued(),
                session.continuous.metrics());
    }
    record C1F2WalkProgress(int consumed,int remaining,int queued,
            C1F2MotionTelemetry.Summary telemetry) { }

    /** Only the SAME active C1 ticket accepts additional already-verified
     * horizontal targets. No new owner/plan, no velocity reset. */
    static boolean extendC2ContinuousWalk(UUID profile,UUID token,
            List<C1F2ContinuousWalk.Point> targets) {
        Session session=ACTIVE.get(profile);
        return session!=null && token!=null && session.token.equals(token)
                && session.mixed==null && session.continuous!=null && session.continuous.moving()
                && session.combatTicksRemaining==0
                && session.origin==C2S4RecoveryPolicy.MotionOrigin.C2_COMMAND
                && session.continuous.extend(targets);
    }

    /** Sprint 2 operator-only 3D motion proof. Does not create C0 goals, graph
     * edges, or C2 waypoints; Sprint 3 must validate those independently.
     * The same C1 physics loop owns horizontal movement, jump, fall and combat.
     */
    static String traverse(MinecraftServer server, String name, String type,
                           String directionText, double distance) {
        return traverseInternal(server,name,type,directionText,distance,false);
    }

    /** C2-owned segment, invoked only after a freshly verified directed graph
     * edge. Reuses the exact S2 preflight and one vanilla physics/session owner.
     * Its token is read by C2 before any subsequent segment is authorized.
     */
    static String traverseC2(MinecraftServer server, String name, C1S2TraversalPlan.Kind kind,
                             double distance) {
        return traverseInternal(server,name,kind.name(),"forward",distance,true);
    }

    private static String traverseInternal(MinecraftServer server, String name, String type,
                                           String directionText, double distance, boolean c2Owner) {
        if (server == null || !server.isSameThread()) return "S2 requires Minecraft server thread";
        C1PlayerAvatar avatar = resolve(server, name);
        if (avatar == null) return "NPC not live: " + name;
        C1S2TraversalPlan.Kind kind;
        C1P1MotionPlan.Direction direction;
        try {
            kind = C1S2TraversalPlan.parse(type);
            direction = C1P1MotionPlan.Direction.valueOf(directionText.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) { return e.getMessage(); }
        if (!Double.isFinite(distance) || distance < C1S2TraversalPlan.MIN_DISTANCE
                || distance > C1S2TraversalPlan.MAX_DISTANCE)
            return "S2 distance must be 0.65..1.45 blocks";
        if (ACTIVE.containsKey(avatar.getUUID())) return "S2 movement busy";
        if (!C1P1CLifecycleRuntime.mayRestore(server,avatar.getUUID()) || avatar.isRemoved())
            return "S2 NPC lifecycle unavailable";
        refreshGroundContact(avatar);
        if (!avatar.onGround() || !loadedTraversalNeighborhood(avatar,avatar.blockPosition())
                || avatar.serverLevel().noCollision(avatar,avatar.getBoundingBox().move(0,-0.08,0)))
            return "S2 NPC must have confirmed loaded ground support";
        C1P1MotionPlan plan = new C1P1MotionPlan(direction,distance,avatar.getYRot(),
                avatar.getX(),avatar.getZ(),true);
        C1S2TraversalPlan traversal = new C1S2TraversalPlan(kind, avatar.getX(),avatar.getY(),avatar.getZ(),
                avatar.getX()+plan.axisX*distance,avatar.getZ()+plan.axisZ*distance,distance);
        // Terrain Intelligence is a standing *observation*. Never turn a
        // partial/unknown cell into a path edge; this local operator test is
        // allowed to test multi-box stairs if the actual landing AABB is free.
        int tx = net.minecraft.util.Mth.floor(traversal.targetX);
        int tz = net.minecraft.util.Mth.floor(traversal.targetZ);
        int sy = net.minecraft.util.Mth.floor(traversal.targetY - 0.04);
        C2TerrainObservation landing = C2NavigationSensor.landingAt(avatar,tx,sy,tz);
        if (landing == null || landing.cell().condition() == C2NavGrid.Condition.UNLOADED)
            return "S2 CHUNK_UNLOADED/OUT_OF_BOUNDS; no movement";
        if (Math.abs(landing.cell().footY()-traversal.targetY) > C1S2TraversalPlan.HEIGHT_TOLERANCE)
            return "S2 LANDING_HEIGHT_MISMATCH actual=" + fmt(landing.cell().footY())
                    + " requested=" + fmt(traversal.targetY);
        boolean safe = C2NavGrid.plannable(landing.cell().condition());
        // Stair top steps can supply half a footprint at a given local height.
        // Accept a local *physics test* only when coverage >= half and an exact
        // Vanilla AABB collision check at the expected standing pose succeeds.
        if (!safe && landing.cell().condition()==C2NavGrid.Condition.PARTIAL_SUPPORT
                && landing.supportedFraction() >= 0.5 && landing.risk()==C2TerrainObservation.Risk.NONE)
            safe = true;
        if (!safe) return "S2 UNSAFE_LANDING " + landing.cell().condition()
                    + "/" + landing.reason();
        AABB landingBox = avatar.getBoundingBox().move(traversal.targetX-avatar.getX(),
                traversal.targetY-avatar.getY(), traversal.targetZ-avatar.getZ());
        if (!avatar.serverLevel().noCollision(avatar,landingBox))
            return "S2 LANDING_COLLISION; no movement";
        if (kind.jump && !avatar.serverLevel().noCollision(avatar,
                avatar.getBoundingBox().move(0,0.5,0)))
            return "S2 JUMP_HEADROOM_BLOCKED; no movement";
        preparePhysics(avatar);
        Session session = new Session(avatar,plan);
        session.origin = c2Owner ? C2S4RecoveryPolicy.MotionOrigin.C2_COMMAND
                : C2S4RecoveryPolicy.MotionOrigin.OPERATOR_COMMAND;
        session.traversal = traversal;
        ACTIVE.put(avatar.getUUID(),session);
        LAST_RESULT.put(avatar.getUUID(), "TRAVERSING_"+kind.name());
        if (kind.jump) {
            session.jumping = true;
            avatar.jumpFromGround(); // vanilla, once, no alternate physics.
        }
        return (c2Owner ? "S3 C2 traversal started " : "S2 traverse started ")
                + name + " " + kind + " " + directionText
                + " " + distance + " targetY=" + fmt(traversal.targetY)
                + " token=" + session.token + " (operator-only; inspect c1 motion)";
    }

    static String turn(MinecraftServer server, String name, float yaw) {
        ServerThread.require(server, "C1 turn");
        C1PlayerAvatar avatar = resolve(server, name);
        if (avatar == null) return "NPC not live: " + name;
        if (!Float.isFinite(yaw) || yaw < -180 || yaw > 180) return "Yaw must be -180..180 degrees";
        if (ACTIVE.containsKey(avatar.getUUID())) return "Movement busy; stop before turning";
        avatar.setYRot(yaw);
        // Explicit turn keeps authoritative facing instant (existing contract),
        // while Follow TURNING displays head/body with a bounded settle.
        updatePresentation(avatar, yaw, 0, false, false, false);
        C1P0Runtime.persistPosition(server, avatar);
        C1P0Runtime.syncRotationForActiveViewers(avatar);
        LAST_RESULT.put(avatar.getUUID(), "TURN yaw=" + yaw);
        // Vanilla ChunkMap tracking synchronizes yaw/head rotation to clients.
        return "Turned " + name + " to yaw=" + yaw + " (verify client facing)";
    }

    static String jump(MinecraftServer server, String name) {
        ServerThread.require(server, "C1 jump");
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
        ServerThread.require(server, "C1 settle");
        C1PlayerAvatar avatar = resolve(server, name);
        if (avatar == null) return "NPC not live: " + name;
        if (ACTIVE.containsKey(avatar.getUUID())) return "Movement busy; stop first";
        preparePhysics(avatar);
        Session manual = new Session(avatar, null);
        manual.origin = C2S4RecoveryPolicy.MotionOrigin.OPERATOR_COMMAND;
        ACTIVE.put(avatar.getUUID(), manual);
        LAST_RESULT.put(avatar.getUUID(), "SETTLING");
        return "Gravity/collision settling requested: " + name + " (max 100 ticks)";
    }

    static String stop(MinecraftServer server, String name) {
        ServerThread.require(server, "C1 stop");
        C1PlayerAvatar avatar = resolve(server, name);
        if (avatar == null) return "NPC not live: " + name;
        Session session = ACTIVE.get(avatar.getUUID());
        if (session == null) return "No active movement for " + name;
        if (session.plan != null) session.plan.cancel();
        if (session.continuous != null) session.continuous.cancel();
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
                : session.traversal != null ? "TRAVERSING " + session.traversal.kind
                    + " remaining=" + (session.plan == null ? "?" : fmt(session.plan.remaining()))
                    + " targetY=" + fmt(session.traversal.targetY)
                    + " airborne=" + session.traversal.leftGround
                : session.continuous != null && session.continuous.moving()
                    ? "CONTINUOUS_WALK node="+session.continuous.consumed()
                : session.plan != null && session.plan.moving()
                    ? "MOVING remaining=" + String.format(java.util.Locale.ROOT, "%.2f", session.plan.remaining())
                    : session.jumping ? "JUMPING/SETTLING" : "SETTLING";
        return name + " motion=" + state
                + (session!=null && session.mixed!=null
                    ? " mixedStage="+(session.mixed.consumed()+1)+"/"+session.mixed.count()
                        + " kind="+session.mixed.current().kind() : "")
                + " pos=" + fmt(avatar.getX()) + "," + fmt(avatar.getY()) + "," + fmt(avatar.getZ())
                + " grounded=" + avatar.onGround() + " horizontalCollision=" + avatar.horizontalCollision
                + " velocity=" + fmt(avatar.getDeltaMovement().length())
                + " locomotion=" + presentationPhase(avatar.getUUID());
    }

    /** Read-only action receipt, after C1 has completed its one authoritative physics tick. */
    record MotionReceipt(boolean active, String phase) {}

    /** P1-C correlation token: never equate a stale COMPLETE from another C1 session
     * with the completion of a current waypoint. Tokens never persist across restart.
     */
    record C2MotionTicket(UUID token, boolean active, String phase, boolean combatActive,
                          C2S4RecoveryPolicy.MotionOrigin origin) { }

    static C2MotionTicket c2Ticket(UUID profileId) {
        Session session = ACTIVE.get(profileId);
        if (session != null) return new C2MotionTicket(session.token, true, "ACTIVE",
                session.combatTicksRemaining > 0, session.origin);
        return new C2MotionTicket(LAST_SESSION_TOKEN.get(profileId), false,
                LAST_RESULT.getOrDefault(profileId,"UNKNOWN"), false,
                LAST_SESSION_ORIGIN.getOrDefault(profileId,C2S4RecoveryPolicy.MotionOrigin.NONE));
    }

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

    private static String presentationPhase(UUID npc) {
        C1LocomotionPresentation pose = PRESENTATIONS.get(npc);
        return pose == null ? "IDLE" : pose.phase().name();
    }

    /** Pose only: NEVER set the movement yaw, input vector or ticket here. */
    private static void updatePresentation(C1PlayerAvatar avatar, float targetYaw,
            double horizontalSpeed, boolean commanded, boolean corner, boolean interrupted) {
        C1LocomotionPresentation pose = PRESENTATIONS.computeIfAbsent(avatar.getUUID(),
                id -> new C1LocomotionPresentation(avatar.getYHeadRot(), avatar.yBodyRot));
        var result = pose.tick(avatar.getYRot(), targetYaw, horizontalSpeed,
                commanded, corner, interrupted);
        if (interrupted) return; // combat and knockback have priority over pose updates
        avatar.setYHeadRot(result.headYaw());
        avatar.setYBodyRot(result.bodyYaw());
        // Revision/token-fenced C1 sync with an upper limit of 10Hz. Vanilla
        // entity tracking remains in charge of physical yaw and position.
        if ((avatar.getServer().getTickCount() & 1) == 0)
            C1P0Runtime.syncRotationForActiveViewers(avatar);
    }

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
        ServerThread.require(server, "C1 motion tick");
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
            if(session.mixed!=null && !session.mixed.tick()) {
                if(session.continuous!=null) session.continuous.cancel();
                if(session.plan!=null) session.plan.cancel();
                session.completion="MIXED_TIMEOUT";
            }
            // C-3D: bounded in-place yaw alignment inside the SAME C1 token.
            // Do not snap 180 degrees or begin a vertical primitive while
            // the avatar is still facing the wrong direction. Before actually
            // moving/jumping, re-probe the live terrain AGAIN after turning.
            if(session.mixedTurning && session.combatTicksRemaining==0) {
                float next=C2F2Locomotion.approach(avatar.getYRot(),session.mixedDesiredYaw);
                avatar.setYRot(next);
                if(C2F2Locomotion.aligned(next,session.mixedDesiredYaw)) {
                    if(!armMixedStage(session)) {
                        session.mixedTurning=false;
                        if(session.plan!=null) session.plan.cancel();
                        session.completion="MIXED_UNSAFE_TURN";
                    }
                }
            }
            Vec3 previousPosition = avatar.position();
            float poseTargetYaw = avatar.getYRot();
            boolean poseCorner = false;
            // Exactly one motion owner per END-tick: combat > commanded > settle.
            // Never execute a commanded travel() in the same tick as an impulse.
            C1PlayerMotionPhase phase = C1PlayerMotionPhase.choose(
                    session.combatTicksRemaining, !session.mixedTurning
                            && !"MIXED_UNSAFE_TURN".equals(session.completion)
                            && ((session.plan != null && session.plan.moving())
                            || (session.continuous != null && session.continuous.moving())));
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
            } else if (phase == C1PlayerMotionPhase.COMMANDED && session.continuous != null) {
                // Continuous WALK is STILL this one Vanilla Player.travel owner.
                // No velocity reset at intermediate nodes; fail closed for
                // changed floor/corridor or unloaded chunks on each tick.
                C1F2ContinuousWalk.Intent intent=session.continuous.next(
                        avatar.getX(),avatar.getY(),avatar.getZ(),avatar.getYRot());
                if(intent!=null) {
                    // C-S3: turning in place has ZERO commanded forward travel.
                    // Do not fail the whole C1 ticket by probing .4 blocks into
                    // a wall before the avatar has actually requested motion.
                    // Clear residual horizontal inertia to avoid unproven coast.
                    if(!C1F2PreflightPolicy.requiresForwardProbe(intent.forward())) {
                        stopHorizontalVelocity(avatar);
                        avatar.setYRot(intent.yaw());
                        avatar.travel(Vec3.ZERO);
                    } else {
                    // Probe the direction ACTUALLY driven by Vanilla forward
                    // input after yaw steering, not the straight ideal target
                    // vector: curved turns may otherwise cut a blocked corner.
                    double facing=Math.toRadians(intent.yaw());
                    double probeX=avatar.getX()-Math.sin(facing)*.4;
                    double probeZ=avatar.getZ()+Math.cos(facing)*.4;
                    BlockPos probe=BlockPos.containing(probeX,avatar.getY(),probeZ);
                    if(!avatar.serverLevel().hasChunkAt(probe)
                            || !avatar.serverLevel().getWorldBorder().isWithinBounds(probe)) {
                        session.continuous.blocked("CHUNK_UNLOADED");
                    } else {
                        C2TerrainObservation ground=C2NavigationSensor.landingAt(avatar,
                                Mth.floor(probeX),Mth.floor(avatar.getY()-.04),Mth.floor(probeZ));
                        if(ground==null || (ground.risk()==C2TerrainObservation.Risk.HAZARD
                                || ground.risk()==C2TerrainObservation.Risk.UNKNOWN)
                                || Math.abs(ground.cell().footY()-avatar.getY())>.25
                                || (!C2NavGrid.plannable(ground.cell().condition())
                                    && !(ground.cell().condition()==C2NavGrid.Condition.PARTIAL_SUPPORT
                                         && ground.supportedFraction()>=.5))
                                || !C2WaypointBridge.sweptWalkClear(avatar,
                                    avatar.getX(),avatar.getZ(),probeX,avatar.getY(),probeZ))
                            session.continuous.blocked("BLOCKED");
                    }
                    if(session.continuous.moving()) {
                        // Only movement yaw steers C1 Player.travel. Head/body
                        // presentation is applied *after* the owned physics tick.
                        avatar.setYRot(intent.yaw());
                        poseTargetYaw = (float) Math.toDegrees(Math.atan2(
                                -(intent.targetX()-avatar.getX()),
                                intent.targetZ()-avatar.getZ()));
                        poseCorner = Math.abs(C1LocomotionPresentation.difference(
                                avatar.getYRot(), poseTargetYaw)) > 20;
                        avatar.travel(new Vec3(0,0,intent.forward()));
                    }
                    } // Nonzero forward input: live collision/hazard preflight.
                }
                if(!session.continuous.moving()) {
                    session.completion=session.continuous.terminal();
                    if(C1CombatTickPolicy.canClearHorizontalVelocity(session.combatTicksRemaining))
                        stopHorizontalVelocity(avatar);
                }
            } else if (phase == C1PlayerMotionPhase.COMMANDED) {
                // Check the next position without generating / loading chunks.
                BlockPos ahead = BlockPos.containing(avatar.getX() + session.plan.axisX,
                        avatar.getY(), avatar.getZ() + session.plan.axisZ);
                if (!avatar.serverLevel().hasChunkAt(ahead)
                        || (session.traversal != null && !loadedTraversalNeighborhood(avatar,ahead))) {
                    finishIter(server, session, "CHUNK_UNLOADED", it);
                    continue;
                }
                // Forward motion is applied in player-local axes. Player.travel
                // performs Vanilla friction, gravity and collision resolution.
                avatar.travel(new Vec3(session.plan.direction.strafe, 0, session.plan.direction.forward));
                session.plan.observe(avatar.getX(), avatar.getZ());
                if (session.plan.terminal()) {
                    if (session.traversal == null || session.completion.isEmpty())
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
            // Account for *all* authoritative physics phases. While commanded
            // horizontal input is active, a jump can leave and re-enter ground.
            // Never infer jumping solely from the motion-plan completion.
            if (session.traversal != null) {
                session.traversal.onTick(avatar.getX(),avatar.getY(),avatar.getZ(),
                        avatar.onGround(), session.combatTicksRemaining > 0);
            }
            // Vanilla ServerPlayer.checkFallDamage is intentionally empty:
            // a real client's movement packets call doCheckFallDamage instead.
            // Our synthetic player has no client movement packets, so call the
            // SAME vanilla method once after each authoritative travel step.
            // Never call it outside this single motion controller.
            Vec3 displacement = avatar.position().subtract(previousPosition);
            updatePresentation(avatar, poseTargetYaw,
                    Math.hypot(displacement.x, displacement.z),
                    phase == C1PlayerMotionPhase.COMMANDED,
                    poseCorner, phase == C1PlayerMotionPhase.COMBAT);
            fallChecks.add(new FallStep(avatar, displacement, avatar.onGround()));
            if (session.ticks % SAVE_INTERVAL == 0) C1P0Runtime.persistPosition(server, avatar);
            if (session.combatTicksRemaining > 0) {
                // Never finish, clear horizontal speed, or resume command input
                // while the Vanilla impulse is still moving the actor.
            } else if (session.mixed!=null) {
                // Operator unfollow/cancel must retain the EXACT terminal
                // CANCELLED contract. Never replan a cancelled mixed lease.
                if("CANCELLED".equals(session.completion)) {
                    if(avatar.onGround()) finishIter(server,session,"CANCELLED",it);
                    continue;
                }
                // Same C1 owner/token across 3D modes. Each grounded physical
                // receipt is checked before arming the next verified stage.
                boolean terminalWalk=session.continuous!=null && !session.continuous.moving();
                boolean terminal3D=session.traversal!=null && session.plan!=null
                        && session.plan.terminal() && avatar.onGround();
                if((terminalWalk || terminal3D) && avatar.onGround()) {
                    String verdict=terminalWalk ? session.continuous.terminal()
                            : session.traversal.verify(avatar.getX(),avatar.getY(),avatar.getZ(),
                                    true,session.plan.phase.name());
                    if(terminal3D && verdict.startsWith("TRAVERSAL_COMPLETE_")
                            && !landingStillSafe(avatar,session.traversal))
                        verdict="TRAVERSAL_LANDING_CHANGED_OR_UNSAFE";
                    boolean verified=terminalWalk ? "COMPLETE".equals(verdict)
                            : verdict.equals("TRAVERSAL_COMPLETE_"+session.mixed.current().kind());
                    if(!verified) finishIter(server,session,"MIXED_"+verdict,it);
                    else if(!session.mixed.advance()) finishIter(server,session,"MIXED_COMPLETE",it);
                    else if(!armMixedStage(session)) finishIter(server,session,"MIXED_UNSAFE_NEXT_STAGE",it);
                    // No second travel() this tick. Next stage resumes on the
                    // next normal C1 END-tick, retaining the same token.
                } else if(session.mixed.stageAge()>=C1S2TraversalPlan.MAX_SESSION_TICKS) {
                    if(session.plan!=null) session.plan.cancel();
                    if(session.continuous!=null) session.continuous.cancel();
                    if(avatar.onGround()) finishIter(server,session,"MIXED_STAGE_TIMEOUT",it);
                } else if(session.ticks>C1Persistent3DChain.MAX_TOTAL_TICKS && avatar.onGround())
                    finishIter(server,session,"MIXED_TIMEOUT",it);
            } else if (session.traversal != null && session.plan != null
                    && session.plan.terminal() && avatar.onGround()) {
                String verdict = "CANCELLED".equals(session.completion) ? "CANCELLED"
                        : "TRAVERSAL_TIMEOUT".equals(session.completion) ? "TRAVERSAL_TIMEOUT"
                        : session.traversal.verify(avatar.getX(),avatar.getY(),avatar.getZ(),
                        avatar.onGround(),session.plan.phase.name());
                if (verdict.startsWith("TRAVERSAL_COMPLETE_") && !landingStillSafe(avatar,session.traversal))
                    verdict = "TRAVERSAL_LANDING_CHANGED_OR_UNSAFE";
                finishIter(server,session,verdict,it);
            } else if (session.traversal != null && session.ticks >= C1S2TraversalPlan.MAX_SESSION_TICKS) {
                if (session.plan != null) session.plan.cancel();
                session.completion = "TRAVERSAL_TIMEOUT";
                stopHorizontalVelocity(avatar);
                // Continue Vanilla gravity until safe landing, not an airborne
                // orphan after the horizontal motion budget expires.
                if (avatar.onGround()) finishIter(server,session,"TRAVERSAL_TIMEOUT",it);
            } else if (session.traversal == null && session.jumping && session.leftGround && avatar.onGround()) {
                finishIter(server, session, "JUMP_COMPLETE", it);
            } else if (session.continuous!=null && !session.continuous.moving() && avatar.onGround()) {
                finishIter(server,session,session.completion,it);
            } else if (session.plan != null && session.plan.terminal() && avatar.onGround()) {
                finishIter(server, session, session.completion, it);
            } else if (session.plan == null && !session.jumping && avatar.onGround()
                    && session.settleTicks >= 2) {
                finishIter(server, session, session.completion.isEmpty() ? "SETTLED" : session.completion, it);
            } else if (session.settleTicks >= C1P1MotionPlan.MAX_SETTLE_TICKS
                    || (session.continuous==null &&
                        session.ticks >= C1P1MotionPlan.MAX_TICKS + C1P1MotionPlan.MAX_SETTLE_TICKS)) {
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
            // The real movement-packet handler also feeds walk/sprint/jump statistics and exhaustion (hunger parity).
            avatar.checkMovementStatistics(d.x, d.y, d.z);
        }
    }

    /** No chunk loads during a bounded traversal, even if the AABB straddles
     * a border. Neighbor checks only run for an active operator primitive.
     */
    private static boolean loadedTraversalNeighborhood(C1PlayerAvatar avatar,BlockPos center) {
        for (int dx=-1; dx<=1; dx++) for (int dz=-1; dz<=1; dz++)
            if (!avatar.serverLevel().hasChunkAt(center.offset(dx,0,dz))) return false;
        return true;
    }

    /** TOCTOU defense: a player may break/replace landing blocks after the
     * preflight. A successful motion receipt requires loaded/safe terrain NOW.
     */
    private static boolean landingStillSafe(C1PlayerAvatar avatar,C1S2TraversalPlan traversal) {
        int x=net.minecraft.util.Mth.floor(traversal.targetX);
        int z=net.minecraft.util.Mth.floor(traversal.targetZ);
        int y=net.minecraft.util.Mth.floor(traversal.targetY-0.04);
        C2TerrainObservation landing=C2NavigationSensor.landingAt(avatar,x,y,z);
        if (landing==null || (landing.risk()==C2TerrainObservation.Risk.HAZARD
                || landing.risk()==C2TerrainObservation.Risk.UNKNOWN)
                || Math.abs(landing.cell().footY()-traversal.targetY) > C1S2TraversalPlan.HEIGHT_TOLERANCE)
            return false;
        if (C2NavGrid.plannable(landing.cell().condition())) return true;
        return landing.cell().condition()==C2NavGrid.Condition.PARTIAL_SUPPORT
                && landing.supportedFraction()>=0.5;
    }

    private static void finishIter(MinecraftServer server, Session session, String result,
                                   Iterator<Map.Entry<UUID, Session>> iterator) {
        if (C1CombatTickPolicy.canClearHorizontalVelocity(session.combatTicksRemaining))
            stopHorizontalVelocity(session.avatar);
        C1P0Runtime.persistPosition(server, session.avatar);
        session.combatVelocity.clear();
        LAST_RESULT.put(session.avatar.getUUID(), result);
        LAST_SESSION_TOKEN.put(session.avatar.getUUID(), session.token);
        LAST_SESSION_ORIGIN.put(session.avatar.getUUID(), session.origin);
        if(session.continuous!=null)
            LOG.info("NPCortex C2-F2FIX2 WALK_TELEMETRY npc={} token={} result={} {}",
                    session.avatar.getUUID(),session.token,result,session.continuous.metrics());
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
        LAST_SESSION_TOKEN.put(session.avatar.getUUID(), session.token);
        LAST_SESSION_ORIGIN.put(session.avatar.getUUID(), session.origin);
        if(session.continuous!=null)
            LOG.info("NPCortex C2-F2FIX2 WALK_TELEMETRY npc={} token={} result={} {}",
                    session.avatar.getUUID(),session.token,
                    session.completion.isEmpty() ? "CANCELLED" : session.completion,
                    session.continuous.metrics());
    }

    static void beforeDespawn(MinecraftServer server, C1PlayerAvatar avatar) {
        if (avatar == null) return;
        ServerThread.require(server, "C1 despawn cleanup");
        ACTIVE.remove(avatar.getUUID());
        LAST_RESULT.remove(avatar.getUUID());
        LAST_SESSION_TOKEN.remove(avatar.getUUID());
        LAST_SESSION_ORIGIN.remove(avatar.getUUID());
        LAST_EXTERNAL_IMPULSE.remove(avatar.getUUID());
        PRESENTATIONS.remove(avatar.getUUID());
        C1P0Runtime.persistPosition(server, avatar);
    }

    static void beforeServerStop(MinecraftServer server) {
        for (Session session : ACTIVE.values()) {
            C1P0Runtime.persistPosition(server, session.avatar);
        }
        ACTIVE.clear();
        LAST_RESULT.clear();
        LAST_SESSION_TOKEN.clear();
        LAST_SESSION_ORIGIN.clear();
        LAST_EXTERNAL_IMPULSE.clear();
        PRESENTATIONS.clear();
    }
}
