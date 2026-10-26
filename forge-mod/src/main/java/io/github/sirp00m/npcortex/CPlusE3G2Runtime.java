package io.github.sirp00m.npcortex;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.sirp00m.npcortex.cplus.CPlusF1Delivery;
import io.github.sirp00m.npcortex.cplus.CPlusLocalAuth;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.server.ServerStoppingEvent;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** E3-D G2: operator-initiated, one-target guarded C2 handoff only.
 * C2 remains sole owner of path/physics. Backend records admission/goal receipt,
 * NOT movement completion. G3 will add verified terminal lifecycle/cancellation.
 */
final class CPlusE3G2Runtime {
    private static final Map<MinecraftServer,ExecutorService> WORKERS = new IdentityHashMap<>();
    private static final Map<MinecraftServer,Map<UUID,UUID>> PENDING = new IdentityHashMap<>();
    private CPlusE3G2Runtime() {}

    private static ExecutorService worker(MinecraftServer server) {
        return WORKERS.computeIfAbsent(server, ignored -> new ThreadPoolExecutor(
                1,1,0L,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(8),
                job -> { Thread t=new Thread(job,"npcortex-e3-g2-motion");t.setDaemon(true);return t; },
                new ThreadPoolExecutor.AbortPolicy()));
    }
    private static void msg(CommandSourceStack source,String detail) {
        source.sendSuccess(() -> Component.literal("NPCortex: "+detail),false);
    }
    private static JsonObject payload(CommandSourceStack source,String name,int x,int y,int z) {
        MinecraftServer server=source.getServer();
        C1IdentityData.Entry id=C1IdentityData.get(server).find(name);
        if(id==null)throw new IllegalStateException("unknown actor");
        C1PlayerAvatar avatar=C1P0Runtime.activeAvatar(server,name);
        if(avatar==null || avatar.isRemoved() || !avatar.getUUID().equals(id.profileId())
                || !avatar.serverLevel().dimension().location().toString().equals(id.dimension())
                || !C1P1CLifecycleRuntime.mayRestore(server,id.profileId()))
            throw new IllegalStateException("NPC unavailable/dimension mismatch");
        if(C2AgentRuntime.hasActiveActor(id.actorId()) || C2FollowRuntime.hasActiveActor(id.actorId())
                || C1P1MotionRuntime.c2Receipt(id.profileId()).active() || !avatar.onGround())
            throw new IllegalStateException("C1/C2/Follow owns motion or not grounded");
        C2ActionJournal.Row old=C2ActionJournal.get(server).find(id.actorId());
        if(old!=null && old.uncertain())throw new IllegalStateException("C2 goal/cancel unresolved");
        if(Math.abs((long)x-avatar.blockPosition().getX())>6 ||
                Math.abs((long)y-avatar.blockPosition().getY())>3 ||
                Math.abs((long)z-avatar.blockPosition().getZ())>6 ||
                !avatar.serverLevel().hasChunkAt(new BlockPos(x,y,z)))
            throw new IllegalStateException("target outside local loaded region");
        JsonObject snap=CPlusF2WorldSampler.capture(server,name);
        JsonObject target=new JsonObject();target.addProperty("x",x);
        target.addProperty("y",y);target.addProperty("z",z);
        JsonObject body=new JsonObject();
        body.addProperty("schema_version",1);
        body.addProperty("request_id",UUID.randomUUID().toString());
        body.addProperty("task_id",UUID.randomUUID().toString());
        body.addProperty("requester_id",source.getPlayer().getUUID().toString());
        for(String key:new String[]{"actor_id","world_id","dimension_id","session_id"})
            body.addProperty(key,snap.get(key).getAsString());
        body.addProperty("node_id","move");
        body.addProperty("attempt_id",UUID.randomUUID().toString());
        body.addProperty("action_kind","move_to");
        body.add("target",target);body.addProperty("tolerance",0.75);
        body.add("snapshot",snap);
        return body;
    }

    static boolean pending(MinecraftServer server,UUID actor) {
        return PENDING.containsKey(server) && PENDING.get(server).containsKey(actor);
    }
    static int move(CommandSourceStack src,String name,int x,int y,int z) {
        if(src.getPlayer()==null){msg(src,"E3 G2 HOLD: operator player required");return 0;}
        MinecraftServer server=src.getServer();
        JsonObject body;
        try {body=payload(src,name,x,y,z);}
        catch(RuntimeException ex){msg(src,"E3 G2 HOLD: "+ex.getMessage());return 0;}
        UUID actor=UUID.fromString(body.get("actor_id").getAsString());
        if(CPlusTransportRecovery.busy(server,actor) || CPlusE3G3Runtime.pending(server,actor)){msg(src,"HOLD: transport recovery active");return 0;}
        UUID request=UUID.fromString(body.get("request_id").getAsString());
        Map<UUID,UUID> waiting=PENDING.computeIfAbsent(server,ignored->new HashMap<>());
        if(waiting.putIfAbsent(actor,request)!=null) {msg(src,"E3 G2 HOLD: pending local admission");return 0;}
        final String token;
        final URI base;
        try {
            token=CPlusLocalAuth.readToken();
            base=CPlusF1Delivery.safeBackend(NPCortexConfig.BACKEND_URL.get());
        } catch(IOException|RuntimeException ex) {
            waiting.remove(actor,request);msg(src,"E3 G2 HOLD: authentication/config unavailable");return 0;
        }
        try {
            CompletableFuture.supplyAsync(() -> CPlusOutbox.send(CPlusOutbox.directory(server),base,token,"/v1/cplus/e3/reserve",body),worker(server))
                    .whenComplete((reply,error) -> server.execute(() -> {
                        if(!request.equals(waiting.get(actor)))return;
                        if(error!=null){waiting.remove(actor,request);
                            msg(src,"E3 G2 HOLD: reserve failed ["+io.github.sirp00m.npcortex.cplus.CPlusTransport.diagnosticCode(error)+"] task="+body.get("task_id").getAsString()+"; no navigation started. Inspect backend HTTP status and durable outbox; do not retry blindly");return;}
                        JsonObject response;
                        try {
                            response=JsonParser.parseString(reply).getAsJsonObject();
                            requireIdentity(body,response);
                            if(!"RESERVED".equals(response.get("state").getAsString())
                                    || !"GUARDED_C2".equals(response.get("execution_mode").getAsString())
                                    || !response.get("can_execute").getAsBoolean()
                                    || response.get("c2_goal_accepted").getAsBoolean()
                                    || response.get("motion_started").getAsBoolean()
                                    || response.get("completion_verified").getAsBoolean()
                                    || !body.getAsJsonObject("target").equals(response.getAsJsonObject("target"))
                                    || body.get("tolerance").getAsDouble()!=response.get("tolerance").getAsDouble())
                                throw new IllegalArgumentException("invalid G2 reserve authorization");
                        }catch(RuntimeException ex){waiting.remove(actor,request);
                            msg(src,"E3 G2 HOLD: invalid reserve reply; DO NOT retry blindly");return;}
                        // Delayed HTTP responses may arrive after Follow, C1 lifecycle,
                        // dimensions or server session changed. Refresh on SERVER THREAD.
                        boolean allowed=server.isRunning() && server.getPlayerList().getPlayer(
                                UUID.fromString(body.get("requester_id").getAsString()))!=null;
                        if(allowed) {
                            try {
                                JsonObject fresh=payload(src,name,x,y,z);
                                for(String key:new String[]{"actor_id","world_id","dimension_id","session_id"})
                                    if(!body.get(key).equals(fresh.get(key)))allowed=false;
                            }catch(RuntimeException ex){allowed=false;}
                        }
                        C2AgentRuntime.NavigationAdmission admitted = allowed
                            ? C2AgentRuntime.guardedNavigate(server,name,x,y,z)
                            : new C2AgentRuntime.NavigationAdmission(false,null,"stale Forge owner/session; no motion");
                        // No completion claims: only an authoritative C2 goal ID
                        // can establish that C2 admitted a new movement run.
                        JsonObject ack=new JsonObject();
                        for(String key:new String[]{"task_id","attempt_id","world_id","actor_id","session_id"})
                            ack.addProperty(key,body.get(key).getAsString());
                        ack.addProperty("state",admitted.accepted()?"C2_ACCEPTED":"HOLD");
                        if(admitted.accepted())ack.addProperty("goal_id",admitted.goalId().toString());
                        else ack.add("goal_id",com.google.gson.JsonNull.INSTANCE);
                        try { CompletableFuture.supplyAsync(() -> CPlusOutbox.send(CPlusOutbox.directory(server),base,token,"/v1/cplus/e3/report",ack),worker(server))
                                .whenComplete((report,reportError) -> server.execute(() -> {
                                    waiting.remove(actor,request);
                                    if(reportError!=null) {
                                        msg(src,"E3 G2 HOLD: report ["+io.github.sirp00m.npcortex.cplus.CPlusTransport.diagnosticCode(reportError)+"] C2="+admitted.detail()+
                                                "; task="+body.get("task_id").getAsString()+
                                                "; inspect /npcortex c2 status "+name+
                                                "; use tasktransportrecover; NO navigation replay");return;
                                    }
                                    try {
                                        JsonObject out=JsonParser.parseString(report).getAsJsonObject();
                                        requireIdentity(body,out);
                                        if(!ack.get("state").getAsString().equals(out.get("state").getAsString())
                                                || out.get("c2_goal_accepted").getAsBoolean()!=admitted.accepted()
                                                || out.get("motion_started").getAsBoolean()
                                                || out.get("completion_verified").getAsBoolean()
                                                || (admitted.accepted() && (!out.has("goal_id")
                                                || !admitted.goalId().toString().equals(out.get("goal_id").getAsString()))))
                                            throw new IllegalArgumentException("G2 result mismatch");
                                        msg(src,admitted.accepted()
                                                ? "E3 G2 C2_ACCEPTED task="+body.get("task_id").getAsString()+
                                                " goal="+admitted.goalId()+"; C2 goal queued, motion pending; NOT COMPLETED (run taskmovefinalize after arrival)"
                                                : "E3 G2 HOLD: C2 refused: "+admitted.detail());
                                    }catch(RuntimeException ex){msg(src,"E3 G2 HOLD: invalid result ACK; inspect C2; no retry");}
                                })); }catch(RejectedExecutionException ex){waiting.remove(actor,request);
                                    msg(src,"HOLD: report queue rejected; inspect C2, no movement replay");}
                    }));
        }catch(RejectedExecutionException ex){waiting.remove(actor,request);msg(src,"E3 G2 HOLD: worker queue busy");return 0;}
        msg(src,"E3 G2 typed motion admission queued (operator-only); no block mutation");return 1;
    }

    static int status(CommandSourceStack src,String name) {
        C1IdentityData.Entry actor=C1IdentityData.get(src.getServer()).find(name);
        if(actor==null){msg(src,"E3 G2 unknown NPC");return 0;}
        String local=C2AgentRuntime.status(src.getServer(),name);
        final URI endpoint;
        final String token;
        try {
            endpoint=CPlusF1Delivery.safeBackend(NPCortexConfig.BACKEND_URL.get())
                    .resolve("/v1/cplus/e3/status");token=CPlusLocalAuth.readToken();
        }catch(IOException|RuntimeException ex){msg(src,"E3 G2 HOLD: backend auth/config unavailable; "+local);return 0;}
        JsonObject request=new JsonObject();
        request.addProperty("world_id",C1IdentityData.get(src.getServer()).worldId().toString());
        request.addProperty("actor_id",actor.actorId().toString());
        MinecraftServer server=src.getServer();
        try {
            CompletableFuture.supplyAsync(() -> send(endpoint,token,request),worker(server))
                    .whenComplete((out,error)->server.execute(()->{
                        if(error!=null) {msg(src,"E3 G2 backend HOLD ["+io.github.sirp00m.npcortex.cplus.CPlusTransport.diagnosticCode(error)+"]; "+local);return;}
                        try {
                            JsonObject reply=JsonParser.parseString(out).getAsJsonObject();
                            if(!request.get("world_id").equals(reply.get("world_id"))
                                    || !request.get("actor_id").equals(reply.get("actor_id"))
                                    || reply.get("world_effects").getAsBoolean()
                                    || (reply.get("completion_verified").getAsBoolean()
                                         != "COMPLETED".equals(reply.get("state").getAsString())))
                                throw new IllegalArgumentException("bad G2 status reply");
                            msg(src,"E3 G2="+reply.get("state").getAsString()+"; "+
                                    C2AgentRuntime.status(server,name)+
                                    ("COMPLETED".equals(reply.get("state").getAsString())
                                            ? "; G3 durable terminal recorded" : "; no G3 completion proof"));
                        }catch(RuntimeException ex){msg(src,"E3 G2 HOLD: invalid status reply");}
                    }));
        }catch(RejectedExecutionException ex){msg(src,"E3 G2 HOLD: worker busy");return 0;}
        msg(src,"E3 G2 status requested");return 1;
    }

    private static void requireIdentity(JsonObject sent,JsonObject received) {
        // Shared, pure-Gson identity contract; validated on BOTH reserve and report.
        CPlusE3G2Identity.requireIdentity(sent,received);
    }
    private static String send(URI url,String token,JsonObject body) {
        return io.github.sirp00m.npcortex.cplus.CPlusTransport.exchange(url,token,body.toString(),5,24000);
    }
    static void onServerStopping(ServerStoppingEvent event) {
        ExecutorService e=WORKERS.remove(event.getServer());if(e!=null)e.shutdownNow();
        PENDING.remove(event.getServer());
    }
}
