package io.github.sirp00m.npcortex;

import com.google.gson.*;
import io.github.sirp00m.npcortex.cplus.*;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import java.nio.file.*;
import java.net.URI;
import java.util.concurrent.*;

/** Explicit metadata reconciliation only. No call to navigation or C1 movement. */
final class CPlusTransportRecovery {
    private static final java.util.Map<net.minecraft.server.MinecraftServer,java.util.Set<java.util.UUID>> BUSY=new java.util.IdentityHashMap<>();
    static boolean busy(net.minecraft.server.MinecraftServer server,java.util.UUID actor){
        return BUSY.containsKey(server) && BUSY.get(server).contains(actor);
    }
    private static final ExecutorService WORKER=new ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(8),r->{Thread t=new Thread(r,"npcortex-transport-recovery");t.setDaemon(true);return t;},
            new ThreadPoolExecutor.AbortPolicy());
    static int recover(CommandSourceStack src,String name){
        if(src.getPlayer()==null)return 0;
        final JsonObject snapshot;final Path dir;final URI base;final String token,requester;
        try{
            snapshot=CPlusF2WorldSampler.capture(src.getServer(),name);dir=CPlusOutbox.directory(src.getServer());
            base=CPlusF1Delivery.safeBackend(NPCortexConfig.BACKEND_URL.get());token=CPlusLocalAuth.readToken();
            requester=src.getPlayer().getUUID().toString();
        }catch(Exception e){message(src,"HOLD: "+e.getMessage());return 0;}
        var server=src.getServer();var actor=java.util.UUID.fromString(snapshot.get("actor_id").getAsString());
        if(busy(server,actor) || CPlusE3G2Runtime.pending(server,actor) || CPlusE3G3Runtime.pending(server,actor)){
            message(src,"HOLD: another transport operation pending");return 0;}
        BUSY.computeIfAbsent(server,k->new java.util.HashSet<>()).add(actor);
        try{
            CompletableFuture.supplyAsync(()->recoverFiles(dir,base,token,snapshot,requester),WORKER)
                .whenComplete((result,error)->server.execute(()->{BUSY.get(server).remove(actor);
                    message(src,error==null?result:"HOLD: "+error.getMessage());}));
        }catch(RejectedExecutionException e){BUSY.get(server).remove(actor);message(src,"HOLD: recovery worker busy");return 0;}
        message(src,"metadata recovery queued; no navigation replay");return 1;
    }
    static String recoverFiles(Path dir,URI base,String token,JsonObject snap,String requester){
        try {
            if(!Files.isDirectory(dir))return "no durable transport records";
            JsonObject lookup=new JsonObject();lookup.add("world_id",snap.get("world_id"));lookup.add("actor_id",snap.get("actor_id"));
            JsonObject status=JsonParser.parseString(CPlusTransport.post(base.resolve("/v1/cplus/e3/status"),token,lookup.toString(),5,24000)).getAsJsonObject();
            for(String key:new String[]{"world_id","actor_id"})
                if(!lookup.get(key).equals(status.get(key)))throw new IllegalStateException("status identity mismatch");
            if("NONE".equals(status.get("state").getAsString()))return "backend NONE; no movement replay (inspect unsent reserve records)";
            String state=status.get("state").getAsString();
            Path reserve=CPlusOutbox.file(dir,"/reserve",status);
            if(!Files.exists(reserve))return "HOLD: no durable reserve provenance for backend attempt";
            JsonObject record=CPlusOutbox.read(reserve), body=record.getAsJsonObject("body");
            for(String key:new String[]{"task_id","request_id","attempt_id","requester_id","observation_id"}) {
                JsonElement original=key.equals("observation_id")?body.getAsJsonObject("snapshot").get(key):body.get(key);
                if(original==null || !original.equals(status.get(key)))
                    return "HOLD: backend attempt/provenance mismatch; no metadata replay";
            }
            for(String key:new String[]{"world_id","actor_id","session_id","dimension_id"})
                if(!snap.get(key).equals(body.get(key)) || !body.get(key).equals(status.get(key)))
                    return "HOLD: stale session/identity; no metadata replay";
            if(!requester.equals(body.get("requester_id").getAsString()))return "HOLD: foreign requester";
            if(state.equals("COMPLETED") || state.equals("CANCELLED"))return "durable backend terminal="+state+"; no replay needed";
            if(!record.get("handoff_possible").getAsBoolean()){
                JsonObject proof=new JsonObject();
                for(String key:new String[]{"task_id","attempt_id","world_id","actor_id","session_id","requester_id"})proof.add(key,body.get(key));
                proof.addProperty("no_handoff",true);
                JsonObject ack=JsonParser.parseString(CPlusOutbox.send(dir,base,token,"/v1/cplus/e3/abandon",proof)).getAsJsonObject();
                for(String key:new String[]{"task_id","attempt_id","world_id","actor_id","session_id"})
                    if(!proof.get(key).equals(ack.get(key)))throw new IllegalStateException("abandon ACK identity mismatch");
                if(!"CANCELLED".equals(ack.get("state").getAsString()) || !ack.get("durable_ack").getAsBoolean()
                        || !ack.get("release_actor_fence").getAsBoolean() || ack.get("world_effects").getAsBoolean())
                    throw new IllegalStateException("invalid abandon ACK");
                return "unstarted reserve cancelled; no motion was replayed";
            }
            String route=state.equals("RESERVED")?"report":state.equals("CANCEL_PENDING")?"cancel-ack":
                    state.equals("C2_ACCEPTED")?"finalize":null;
            if(route==null)return "HOLD: restart/ambiguous handoff; live proof required";
            Path file=CPlusOutbox.file(dir,"/"+route,status);
            if(!Files.exists(file))return "HOLD: no durable "+route+" evidence; for CANCEL_PENDING use taskmovecancel then taskmovereconcile";
            JsonObject saved=CPlusOutbox.read(file);JsonObject payload=saved.getAsJsonObject("body");
            for(String key:new String[]{"task_id","attempt_id","world_id","actor_id","session_id"})
                if(!body.get(key).equals(payload.get(key)))throw new IllegalStateException("outbox identity mismatch");
            if(!route.equals("report") && (!body.get("requester_id").equals(payload.get("requester_id"))
                    || payload.get("goal_id")==null || !payload.get("goal_id").equals(status.get("goal_id"))))
                return "HOLD: terminal requester/goal mismatch; no metadata replay";
            JsonObject ack=JsonParser.parseString(CPlusOutbox.send(dir,base,token,"/v1/cplus/e3/"+route,payload)).getAsJsonObject();
            for(String key:new String[]{"task_id","attempt_id","world_id","actor_id","session_id"})
                if(!payload.get(key).equals(ack.get(key)))throw new IllegalStateException("recovery ACK identity mismatch");
            String expected=route.equals("report")?payload.get("state").getAsString():route.equals("finalize")?"COMPLETED":"CANCELLED";
            if(!expected.equals(ack.get("state").getAsString()) || ack.get("world_effects").getAsBoolean())
                throw new IllegalStateException("recovery ACK state mismatch");
            if(!route.equals("report") && (!ack.get("durable_ack").getAsBoolean()
                    || !ack.get("release_actor_fence").getAsBoolean()
                    || !payload.get("goal_id").equals(ack.get("goal_id"))
                    || ack.get("completion_verified").getAsBoolean()!=route.equals("finalize")))
                throw new IllegalStateException("recovery terminal ACK invalid");
            return "metadata "+route+" delivered; run taskmovestatus; no navigation replay";
        }catch(Exception e){throw new IllegalStateException(e.getMessage(),e);}
    }
    private static void message(CommandSourceStack src,String text){src.sendSuccess(()->Component.literal("NPCortex transport: "+text),false);}
}
