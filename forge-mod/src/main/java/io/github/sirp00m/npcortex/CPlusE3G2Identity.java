package io.github.sirp00m.npcortex;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** G2 Reserve/Report response correlation. No Forge/world mutation dependency.
 * The request's observation_id is part of the trusted C-F2 snapshot, whereas
 * the backend's reserve/report replies expose that ID as a top-level field.
 */
final class CPlusE3G2Identity {
    private CPlusE3G2Identity() {}

    static void requireIdentity(JsonObject sent,JsonObject received) {
        if(sent==null || received==null)
            throw new IllegalArgumentException("G2 identity missing request/reply");
        for(String key:new String[]{"task_id","request_id","attempt_id","requester_id",
                "world_id","dimension_id","session_id","actor_id"})
            requireMatch(sent,received,key);

        // The snapshot must be present and bound to the same actor and world.
        if(!sent.has("snapshot") || !sent.get("snapshot").isJsonObject())
            throw new IllegalArgumentException("G2 identity mismatch: snapshot");
        JsonObject snapshot=sent.getAsJsonObject("snapshot");
        for(String key:new String[]{"world_id","dimension_id","session_id","actor_id"})
            requireMatch(snapshot,sent,key);

        // CRITICAL: sent has snapshot.observation_id (not sent.observation_id).
        // Backend reserve AND report reply use response.observation_id.
        requireMatch(snapshot,received,"observation_id");

        // Read-only operation: do not accept absent, coerced, or true mutation flag.
        JsonElement effects=received.get("world_effects");
        if(effects==null || !effects.isJsonPrimitive()
                || !effects.getAsJsonPrimitive().isBoolean() || effects.getAsBoolean())
            throw new IllegalArgumentException("G2 cannot mutate blocks");
    }

    private static void requireMatch(JsonObject expected,JsonObject actual,String key) {
        JsonElement source=expected.get(key);
        JsonElement destination=actual.get(key);
        if(source==null || destination==null || !source.isJsonPrimitive()
                || !destination.isJsonPrimitive() || !source.getAsJsonPrimitive().isString()
                || !destination.getAsJsonPrimitive().isString() || !source.equals(destination))
            throw new IllegalArgumentException("G2 identity mismatch: "+key);
    }
}
