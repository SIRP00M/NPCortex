package io.github.sirp00m.npcortex;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.Set;
import java.util.UUID;

/** G3-1 strict, pure typed TERMINAL CANDIDATE contract.
 * This is NOT a proof of C1 motion, C2 completion or C0 verification.
 * No caller may interpret successful shape validation as C+ COMPLETED.
 * Live authoritative evidence and durable ACK belong exclusively to G3-2.
 */
final class CPlusE3G3TerminalContract {
    private static final Set<String> ID_FIELDS = Set.of(
            "task_id", "request_id", "attempt_id", "requester_id", "world_id",
            "dimension_id", "session_id", "actor_id", "observation_id", "goal_id");
    private static final Set<String> ALL_FIELDS = Set.of(
            "schema_version", "task_id", "request_id", "attempt_id", "requester_id",
            "world_id", "dimension_id", "session_id", "actor_id", "observation_id",
            "goal_id", "action_id", "node_id", "action_kind", "target", "tolerance",
            "outcome", "c1", "c2", "c0", "world_effects");
    private CPlusE3G3TerminalContract() {}

    static JsonObject validateCandidate(JsonObject candidate, JsonObject admitted) {
        requireFields(candidate, ALL_FIELDS, "candidate");
        if (admitted == null) throw new IllegalArgumentException("G3-1 admission missing");
        JsonElement version = candidate.get("schema_version");
        if (version==null || !version.isJsonPrimitive() ||
                !version.getAsJsonPrimitive().isNumber() || !"1".equals(version.getAsString()))
            throw new IllegalArgumentException("G3-1 version");
        for (String key : ID_FIELDS) {
            String value = string(candidate, key);
            if (key.equals("dimension_id")) {
                if (!value.matches("[a-z0-9_.-]+:[a-z0-9_./-]{1,120}"))
                    throw new IllegalArgumentException("G3-1 invalid dimension");
            } else validateUUID(value, key);
            if (!value.equals(string(admitted, key)))
                throw new IllegalArgumentException("G3-1 admission mismatch "+key);
        }
        validateUUID(string(candidate,"action_id"),"action_id");
        if (!java.util.Set.of("C2_ACCEPTED","CANCEL_PENDING").contains(string(admitted,"state")))
            throw new IllegalArgumentException("G3-1 unresolved actor fence");
        requireEqual(candidate, "node_id", "move");
        requireEqual(candidate, "action_kind", "move_to");
        requireEqual(candidate, "outcome", "SUCCEEDED");
        if (flag(candidate,"world_effects"))
            throw new IllegalArgumentException("G3-1 world effect forbidden");
        JsonObject target = object(candidate,"target");
        JsonObject acceptedTarget = object(admitted,"target");
        requireFields(target,Set.of("x","y","z"),"target");
        requireFields(acceptedTarget,Set.of("x","y","z"),"admitted target");
        for (String axis : new String[]{"x","y","z"}) {
            int coordinate = coordinate(target, axis);
            if (coordinate != coordinate(acceptedTarget,axis))
                throw new IllegalArgumentException("G3-1 target mismatch");
        }
        double tolerance=number(candidate,"tolerance");
        if (!Double.isFinite(tolerance) || tolerance < 0.20 || tolerance > 0.75 ||
                tolerance != number(admitted,"tolerance"))
            throw new IllegalArgumentException("G3-1 tolerance mismatch");
        JsonObject c1=object(candidate,"c1");
        requireFields(c1,Set.of("phase","active","position"),"c1");
        requireEqual(c1,"phase","COMPLETE");
        if (flag(c1,"active")) throw new IllegalArgumentException("G3-1 motion still active");
        JsonObject position=object(c1,"position");
        requireFields(position,Set.of("x","y","z"),"position");
        double distanceSq=0.0;
        for (String axis : new String[]{"x","y","z"}) {
            double p=number(position,axis), t=coordinate(target,axis);
            if(!Double.isFinite(p) || Math.abs(p)>=16777216)
                throw new IllegalArgumentException("G3-1 invalid position");
            distanceSq += (p-t)*(p-t);
        }
        if (distanceSq > tolerance*tolerance)
            throw new IllegalArgumentException("G3-1 position outside tolerance");
        JsonObject c2=object(candidate,"c2");
        requireFields(c2,Set.of("goal_id","action_id","state","local_verified"),"c2");
        requireMatch(candidate,c2,"goal_id");requireMatch(candidate,c2,"action_id");
        requireEqual(c2,"state","COMPLETED");
        if (!flag(c2,"local_verified")) throw new IllegalArgumentException("G3-1 C2 not verified");
        JsonObject c0=object(candidate,"c0");
        requireFields(c0,Set.of("goal_id","action_id","status","verified"),"c0");
        requireMatch(candidate,c0,"goal_id");requireMatch(candidate,c0,"action_id");
        requireEqual(c0,"status","completed");
        if (!flag(c0,"verified")) throw new IllegalArgumentException("G3-1 C0 not verified");
        JsonObject result = new JsonObject();
        result.addProperty("schema_version",1);
        for (String key:new String[]{"task_id","attempt_id","goal_id","action_id"})
            result.addProperty(key,string(candidate,key));
        result.addProperty("contract_valid",true);
        result.addProperty("state","DRY_RUN_VALIDATED");
        result.addProperty("completion_verified",false);
        result.addProperty("durable_ack",false);
        result.addProperty("release_actor_fence",false);
        result.addProperty("world_effects",false);
        return result;
    }

    private static void validateUUID(String raw, String key) {
        try {
            UUID id=UUID.fromString(raw);
            if (id.getMostSignificantBits()==0 && id.getLeastSignificantBits()==0 || !id.toString().equals(raw))
                throw new IllegalArgumentException("G3-1 noncanonical "+key);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("G3-1 invalid UUID "+key,ex);
        }
    }
    private static void requireFields(JsonObject o, Set<String> fields, String key) {
        if (o==null || !o.keySet().equals(fields))
            throw new IllegalArgumentException("G3-1 unexpected fields: "+key);
    }
    private static JsonObject object(JsonObject parent,String key) {
        JsonElement item=parent.get(key);
        if (item==null || !item.isJsonObject()) throw new IllegalArgumentException("G3-1 object "+key);
        return item.getAsJsonObject();
    }
    private static String string(JsonObject object,String key) {
        if (object==null) throw new IllegalArgumentException("G3-1 missing "+key);
        JsonElement item=object.get(key);
        if(item==null || !item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("G3-1 string "+key);
        return item.getAsString();
    }
    private static void requireEqual(JsonObject o,String key,String required) {
        if(!required.equals(string(o,key)))throw new IllegalArgumentException("G3-1 invalid "+key);
    }
    private static void requireMatch(JsonObject left,JsonObject right,String key) {
        if(!string(left,key).equals(string(right,key)))
            throw new IllegalArgumentException("G3-1 evidence mismatch "+key);
    }
    private static boolean flag(JsonObject o,String key) {
        JsonElement item=o.get(key);
        if(item==null || !item.isJsonPrimitive() || !item.getAsJsonPrimitive().isBoolean())
            throw new IllegalArgumentException("G3-1 boolean "+key);
        return item.getAsBoolean();
    }
    private static Double number(JsonObject o,String key) {
        JsonElement item=o.get(key);
        if(item==null || !item.isJsonPrimitive() || !item.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("G3-1 number "+key);
        return item.getAsDouble();
    }
    private static int coordinate(JsonObject o,String key) {
        JsonElement item=o.get(key);
        if (item==null || !item.isJsonPrimitive() || !item.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("G3-1 coordinate "+key);
        String raw=item.getAsString();
        // Python requires JSON integers, not floats, booleans, or exponent notation.
        if (!raw.matches("-?(?:0|[1-9][0-9]*)"))
            throw new IllegalArgumentException("G3-1 coordinate must be integer");
        long value;
        try {value=Long.parseLong(raw);} catch(NumberFormatException ex){throw new IllegalArgumentException("G3-1 coordinate",ex);}
        if(value<=-16777216 || value>=16777216)
            throw new IllegalArgumentException("G3-1 coordinate out of bounds");
        return (int)value;
    }
}
