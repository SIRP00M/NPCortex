package io.github.sirp00m.npcortex;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** G1: the world-action allow-list is deny-by-default and strict (no Minecraft, no files). */
public final class WorldActionPolicySelfTest {
    private static int n;

    private static void check(boolean ok, String what) {
        n++;
        if (!ok) throw new AssertionError(what);
    }

    private static WorldActionPolicy parse(String json) { return WorldActionPolicy.parse(json.getBytes(StandardCharsets.UTF_8)); }

    private static String file(String entries) { return "{\"schema_version\":1,\"entries\":[" + entries + "]}"; }

    private static String entry(String id, UUID requester, String actions, String dims, String actors) {
        return "{\"id\":\"" + id + "\",\"requester\":\"" + requester + "\",\"actions\":" + actions
                + ",\"dimensions\":" + dims + ",\"actors\":" + actors + "}";
    }

    private static void denyAll(WorldActionPolicy p, String why) {
        check(!p.loaded() && p.problem() != null, why + ": policy reports a load problem");
        check(!p.decide(UUID.randomUUID(), "noop", "minecraft:overworld", "Reika").allow(), why + ": nothing is allowed");
    }

    public static void main(String[] args) {
        UUID owner = UUID.randomUUID(), other = UUID.randomUUID();
        String good = entry("owner-noop", owner, "[\"noop\"]", "[\"minecraft:overworld\"]", "[\"Reika\"]");

        // ---- deny by default
        denyAll(WorldActionPolicy.parse(null), "missing file");
        denyAll(WorldActionPolicy.denyAll("x"), "explicit deny-all");
        denyAll(parse(""), "empty file");
        denyAll(parse("not json"), "garbage");
        denyAll(parse("[]"), "array root");
        denyAll(parse("{\"schema_version\":1}"), "no entries key");
        denyAll(parse("{\"schema_version\":2,\"entries\":[]}"), "future schema");
        denyAll(parse("{\"schema_version\":\"1\",\"entries\":[]}"), "string schema");
        for (String v : new String[]{"1.9", "1e0", "1.0", "4294967297", "true", "0", "-1"})
            denyAll(parse("{\"schema_version\":" + v + ",\"entries\":[]}"), "schema_version " + v);
        denyAll(parse("{\"schema_version\":1,\"entries\":" + "[".repeat(2000) + "]".repeat(2000) + "}"), "deep nesting");
        denyAll(WorldActionPolicy.parse(new byte[]{(byte) 0xC3, 0x28}), "invalid UTF-8");
        denyAll(WorldActionPolicy.parse(new byte[WorldActionPolicy.MAX_BYTES + 1]), "oversized file");
        check(parse(file("")).loaded(), "an empty entry list is a valid policy");
        check(!parse(file("")).decide(owner, "noop", "minecraft:overworld", "Reika").allow(), "empty list allows nothing");

        // ---- strictness: anything surprising rejects the WHOLE file
        denyAll(parse(file(good + ",{\"id\":\"owner-noop\",\"requester\":\"" + other + "\",\"actions\":[\"noop\"],\"dimensions\":[\"minecraft:overworld\"],\"actors\":[\"Reika\"]}")), "duplicate entry id");
        denyAll(parse("{\"schema_version\":1,\"entries\":[],\"entries\":[" + good + "]}"), "duplicate root key");
        denyAll(parse(good.replace("\"id\"", "\"id\":\"a\",\"id\"")), "duplicate entry key / wrong shape");
        denyAll(parse(file(good.replace("}", ",\"extra\":1}"))), "unknown entry key");
        denyAll(parse("{\"schema_version\":1,\"entries\":[],\"surprise\":true}"), "unknown root key");
        denyAll(parse(file(entry("e", owner, "[\"break_block\"]", "[\"minecraft:overworld\"]", "[\"Reika\"]"))), "native action is not representable");
        for (String action : new String[]{"place_block", "pickup_item", "use_item", "equip_item", "*", "NOOP", ""})
            denyAll(parse(file(entry("e", owner, "[\"" + action + "\"]", "[\"minecraft:overworld\"]", "[\"Reika\"]"))), "action " + action);
        denyAll(parse(file(entry("e", owner, "[]", "[\"minecraft:overworld\"]", "[\"Reika\"]"))), "empty actions");
        denyAll(parse(file(entry("e", owner, "[\"noop\"]", "[]", "[\"Reika\"]"))), "empty dimensions");
        denyAll(parse(file(entry("e", owner, "[\"noop\"]", "[\"minecraft:overworld\"]", "[]"))), "empty actors");
        denyAll(parse(file(entry("e", owner, "[\"noop\"]", "[\"*\"]", "[\"Reika\"]"))), "wildcard dimension is not allowed");
        denyAll(parse(file(entry("e", owner, "[\"noop\"]", "[\"Minecraft:Overworld\"]", "[\"Reika\"]"))), "malformed dimension");
        denyAll(parse(file(entry("e", owner, "[\"noop\"]", "[\"minecraft:overworld\"]", "[\"bad name\"]"))), "malformed actor");
        denyAll(parse(file(entry("e", owner, "[\"noop\",\"noop\"]", "[\"minecraft:overworld\"]", "[\"Reika\"]"))), "duplicate list item");
        denyAll(parse(file(entry("bad id!", owner, "[\"noop\"]", "[\"minecraft:overworld\"]", "[\"Reika\"]"))), "malformed id");
        denyAll(parse(file(good.replace(owner.toString(), "not-a-uuid"))), "malformed requester");
        denyAll(parse(file(good.replace(owner.toString(), owner.toString().toUpperCase()))), "non-canonical requester");
        denyAll(parse(file(good.replace(owner.toString(), new UUID(0, 0).toString()))), "nil requester");
        denyAll(parse(file(good.replace("\"requester\":\"" + owner + "\"", "\"requester\":42"))), "numeric requester");
        denyAll(parse(file(good + "," + good.replace("owner-noop", "b"))
                .replace("]}", ",1]}")), "non-object entry");
        StringBuilder many = new StringBuilder();
        for (int i = 0; i <= WorldActionPolicy.MAX_ENTRIES; i++) many.append(i == 0 ? "" : ",").append(good.replace("owner-noop", "e" + i));
        denyAll(parse(file(many.toString())), "too many entries");

        // ---- decisions
        WorldActionPolicy p = parse(file(good));
        check(p.loaded() && p.entryCount() == 1 && p.problem() == null, "good file loads");
        WorldActionPolicy.Decision allow = p.decide(owner, "noop", "minecraft:overworld", "Reika");
        check(allow.allow() && allow.entryId().equals("owner-noop") && allow.policySha256().equals(p.sha256()), "owner allowed");
        check(p.decide(owner, "noop", "minecraft:overworld", "REIKA").allow(), "actor names compare case-insensitively");
        check(!p.decide(other, "noop", "minecraft:overworld", "Reika").allow(), "other requester denied");
        check(p.decide(other, "noop", "minecraft:overworld", "Reika").reason().contains("not on the allow-list"), "reason names the cause");
        check(!p.decide(owner, "noop", "minecraft:the_nether", "Reika").allow(), "other dimension denied");
        check(!p.decide(owner, "noop", "minecraft:overworld", "Sakura").allow(), "other actor denied");
        for (String action : new String[]{"break_block", "place_block", "pickup_item", "use_item", "equip_item", "", "*"})
            check(!p.decide(owner, action, "minecraft:overworld", "Reika").allow(), "action " + action + " is never allowed");
        check(!p.decide(null, "noop", "minecraft:overworld", "Reika").allow(), "null requester");
        check(!p.decide(owner, null, "minecraft:overworld", "Reika").allow(), "null action");
        check(!p.decide(owner, "noop", null, "Reika").allow(), "null dimension");
        check(!p.decide(owner, "noop", "minecraft:overworld", null).allow(), "null actor");
        WorldActionPolicy star = parse(file(entry("star", owner, "[\"noop\"]", "[\"minecraft:overworld\"]", "[\"*\"]")));
        check(star.decide(owner, "noop", "minecraft:overworld", "Anyone").allow(), "explicit wildcard actor is honoured");
        check(!star.decide(other, "noop", "minecraft:overworld", "Anyone").allow(), "wildcard actor does not widen the requester");
        WorldActionPolicy two = parse(file(good + "," + entry("second", other, "[\"noop\"]", "[\"minecraft:the_nether\"]", "[\"Sakura\"]")));
        check(two.decide(other, "noop", "minecraft:the_nether", "Sakura").entryId().equals("second"), "second entry matches");
        check(!two.decide(other, "noop", "minecraft:overworld", "Sakura").allow(), "entries are not merged across requesters");
        check(WorldActionPolicy.KNOWN_ACTIONS.equals(java.util.Set.of("noop")), "noop is the ONLY representable action");

        // ---- hash binds the exact file
        check(parse(file(good)).sha256().equals(p.sha256()), "same bytes, same hash");
        check(!parse(file(good + " ")).sha256().equals(p.sha256()), "different bytes, different hash");
        System.out.println("PASS WorldActionPolicySelfTest (" + n + " assertions)");
    }
}
