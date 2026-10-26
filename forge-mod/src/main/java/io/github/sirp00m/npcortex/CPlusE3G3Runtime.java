package io.github.sirp00m.npcortex;

import com.google.gson.JsonObject;
import com.google.gson.JsonNull;
import com.google.gson.JsonParser;
import io.github.sirp00m.npcortex.cplus.CPlusF1Delivery;
import io.github.sirp00m.npcortex.cplus.CPlusLocalAuth;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.server.ServerStoppingEvent;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.IdentityHashMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** G3 explicit, operator-only one-goal terminal flow. Never replays navigation.
 * C2 owns motion and cancellation. Backend owns durable terminal/fence release.
 * All Minecraft inspection happens on the server thread; network on a worker.
 */
final class CPlusE3G3Runtime {
    private static final Map<MinecraftServer,ExecutorService> WORKERS=new IdentityHashMap<>();
    private static final Map<MinecraftServer,Map<UUID,UUID>> BUSY=new IdentityHashMap<>();
    private CPlusE3G3Runtime() {}
    private static ExecutorService worker(MinecraftServer server) {
        return WORKERS.computeIfAbsent(server, ignored -> new ThreadPoolExecutor(1,1,0L,
                TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(8),r -> {
                    Thread t=new Thread(r,"npcortex-g3-terminal");t.setDaemon(true);return t;
                },new ThreadPoolExecutor.AbortPolicy()));
    }
    private static void msg(CommandSourceStack src,String detail) {
        src.sendSuccess(() -> Component.literal("NPCortex: "+detail),false);
    }
    private static String send(URI url,String token,JsonObject body) {
        return io.github.sirp00m.npcortex.cplus.CPlusTransport.exchange(url,token,body.toString(),5,24000);
    }
    private static String str(JsonObject o,String key) {
        if(o==null || !o.has(key) || !o.get(key).isJsonPrimitive() ||
                !o.get(key).getAsJsonPrimitive().isString())throw new IllegalArgumentException("missing "+key);
        return o.get(key).getAsString();
    }
    private static JsonObject currentSnapshot(CommandSourceStack src,String name) {
        return CPlusF2WorldSampler.capture(src.getServer(),name);
    }
    private static void checkStatus(CommandSourceStack src,String name,JsonObject row,
                                    String expectedState) {
        if(src.getPlayer()==null ||
                src.getServer().getPlayerList().getPlayer(src.getPlayer().getUUID())==null)
            throw new IllegalArgumentException("requester not online");
        JsonObject snap=currentSnapshot(src,name);
        for(String key:new String[]{"world_id","actor_id","dimension_id","session_id"})
            if(!str(row,key).equals(str(snap,key)))
                throw new IllegalArgumentException("stale identity: "+key);
        if(!str(row,"requester_id").equals(src.getPlayer().getUUID().toString()))
            throw new IllegalArgumentException("foreign requester");
        if(!expectedState.equals(str(row,"state")))
            throw new IllegalArgumentException("G3 expected "+expectedState+" got "+str(row,"state"));
        if(row.get("world_effects").getAsBoolean())
            throw new IllegalArgumentException("world effect not permitted");
        if(C2FollowRuntime.hasActiveActor(UUID.fromString(str(row,"actor_id"))))
            throw new IllegalArgumentException("Follow owns actor");
    }
    private static JsonObject identity(JsonObject row) {
        JsonObject payload=new JsonObject();
        for(String key:new String[]{"task_id","attempt_id","requester_id","world_id",
                "actor_id","session_id","goal_id"})payload.addProperty(key,str(row,key));
        return payload;
    }
    private interface Operation { void apply(CommandSourceStack source,String name,JsonObject status,
                                              URI base,String token,UUID actor,UUID ticket); }
    static boolean pending(MinecraftServer server,UUID actor) {
        return BUSY.containsKey(server) && BUSY.get(server).containsKey(actor);
    }
    private static int request(CommandSourceStack src,String name,Operation operation) {
        if(src.getPlayer()==null){msg(src,"G3 HOLD: player/operator required");return 0;}
        MinecraftServer server=src.getServer();
        C1IdentityData.Entry id=C1IdentityData.get(server).find(name);
        if(id==null){msg(src,"G3 HOLD: unknown actor");return 0;}
        UUID actor=id.actorId(), ticket=UUID.randomUUID();
        if(CPlusTransportRecovery.busy(server,actor) || CPlusE3G2Runtime.pending(server,actor)){msg(src,"HOLD: transport recovery active");return 0;}
        Map<UUID,UUID> pending=BUSY.computeIfAbsent(server,x->new HashMap<>());
        if(pending.putIfAbsent(actor,ticket)!=null){msg(src,"G3 HOLD: operation already pending");return 0;}
        final URI base;final String token;
        try {base=CPlusF1Delivery.safeBackend(NPCortexConfig.BACKEND_URL.get());
            token=CPlusLocalAuth.readToken();}
        catch(IOException|RuntimeException ex){pending.remove(actor,ticket);
            msg(src,"G3 HOLD: local authentication unavailable");return 0;}
        JsonObject lookup=new JsonObject();
        lookup.addProperty("world_id",C1IdentityData.get(server).worldId().toString());
        lookup.addProperty("actor_id",actor.toString());
        sendAsync(server,base,token,"/v1/cplus/e3/status",lookup,(reply,error)->{
            if(!ticket.equals(pending.get(actor)))return;
            if(error!=null){pending.remove(actor,ticket);msg(src,"G3 HOLD: backend status ["+io.github.sirp00m.npcortex.cplus.CPlusTransport.diagnosticCode(error)+"]");return;}
            try {
                JsonObject status=JsonParser.parseString(reply).getAsJsonObject();
                if(!lookup.get("world_id").equals(status.get("world_id")) ||
                        !lookup.get("actor_id").equals(status.get("actor_id")))
                    throw new IllegalArgumentException("backend identity mismatch");
                operation.apply(src,name,status,base,token,actor,ticket);
            }catch(RuntimeException ex){pending.remove(actor,ticket);
                msg(src,"G3 HOLD: "+ex.getMessage());}
        }, () -> {pending.remove(actor,ticket);msg(src,"G3 HOLD: worker busy");});
        msg(src,"G3 terminal operation requested (no mutation)");return 1;
    }
    private interface Callback {void receive(String result,Throwable error);}
    private static void sendAsync(MinecraftServer server,URI base,String token,String route,JsonObject payload,
                                  Callback callback,Runnable queueFailure) {
        try {CompletableFuture.supplyAsync(()->route.endsWith("/status") ? send(base.resolve(route),token,payload)
                        : CPlusOutbox.send(CPlusOutbox.directory(server),base,token,route,payload),worker(server))
                .whenComplete((result,error)->server.execute(()->callback.receive(result,error)));
        }catch(RejectedExecutionException ex){queueFailure.run();}
    }
    private static void terminalAck(CommandSourceStack src,String name,JsonObject admitted,
                                    URI base,String token,UUID actor,UUID ticket,
                                    String route,JsonObject proof,String expected) {
        MinecraftServer server=src.getServer();
        sendAsync(server,base,token,route,proof,(reply,error)->{
            Map<UUID,UUID> pending=BUSY.get(server);
            if(pending==null || !ticket.equals(pending.get(actor)))return;
            pending.remove(actor,ticket);
            if(error!=null){msg(src,"G3 HOLD: terminal ACK ["+io.github.sirp00m.npcortex.cplus.CPlusTransport.diagnosticCode(error)+"]. Inspect attempt; no retry blindly");return;}
            try {
                JsonObject ack=JsonParser.parseString(reply).getAsJsonObject();
                for(String key:new String[]{"task_id","attempt_id","world_id","actor_id","session_id","goal_id"})
                    if(!str(admitted,key).equals(str(ack,key)))
                        throw new IllegalArgumentException("terminal ACK identity mismatch");
                if(!expected.equals(str(ack,"state")) || !ack.get("durable_ack").getAsBoolean()
                        || !ack.get("release_actor_fence").getAsBoolean()
                        || ack.get("world_effects").getAsBoolean()
                        || ack.get("completion_verified").getAsBoolean()!=expected.equals("COMPLETED"))
                    throw new IllegalArgumentException("invalid terminal ACK");
                msg(src,"G3 "+expected+" task="+str(admitted,"task_id")+
                        " durable ACK; actor fence released; no world mutation");
                // TEMP Test-5 observability hook AFTER strict identity + ACK validation.
                CPlusTest5Runtime.onTerminalAck(server,actor,str(admitted,"task_id"),expected);
            }catch(RuntimeException ex){msg(src,"G3 HOLD: invalid terminal ACK; inspect backend");}
        },()->{Map<UUID,UUID> pending=BUSY.get(server);
            if(pending!=null)pending.remove(actor,ticket);
            msg(src,"G3 HOLD: worker busy");});
    }
    static int finalizeMove(CommandSourceStack src,String name) {
        return request(src,name,(source,n,admitted,base,token,actor,ticket)->{
            String finalState=str(admitted,"state");
            if(!java.util.Set.of("C2_ACCEPTED","CANCEL_PENDING").contains(finalState))
                throw new IllegalArgumentException("finalize requires accepted/pending goal");
            checkStatus(source,n,admitted,finalState);
            UUID goal=UUID.fromString(str(admitted,"goal_id"));
            C2AgentRuntime.TerminalWitness witness=C2AgentRuntime.terminalWitness(actor,goal);
            C2ActionJournal.Row journal=C2ActionJournal.get(source.getServer()).find(actor);
            C1IdentityData.Entry id=C1IdentityData.get(source.getServer()).find(n);
            C1PlayerAvatar avatar=C1P0Runtime.activeAvatar(source.getServer(),n);
            if(witness==null || journal==null || avatar==null || !"COMPLETED".equals(journal.state())
                    || !goal.equals(journal.goal()) || !witness.action().equals(journal.action())
                    || !witness.world().equals(UUID.fromString(str(admitted,"world_id")))
                    || !witness.dimension().equals(str(admitted,"dimension_id"))
                    || C2AgentRuntime.hasActiveActor(actor)
                    || C1P1MotionRuntime.c2Receipt(id.profileId()).active()
                    || !"COMPLETE".equals(C1P1MotionRuntime.c2Receipt(id.profileId()).phase())
                    || !"COMPLETE".equals(witness.c1Phase())
                    || !avatar.onGround())
                throw new IllegalArgumentException("live C1/C2/C0 terminal witness unavailable; old HOLD cannot finalize");
            JsonObject candidate=new JsonObject();
            candidate.addProperty("schema_version",1);
            for(String key:new String[]{"task_id","request_id","attempt_id","requester_id","world_id",
                    "dimension_id","session_id","actor_id","observation_id","goal_id"})
                candidate.addProperty(key,str(admitted,key));
            candidate.addProperty("action_id",witness.action().toString());
            candidate.addProperty("node_id","move");candidate.addProperty("action_kind","move_to");
            candidate.add("target",admitted.getAsJsonObject("target").deepCopy());
            candidate.addProperty("tolerance",admitted.get("tolerance").getAsDouble());
            candidate.addProperty("outcome","SUCCEEDED");candidate.addProperty("world_effects",false);
            JsonObject position=new JsonObject();
            position.addProperty("x",avatar.getX());position.addProperty("y",avatar.getY());
            position.addProperty("z",avatar.getZ());
            JsonObject c1=new JsonObject();c1.addProperty("phase",witness.c1Phase());
            c1.addProperty("active",false);c1.add("position",position);candidate.add("c1",c1);
            JsonObject c2=new JsonObject();c2.addProperty("goal_id",goal.toString());
            c2.addProperty("action_id",witness.action().toString());
            c2.addProperty("state","COMPLETED");c2.addProperty("local_verified",true);
            candidate.add("c2",c2);
            JsonObject c0=new JsonObject();c0.addProperty("goal_id",goal.toString());
            c0.addProperty("action_id",witness.action().toString());
            c0.addProperty("status","completed");c0.addProperty("verified",witness.c0Verified());
            candidate.add("c0",c0);
            CPlusE3G3TerminalContract.validateCandidate(candidate,admitted);
            terminalAck(source,n,admitted,base,token,actor,ticket,
                    "/v1/cplus/e3/finalize",candidate,"COMPLETED");
        });
    }
    static int cancelMove(CommandSourceStack src,String name) {
        return request(src,name,(source,n,admitted,base,token,actor,ticket)->{
            String state=str(admitted,"state");
            if(!state.equals("C2_ACCEPTED") && !state.equals("CANCEL_PENDING"))
                throw new IllegalArgumentException("cancel requires accepted/pending goal");
            checkStatus(source,n,admitted,state);
            UUID goal=UUID.fromString(str(admitted,"goal_id"));
            if(!C2AgentRuntime.hasActiveGoal(actor,goal))
                throw new IllegalArgumentException("goal not active; do not cancel Follow or completed goal");
            JsonObject identity=identity(admitted);
            MinecraftServer server=source.getServer();
            sendAsync(server,base,token,"/v1/cplus/e3/cancel-begin",identity,(reply,error)->{
                Map<UUID,UUID> pending=BUSY.get(server);
                if(pending==null || !ticket.equals(pending.get(actor)))return;
                if(error!=null){pending.remove(actor,ticket);
                    msg(source,"G3 HOLD: cancel intent ["+io.github.sirp00m.npcortex.cplus.CPlusTransport.diagnosticCode(error)+"]; NO local cancel");return;}
                try {
                    JsonObject result=JsonParser.parseString(reply).getAsJsonObject();
                    for(String key:new String[]{"task_id","attempt_id","world_id","actor_id","session_id","requester_id","goal_id"})
                        if(!str(admitted,key).equals(str(result,key)))
                            throw new IllegalArgumentException("cancel intent mismatched");
                    if(!"CANCEL_PENDING".equals(str(result,"state")))
                        throw new IllegalArgumentException("cancel intent not durable");
                    checkStatus(source,n,admitted,state);
                    boolean scoped=C2AgentRuntime.cancelGuardedGoal(server,n,goal);
                    pending.remove(actor,ticket);
                    msg(source,scoped
                            ? "G3 CANCEL_PENDING goal="+goal+"; C0 terminal ACK required; run taskmovereconcile later"
                            : "G3 HOLD: goal completed/changed during cancel; intent remains fenced; inspect C2");
                }catch(RuntimeException ex){pending.remove(actor,ticket);
                    msg(source,"G3 HOLD: invalid cancel intent ACK; no motion cancelled");}
            },()->{Map<UUID,UUID> pending=BUSY.get(server);
                if(pending!=null)pending.remove(actor,ticket);
                msg(source,"G3 HOLD: worker busy");});
        });
    }
    static int reconcileCancel(CommandSourceStack src,String name) {
        return request(src,name,(source,n,admitted,base,token,actor,ticket)->{
            checkStatus(source,n,admitted,"CANCEL_PENDING");
            C2ActionJournal.Row journal=C2ActionJournal.get(source.getServer()).find(actor);
            C1IdentityData.Entry id=C1IdentityData.get(source.getServer()).find(n);
            C1PlayerAvatar avatar=C1P0Runtime.activeAvatar(source.getServer(),n);
            UUID goal=UUID.fromString(str(admitted,"goal_id"));
            if(journal==null || !goal.equals(journal.goal()) || !"CANCELLED".equals(journal.state())
                    || C2AgentRuntime.hasActiveActor(actor) ||
                    C1P1MotionRuntime.c2Receipt(id.profileId()).active() ||
                    avatar==null || !avatar.onGround())
                throw new IllegalArgumentException("C2/C0 cancel ACK pending or motion not stopped");
            JsonObject ack=identity(admitted);ack.addProperty("c2_state","CANCELLED");
            if(journal.action()!=null)ack.addProperty("action_id",journal.action().toString());
            else ack.add("action_id",JsonNull.INSTANCE);
            ack.addProperty("c1_stopped",true);
            terminalAck(source,n,admitted,base,token,actor,ticket,
                    "/v1/cplus/e3/cancel-ack",ack,"CANCELLED");
        });
    }
    static void onServerStopping(ServerStoppingEvent event) {
        ExecutorService executor=WORKERS.remove(event.getServer());
        if(executor!=null)executor.shutdownNow();
        BUSY.remove(event.getServer());
    }
}
