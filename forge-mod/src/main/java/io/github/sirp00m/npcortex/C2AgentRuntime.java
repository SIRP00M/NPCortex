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

    static final class Run {
        final MinecraftServer server;
        final String name;
        final UUID world, actor, profile, goal;
        final String dimension;
        final double tx, ty, tz, tolerance;
        UUID action;
        String phase = "REQUESTED";
        int ticks;
        boolean moved;
        Run(MinecraftServer server, C1IdentityData.Entry id, UUID world, double tx, double ty, double tz, double tolerance) {
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

    static String start(MinecraftServer server, String name, double x, double y, double z) {
        return start(server,name,x,y,z,C2MoveGeometry.DEFAULT_TOLERANCE);
    }

    static String start(MinecraftServer server, String name, double x, double y, double z, double tolerance) {
        try { C2MoveGeometry.validatedTolerance(tolerance); }
        catch (IllegalArgumentException ex) { return ex.getMessage(); }
        if (!C1IdentityData.validName(name)) return "Invalid NPC character name";
        C1IdentityData ids = C1IdentityData.get(server);
        C1IdentityData.Entry id = ids.find(name);
        if (id == null) return "Unknown NPC: " + name;
        if (ACTIVE.containsKey(id.actorId())) return "C2 actor busy: " + name;
        if (ACTIVE.size() >= MAX_ACTORS) return "C2 max 10 concurrent actors";
        C2ActionJournal.Row prior = C2ActionJournal.get(server).find(id.actorId());
        if (prior != null && prior.uncertain())
            return "Unresolved C2 journal: " + prior.state() + ". Inspect C0 then /npcortex c2 reset " + name;
        C1PlayerAvatar avatar = C1P0Runtime.activeAvatar(server,name);
        if (avatar == null) return "NPC not live/alive: " + name;
        if (!avatar.onGround()) return "NPC must be grounded";
        if (!(Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)))
            return "Non-finite movement target";
        if (C2MoveGeometry.reached(avatar.getX(),avatar.getY(),avatar.getZ(),x,y,z,tolerance))
            return "Already within C2 goal tolerance (" + tolerance + " blocks); no movement needed";
        try { C2MoveGeometry.plan(avatar.getX(),avatar.getY(),avatar.getZ(),x,y,z); }
        catch (IllegalArgumentException ex) { return ex.getMessage(); }
        if (!avatar.serverLevel().hasChunkAt(BlockPos.containing(x,y,z))) return "Target chunk not loaded";
        if (C1P1MotionRuntime.c2Receipt(id.profileId()).active()) return "C1 movement busy";
        Run run = new Run(server,id,ids.worldId(),x,y,z,tolerance);
        if (!ACTIVE.claim(run.actor,run)) return "C2 scheduler rejected request";
        EVENTS.publish(C2Events.Kind.REQUESTED,run.actor,run.goal,null,"move_to");
        write(run, "REQUESTED", "Waiting for C0 identity registration");
        JsonObject world = new JsonObject();
        world.addProperty("world_id",run.world.toString());
        world.addProperty("name","NPCortex-C1-" + run.world);
        send(run,"/v1/worlds",world,10,reply -> registerActor(run));
        return "C2 goal queued for " + name + " goal=" + run.goal + " target=" + x + "," + y + "," + z + " tolerance=" + tolerance;
    }

    static String status(MinecraftServer server, String name) {
        C1IdentityData.Entry id = C1IdentityData.get(server).find(name);
        if (id == null) return "Unknown NPC: " + name;
        Run current = ACTIVE.get(id.actorId());
        if (current != null) return name + " C2=" + current.phase + " goal=" + current.goal
                + " action=" + current.action + " | C1=" + C1P1MotionRuntime.c2Receipt(current.profile).phase();
        C2ActionJournal.Row row = C2ActionJournal.get(server).find(id.actorId());
        return row == null ? name + " C2=NONE" : name + " C2=" + row.state() + " goal=" + row.goal()
                + " action=" + row.action() + " reason=" + row.message();
    }

    static String reset(MinecraftServer server, String name) {
        C1IdentityData.Entry id = C1IdentityData.get(server).find(name);
        if (id == null) return "Unknown NPC: " + name;
        if (ACTIVE.containsKey(id.actorId())) return "Active C2 goal; use c2 cancel first";
        if (C1P1MotionRuntime.c2Receipt(id.profileId()).active()) return "C1 movement still active";
        C2ActionJournal.get(server).reset(id.actorId());
        return "C2 journal reset (does NOT resolve any pending C0 backend action). Inspect old goal before retry.";
    }

    static String cancel(MinecraftServer server, String name) {
        C1IdentityData.Entry id = C1IdentityData.get(server).find(name);
        if (id == null) return "Unknown NPC: " + name;
        Run run = ACTIVE.get(id.actorId());
        if (run == null) return "No active C2 goal";
        if (run.moved && run.avatar() != null) C1P1MotionRuntime.stop(server,name);
        // A pending backend action must remain unresolved until operator reconciliation.
        interrupt(run, "Operator cancelled; C0 goal may have a pending action");
        return "C2 cancelled; inspect status, use reset before new run";
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
            if (error != null) { result(run,false,error); return; }
            run.moved = true;
            run.ticks = 0;
            write(run,"MOVING","C1 owns the Vanilla movement session");
            EVENTS.publish(C2Events.Kind.ACTION_STARTED,run.actor,run.goal,run.action,skill);
        });
    }

    /** Called after C1 motion & lifecycle ticks at END. No extra physics tick. */
    static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || ACTIVE.isEmpty()) return;
        for (Run run : java.util.List.copyOf(ACTIVE.values())) {
            if (!"MOVING".equals(run.phase)) continue;
            if (!run.isAlive()) { interrupt(run,"NPC died, despawned, or changed lifecycle/dimension"); continue; }
            run.ticks++;
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
            finish(run,verified,"C0 action="+reply.get("action_status")+" verified="+verified);
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
        write(run,success?"COMPLETED":"FAILED",detail);
        EVENTS.publish(success?C2Events.Kind.VERIFIED:C2Events.Kind.REJECTED,
                run.actor,run.goal,run.action,detail);
        LOG.info("NPCortex C2 goal={} actor={} result={} detail={}",run.goal,run.actor,
                success?"COMPLETED":"FAILED",detail);
    }

    private static void fail(Run run,String detail) { finish(run,false,detail); }

    private static void interrupt(Run run,String detail) {
        if (!ACTIVE.release(run.actor,run)) return;
        write(run,"INTERRUPTED",detail);
        EVENTS.publish(C2Events.Kind.INTERRUPTED,run.actor,run.goal,run.action,detail);
        LOG.warn("NPCortex C2 interrupted goal={} actor={} reason={}",run.goal,run.actor,detail);
    }

    static void beforeServerStop(MinecraftServer server) {
        for (Run run : java.util.List.copyOf(ACTIVE.values())) if (run.server==server) interrupt(run,"Server shutdown");
        EVENTS.clear();
    }
}
