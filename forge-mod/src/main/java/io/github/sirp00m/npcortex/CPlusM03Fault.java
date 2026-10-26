package io.github.sirp00m.npcortex;

import com.google.gson.*;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;

/** TEMP M03: explicit one-shot scope, disabled until an online OP arms it.
 * AFTER_RESPONSE drops a successful response before runtime handoff, outside
 * transport retry. It does not manufacture a backend ACK or change payloads. */
final class CPlusM03Fault {
    private record Fault(String route, JsonObject scope, boolean after, long deadline) {}
    private static final Map<Path,Fault> ARMED=new HashMap<>();
    static synchronized void arm(Path dir,String route,JsonObject scope,boolean after) {
        if(!Set.of("reserve","finalize","cancel-ack").contains(route))throw new IllegalArgumentException("route");
        if(ARMED.containsKey(dir))throw new IllegalStateException("fault already armed; disable first");
        ARMED.put(dir,new Fault("/v1/cplus/e3/"+route,scope.deepCopy(),after,System.nanoTime()+60_000_000_000L));
    }
    static synchronized void clear(Path dir){ARMED.remove(dir);}
    static synchronized void check(Path dir,String route,JsonObject body,boolean after) throws IOException {
        Fault f=ARMED.get(dir);
        if(f==null)return;
        if(System.nanoTime()>f.deadline){ARMED.remove(dir);return;}
        if(!f.route.equals(route)||f.after!=after)return;
        for(var e:f.scope.entrySet())if(!e.getValue().equals(body.get(e.getKey())))return;
        ARMED.remove(dir);
        JsonObject evidence=new JsonObject();evidence.addProperty("kind",after?"AFTER_SUCCESSFUL_RESPONSE":"BEFORE_NETWORK_SEND");
        evidence.addProperty("route",route);evidence.add("identity",f.scope);evidence.add("task_id",body.get("task_id"));
        evidence.add("attempt_id",body.get("attempt_id"));
        Path output=dir.getParent().resolve("m03-evidence").resolve("fault-"+UUID.randomUUID()+".json");
        CPlusOutbox.write(output,evidence);
        if(after)throw new IOException("M03 scoped response withheld after backend success");
        throw new java.net.ConnectException("M03 scoped before-send outage");
    }
}
