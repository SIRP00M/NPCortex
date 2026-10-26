package io.github.sirp00m.npcortex;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** G3-1 Java17/Gson fixture parity, no Forged terminal transition or Minecraft world access. */
public final class CPlusE3G3TerminalContractSelfTest {
    private static int checks;
    private static void reject(JsonObject candidate, JsonObject admitted) {
        checks++;
        try { CPlusE3G3TerminalContract.validateCandidate(candidate, admitted); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected G3-1 rejection #"+checks);
    }
    private static void altered(JsonObject base,JsonObject admitted,String key,String replacement) {
        JsonObject copy=base.deepCopy();copy.addProperty(key,replacement);reject(copy,admitted);
        copy=base.deepCopy();copy.remove(key);reject(copy,admitted);
    }
    public static void main(String[] args) throws Exception {
        Path fixture=Path.of(args.length>0?args[0]:"../docs/contracts/e3_g3_terminal_candidate_v1.json");
        JsonObject root=JsonParser.parseString(Files.readString(fixture)).getAsJsonObject();
        JsonObject c=root.getAsJsonObject("candidate"), a=root.getAsJsonObject("admitted");
        JsonObject result=CPlusE3G3TerminalContract.validateCandidate(c,a);
        if(!"DRY_RUN_VALIDATED".equals(result.get("state").getAsString()) ||
                result.get("completion_verified").getAsBoolean() ||
                result.get("release_actor_fence").getAsBoolean() ||
                result.get("durable_ack").getAsBoolean())
            throw new AssertionError("G3-1 must never prove completion or unlock");
        checks++;
        for (String key: new String[]{"task_id","request_id","attempt_id","requester_id",
                "world_id","dimension_id","session_id","actor_id","observation_id","goal_id"}) {
            altered(c,a,key,key.equals("dimension_id")?"minecraft:the_nether":UUID.randomUUID().toString());
        }
        for(String key: new String[]{"goal_id","action_id"}) {
            JsonObject x=c.deepCopy();x.getAsJsonObject("c2").addProperty(key,UUID.randomUUID().toString());reject(x,a);
            x=c.deepCopy();x.getAsJsonObject("c0").addProperty(key,UUID.randomUUID().toString());reject(x,a);
        }
        JsonObject x=c.deepCopy();x.getAsJsonObject("c1").addProperty("active",true);reject(x,a);
        x=c.deepCopy();x.getAsJsonObject("c1").getAsJsonObject("position").addProperty("x",150);reject(x,a);
        x=c.deepCopy();x.getAsJsonObject("c0").addProperty("verified",false);reject(x,a);
        x=c.deepCopy();x.getAsJsonObject("c2").addProperty("local_verified",false);reject(x,a);
        x=c.deepCopy();x.addProperty("world_effects",true);reject(x,a);
        x=c.deepCopy();x.addProperty("outcome","CANCELLED");reject(x,a);
        x=c.deepCopy();x.addProperty("unexpected",true);reject(x,a);
        x=c.deepCopy();x.getAsJsonObject("target").addProperty("x",101.0);reject(x,a);
        x=c.deepCopy();x.addProperty("tolerance",1.5);reject(x,a);
        x=c.deepCopy();x.addProperty("schema_version",1.0);reject(x,a);
        x=a.deepCopy();x.addProperty("state","HOLD");reject(c,x);
        System.out.println("PASS E3-D G3-1 dry-run terminal contract checks="+checks+
                "; NO terminal state write; NO actor fence release");
    }
}
