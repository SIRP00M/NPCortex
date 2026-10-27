package io.github.sirp00m.npcortex;

import com.google.gson.JsonObject;

import java.util.Set;
import java.util.UUID;

/** G8: which /v1/actors body each kind of saved NPC sends. Pure JVM; the HTTP exchange itself is G3. */
public final class C2RegistrationPayloadSelfTest {
    private static int n;
    private static void check(boolean ok, String what) { n++; if (!ok) throw new AssertionError(what); }

    private static C1IdentityData.Entry entry(String name, String mode, String preset) {
        return new C1IdentityData.Entry(name, UUID.randomUUID(), UUID.randomUUID(), "N" + name + "_abc123",
                "minecraft:overworld", 0, 64, 0, C1RotationState.legacy(0), true, mode, preset);
    }

    private static Set<String> keys(JsonObject o) { return o.keySet(); }

    public static void main(String[] args) {
        UUID world = UUID.randomUUID();

        // legacy: exactly the pre-profile body (character_id = lower-cased name), nothing else new
        C1IdentityData.Entry yui = entry("Yui", "", "");
        JsonObject legacy = C2RegistrationPayload.build(world, "minecraft:overworld", yui);
        check(keys(legacy).equals(Set.of("world_id", "dimension_id", "actor_id", "character_id")), "legacy body keys unchanged");
        check(legacy.get("character_id").getAsString().equals("yui"), "legacy NPC still maps name -> pack id");
        check(legacy.get("actor_id").getAsString().equals(yui.actorId().toString()) && legacy.get("world_id").getAsString().equals(world.toString()), "ids from server state");

        // a legacy NPC with no pack keeps failing loudly: the body still names the pack, nothing is substituted
        check(C2RegistrationPayload.build(world, "minecraft:overworld", entry("g5n02", "", "")).get("character_id").getAsString().equals("g5n02"), "legacy g5n02 unchanged");

        // new NPCs never derive a pack id from their name
        JsonObject guide = C2RegistrationPayload.build(world, "minecraft:overworld", entry("Guide", "default", ""));
        check(!guide.has("character_id") && !guide.has("preset"), "a new NPC called Guide does not select the Guide pack");
        check(guide.getAsJsonObject("default_profile").get("display_name").getAsString().equals("Guide"), "default mode sends the NPC name as display name");
        check(keys(guide).equals(Set.of("world_id", "dimension_id", "actor_id", "default_profile")), "default body keys");

        JsonObject custom = C2RegistrationPayload.build(world, "minecraft:overworld", entry("Reika", "custom", ""));
        check(custom.get("require_existing_profile").getAsBoolean() && !custom.has("profile") && !custom.has("default_profile") && !custom.has("character_id"), "custom mode binds only, never creates");
        check(keys(custom).equals(Set.of("world_id", "dimension_id", "actor_id", "require_existing_profile")), "custom body keys");

        JsonObject preset = C2RegistrationPayload.build(world, "minecraft:overworld", entry("Mika", "preset", "yui"));
        check(preset.get("preset").getAsString().equals("yui") && !preset.has("character_id"), "preset mode sends the explicit pack id");

        // the body never carries a profile text, a token or a URL
        for (JsonObject body : new JsonObject[]{legacy, guide, custom, preset}) {
            String text = body.toString().toLowerCase();
            check(!text.contains("token") && !text.contains("http") && !text.contains("system_prompt"), "no secrets/prompts in C2 registration");
        }

        // unreadable saved mode: refuse, do not guess
        try {
            C2RegistrationPayload.build(world, "minecraft:overworld", entry("Rei", C1IdentityData.MODE_INVALID, ""));
            throw new AssertionError("invalid mode must be refused");
        } catch (IllegalStateException expected) {
            n++;
        }
        System.out.println("PASS C2RegistrationPayloadSelfTest (" + n + " assertions)");
    }
}
