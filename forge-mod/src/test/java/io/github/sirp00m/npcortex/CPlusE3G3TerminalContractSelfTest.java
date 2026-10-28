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
        // Shared arrival-position vectors (the same file is replayed by the Python test).
        Path vectorFile=Path.of(args.length>1?args[1]:"../docs/contracts/e3_g3_position_vectors_v1.json");
        com.google.gson.JsonArray vectors=JsonParser.parseString(Files.readString(vectorFile))
                .getAsJsonObject().getAsJsonArray("vectors");
        if(vectors.size()<30) throw new AssertionError("position vectors missing");
        for(com.google.gson.JsonElement element: vectors) {
            JsonObject vec=element.getAsJsonObject();
            com.google.gson.JsonArray tg=vec.getAsJsonArray("target"), ps=vec.getAsJsonArray("position");
            JsonObject cand=c.deepCopy(), adm=a.deepCopy();
            for(int i=0;i<3;i++) {
                String axis=new String[]{"x","y","z"}[i];
                cand.getAsJsonObject("target").addProperty(axis,tg.get(i).getAsInt());
                adm.getAsJsonObject("target").addProperty(axis,tg.get(i).getAsInt());
                cand.getAsJsonObject("c1").getAsJsonObject("position").addProperty(axis,ps.get(i).getAsDouble());
            }
            checks++;
            boolean expectPass="PASS".equals(vec.get("expect").getAsString());
            try {
                CPlusE3G3TerminalContract.validateCandidate(cand,adm);
                if(!expectPass) throw new AssertionError("vector must be REJECTED: "+vec.get("name").getAsString());
            } catch(IllegalArgumentException ex) {
                if(expectPass || !ex.getMessage().contains("outside tolerance"))
                    throw new AssertionError("vector verdict mismatch: "+vec.get("name").getAsString()+" -> "+ex.getMessage());
            }
        }
        double[] centre=CPlusE3G3TerminalContract.arrivalPoint(-3,-60,-8);
        if(centre[0]!=-2.5||centre[1]!=-60.0||centre[2]!=-7.5) throw new AssertionError("arrivalPoint contract");
        checks++;
        System.out.println("PASS E3-D G3-1 dry-run terminal contract checks="+checks+
                "; NO terminal state write; NO actor fence release");
    }
}
