package io.github.sirp00m.npcortex;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.TickEvent;
import org.slf4j.Logger;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** C2-P0 event-driven orchestration of C0 and the existing C1 Player motion owner.
 * ALL methods and state changes except HTTP completion occur on the server thread.
 * No action/AI logic is allowed inside HTTP callbacks before server.execute().
 * One bounded in-flight goal per actor; no autonomous background goal creation.
 */
final class C2AgentRuntime {
    private static final Logger LOG = LogUtils.getLogger();
    private static final C2Scheduler<Run> ACTIVE = new C2Scheduler<>(10);
    private static final C2Events EVENTS = new C2Events();
    private static final int MAX_ACTORS = 10;
    private static final int MOVE_TIMEOUT_TICKS = 380;
    private static final int ROUTE_TIMEOUT_TICKS = 2400;
    // Throttle the heavy region planner globally to one C2 route replacement
    // attempt per server END tick, even with 10 NPCs reaching obstacles together.
    private static long serverTicks;
    private static long lastPlannerTick = -1;

    // G3 current-process-only C0+C1+C2 terminal witness. Never rebuild from
    // SavedData text after restart; old HOLD remains fenced for manual audit.
    record TerminalWitness(UUID actor, UUID goal, UUID action, UUID world, String dimension,
            double x, double y, double z, String c1Phase, boolean c0Verified) {}
    private static final java.util.Map<UUID,TerminalWitness> VERIFIED_WITNESSES = new java.util.HashMap<>();
    static TerminalWitness terminalWitness(UUID actor,UUID goal) {
        TerminalWitness witness=VERIFIED_WITNESSES.get(actor);
        return witness!=null && witness.goal().equals(goal) ? witness : null;
    }
    static final class Run {
        final MinecraftServer server;
        final String name;
        final UUID world, actor, profile, goal;
        final String dimension;
        final double tx, ty, tz, tolerance;
        C2WaypointExecution route;
        final C2ReplanningPolicy replanning = new C2ReplanningPolicy();
        UUID action;
        int routeTicks;
        int recoveryWaitTicks;
        int stableGroundTicks;
        boolean recoveryFrom3D;
        UUID replanningEntryToken;
        UUID passiveMotionToken;
        String phase = "REQUESTED";
        int ticks;
        boolean moved;
        Run(MinecraftServer server, C1IdentityData.Entry id, UUID world, double tx, double ty, double tz, double tolerance, C2WaypointExecution route) {
            this.route=route;
            this.server = server;
            this.name = id.character();
            this.world = world;
            this.actor = id.actorId();
            this.profile = id.profileId();
            this.dimension = id.dimension();
            this.goal = UUID.randomUUID();
            this.tx = tx; this.ty = ty; this.tz = tz; this.tolerance = tolerance;
        }
        C1PlayerAvatar avatar() {
            C1PlayerAvatar avatar = C1P0Runtime.activeAvatar(server, name);
            return avatar != null && avatar.getUUID().equals(profile) && !avatar.isRemoved()
                    && avatar.serverLevel().dimension().location().toString().equals(dimension)
                    ? avatar : null;
        }
        boolean isAlive() {
            return avatar() != null && C1P1CLifecycleRuntime.mayRestore(server, profile);
        }
    }

    private C2AgentRuntime() {}

    /** Exposed only to journal recovery to avoid reconciling an active C0 owner. */
    static boolean hasActiveActor(UUID actor) { return ACTIVE.containsKey(actor); }
    static boolean hasActiveGoal(UUID actor, UUID goal) {
        Run current=ACTIVE.get(actor);
        return current!=null && current.goal.equals(goal);
    }

    static String start(MinecraftServer server, String name, double x, double y, double z) {
        return start(server,name,x,y,z,C2MoveGeometry.DEFAULT_TOLERANCE);
    }

    // G2 typed admission: never infer success from a human-readable message.
    // The existing navigate() is the single authoritative planner/owner; this
    // wrapper observes its *new* C2 scheduler-owned run on the server thread.
    record NavigationAdmission(boolean accepted, UUID goalId, String detail) {}
    static NavigationAdmission guardedNavigate(MinecraftServer server,String name,int x,double feetY,int z) {
        C1IdentityData.Entry id=C1IdentityData.get(server).find(name);
        UUID before=null;
        if(id!=null) {
            Run existing=ACTIVE.get(id.actorId());
            if(existing!=null)before=existing.goal;
        }
        String result=navigate(server,name,x,feetY,z);
        Run live=id==null?null:ACTIVE.get(id.actorId());
        boolean accepted=live!=null && !live.goal.equals(before)
                && live.route!=null && live.world.equals(C1IdentityData.get(server).worldId())
                && live.name.equals(name);
        return new NavigationAdmission(accepted,accepted?live.goal:null,result);
    }

    static String navigate(MinecraftServer server,String name,int x,double feetY,int z) {
        if (server==null || !server.isSameThread()) return "P1-C navigation requires server thread";
        C1IdentityData.Entry id=C1IdentityData.get(server).find(name);
        if (id==null) return "Unknown NPC: "+name;
        if (ACTIVE.containsKey(id.actorId())) return "C2 actor busy: "+name;
        if (C2FollowRuntime.hasActiveActor(id.actorId())) return "C2-F1 follow owns NPC; use c2 unfollow first";
        C1PlayerAvatar avatar=C1P0Runtime.activeAvatar(server,name);
        if (avatar==null || !C1P1CLifecycleRuntime.mayRestore(server,id.profileId())
                || !avatar.getUUID().equals(id.profileId())
                || !avatar.serverLevel().dimension().location().toString().equals(id.dimension()))
            return "P1-C actor not live/alive in registered dimension";
        if (!avatar.onGround() || C1P1MotionRuntime.c2Receipt(id.profileId()).active())
            return "P1-C NPC not grounded or movement busy";
        if (!Double.isFinite(feetY) || feetY<Integer.MIN_VALUE+2.0 || feetY>Integer.MAX_VALUE-2.0)
            return "S3 target footY invalid";
        if (!avatar.serverLevel().hasChunkAt(new BlockPos(x,C2S3SupportY.at(feetY),z))) return "P1-C target chunk not loaded";
        C2WaypointExecution route;
        C2HierarchicalPlanner.Result planned;
        try {
            var capture=C2GraphWorldProbe.captureWithSnapshot(avatar,C2NavGrid.MAX_RADIUS);
            var start=new C2AStarPlanner.Target(net.minecraft.util.Mth.floor(avatar.getX()),
                    C2S3SupportY.at(avatar.getY()),net.minecraft.util.Mth.floor(avatar.getZ()));
            planned=C2HierarchicalPlanner.plan(capture.graph(),capture.snapshot(),start,
                    new C2AStarPlanner.Target(x,C2S3SupportY.at(feetY),z),C2RegionGraph.DEFAULT_TILE_SIZE,
                    C2HierarchicalPlanner.DEFAULT_BUDGET);
            if (!planned.found()) return "P1-C planning rejected: "+planned.status();
            route=C2WaypointExecution.navigation3D(planned.path());
        } catch (RuntimeException ex) { return "P1-C navigation rejected: "+ex.getMessage(); }
        var target=route.path().get(route.path().size()-1);
        if (Math.abs(target.footY()-feetY)>C1S2TraversalPlan.HEIGHT_TOLERANCE)
            return "S3 target footY does not match observed terrain surface";
        double tx=target.x()+0.5, ty=target.footY(), tz=target.z()+0.5;
        return startInternal(server,name,tx,ty,tz,C2MoveGeometry.DEFAULT_TOLERANCE,route);
    }

    static String start(MinecraftServer server, String name, double x, double y, double z, double tolerance) {
        return startInternal(server,name,x,y,z,tolerance,null);
    }

    private static String startInternal(MinecraftServer server, String name, double x,double y,double z,
                                        double tolerance,C2WaypointExecution route) {
        try { C2MoveGeometry.validatedTolerance(tolerance); }
        catch (IllegalArgumentException ex) { return ex.getMessage(); }
        if (!C1IdentityData.validName(name)) return "Invalid NPC character name";
        C1IdentityData ids = C1IdentityData.get(server);
        C1IdentityData.Entry id = ids.find(name);
        if (id == null) return "Unknown NPC: " + name;
        if (ACTIVE.containsKey(id.actorId())) return "C2 actor busy: " + name;
        if (C2FollowRuntime.hasActiveActor(id.actorId())) return "C2-F1 follow owns NPC; use c2 unfollow first";
        if (ACTIVE.size() >= MAX_ACTORS) return "C2 max 10 concurrent actors";
        C2ActionJournal.Row prior = C2ActionJournal.get(server).find(id.actorId());
        if (prior != null && prior.uncertain())
            return "Unresolved C2 journal: " + prior.state() +
                    ("CANCEL_PENDING".equals(prior.state()) ? ". Automatic backend reconciliation pending; retry shortly" :
                     ". Inspect C0 then /npcortex c2 reset " + name);
        C1PlayerAvatar avatar = C1P0Runtime.activeAvatar(server,name);
        if (avatar == null) return "NPC not live/alive: " + name;
        if (!avatar.onGround()) return "NPC must be grounded";
        if (!(Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)))
            return "Non-finite movement target";
        if (C2MoveGeometry.reached(avatar.getX(),avatar.getY(),avatar.getZ(),x,y,z,tolerance))
            return "Already within C2 goal tolerance (" + tolerance + " blocks); no movement needed";
        if (route==null) {
            try { C2MoveGeometry.plan(avatar.getX(),avatar.getY(),avatar.getZ(),x,y,z); }
            catch (IllegalArgumentException ex) { return ex.getMessage(); }
        }
        if (!avatar.serverLevel().hasChunkAt(BlockPos.containing(x,y,z))) return "Target chunk not loaded";
        if (C1P1MotionRuntime.c2Receipt(id.profileId()).active()) return "C1 movement busy";
        Run run = new Run(server,id,ids.worldId(),x,y,z,tolerance,route);
        if (!ACTIVE.claim(run.actor,run)) return "C2 scheduler rejected request";
        VERIFIED_WITNESSES.remove(run.actor); // never use evidence from an older goal
        EVENTS.publish(C2Events.Kind.REQUESTED,run.actor,run.goal,null,"move_to");
        write(run, "REQUESTED", "Waiting for C0 identity registration");
        JsonObject world = new JsonObject();
        world.addProperty("world_id",run.world.toString());
        world.addProperty("name","NPCortex-C1-" + run.world);
        send(run,"/v1/worlds",world,10,reply -> registerActor(run));
        return (route==null ? "C2 goal queued for " : "P1-C waypoint route queued for ") + name + " goal=" + run.goal + " target=" + x + "," + y + "," + z + " tolerance=" + tolerance;
    }

    static String status(MinecraftServer server, String name) {
        C1IdentityData.Entry id = C1IdentityData.get(server).find(name);
        if (id == null) return "Unknown NPC: " + name;
        Run current = ACTIVE.get(id.actorId());
        if (current != null) return name + " C2=" + current.phase + " goal=" + current.goal
                + " action=" + current.action
                + (current.route==null ? "" : " waypoint="+current.route.currentIndex()+"/"+current.route.totalSegments()
                    + " generation="+current.replanning.generation()+" replans="+current.replanning.attempts())
                + ("REPLANNING".equals(current.phase)
                    ? " recoveryWait="+current.recoveryWaitTicks+"/"+C2S4RecoveryPolicy.MAX_SETTLE_WAIT_TICKS
                        +" stableGround="+current.stableGroundTicks+"/"+C2S4RecoveryPolicy.REQUIRED_STABLE_GROUND_TICKS
                    : "")
                + " | C1=" + C1P1MotionRuntime.c2Receipt(current.profile).phase();
        C2ActionJournal.Row row = C2ActionJournal.get(server).find(id.actorId());
        return row == null ? name + " C2=NONE" : name + " C2=" + row.state() + " goal=" + row.goal()
                + " action=" + row.action() + " reason=" + row.message();
    }

    static String reset(MinecraftServer server, String name) {
        C1IdentityData.Entry id = C1IdentityData.get(server).find(name);
        if (id == null) return "Unknown NPC: " + name;
        if (C2FollowRuntime.hasActiveActor(id.actorId())) return "Active C2-F1 follow; use c2 unfollow first";
        if (ACTIVE.containsKey(id.actorId())) return "Active C2 goal; use c2 cancel first";
        if (C1P1MotionRuntime.c2Receipt(id.profileId()).active()) return "C1 movement still active";
        C2ActionJournal.Row pending=C2ActionJournal.get(server).find(id.actorId());
        if (pending!=null && "CANCEL_PENDING".equals(pending.state()))
            return "C2 cancellation is reconciling with Backend; manual reset would bypass safety";
        C2ActionJournal.get(server).reset(id.actorId());
        return "C2 journal reset (does NOT resolve any pending C0 backend action). Inspect old goal before retry.";
    }

    /** G3: cancel ONLY this C2 goal; never fall through to Follow/unfollow. */
    static boolean cancelGuardedGoal(MinecraftServer server,String name,UUID expectedGoal) {
        if(!server.isSameThread() || expectedGoal==null)return false;
        C1IdentityData.Entry id=C1IdentityData.get(server).find(name);
        if(id==null)return false;
        Run current=ACTIVE.get(id.actorId());
        if(current==null || !current.goal.equals(expectedGoal))return false;
        cancel(server,name);
        return true; // backend release still requires C0 terminal reconciliation
    }

    static String cancel(MinecraftServer server, String name) {
        ServerThread.require(server, "C2 cancel");
        C1IdentityData.Entry id = C1IdentityData.get(server).find(name);
        if (id == null) return "Unknown NPC: " + name;
        Run run = ACTIVE.get(id.actorId());
        if (run == null) return C2FollowRuntime.hasActiveActor(id.actorId())
                ? C2FollowRuntime.unfollow(server,name) : "No active C2 goal";
        if (run.moved && run.avatar() != null) C1P1MotionRuntime.stop(server,name);
        run.replanning.stop();
        // The earliest registration steps cannot have created a C0 goal yet:
        // callbacks are fenced by ACTIVE ownership and will never create one.
        C2ActionJournal.Row old=C2ActionJournal.get(server).find(id.actorId());
        boolean beforeGoal=old!=null && "HTTP_PENDING".equals(old.state())
                && ("/v1/worlds".equals(old.message()) || "/v1/actors".equals(old.message()));
        if (!ACTIVE.release(run.actor,run)) return "C2 cancellation raced with completion; inspect status";
        if (beforeGoal) {
            write(run,"CANCELLED","Cancelled before backend goal registration");
            return "C2 cancelled before goal creation; ready for new task";
        }
        C2CancellationRecovery.begin(run,"Operator cancellation; waiting for C0 terminal acknowledgement");
        EVENTS.publish(C2Events.Kind.INTERRUPTED,run.actor,run.goal,run.action,"operator cancellation pending reconciliation");
        return "C2 motion cancelled; backend reconciliation pending (no manual reset needed)";
    }

    private static void registerActor(Run run) {
        JsonObject actor = new JsonObject();
        actor.addProperty("world_id",run.world.toString());
        actor.addProperty("dimension_id",run.dimension);
        actor.addProperty("actor_id",run.actor.toString());
        actor.addProperty("character_id",run.name.toLowerCase(java.util.Locale.ROOT));
        send(run,"/v1/actors",actor,10,reply -> createGoal(run));
    }

    private static void createGoal(Run run) {
        if (!run.isAlive()) { interrupt(run,"NPC unavailable during registration"); return; }
        EVENTS.publish(C2Events.Kind.REGISTERED,run.actor,run.goal,null,"world/actor registered");
        JsonObject goal = new JsonObject();
        goal.addProperty("world_id",run.world.toString());
        goal.addProperty("dimension_id",run.dimension);
        goal.addProperty("actor_id",run.actor.toString());
        goal.addProperty("goal_id",run.goal.toString());
        goal.addProperty("objective","Move to user-authorized world coordinates; use move_to exactly once");
        goal.addProperty("max_decisions",1);
        JsonObject criterion = new JsonObject();
        criterion.addProperty("kind","position_near");
        criterion.add("position",coords(run.tx,run.ty,run.tz));
        criterion.addProperty("distance",run.tolerance);
        JsonArray criteria = new JsonArray(); criteria.add(criterion);
        goal.add("success_criteria",criteria);
        send(run,"/v1/agent/goals",goal,10,reply -> observe(run));
    }

    private static void observe(Run run) {
        if (!run.isAlive()) { interrupt(run,"NPC unavailable before observation"); return; }
        JsonObject payload = new JsonObject();
        payload.addProperty("observation_id",UUID.randomUUID().toString());
        payload.add("snapshot",C2WorldSensor.snapshot(run));
        send(run,"/v1/agent/goals/"+run.goal+"/observe",payload,10,reply -> {
            EVENTS.publish(C2Events.Kind.OBSERVED,run.actor,run.goal,null,"trusted server snapshot");
            JsonObject goal = reply.getAsJsonObject("goal");
            if (goal == null || !goal.has("status")) { interrupt(run,"Malformed C0 observation reply"); return; }
            if ("completed".equals(goal.get("status").getAsString())) {
                finish(run,true,"Already observed at destination"); return;
            }
            decide(run);
        });
    }

    private static void decide(Run run) {
        if (!run.isAlive()) { interrupt(run,"NPC unavailable before AI decision"); return; }
        JsonObject request = new JsonObject();
        request.addProperty("request_id",UUID.randomUUID().toString());
        send(run,"/v1/agent/goals/"+run.goal+"/decide",request,95,reply -> {
            if (!run.isAlive()) { interrupt(run,"NPC unavailable after Qwen decision"); return; }
            JsonObject decision = reply.getAsJsonObject("decision");
            if (decision != null && decision.has("action")
                    && "blocked".equals(decision.get("action").getAsString())
                    && reply.has("action_id") && reply.get("action_id").isJsonNull()) {
                fail(run,"Qwen reports no feasible action"); return;
            }
            if (decision == null || !decision.has("action") || !"use_skill".equals(decision.get("action").getAsString())
                    || !reply.has("action_id") || reply.get("action_id").isJsonNull()) {
                interrupt(run,"Ambiguous C0 decision reply; reconciliation required"); return;
            }
            final UUID actionId;
            try { actionId = UUID.fromString(reply.get("action_id").getAsString()); }
            catch (RuntimeException ex) { interrupt(run,"Invalid C0 action id"); return; }
            run.action = actionId;
            EVENTS.publish(C2Events.Kind.DECIDED,run.actor,run.goal,run.action,"model selected action");
            write(run,"DECIDED","C0 action pending");
            String skill = decision.get("skill_id").getAsString();
            C2SkillRegistry.Executor executor = C2SkillRegistry.get(skill);
            if (executor == null || !decision.has("args") || !decision.get("args").isJsonObject()) {
                result(run,false,"Unadvertised or malformed executor action"); return;
            }
            // Preconditions are checked on this same thread, immediately before C1 execution.
            String error = executor.start(run,decision.getAsJsonObject("args"));
            if (error != null) {
                // A live-world edge may have changed AFTER the initial B3 snapshot.
                // This still owns the SAME C0 action and never creates a second one.
                if (run.route!=null && C2WaypointBridge.recoverableFailure(error)) {
                    run.moved=true;
                    if (C2S4RecoveryPolicy.recoverableStartRefusal(error)) run.recoveryFrom3D=true;
                    scheduleReplanning(run,C2ReplanningPolicy.Trigger.EDGE_INVALIDATED,error);
                } else result(run,false,error);
                return;
            }
            run.moved = true;
            run.ticks = 0;
            write(run,"MOVING",run.route==null ? "C1 owns Vanilla motion" :
                    "P1-C segment="+run.route.currentIndex()+" ticket="+run.route.expectedTicket());
            EVENTS.publish(C2Events.Kind.ACTION_STARTED,run.actor,run.goal,run.action,skill);
        });
    }

    /** Called after C1 motion & lifecycle ticks at END. No extra physics tick. */
    static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        serverTicks++;
        C2CancellationRecovery.onServerTick(event.getServer());
        if (ACTIVE.isEmpty()) return;
        for (Run run : java.util.List.copyOf(ACTIVE.values())) {
            if (!"MOVING".equals(run.phase) && !"REPLANNING".equals(run.phase)) continue;
            if (!run.isAlive()) { interrupt(run,"NPC died, despawned, or changed lifecycle/dimension"); continue; }
            run.ticks++;
            if ("REPLANNING".equals(run.phase)) {
                tickReplanning(run);
                continue;
            }
            if (run.route!=null) {
                tickRoute(run);
                continue;
            }
            C1P1MotionRuntime.MotionReceipt receipt = C1P1MotionRuntime.c2Receipt(run.profile);
            if (receipt.active() && run.ticks < MOVE_TIMEOUT_TICKS) continue;
            if (receipt.active()) {
                C1P1MotionRuntime.stop(run.server,run.name);
                interrupt(run,"C1 movement watchdog expired");
                continue;
            }
            boolean reached = C2ActionVerifier.localMovement(run,receipt);
            result(run,reached,"C1 motion="+receipt.phase());
        }
    }

    private static void tickRoute(Run run) {
        run.routeTicks++;
        if (run.routeTicks > ROUTE_TIMEOUT_TICKS || run.ticks > MOVE_TIMEOUT_TICKS) {
            if (run.avatar()!=null) C1P1MotionRuntime.stop(run.server,run.name);
            interrupt(run,"P1-C route/segment timeout; operator reconciliation required");
            return;
        }
        var ticket=C1P1MotionRuntime.c2Ticket(run.profile);
        if (ticket.combatActive()) {
            // The C1 owner handles combat impulse; P1-C never writes velocity.
            return;
        }
        var to=run.route.receiptTarget();
        var avatar=run.avatar();
        boolean reached=avatar!=null && C2MoveGeometry.near(avatar.getX(),avatar.getY(),avatar.getZ(),
                to.x()+0.5,to.footY(),to.z()+0.5,C2WaypointExecution.WAYPOINT_TOLERANCE);
        boolean ticketMatches=run.route.acceptsTicket(ticket.token());
        // Centering is a flat C1 ticket, even when its *next* edge is vertical.
        var receiptKind=run.route.centering() ? C2TraversabilityGraph.MoveKind.WALK
                : run.route.currentKind();
        var updated=run.route.observe(ticket.token(),ticket.active(),ticket.phase(),reached);
        if (updated==C2WaypointExecution.Update.ACTIVE) return;
        if (updated==C2WaypointExecution.Update.FAILED) {
            C2S4RecoveryPolicy.Verdict verdict=C2S4RecoveryPolicy.classify(
                    receiptKind,ticketMatches,ticket.active(),ticket.phase(),reached);
            if (verdict==C2S4RecoveryPolicy.Verdict.RETRY_3D) {
                run.recoveryFrom3D=true;
                scheduleReplanning(run,"TRAVERSAL_INTERRUPTED_COMBAT".equals(ticket.phase())
                                ? C2ReplanningPolicy.Trigger.COMBAT_DISPLACED
                                : C2ReplanningPolicy.Trigger.VERTICAL_TERMINAL,
                        "S4 C1 3D terminal="+ticket.phase()+" waypoint="+run.route.currentIndex());
            } else if (verdict==C2S4RecoveryPolicy.Verdict.RETRY_FLAT) {
                scheduleReplanning(run,C2ReplanningPolicy.Trigger.BLOCKED,
                        "C1 terminal="+ticket.phase()+" waypoint="+run.route.currentIndex());
            } else if (verdict==C2S4RecoveryPolicy.Verdict.FOREIGN_TICKET) {
                // Unmatched token never grants a route replacement or cancels
                // a foreign C1 owner.
                interrupt(run,"P1-D foreign/stale motion ticket; operator reconciliation required");
            } else {
                result(run,false,"S4 non-recoverable C1 segment terminal="+ticket.phase());
            }
            return;
        }
        if (updated==C2WaypointExecution.Update.COMPLETE) {
            boolean finalReached=C2MoveGeometry.near(avatar.getX(),avatar.getY(),avatar.getZ(),
                    run.tx,run.ty,run.tz,run.tolerance);
            result(run,finalReached,finalReached?"P1-C all waypoints verified":"P1-C final goal tolerance not met");
            return;
        }
        String error;
        try { error=C2WaypointBridge.beginSegment(run); }
        catch (RuntimeException ex) {
            LOG.warn("NPCortex P1-C fresh edge validation failed goal={}",run.goal,ex);
            error="P1-C edge validation error; stopped without replanning";
        }
        if (error!=null) {
            if (C2WaypointBridge.recoverableFailure(error)) {
                if (C2S4RecoveryPolicy.recoverableStartRefusal(error)) run.recoveryFrom3D=true;
                scheduleReplanning(run, C2ReplanningPolicy.Trigger.EDGE_INVALIDATED, error);
            }
            else result(run,false,error);
            return;
        }
        write(run,"MOVING","P1-D generation="+run.replanning.generation()+" segment="
                +run.route.currentIndex()+" ticket="+run.route.expectedTicket());
    }

    /** P1-D route replacement is synchronous, main-thread-only, and bounded.
     * Never changes the C0 goal/action ID and never calls Qwen for waypoints.
     */
    private static void scheduleReplanning(Run run,C2ReplanningPolicy.Trigger trigger,String why) {
        var avatar=run.avatar();
        if (avatar==null || !run.isAlive() || C1P1MotionRuntime.c2Receipt(run.profile).active()
                || run.route==null || run.action==null) {
            result(run,false,"P1-D cannot safely replan while C1 owns movement: "+why);
            return;
        }
        run.recoveryWaitTicks=0;
        run.stableGroundTicks=0;
        run.replanningEntryToken=C1P1MotionRuntime.c2Ticket(run.profile).token();
        run.passiveMotionToken=null;
        if (!run.replanning.request(trigger,run.routeTicks)) {
            result(run,false,"P1-D replan attempt/budget exhausted: "+why);
            return;
        }
        EVENTS.publish(C2Events.Kind.RECOVERY_WAIT,run.actor,run.goal,run.action,
                "S4 wait for grounded/stable C1 release: "+why);
        write(run,"REPLANNING","S4 g="+run.replanning.generation()+" reason="+trigger
                +" attempt="+(run.replanning.attempts()+1)+"/"+C2ReplanningPolicy.MAX_REPLANS);
        EVENTS.publish(C2Events.Kind.REPLAN_QUEUED,run.actor,run.goal,run.action,
                "P1-D generation="+run.replanning.generation()+" reason="+trigger);
        LOG.info("NPCortex P1-D replan queued goal={} actor={} generation={} trigger={}",
                run.goal,run.actor,run.replanning.generation(),trigger);
    }

    private static void tickReplanning(Run run) {
        run.routeTicks++;
        if (run.routeTicks > ROUTE_TIMEOUT_TICKS) {
            run.replanning.stop();
            interrupt(run,"P1-D route timeout while replanning"); return;
        }
        C1PlayerAvatar avatar=run.avatar();
        if (avatar==null || !run.isAlive()) {
            interrupt(run,"P1-D actor unavailable during replanning"); return;
        }
        var motion=C1P1MotionRuntime.c2Ticket(run.profile);
        // Classify the actual session origin, not merely motion.active().
        // C1's autonomous gravity / combat tick is an allowed PHYSICS owner,
        // never permission for C2 to command movement while it is still active.
        boolean tokenChanged=!java.util.Objects.equals(motion.token(),run.replanningEntryToken);
        var owner=C2S4RecoveryPolicy.replanOwnership(motion.active(),motion.combatActive(),
                motion.origin(),tokenChanged);
        if (owner==C2S4RecoveryPolicy.ReplanOwnership.FOREIGN_OWNER) {
            run.replanning.stop();
            interrupt(run,"S4 foreign C1 motion during replanning origin="+motion.origin()
                    +" token="+motion.token()+"; C0 reconciliation required");
            return;
        }
        // Deadline includes passive settling. Never wait indefinitely for gravity,
        // combat or unsupported loaded ground.
        run.recoveryWaitTicks++;
        if (owner==C2S4RecoveryPolicy.ReplanOwnership.WAIT_PASSIVE_PHYSICS) {
            if (!java.util.Objects.equals(run.passiveMotionToken,motion.token())) {
                run.passiveMotionToken=motion.token();
                LOG.info("NPCortex S4 recovery waits for passive C1 owner={} token={} goal={}",
                        motion.origin(),motion.token(),run.goal);
            }
            run.stableGroundTicks=0;
            if (run.recoveryWaitTicks>C2S4RecoveryPolicy.MAX_SETTLE_WAIT_TICKS) {
                run.replanning.stop();
                interrupt(run,"S4 passive physics exceeded recovery deadline; reconcile C0");
            }
            return;
        }
        // S4: the previous C1 owner must have ended, and we require several
        // consecutive server ticks on loaded, safe ground before any fresh plan.
        // The retry deadline is bounded independently from route timeout.
        boolean supported=false;
        boolean loaded=avatar.serverLevel().hasChunkAt(avatar.blockPosition());
        if (loaded && avatar.onGround()) {
            try {
                C2TerrainObservation standing=C2NavigationSensor.landingAt(avatar,
                        net.minecraft.util.Mth.floor(avatar.getX()),C2S3SupportY.at(avatar.getY()),
                        net.minecraft.util.Mth.floor(avatar.getZ()));
                supported=standing!=null && (standing.risk()==C2TerrainObservation.Risk.NONE
                        || standing.risk()==C2TerrainObservation.Risk.AVOIDABLE)
                        && Math.abs(standing.cell().footY()-avatar.getY())<=C1S2TraversalPlan.HEIGHT_TOLERANCE
                        && (C2NavGrid.plannable(standing.cell().condition())
                            || standing.cell().condition()==C2NavGrid.Condition.PARTIAL_SUPPORT
                                && standing.supportedFraction()>=0.5);
            } catch (RuntimeException ex) {
                // An unreadable terrain observation is UNKNOWN, never safe.
                supported=false;
            }
        }
        if (loaded && avatar.onGround() && supported) run.stableGroundTicks++;
        else run.stableGroundTicks=0;
        if (!C2S4RecoveryPolicy.safeToPlan(run.isAlive(),loaded,avatar.onGround(),
                motion.active(),motion.combatActive(),supported,run.stableGroundTicks)) {
            if (run.recoveryWaitTicks > C2S4RecoveryPolicy.MAX_SETTLE_WAIT_TICKS) {
                run.replanning.stop();
                result(run,false,"S4 recovery failed: no stable loaded ground before deadline");
            }
            return;
        }
        if (run.stableGroundTicks==C2S4RecoveryPolicy.REQUIRED_STABLE_GROUND_TICKS) {
            EVENTS.publish(C2Events.Kind.RECOVERY_READY,run.actor,run.goal,run.action,
                    "S4 safe grounded recovery pose verified");
        }
        if (C2MoveGeometry.near(avatar.getX(),avatar.getY(),avatar.getZ(),
                run.tx,run.ty,run.tz,run.tolerance)) {
            run.replanning.stop();
            result(run,true,"P1-D destination observed after movement stopped"); return;
        }
        C2ReplanningPolicy.Gate gate=run.replanning.gate(run.routeTicks);
        if (gate==C2ReplanningPolicy.Gate.WAIT) return;
        if (gate!=C2ReplanningPolicy.Gate.READY) {
            EVENTS.publish(C2Events.Kind.REPLAN_EXHAUSTED,run.actor,run.goal,run.action,
                    "P1-D last status="+run.replanning.lastStatus());
            run.replanning.stop();
            result(run,false,"P1-D replanning exhausted; last="+run.replanning.lastStatus()); return;
        }
        if (lastPlannerTick==serverTicks) return; // one B3 plan per server tick globally
        lastPlannerTick=serverTicks;
        final int budget=run.replanning.budget();
        try {
            if (!avatar.serverLevel().hasChunkAt(BlockPos.containing(run.tx,run.ty,run.tz))) {
                run.replanning.stop(); result(run,false,"P1-D destination chunk unloaded"); return;
            }
            var capture=C2GraphWorldProbe.captureWithSnapshot(avatar,C2NavGrid.MAX_RADIUS);
            var start=new C2AStarPlanner.Target(net.minecraft.util.Mth.floor(avatar.getX()),
                    C2S3SupportY.at(avatar.getY()),net.minecraft.util.Mth.floor(avatar.getZ()));
            var goal=new C2AStarPlanner.Target(net.minecraft.util.Mth.floor(run.tx),
                    C2S3SupportY.at(run.ty),net.minecraft.util.Mth.floor(run.tz));
            var planned=C2HierarchicalPlanner.plan(capture.graph(),capture.snapshot(),start,goal,
                    C2RegionGraph.DEFAULT_TILE_SIZE,budget);
            C2WaypointExecution replacement=null;
            if (planned.found()) replacement=C2WaypointExecution.navigation3D(planned.path());
            run.replanning.evaluated(budget,planned.expandedNodes(),planned.status().name(),
                    replacement!=null,run.routeTicks);
            if (replacement!=null) {
                // Motion and action ownership must be checked AGAIN before committing.
                if (ACTIVE.get(run.actor)!=run || C1P1MotionRuntime.c2Receipt(run.profile).active()) {
                    interrupt(run,"P1-D movement owner changed during replanning"); return;
                }
                run.route=replacement;
                run.recoveryFrom3D=false;
                EVENTS.publish(C2Events.Kind.ROUTE_REPLACED,run.actor,run.goal,run.action,
                        "P1-D generation="+run.replanning.generation()+" cost="+planned.cost());
                LOG.info("NPCortex P1-D route replaced goal={} action={} generation={} attempt={} cost={} waypoints={}",
                        run.goal,run.action,run.replanning.generation(),run.replanning.attempts(),
                        planned.cost(),replacement.path().size());
                String error=C2WaypointBridge.beginSegment(run);
                if (error!=null) {
                    if (C2WaypointBridge.recoverableFailure(error)) {
                        // A second world change consumes a separate bounded replan attempt.
                        if (C2S4RecoveryPolicy.recoverableStartRefusal(error)) run.recoveryFrom3D=true;
                        scheduleReplanning(run,C2ReplanningPolicy.Trigger.EDGE_INVALIDATED,error);
                    } else result(run,false,"P1-D replacement refused: "+error);
                    return;
                }
                run.ticks=0;
                write(run,"MOVING","P1-D g="+run.replanning.generation()+" segment="
                        +run.route.currentIndex()+" ticket="+run.route.expectedTicket());
                return;
            }
            // Unsafe/unknown chunk or malformed input is NOT an invitation to retry.
            if (planned.status()!=C2HierarchicalPlanner.Status.NO_PATH
                    && planned.status()!=C2HierarchicalPlanner.Status.BUDGET_EXCEEDED) {
                run.replanning.stop();
                result(run,false,"P1-D unsafe planner result: "+planned.status()); return;
            }
            if (!run.replanning.retryable()) {
                EVENTS.publish(C2Events.Kind.REPLAN_EXHAUSTED,run.actor,run.goal,run.action,
                        "P1-D status="+planned.status());
                run.replanning.stop();
                result(run,false,"P1-D "+planned.status()+" after "+run.replanning.attempts()+" bounded attempts");
                return;
            }
            write(run,"REPLANNING","P1-D attempt="+run.replanning.attempts()+" status="+planned.status()
                    +" next retry after cooldown");
        } catch (RuntimeException ex) {
            LOG.warn("NPCortex P1-D planner/probe rejected goal={}",run.goal,ex);
            run.replanning.stop();
            result(run,false,"P1-D planning/probe rejected: "+ex.getClass().getSimpleName());
        }
    }

    private static JsonArray coords(double x,double y,double z) {
        JsonArray a=new JsonArray(); a.add(x);a.add(y);a.add(z);return a;
    }

    private static void result(Run run,boolean succeeded,String detail) {
        if (!ACTIVE.containsKey(run.actor) || ACTIVE.get(run.actor)!=run) return;
        if (!run.isAlive()) { interrupt(run,"Actor unavailable before result submission"); return; }
        EVENTS.publish(C2Events.Kind.ACTION_FINISHED,run.actor,run.goal,run.action,detail);
        JsonObject payload = new JsonObject();
        payload.addProperty("result_id",UUID.randomUUID().toString());
        payload.addProperty("observation_id",UUID.randomUUID().toString());
        payload.addProperty("status",succeeded?"succeeded":"failed");
        payload.add("snapshot",C2WorldSensor.snapshot(run));
        write(run,"RESULT_PENDING",detail);
        send(run,"/v1/agent/actions/"+run.action+"/result",payload,10,reply -> {
            boolean verified = C2ActionVerifier.backendVerified(reply);
            finish(run,succeeded && verified,"C0 action="+reply.get("action_status")+" verified="+verified+" local="+succeeded);
        });
    }

    private static void send(Run run,String route,JsonObject payload,int timeout,Consumer<JsonObject> next) {
        if (ACTIVE.get(run.actor)!=run) return;
        if (!"RESULT_PENDING".equals(run.phase)) write(run,"HTTP_PENDING",route);
        CompletableFuture<JsonObject> future = ChatBridge.c2Post(route,payload,timeout);
        future.whenComplete((reply,error) -> run.server.execute(() -> {
            if (ACTIVE.get(run.actor)!=run) return;
            if (error != null) { interrupt(run,"HTTP: "+ChatBridge.describeFailure(error)); return; }
            try { next.accept(reply); }
            catch (RuntimeException ex) {
                LOG.warn("NPCortex C2 event rejected goal={} phase={}",run.goal,run.phase,ex);
                interrupt(run,"C2 protocol/sensor error: "+ex.getClass().getSimpleName());
            }
        }));
    }

    private static void write(Run run,String phase,String message) {
        run.phase=phase;
        C2ActionJournal.get(run.server).record(run.actor,run.goal,run.action,phase,
                message.substring(0,Math.min(message.length(),180)));
    }

    private static void finish(Run run,boolean success,String detail) {
        if (!ACTIVE.release(run.actor,run)) return;
        run.replanning.stop();
        if (success && run.action!=null && run.isAlive()) {
            C1P1MotionRuntime.MotionReceipt receipt=C1P1MotionRuntime.c2Receipt(run.profile);
            C1PlayerAvatar avatar=run.avatar();
            // Evidence is issued ONLY from the verified C0 response path AND
            // completed C1 movement, after C2 releases its active run.
            if (avatar!=null && !receipt.active() && "COMPLETE".equals(receipt.phase())
                    && C2MoveGeometry.near(avatar.getX(),avatar.getY(),avatar.getZ(),
                            run.tx,run.ty,run.tz,run.tolerance)) {
                VERIFIED_WITNESSES.put(run.actor,new TerminalWitness(run.actor,run.goal,
                        run.action,run.world,run.dimension,avatar.getX(),avatar.getY(),
                        avatar.getZ(),receipt.phase(),true));
            }
        }
        write(run,success?"COMPLETED":"FAILED",detail);
        EVENTS.publish(success?C2Events.Kind.VERIFIED:C2Events.Kind.REJECTED,
                run.actor,run.goal,run.action,detail);
        LOG.info("NPCortex C2 goal={} actor={} result={} detail={}",run.goal,run.actor,
                success?"COMPLETED":"FAILED",detail);
    }

    private static void fail(Run run,String detail) { finish(run,false,detail); }

    private static void interrupt(Run run,String detail) {
        ServerThread.require(run.server, "C2 interrupt");
        if (!ACTIVE.release(run.actor,run)) return;
        run.replanning.stop();
        if (run.action!=null) {
            // Never stop a foreign C1 motion. Only stop an active ticket owned
            // by this C2 route. C1 remains the exclusive Vanilla physics owner.
            var motion=C1P1MotionRuntime.c2Ticket(run.profile);
            if (run.route!=null && motion.active() && run.route.acceptsTicket(motion.token())
                    && run.avatar()!=null) C1P1MotionRuntime.stop(run.server,run.name);
            // An INTERRUPTED journal is NOT a safe replacement for cancelling
            // the pending C0 action. Keep it fenced until terminal ACK.
            C2CancellationRecovery.begin(run,"S4 interrupted; "+detail);
        } else write(run,"INTERRUPTED",detail);
        EVENTS.publish(C2Events.Kind.INTERRUPTED,run.actor,run.goal,run.action,detail);
        LOG.warn("NPCortex C2 interrupted goal={} actor={} pendingReconciliation={} reason={}",
                run.goal,run.actor,run.action!=null,detail);
    }

    static void beforeServerStop(MinecraftServer server) {
        ServerThread.require(server, "C2 server stop");
        for (Run run : java.util.List.copyOf(ACTIVE.values())) {
            if (run.server!=server) continue;
            // On a normal Save/Quit, no old movement may execute on rejoin.
            // If the C0 action exists, fence it with the SAME durable
            // CANCEL_PENDING reconciliation as operator cancellation.
            // No C1 motion stop here: the coordinator already ran C1P0Runtime.onServerStopping,
            // which cleared C1 motion sessions and LIVE, so run.avatar() is null on this path.
            run.replanning.stop();
            if (run.action!=null && ACTIVE.release(run.actor,run)) {
                C2CancellationRecovery.begin(run,"S4 server shutdown; reconcile C0 before new movement");
                EVENTS.publish(C2Events.Kind.INTERRUPTED,run.actor,run.goal,run.action,
                        "S4 shutdown: C0 reconciliation pending");
            } else interrupt(run,"Server shutdown during C0 registration; inspect C0 before manual reset");
        }
        C2CancellationRecovery.beforeStop();
        serverTicks=0;
        VERIFIED_WITNESSES.clear();
        lastPlannerTick=-1;
        EVENTS.clear();
    }
}
