package io.github.sirp00m.npcortex;

import com.google.gson.JsonObject;

import java.util.Locale;
import java.util.UUID;

/**
 * The /v1/actors body a C1 NPC sends when C2 registers it. Kept apart from C2AgentRuntime so the
 * name-versus-pack rule is testable: only a LEGACY NPC (no saved profile mode) derives a Character Pack
 * id from its name. Every newer NPC names its profile source explicitly.
 */
final class C2RegistrationPayload {
    private C2RegistrationPayload() {}

    /** @throws IllegalStateException when the saved profile mode is unreadable (never guess a personality). */
    static JsonObject build(UUID world, String dimension, C1IdentityData.Entry entry) {
        JsonObject actor = new JsonObject();
        actor.addProperty("world_id", world.toString());
        actor.addProperty("dimension_id", dimension);
        actor.addProperty("actor_id", entry.actorId().toString());
        switch (entry.profileMode()) {
            case "" -> actor.addProperty("character_id", entry.character().toLowerCase(Locale.ROOT));
            case C1IdentityData.MODE_DEFAULT -> {
                JsonObject defaults = new JsonObject();
                defaults.addProperty("display_name", entry.character());
                actor.add("default_profile", defaults);
            }
            case C1IdentityData.MODE_CUSTOM -> actor.addProperty("require_existing_profile", true);
            case C1IdentityData.MODE_PRESET -> actor.addProperty("preset", entry.preset());
            default -> throw new IllegalStateException("saved profile mode is unreadable; recreate this NPC");
        }
        return actor;
    }
}
