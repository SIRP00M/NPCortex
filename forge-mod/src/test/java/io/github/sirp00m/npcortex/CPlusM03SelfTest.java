package io.github.sirp00m.npcortex;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Actual Java outbox/recovery code against explicit HTTP test doubles.
 * Does not assert gameplay or manufacture receipts in a real backend. */
public final class CPlusM03SelfTest {
    static int checks;
    static void check(boolean b,String label){checks++;if(!b)throw new AssertionError(label);}
    public static void main(String[] args)throws Exception{
        Path dir=Files.createTempDirectory("m03-java-");
        HttpServer http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        AtomicReference<JsonObject> status=new AtomicReference<>(), response=new AtomicReference<>();
        AtomicInteger sends=new AtomicInteger();
        http.createContext("/v1/cplus/e3/",e->{
            e.getRequestBody().readAllBytes();
            boolean query=e.getRequestURI().getPath().endsWith("/status");
            if(!query)sends.incrementAndGet();
            byte[] bytes=(query?status.get():response.get()).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();
        });http.start();
        URI base=URI.create("http://127.0.0.1:"+http.getAddress().getPort());String token="m03-test-token-never-production-secret";
        try {
            JsonObject b=new JsonObject();
            for(String k:new String[]{"task_id","request_id","attempt_id","requester_id","world_id","actor_id","session_id"})b.addProperty(k,UUID.randomUUID().toString());
            b.addProperty("dimension_id","minecraft:overworld");
            JsonObject snap=new JsonObject();snap.addProperty("observation_id",UUID.randomUUID().toString());b.add("snapshot",snap);
            JsonObject row=b.deepCopy();row.remove("snapshot");row.add("observation_id",snap.get("observation_id"));
            row.addProperty("state","RESERVED");row.addProperty("world_effects",false);status.set(row);response.set(row);
            JsonObject scope=new JsonObject();for(String k:new String[]{"world_id","actor_id","session_id","requester_id"})scope.add(k,b.get(k));
            Path box=dir.resolve("transport");
            CPlusM03Fault.arm(box,"reserve",scope,false);
            JsonObject foreign=b.deepCopy();foreign.addProperty("actor_id",UUID.randomUUID().toString());
            CPlusM03Fault.check(box,"/v1/cplus/e3/reserve",foreign,false);
            CPlusM03Fault.check(box,"/v1/cplus/e3/report",b,false);
            try{CPlusOutbox.send(box,base,token,"/v1/cplus/e3/reserve",b);throw new AssertionError("before-send missing");}
            catch(IllegalStateException expected){check(sends.get()==0,"never reached backend");}
            Path reserve=CPlusOutbox.file(box,"/reserve",b);
            check(!CPlusOutbox.read(reserve).get("handoff_possible").getAsBoolean(),"durable before-send no handoff");
            CPlusM03Fault.arm(box,"reserve",scope,true);
            try{CPlusOutbox.send(box,base,token,"/v1/cplus/e3/reserve",b);throw new AssertionError("response-loss missing");}
            catch(IllegalStateException expected){check(sends.get()==1,"response lost AFTER server success");}
            check(!CPlusOutbox.read(reserve).get("handoff_possible").getAsBoolean(),"reserve response withheld before handoff");
            byte[] original=Files.readAllBytes(reserve);
            String requester=b.get("requester_id").getAsString();
            for(String key:new String[]{"task_id","request_id","attempt_id","requester_id","observation_id"}){
                JsonObject bad=row.deepCopy();bad.addProperty(key,UUID.randomUUID().toString());status.set(bad);
                String out=CPlusTransportRecovery.recoverFiles(box,base,token,b,requester);
                check(out.contains("HOLD"),"stale "+key+" HOLD");check(sends.get()==1,"no send for stale "+key);
            }
            status.set(row);JsonObject terminal=row.deepCopy();terminal.addProperty("state","CANCELLED");
            terminal.addProperty("durable_ack",true);terminal.addProperty("release_actor_fence",true);terminal.addProperty("completion_verified",false);response.set(terminal);
            check(CPlusTransportRecovery.recoverFiles(box,base,token,b,requester).contains("unstarted reserve cancelled"),"metadata abandon");
            check(Arrays.equals(original,Files.readAllBytes(reserve)),"original reserve preserved");
            status.set(terminal);int previous=sends.get();
            check(CPlusTransportRecovery.recoverFiles(box,base,token,b,requester).contains("no replay needed"),"already terminal");
            check(sends.get()==previous,"no duplicate terminal send");
            JsonObject record=CPlusOutbox.read(reserve);record.addProperty("handoff_possible",true);CPlusOutbox.write(reserve,record);
            JsonObject pending=row.deepCopy();pending.addProperty("state","CANCEL_PENDING");status.set(pending);
            JsonObject proof=b.deepCopy();proof.addProperty("goal_id",UUID.randomUUID().toString());
            pending.add("goal_id",proof.get("goal_id"));terminal.add("goal_id",proof.get("goal_id"));
            JsonObject saved=new JsonObject();saved.addProperty("route","/v1/cplus/e3/cancel-ack");saved.add("body",proof);saved.addProperty("handoff_possible",false);
            CPlusOutbox.write(CPlusOutbox.file(box,"/cancel-ack",proof),saved);
            JsonObject wrongGoal=pending.deepCopy();wrongGoal.addProperty("goal_id",UUID.randomUUID().toString());status.set(wrongGoal);
            previous=sends.get();
            check(CPlusTransportRecovery.recoverFiles(box,base,token,b,requester).contains("HOLD"),"stale terminal goal HOLD");
            check(sends.get()==previous,"stale goal no metadata sent");status.set(pending);
            JsonObject wrongRequester=saved.deepCopy();wrongRequester.getAsJsonObject("body").addProperty("requester_id",UUID.randomUUID().toString());
            CPlusOutbox.write(CPlusOutbox.file(box,"/cancel-ack",proof),wrongRequester);
            check(CPlusTransportRecovery.recoverFiles(box,base,token,b,requester).contains("HOLD"),"foreign terminal requester HOLD");
            check(sends.get()==previous,"foreign requester no send");CPlusOutbox.write(CPlusOutbox.file(box,"/cancel-ack",proof),saved);
            for(String flag:new String[]{"durable_ack","release_actor_fence"}){
                JsonObject invalid=terminal.deepCopy();invalid.addProperty(flag,false);response.set(invalid);
                try{CPlusTransportRecovery.recoverFiles(box,base,token,b,requester);throw new AssertionError("invalid ACK accepted");}
                catch(IllegalStateException expected){check(true,"invalid terminal "+flag);}
            }
            response.set(terminal);
            check(CPlusTransportRecovery.recoverFiles(box,base,token,b,requester).contains("metadata cancel-ack delivered"),"valid terminal recovery");
            JsonObject stale=b.deepCopy();stale.addProperty("session_id",UUID.randomUUID().toString());previous=sends.get();
            check(CPlusTransportRecovery.recoverFiles(box,base,token,stale,requester).contains("HOLD"),"rejoin stale session HOLD");
            check(sends.get()==previous,"rejoin no replay");
            CPlusM03Fault.arm(box,"cancel-ack",scope,true);
            try{CPlusOutbox.send(box,base,token,"/v1/cplus/e3/cancel-ack",proof);throw new AssertionError("terminal loss missing");}
            catch(IllegalStateException expected){check(true,"terminal success withheld");}
            check(CPlusOutbox.read(CPlusOutbox.file(box,"/cancel-ack",proof)).equals(saved),"terminal original persistence");
            System.out.println("PASS M03 outbox/fault/recovery "+checks+" assertions; NOT gameplay");
        }finally{
            http.stop(0);
            try(var paths=Files.walk(dir)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}
        }
    }
}
