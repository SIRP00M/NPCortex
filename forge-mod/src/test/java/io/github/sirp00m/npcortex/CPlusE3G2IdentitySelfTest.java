package io.github.sirp00m.npcortex;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.UUID;

/** Standalone Java17/Gson G2 reserve+report identity and fail-closed regression.
 * Invoked via Gradle verifyG2Identity; no Forge client or world needed.
 */
public final class CPlusE3G2IdentitySelfTest {
    private static int checks;
    private static void pass(JsonObject sent,JsonObject response) {
        CPlusE3G2Identity.requireIdentity(sent,response);
        checks++;
    }
    private static void rejects(JsonObject sent,JsonObject response) {
        checks++;
        try { CPlusE3G2Identity.requireIdentity(sent,response); }
        catch(IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected G2 reply rejection on check "+checks);
    }
    private static String id() { return UUID.randomUUID().toString(); }
    private static JsonObject newRequest() {
        JsonObject req=new JsonObject();
        JsonObject snap=new JsonObject();
        for(String key:new String[]{"task_id","request_id","attempt_id","requester_id",
                "world_id","dimension_id","session_id","actor_id"}) {
            req.addProperty(key,id());
            if(key.equals("world_id") || key.equals("dimension_id")
                    || key.equals("session_id") || key.equals("actor_id"))
                snap.addProperty(key,req.get(key).getAsString());
        }
        snap.addProperty("observation_id",id());
        req.add("snapshot",snap);
        return req;
    }
    private static JsonObject newReply(JsonObject request,String state) {
        JsonObject reply=new JsonObject();
        for(String key:new String[]{"task_id","request_id","attempt_id","requester_id",
                "world_id","dimension_id","session_id","actor_id"})
            reply.addProperty(key,request.get(key).getAsString());
        reply.addProperty("observation_id",request.getAsJsonObject("snapshot")
                .get("observation_id").getAsString());
        reply.addProperty("state",state);
        reply.addProperty("world_effects",false);
        return reply;
    }
    public static void main(String[] args) {
        JsonObject req=newRequest();
        JsonObject reserve=newReply(req,"RESERVED");
        JsonObject report=newReply(req,"C2_ACCEPTED");
        // Valid G2 request has NO top-level observation_id; both phases pass.
        if(req.has("observation_id"))throw new AssertionError("fixture must be nested");
        pass(req,reserve);
        pass(req,report);
        for(String state:new String[]{"RESERVED","C2_ACCEPTED"}) {
            JsonObject good=newReply(req,state);
            for(String key:new String[]{"task_id","request_id","attempt_id","requester_id",
                    "world_id","dimension_id","session_id","actor_id","observation_id"}) {
                JsonObject changed=good.deepCopy();changed.addProperty(key,id());
                rejects(req,changed);
                JsonObject missing=good.deepCopy();missing.remove(key);
                rejects(req,missing);
            }
            JsonObject mutation=good.deepCopy();mutation.addProperty("world_effects",true);
            rejects(req,mutation);
            JsonObject coerced=good.deepCopy();coerced.addProperty("world_effects","false");
            rejects(req,coerced);
            JsonObject absent=good.deepCopy();absent.remove("world_effects");
            rejects(req,absent);
            JsonObject nullEffects=good.deepCopy();nullEffects.add("world_effects",JsonNull.INSTANCE);
            rejects(req,nullEffects);
        }
        JsonObject missingSnapshot=req.deepCopy();missingSnapshot.remove("snapshot");
        rejects(missingSnapshot,reserve);
        JsonObject missingObserved=req.deepCopy();missingObserved.getAsJsonObject("snapshot")
                .remove("observation_id");
        rejects(missingObserved,report);
        JsonObject swappedSnapshot=req.deepCopy();swappedSnapshot.getAsJsonObject("snapshot")
                .addProperty("session_id",id());
        rejects(swappedSnapshot,reserve);
        System.out.println("PASS E3-D G2 Fix #1 Java identity checks="+checks);
    }
}
