package io.github.sirp00m.npcortex;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** G1: the native allow-list is deny-by-default, strict, region-bounded and independent of the no-op allow-list (no Minecraft, no files). */
public final class NativeActionPolicySelfTest {
    private static int n;

    private static void check(boolean ok, String what) {
        n++;
        if (!ok) throw new AssertionError(what);
    }

    private static NativeActionPolicy parse(String json) { return NativeActionPolicy.parse(json.getBytes(StandardCharsets.UTF_8)); }

    private static String file(String entries) { return "{\"schema_version\":1,\"entries\":[" + entries + "]}"; }

    private static String entry(UUID who, String actions, String actors, String blocks, String items, String regions) {
        return "{\"id\":\"e\",\"requester\":\"" + who + "\",\"actions\":" + actions + ",\"dimensions\":[\"minecraft:overworld\"],\"actors\":" + actors
                + (blocks == null ? "" : ",\"blocks\":" + blocks) + (items == null ? "" : ",\"items\":" + items) + ",\"regions\":" + regions + "}";
    }

    private static final String BOX = "[{\"min\":[0,60,0],\"max\":[10,70,10]}]";

    private static void denyAll(NativeActionPolicy p, String why) {
        check(!p.loaded() && p.problem() != null, why + ": policy reports a load problem");
        check(!p.decideBreak(UUID.randomUUID(), "minecraft:overworld", "Reika", "minecraft:oak_log", 5, 65, 5).allow(), why + ": no break allowed");
        check(!p.decidePickup(UUID.randomUUID(), "minecraft:overworld", "Reika", "minecraft:oak_log", 5, 65, 5).allow(), why + ": no pickup allowed");
        check(!p.allowsRequester(UUID.randomUUID(), "minecraft:overworld", "Reika"), why + ": not even a reconcile requester");
    }

    public static void main(String[] args) {
        UUID owner = UUID.randomUUID(), other = UUID.randomUUID();
        String both = entry(owner, "[\"break_block\",\"pickup_item\"]", "[\"Reika\"]", "[\"minecraft:oak_log\"]", "[\"minecraft:oak_log\"]", BOX);

        // ---- deny by default
        denyAll(NativeActionPolicy.parse(null), "missing file");
        denyAll(NativeActionPolicy.denyAll("x"), "explicit deny-all");
        denyAll(parse(""), "empty file");
        denyAll(parse("not json"), "garbage");
        denyAll(parse("[]"), "array root");
        denyAll(parse("{\"schema_version\":1}"), "no entries key");
        denyAll(parse("{\"schema_version\":2,\"entries\":[]}"), "future schema");
        for (String v : new String[]{"1.9", "1e0", "1.0", "4294967297", "true", "\"1\"", "0"})
            denyAll(parse("{\"schema_version\":" + v + ",\"entries\":[]}"), "schema_version " + v);
        denyAll(parse("{\"schema_version\":1,\"entries\":" + "[".repeat(2000) + "]".repeat(2000) + "}"), "deep nesting");
        denyAll(NativeActionPolicy.parse(new byte[]{(byte) 0xC3, 0x28}), "invalid UTF-8");
        denyAll(NativeActionPolicy.parse(new byte[NativeActionPolicy.MAX_BYTES + 1]), "oversized");
        check(parse(file("")).loaded() && !parse(file("")).decideBreak(owner, "minecraft:overworld", "Reika", "minecraft:oak_log", 5, 65, 5).allow(), "an empty list allows nothing");

        // ---- strictness: anything surprising rejects the WHOLE file
        denyAll(parse(file(both + "," + both)), "duplicate entry id");
        denyAll(parse("{\"schema_version\":1,\"entries\":[],\"entries\":[" + both + "]}"), "duplicate root key");
        denyAll(parse(file(both.replace("}", ",\"extra\":1}"))), "unknown entry key");
        denyAll(parse("{\"schema_version\":1,\"entries\":[],\"x\":1}"), "unknown root key");
        for (String action : new String[]{"use_item", "equip_item", "select_tool", "craft", "noop", "*", "BREAK_BLOCK", "PLACE_BLOCK", ""})
            denyAll(parse(file(entry(owner, "[\"" + action + "\"]", "[\"Reika\"]", "[\"minecraft:oak_log\"]", "[\"minecraft:oak_log\"]", BOX))), "action " + action);
        denyAll(parse(file(entry(owner, "[]", "[\"Reika\"]", "[\"minecraft:oak_log\"]", null, BOX))), "empty actions");
        denyAll(parse(file(entry(owner, "[\"break_block\"]", "[\"Reika\"]", null, null, BOX))), "break_block without blocks");
        denyAll(parse(file(entry(owner, "[\"pickup_item\"]", "[\"Reika\"]", null, null, BOX))), "pickup_item without items");
        denyAll(parse(file(entry(owner, "[\"break_block\"]", "[\"Reika\"]", "[]", null, BOX))), "empty blocks");
        denyAll(parse(file(entry(owner, "[\"break_block\"]", "[\"Reika\"]", "[\"oak_log\"]", null, BOX))), "block without a namespace");
        denyAll(parse(file(entry(owner, "[\"break_block\"]", "[\"Reika\"]", "[\"minecraft:oak_log\",\"minecraft:oak_log\"]", null, BOX))), "duplicate block");
        denyAll(parse(file(entry(owner, "[\"break_block\"]", "[\"Reika\"]", "[\"minecraft:oak_log\"]", null, "[]"))), "no regions");
        denyAll(parse(file(entry(owner, "[\"break_block\"]", "[\"Reika\"]", "[\"minecraft:oak_log\"]", null, "[{\"min\":[10,60,0],\"max\":[0,70,10]}]"))), "inverted region (min > max) is not silently swapped");
        denyAll(parse(file(entry(owner, "[\"break_block\"]", "[\"Reika\"]", "[\"minecraft:oak_log\"]", null, "[{\"min\":[0,60,0],\"max\":[10,59,10]}]"))), "inverted y");
        denyAll(parse(file(entry(owner, "[\"break_block\"]", "[\"Reika\"]", "[\"minecraft:oak_log\"]", null, "[{\"min\":[0,60],\"max\":[10,70,10]}]"))), "short corner");
        denyAll(parse(file(entry(owner, "[\"break_block\"]", "[\"Reika\"]", "[\"minecraft:oak_log\"]", null, "[{\"min\":[0.5,60,0],\"max\":[10,70,10]}]"))), "fractional corner");
        denyAll(parse(file(entry(owner, "[\"break_block\"]", "[\"Reika\"]", "[\"minecraft:oak_log\"]", null, "[{\"min\":[0,60,0],\"max\":[99999999,70,10]}]"))), "out-of-range corner");
        denyAll(parse(file(entry(owner, "[\"break_block\"]", "[\"Reika\"]", "[\"minecraft:oak_log\"]", null, "[{\"min\":[0,60,0],\"max\":[10,70,10],\"x\":1}]"))), "unknown region key");
        denyAll(parse(file(entry(new UUID(0, 0), "[\"break_block\"]", "[\"Reika\"]", "[\"minecraft:oak_log\"]", null, BOX))), "nil requester");
        denyAll(parse(file(entry(owner, "[\"break_block\"]", "[\"Reika!\"]", "[\"minecraft:oak_log\"]", null, BOX))), "bad actor name");
        String nine = "[" + "{\"min\":[0,0,0],\"max\":[1,1,1]},".repeat(8) + "{\"min\":[0,0,0],\"max\":[1,1,1]}]";
        denyAll(parse(file(entry(owner, "[\"break_block\"]", "[\"Reika\"]", "[\"minecraft:oak_log\"]", null, nine))), "nine regions");

        // ---- the no-op allow-list and the native allow-list cannot stand in for each other
        String noopFile = "{\"schema_version\":1,\"entries\":[{\"id\":\"owner-noop\",\"requester\":\"" + owner
                + "\",\"actions\":[\"noop\"],\"dimensions\":[\"minecraft:overworld\"],\"actors\":[\"Reika\"]}]}";
        denyAll(parse(noopFile), "a no-op allow-list file grants nothing native");
        check(!WorldActionPolicy.parse(both.getBytes(StandardCharsets.UTF_8)).loaded() || !WorldActionPolicy.parse(file(both).getBytes(StandardCharsets.UTF_8)).loaded(),
                "a native file is not a valid no-op allow-list");
        check(!WorldActionPolicy.parse(file(both).getBytes(StandardCharsets.UTF_8)).decide(owner, "break_block", "minecraft:overworld", "Reika").allow(), "the no-op policy never allows a native action");

        // ---- decisions
        NativeActionPolicy p = parse(file(both));
        check(p.loaded() && p.entryCount() == 1 && p.sha256().length() == 64, "a valid file loads");
        check(p.decideBreak(owner, "minecraft:overworld", "Reika", "minecraft:oak_log", 5, 65, 5).allow(), "allowed inside the region");
        check(p.decideBreak(owner, "minecraft:overworld", "reika", "minecraft:oak_log", 5, 65, 5).allow(), "actor names are case-insensitive");
        check(!p.decideBreak(other, "minecraft:overworld", "Reika", "minecraft:oak_log", 5, 65, 5).allow(), "another requester is denied");
        check(!p.decideBreak(owner, "minecraft:overworld", "Mika", "minecraft:oak_log", 5, 65, 5).allow(), "another NPC is denied");
        check(!p.decideBreak(owner, "minecraft:the_nether", "Reika", "minecraft:oak_log", 5, 65, 5).allow(), "another dimension is denied");
        check(!p.decideBreak(owner, "minecraft:overworld", "Reika", "minecraft:stone", 5, 65, 5).allow(), "another block is denied");
        check(!p.decideBreak(owner, "minecraft:overworld", "Reika", "minecraft:oak_log", 20, 65, 5).allow(), "outside the region is denied");
        check(!p.decideBreak(null, "minecraft:overworld", "Reika", "minecraft:oak_log", 5, 65, 5).allow(), "an incomplete request is denied");
        check(p.allowsRequester(owner, "minecraft:overworld", "Reika") && !p.allowsRequester(other, "minecraft:overworld", "Reika"), "reconcile requesters");

        // the WHOLE 3x3x3 neighbourhood must be inside one region: a side effect cannot leave the Owner's zone
        for (int[] edge : new int[][]{{0, 65, 5}, {10, 65, 5}, {5, 60, 5}, {5, 70, 5}, {5, 65, 0}, {5, 65, 10}})
            check(!p.decideBreak(owner, "minecraft:overworld", "Reika", "minecraft:oak_log", edge[0], edge[1], edge[2]).allow(), "edge cell " + java.util.Arrays.toString(edge) + " touches the border");
        check(p.decideBreak(owner, "minecraft:overworld", "Reika", "minecraft:oak_log", 1, 61, 1).allow() && p.decideBreak(owner, "minecraft:overworld", "Reika", "minecraft:oak_log", 9, 69, 9).allow(), "the innermost cells are allowed");
        // a neighbourhood split over two regions is not inside either
        NativeActionPolicy split = parse(file(entry(owner, "[\"break_block\"]", "[\"*\"]", "[\"minecraft:oak_log\"]", null,
                "[{\"min\":[0,60,0],\"max\":[5,70,10]},{\"min\":[6,60,0],\"max\":[10,70,10]}]")));
        check(!split.decideBreak(owner, "minecraft:overworld", "Reika", "minecraft:oak_log", 5, 65, 5).allow(), "adjacent regions do not combine");
        // negative coordinates and a region that spans the origin
        NativeActionPolicy neg = parse(file(entry(owner, "[\"break_block\",\"pickup_item\"]", "[\"*\"]", "[\"minecraft:oak_log\"]", "[\"minecraft:oak_log\"]",
                "[{\"min\":[-20,-64,-20],\"max\":[-5,320,-5]}]")));
        check(neg.decideBreak(owner, "minecraft:overworld", "Anyone", "minecraft:oak_log", -10, 70, -10).allow(), "negative coordinates");
        check(!neg.decideBreak(owner, "minecraft:overworld", "Anyone", "minecraft:oak_log", -5, 70, -10).allow(), "the border of a negative region");
        check(!neg.decideBreak(owner, "minecraft:overworld", "Anyone", "minecraft:oak_log", 10, 70, 10).allow(), "positive coordinates are outside it");
        // pickup: the item's block position must be inside a region; the item type must be listed; the action must be listed
        check(p.decidePickup(owner, "minecraft:overworld", "Reika", "minecraft:oak_log", 0, 60, 0).allow(), "pickup at the region border is inside");
        check(!p.decidePickup(owner, "minecraft:overworld", "Reika", "minecraft:oak_log", 11, 60, 0).allow(), "pickup outside");
        check(!p.decidePickup(owner, "minecraft:overworld", "Reika", "minecraft:stick", 5, 65, 5).allow(), "an unlisted item");
        NativeActionPolicy breakOnly = parse(file(entry(owner, "[\"break_block\"]", "[\"Reika\"]", "[\"minecraft:oak_log\"]", null, BOX)));
        check(!breakOnly.decidePickup(owner, "minecraft:overworld", "Reika", "minecraft:oak_log", 5, 65, 5).allow(), "break rights are not pickup rights");
        NativeActionPolicy pickOnly = parse(file(entry(owner, "[\"pickup_item\"]", "[\"Reika\"]", null, "[\"minecraft:oak_log\"]", BOX)));
        check(!pickOnly.decideBreak(owner, "minecraft:overworld", "Reika", "minecraft:oak_log", 5, 65, 5).allow(), "pickup rights are not break rights");
        check(p.decideBreak(owner, "minecraft:overworld", "Reika", "minecraft:oak_log", 5, 65, 5).policySha256().equals(p.sha256()), "a decision carries the policy hash");
        check(!p.sha256().equals(parse(file(both.replace("\"e\"", "\"f\""))).sha256()), "a different file has a different hash");
        System.out.println("NativeActionPolicySelfTest: " + n + " checks OK");
    }
}
