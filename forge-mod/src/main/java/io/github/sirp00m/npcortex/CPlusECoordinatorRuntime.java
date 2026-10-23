package io.github.sirp00m.npcortex;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.sirp00m.npcortex.cplus.CPlusF1Delivery;
import io.github.sirp00m.npcortex.cplus.CPlusLocalAuth;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.server.ServerStoppingEvent;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * C-F3 E1 operator-only read-only task coordinator; does NOT own C1/C2 motion,
 * inventory or any Minecraft world effect. All game state is captured on the
 * server thread. Authenticated HTTP runs in a bounded background executor.
 */
final class CPlusECoordinatorRuntime {
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();
    private static final Map<MinecraftServer,ExecutorService> WORKERS = new IdentityHashMap<>();
    private record ReadTicket(String taskId,String leaseId,int generation,String worldId,String actorId,String attemptId) {}
    // Volatile operator-side context. Deliberately lost on game restart: old
    // DISPATCHING attempts become HOLD at backend startup, never auto-replayed.
    private static final Map<MinecraftServer,Map<String,ReadTicket>> TICKETS = new IdentityHashMap<>();
    private CPlusECoordinatorRuntime() {}

    private static ExecutorService worker(MinecraftServer server) {
        return WORKERS.computeIfAbsent(server, ignored -> new ThreadPoolExecutor(
                1,1,0L,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(8),
                job -> { Thread t = new Thread(job,"npcortex-cplus-e-coordinator");
                         t.setDaemon(true); return t; },
                new ThreadPoolExecutor.AbortPolicy()));
    }

    private static JsonObject preset() {
        JsonArray plan = new JsonArray();
        plan.add(node("observe","observe_cells"));
        plan.add(node("coverage","report_coverage","observe"));
        plan.add(node("permission","report_permission","observe"));
        JsonObject o=new JsonObject();o.add("plan",plan);return o;
    }
    private static JsonObject node(String id,String kind,String... depends) {
        JsonObject obj = new JsonObject();obj.addProperty("node_id",id);obj.addProperty("kind",kind);
        JsonArray deps=new JsonArray();for (String dep:depends) deps.add(dep);
        obj.add("depends_on",deps);return obj;
    }

    static int submit(CommandSourceStack src,String name) throws CommandSyntaxException {
        // The /npcortex root is op-only. Requester always comes from the real
        // server player's UUID, never from a client-supplied string.
        UUID requester=src.getPlayerOrException().getUUID();
        JsonObject snapshot;
        try {snapshot=CPlusF2WorldSampler.capture(src.getServer(),name);}
        catch (IllegalStateException ex) {message(src,"E1 HOLD: actor unavailable");return 0;}
        String requestId=UUID.randomUUID().toString();
        JsonObject body=preset();body.addProperty("request_id",requestId);
        body.addProperty("requester_id",requester.toString());body.add("snapshot",snapshot);
        return post(src,"e1/submit",body,snapshot.get("world_id").getAsString(),
                snapshot.get("actor_id").getAsString(),requestId,null);
    }

    /** E3-D G1 preview only: no C2 begin/navigation call and no player motion. */
    static int movePreview(CommandSourceStack src,String name,int x,int y,int z)
            throws CommandSyntaxException {
        UUID requester=src.getPlayerOrException().getUUID();
        MinecraftServer server=src.getServer();
        C1IdentityData.Entry actor=C1IdentityData.get(server).find(name);
        if(actor==null){message(src,"E3 G1 HOLD: unknown NPC");return 0;}
        C1PlayerAvatar avatar=C1P0Runtime.activeAvatar(server,name);
        if(avatar==null || avatar.isRemoved() || !avatar.getUUID().equals(actor.profileId())
                || !C1P1CLifecycleRuntime.mayRestore(server,actor.profileId())
                || !avatar.serverLevel().dimension().location().toString().equals(actor.dimension())) {
            message(src,"E3 G1 HOLD: actor not live/alive or dimension mismatch");return 0;
        }
        if(C2AgentRuntime.hasActiveActor(actor.actorId()) || C2FollowRuntime.hasActiveActor(actor.actorId())
                || C1P1MotionRuntime.c2Receipt(actor.profileId()).active() || !avatar.onGround()) {
            message(src,"E3 G1 HOLD: C1/C2/Follow already owns motion or actor not grounded");return 0;
        }
        C2ActionJournal.Row prior=C2ActionJournal.get(server).find(actor.actorId());
        if(prior!=null && prior.uncertain()) {
            message(src,"E3 G1 HOLD: unresolved C2 goal/cancellation");return 0;
        }
        if(Math.abs((long)x-avatar.blockPosition().getX())>6
                || Math.abs((long)y-avatar.blockPosition().getY())>3
                || Math.abs((long)z-avatar.blockPosition().getZ())>6
                || !avatar.serverLevel().hasChunkAt(new BlockPos(x,y,z))) {
            message(src,"E3 G1 HOLD: target not local/loaded");return 0;
        }
        JsonObject snapshot;
        try { snapshot=CPlusF2WorldSampler.capture(server,name); }
        catch(IllegalStateException ex) {message(src,"E3 G1 HOLD: no trusted snapshot");return 0;}
        JsonObject target=new JsonObject();
        target.addProperty("x",x);target.addProperty("y",y);target.addProperty("z",z);
        JsonObject body=new JsonObject();
        body.addProperty("schema_version",1);
        body.addProperty("request_id",UUID.randomUUID().toString());
        body.addProperty("task_id",UUID.randomUUID().toString());
        body.addProperty("requester_id",requester.toString());
        body.addProperty("actor_id",snapshot.get("actor_id").getAsString());
        body.addProperty("world_id",snapshot.get("world_id").getAsString());
        body.addProperty("dimension_id",snapshot.get("dimension_id").getAsString());
        body.addProperty("session_id",snapshot.get("session_id").getAsString());
        body.addProperty("node_id","move");
        body.addProperty("attempt_id",UUID.randomUUID().toString());
        body.addProperty("action_kind","move_to");
        body.add("target",target);body.addProperty("tolerance",0.75);
        body.add("snapshot",snapshot);
        return post(src,"e3/preview",body,snapshot.get("world_id").getAsString(),
                snapshot.get("actor_id").getAsString(),body.get("request_id").getAsString(),
                body.get("task_id").getAsString());
    }

    static int queue(CommandSourceStack src,String name) {
        C1IdentityData.Entry actor=C1IdentityData.get(src.getServer()).find(name);
        if(actor==null){message(src,"E1 unknown NPC");return 0;}
        JsonObject body=new JsonObject();
        body.addProperty("world_id",C1IdentityData.get(src.getServer()).worldId().toString());
        body.addProperty("actor_id",actor.actorId().toString());
        return post(src,"e1/queue",body,body.get("world_id").getAsString(),
                actor.actorId().toString(),null,null);
    }

    static int dispatch(CommandSourceStack src,String name) throws CommandSyntaxException {
        src.getPlayerOrException(); // This is an operator test, not an AI skill.
        JsonObject snapshot;
        try { snapshot=CPlusF2WorldSampler.capture(src.getServer(),name); }
        catch (IllegalStateException ex) {message(src,"E2 HOLD: actor unavailable");return 0;}
        JsonObject body=new JsonObject();body.add("snapshot",snapshot);
        return post(src,"e2/dispatch",body,snapshot.get("world_id").getAsString(),
                snapshot.get("actor_id").getAsString(),null,null);
    }
    static int verify(CommandSourceStack src,String name) throws CommandSyntaxException {
        src.getPlayerOrException();
        C1IdentityData.Entry actor=C1IdentityData.get(src.getServer()).find(name);
        if(actor==null){message(src,"E2 HOLD: actor unavailable");return 0;}
        ReadTicket ticket=TICKETS.getOrDefault(src.getServer(),Map.of()).get(actor.actorId().toString());
        if(ticket==null){message(src,"E2 HOLD: no active local read-only ticket; restart requires manual recovery");return 0;}
        JsonObject snapshot;
        try { snapshot=CPlusF2WorldSampler.capture(src.getServer(),name); }
        catch (IllegalStateException ex) {message(src,"E2 HOLD: actor unavailable");return 0;}
        if(!ticket.actorId().equals(snapshot.get("actor_id").getAsString())
                || !ticket.worldId().equals(snapshot.get("world_id").getAsString())) {
            message(src,"E2 HOLD: world/actor changed");return 0;
        }
        JsonObject body=new JsonObject();body.add("snapshot",snapshot);
        body.addProperty("task_id",ticket.taskId());body.addProperty("lease_id",ticket.leaseId());
        body.addProperty("generation",ticket.generation());
        return post(src,"e2/verify",body,ticket.worldId(),ticket.actorId(),null,ticket.taskId());
    }

    static int status(CommandSourceStack src,String name,String taskId) {
        return idOperation(src,name,taskId,false);
    }
    static int cancel(CommandSourceStack src,String name,String taskId) throws CommandSyntaxException {
        // This is not an operator override. Backend enforces requester ownership.
        src.getPlayerOrException();
        return idOperation(src,name,taskId,true);
    }
    private static int idOperation(CommandSourceStack src,String name,String taskId,boolean cancel) {
        C1IdentityData.Entry actor=C1IdentityData.get(src.getServer()).find(name);
        if(actor==null){message(src,"E1 unknown NPC");return 0;}
        JsonObject body=new JsonObject();body.addProperty("task_id",taskId);
        if(cancel) {
            if(src.getPlayer()==null){message(src,"E1 cancel requires a player");return 0;}
            body.addProperty("requester_id",src.getPlayer().getUUID().toString());
        }
        return post(src,cancel?"e1/cancel":"e1/status",body,
                C1IdentityData.get(src.getServer()).worldId().toString(),actor.actorId().toString(),null,taskId);
    }

    private static int post(CommandSourceStack src,String route,JsonObject body,
                            String worldId,String actorId,String requestId,String taskId) {
        MinecraftServer server=src.getServer();
        final URI endpoint;
        final String token;
        try {
            token=CPlusLocalAuth.readToken();
            endpoint=CPlusF1Delivery.safeBackend(NPCortexConfig.BACKEND_URL.get())
                    .resolve("/v1/cplus/"+route);
        } catch(IOException | IllegalStateException | IllegalArgumentException error){
            message(src,"E coordinator HOLD: backend auth/config unavailable");return 0;
        }
        String raw=body.toString();
        try {
            CompletableFuture.supplyAsync(() -> send(endpoint,token,raw),worker(server))
                    .whenComplete((reply,error) -> server.execute(() -> {
                        if(error!=null) {message(src,"E coordinator HOLD: backend offline or request rejected; no auto retry");return;}
                        try {
                            JsonObject out=JsonParser.parseString(reply).getAsJsonObject();
                            if(!out.has("world_effects") || out.get("world_effects").getAsBoolean()
                                    || !out.has("world_id") || !worldId.equals(out.get("world_id").getAsString())
                                    || !out.has("actor_id") || !actorId.equals(out.get("actor_id").getAsString()))
                                throw new IllegalArgumentException("invalid task identity");
                            if(requestId!=null && (!out.has("request_id")
                                    || !requestId.equals(out.get("request_id").getAsString())))
                                throw new IllegalArgumentException("request mismatch");
                            if(taskId!=null && (!out.has("task_id")
                                    || !taskId.equals(out.get("task_id").getAsString())))
                                throw new IllegalArgumentException("task mismatch");
                            if(route.equals("e1/submit")) {
                                JsonObject original=body.getAsJsonObject("snapshot");
                                for(String key:new String[]{"session_id","dimension_id","observation_id"})
                                    if(!out.has(key) || !original.get(key).getAsString().equals(out.get(key).getAsString()))
                                        throw new IllegalArgumentException("snapshot identity mismatch");
                                if(!body.get("requester_id").getAsString().equals(out.get("requester_id").getAsString()))
                                    throw new IllegalArgumentException("requester mismatch");
                            }
                            if(route.equals("e3/preview")) {
                                JsonObject original=body.getAsJsonObject("snapshot");
                                for(String key:new String[]{"session_id","dimension_id"})
                                    if(!out.has(key) || !original.get(key).getAsString().equals(out.get(key).getAsString()))
                                        throw new IllegalArgumentException("E3 preview identity mismatch");
                                if(!out.has("schema_version") || out.get("schema_version").getAsInt()!=1
                                        || !out.has("requester_id")
                                        || !body.get("requester_id").getAsString().equals(out.get("requester_id").getAsString())
                                        || !out.has("attempt_id")
                                        || !body.get("attempt_id").getAsString().equals(out.get("attempt_id").getAsString())
                                        || !out.has("observation_id")
                                        || !original.get("observation_id").getAsString().equals(out.get("observation_id").getAsString())
                                        || !"PREVIEW_ONLY".equals(out.get("state").getAsString())
                                        || !"DRY_RUN".equals(out.get("execution_mode").getAsString())
                                        || out.get("can_execute").getAsBoolean()
                                        || out.get("motion_started").getAsBoolean()
                                        || !"move_to".equals(out.get("action_kind").getAsString())
                                        || !"move".equals(out.get("node_id").getAsString())
                                        || !body.getAsJsonObject("target").equals(out.getAsJsonObject("target"))
                                        || body.get("tolerance").getAsDouble()!=out.get("tolerance").getAsDouble())
                                    throw new IllegalArgumentException("E3 preview reply mismatch");
                                message(src,"E3 G1 PREVIEW_ONLY move_to task="+out.get("task_id").getAsString()
                                        +" target="+out.get("target").getAsJsonObject()
                                        +"; no motion, no world mutation, not queued");return;
                            }
                            if(route.equals("e2/dispatch") || route.equals("e2/verify")) {
                                JsonObject original=body.getAsJsonObject("snapshot");
                                for(String key:new String[]{"session_id","dimension_id"})
                                    if(!out.has(key) || !original.get(key).getAsString().equals(out.get(key).getAsString())) {
                                        // EMPTY has no selected task/session and is a valid result.
                                        if(!(route.equals("e2/dispatch") && "EMPTY".equals(out.get("state").getAsString())))
                                            throw new IllegalArgumentException("E2 snapshot identity mismatch");
                                    }
                                String state=out.get("state").getAsString();
                                if("EMPTY".equals(state)) {message(src,"E2 queue empty; no world action");return;}
                                if("DISPATCHING".equals(state) && route.equals("e2/dispatch")) {
                                    if(!out.has("dispatch_observation_id") ||
                                            !original.get("observation_id").getAsString().equals(
                                                    out.get("dispatch_observation_id").getAsString()))
                                        throw new IllegalArgumentException("E2 dispatch evidence mismatch");
                                    String id=out.get("task_id").getAsString();
                                    String lease=out.get("lease_id").getAsString();
                                    UUID.fromString(id);UUID.fromString(lease);
                                    int generation=out.get("generation").getAsInt();
                                    String attempt=out.get("attempt_id").getAsString();
                                    UUID.fromString(attempt);
                                    if(generation<1 || !"READ_ONLY".equals(out.get("execution_mode").getAsString()))
                                        throw new IllegalArgumentException("E2 lease response invalid");
                                    TICKETS.computeIfAbsent(server,ignored -> new java.util.HashMap<>())
                                            .put(actorId,new ReadTicket(id,lease,generation,worldId,actorId,attempt));
                                    message(src,"E2 reserved task="+id+" #"+out.get("sequence").getAsInt()
                                            +"; run /npcortex debug cplus taskverify <npc>; NO world mutation");return;
                                }
                                if(route.equals("e2/verify") && "READ_ONLY_VERIFIED".equals(state)) {
                                    Map<String,ReadTicket> active=TICKETS.get(server);
                                    ReadTicket ticket=active==null?null:active.get(actorId);
                                    String observation=original.get("observation_id").getAsString();
                                    if(ticket==null || !ticket.taskId().equals(out.get("task_id").getAsString())
                                            || !ticket.attemptId().equals(out.get("attempt_id").getAsString())
                                            || !observation.equals(out.get("verified_observation_id").getAsString())
                                            || !"READ_ONLY".equals(out.get("execution_mode").getAsString()))
                                        throw new IllegalArgumentException("E2 verified evidence mismatch");
                                    JsonArray nodes=out.getAsJsonArray("nodes");
                                    if(nodes==null || nodes.size()==0 || nodes.size()!=out.get("read_only_receipts").getAsInt())
                                        throw new IllegalArgumentException("E2 incomplete typed receipts");
                                    for(var element:nodes) {
                                        JsonObject n=element.getAsJsonObject();
                                        if(!"READ_VERIFIED".equals(n.get("state").getAsString())
                                                || !ticket.attemptId().equals(n.get("attempt_id").getAsString())
                                                || !(observation+":"+n.get("node_id").getAsString()).equals(
                                                        n.get("evidence_id").getAsString()))
                                            throw new IllegalArgumentException("E2 node evidence mismatch");
                                    }

                                    if(active!=null)active.remove(actorId);
                                    message(src,"E2 read-only VERIFIED task="+out.get("task_id").getAsString()
                                            +" receipts="+out.get("read_only_receipts").getAsInt()+"; lease released; NO world mutation");return;
                                }
                                message(src,"E2 task="+out.get("task_id").getAsString()+" "+state
                                        +"; fail-closed; NO world mutation");return;
                            }
                            if(route.equals("e1/queue")) {
                                JsonArray tasks=out.getAsJsonArray("tasks");
                                if(tasks==null || tasks.size()>16)throw new IllegalArgumentException("queue invalid");
                                for(var entry:tasks){JsonObject item=entry.getAsJsonObject();
                                    if(!worldId.equals(item.get("world_id").getAsString())
                                            || !actorId.equals(item.get("actor_id").getAsString())
                                            || item.get("world_effects").getAsBoolean())
                                        throw new IllegalArgumentException("queue identity mismatch");}
                                StringBuilder brief=new StringBuilder("E1 queue [");
                                for(var entry:tasks){JsonObject item=entry.getAsJsonObject();
                                    if(brief.length()>12)brief.append(", ");
                                    brief.append('#').append(item.get("sequence").getAsInt()).append(':')
                                            .append(item.get("state").getAsString());}
                                message(src,brief.append("]; no world mutation").toString());return;
                            }
                            if(!out.has("task_id") || !out.has("state") || !out.has("sequence")
                                    || !out.has("execution_mode")
                                    || !"READ_ONLY".equals(out.get("execution_mode").getAsString()))
                                throw new IllegalArgumentException("untyped task reply");
                            if(route.equals("e1/cancel") && "CANCELLED".equals(out.get("state").getAsString())) {
                                Map<String,ReadTicket> active=TICKETS.get(server);
                                if(active!=null && active.get(actorId)!=null
                                        && active.get(actorId).taskId().equals(taskId)) active.remove(actorId);
                            }
                            String summary="E task="+out.get("task_id").getAsString()+" #"
                                    +out.get("sequence").getAsInt()+" "+out.get("state").getAsString()
                                    +" nodes="+out.getAsJsonArray("nodes").size()+"; NO world mutation";
                            message(src,summary);
                        } catch(RuntimeException malformed) {
                            message(src,"E coordinator HOLD: invalid/mismatched backend reply");
                        }
                    }));
        }catch(RejectedExecutionException rejected) {
            message(src,"E coordinator HOLD: bounded worker busy");return 0;
        }
        message(src,"E request submitted asynchronously (read-only, no world mutation)");return 1;
    }
    private static String send(URI uri,String token,String json){
        try {
            HttpRequest request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5))
                    .header("Content-Type","application/json")
                    .header("X-NPCortex-CPlus-Token",token)
                    .POST(HttpRequest.BodyPublishers.ofString(json,StandardCharsets.UTF_8)).build();
            HttpResponse<String> response=HTTP.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if(response.statusCode()!=200 || response.body().length()>24000)
                throw new IOException("backend rejected E coordinator request");
            return response.body();
        }catch(IOException ex){throw new IllegalStateException("E coordinator transport failed",ex);}
        catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IllegalStateException("interrupted",ex);}
    }
    static void onServerStopping(ServerStoppingEvent event){
        ExecutorService executor=WORKERS.remove(event.getServer());
        if(executor!=null)executor.shutdownNow();
        TICKETS.remove(event.getServer());
    }
    private static void message(CommandSourceStack src,String text){
        src.sendSuccess(() -> Component.literal("NPCortex: "+text),false);
    }
}
