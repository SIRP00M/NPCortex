package io.github.sirp00m.npcortex;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Stage 4 deny-by-default permission for the NATIVE actions break_block and pickup_item, pure Java (no Minecraft types).
 *
 * The Owner's file {@code config/npcortex-native-actions.json} is the ONLY source of ALLOW. It is separate from the no-op
 * allow-list on purpose: that file's strict schema stays valid and unchanged. Anything unreadable, oversized, malformed,
 * duplicated, unknown, inverted (min > max) or ambiguous yields a policy that denies everything.
 *
 * Shape (schema 1, unknown keys rejected):
 * {"schema_version":1,"entries":[{"id":"owner-native","requester":"&lt;player uuid&gt;","actions":["break_block","pickup_item"],
 *   "dimensions":["minecraft:overworld"],"actors":["Reika"],"blocks":["minecraft:oak_log"],"items":["minecraft:oak_log"],
 *   "regions":[{"min":[0,60,0],"max":[40,80,40]}]}]}
 *
 * break_block needs "blocks" and a region that contains the target's WHOLE 3x3x3 neighbourhood (so a side effect cannot leave the
 * zone the Owner marked). pickup_item needs "items" and a region that contains the item's block position. Permission level 2,
 * the single-player owner flag and a backend token are NOT inputs. A decision is evidence for one request, never a grant.
 */
final class NativeActionPolicy {
    static final int SCHEMA = 1;
    static final int MAX_BYTES = 16 * 1024;
    static final int MAX_ENTRIES = 32;
    static final int MAX_LIST = 16;
    static final int MAX_REGIONS = 8;
    static final long COORD = 30_000_000L;
    static final Set<String> ACTIONS = Set.of("break_block", "pickup_item", "place_block", "use_block");

    /** PAP-G5 code-level ceiling, applied on top of the Owner's file (a listed block is still refused here). place_block: blocks whose
     * placement can spread, fall, explode, open a portal, create automation or a container. use_block: ONLY these block families. */
    static final Set<String> PLACE_DENY = Set.of("minecraft:tnt", "minecraft:fire", "minecraft:soul_fire", "minecraft:lava", "minecraft:water",
            "minecraft:bedrock", "minecraft:command_block", "minecraft:chain_command_block", "minecraft:repeating_command_block",
            "minecraft:structure_block", "minecraft:jigsaw", "minecraft:structure_void", "minecraft:barrier", "minecraft:light",
            "minecraft:end_portal", "minecraft:end_portal_frame", "minecraft:end_gateway", "minecraft:nether_portal", "minecraft:spawner",
            "minecraft:respawn_anchor", "minecraft:dispenser", "minecraft:dropper", "minecraft:hopper", "minecraft:piston",
            "minecraft:sticky_piston", "minecraft:observer", "minecraft:sand", "minecraft:red_sand", "minecraft:gravel", "minecraft:anvil",
            "minecraft:chipped_anvil", "minecraft:damaged_anvil", "minecraft:dragon_egg", "minecraft:scaffolding", "minecraft:redstone_block");
    static boolean placeCeilingDenies(String blockId) {
        return PLACE_DENY.contains(blockId) || blockId.endsWith("_concrete_powder") || blockId.endsWith("_shulker_box");
    }
    static boolean useCeilingAllows(String blockId) {
        return blockId.equals("minecraft:lever") || (blockId.startsWith("minecraft:") && blockId.endsWith("_button"));
    }

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern DIMENSION = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Pattern RESOURCE = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Pattern ACTOR = Pattern.compile("[A-Za-z0-9_]{1,16}");

    record Region(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        boolean contains(int x, int y, int z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }
        boolean containsBox(int x, int y, int z, int r) {
            return contains(x - r, y - r, z - r) && contains(x + r, y + r, z + r);
        }
    }

    record Entry(String id, UUID requester, Set<String> actions, Set<String> dimensions, Set<String> actors,
                 Set<String> blocks, Set<String> items, List<Region> regions) {}

    record Decision(boolean allow, String reason, String entryId, String policySha256) {}

    private final List<Entry> entries;
    private final String sha256;
    private final String loadProblem;

    private NativeActionPolicy(List<Entry> entries, String sha256, String loadProblem) {
        this.entries = List.copyOf(entries);
        this.sha256 = sha256;
        this.loadProblem = loadProblem;
    }

    static NativeActionPolicy denyAll(String why) {
        return new NativeActionPolicy(List.of(), WorldActionPolicy.sha("DENY_ALL_NATIVE:" + why), why);
    }

    boolean loaded() { return loadProblem == null; }

    String problem() { return loadProblem; }

    String sha256() { return sha256; }

    int entryCount() { return entries.size(); }

    /** Strict parse. Never throws: every problem becomes a deny-all policy that says why. */
    static NativeActionPolicy parse(byte[] raw) {
        if (raw == null) return denyAll("native allow-list file missing");
        if (raw.length > MAX_BYTES) return denyAll("native allow-list larger than " + MAX_BYTES + " bytes");
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(raw)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            return denyAll("native allow-list is not valid UTF-8");
        }
        try {
            rejectDuplicateKeys(text);
            JsonElement root = JsonParser.parseString(text);
            if (!root.isJsonObject()) return denyAll("native allow-list root must be an object");
            JsonObject object = root.getAsJsonObject();
            String bad = unknownKeys(object, Set.of("schema_version", "entries"));
            if (bad != null) return denyAll("unknown key '" + bad + "'");
            if (!object.has("schema_version") || !object.get("schema_version").isJsonPrimitive()
                    || !object.get("schema_version").getAsJsonPrimitive().isNumber()
                    || object.get("schema_version").getAsBigDecimal().compareTo(java.math.BigDecimal.valueOf(SCHEMA)) != 0
                    || !object.get("schema_version").getAsString().equals("1"))
                return denyAll("unsupported schema_version");
            if (!object.has("entries") || !object.get("entries").isJsonArray()) return denyAll("entries must be an array");
            JsonArray array = object.getAsJsonArray("entries");
            if (array.size() > MAX_ENTRIES) return denyAll("too many entries");
            List<Entry> parsed = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (JsonElement item : array) {
                if (!item.isJsonObject()) return denyAll("entry must be an object");
                Entry entry = entry(item.getAsJsonObject());
                if (!seen.add(entry.id())) return denyAll("duplicate entry id '" + entry.id() + "'");
                parsed.add(entry);
            }
            return new NativeActionPolicy(parsed, WorldActionPolicy.sha(text), null);
        } catch (StackOverflowError e) {
            return denyAll("native allow-list nested too deeply");
        } catch (IllegalArgumentException | IllegalStateException | IOException | com.google.gson.JsonParseException e) {
            return denyAll("native allow-list rejected: " + clip(e.getMessage()));
        }
    }

    private static Entry entry(JsonObject o) {
        String bad = unknownKeys(o, Set.of("id", "requester", "actions", "dimensions", "actors", "blocks", "items", "regions"));
        if (bad != null) throw new IllegalArgumentException("unknown entry key '" + bad + "'");
        String id = string(o, "id");
        if (!ID.matcher(id).matches()) throw new IllegalArgumentException("bad entry id");
        String requester = string(o, "requester");
        UUID uuid;
        try {
            uuid = UUID.fromString(requester);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("requester must be a player UUID");
        }
        if (!uuid.toString().equals(requester) || uuid.equals(new UUID(0, 0)))
            throw new IllegalArgumentException("requester must be a canonical, non-nil UUID");
        Set<String> actions = list(o, "actions", null, true);
        for (String action : actions)
            if (!ACTIONS.contains(action)) throw new IllegalArgumentException("unknown action '" + clip(action) + "'");
        Set<String> dimensions = list(o, "dimensions", DIMENSION, true);
        Set<String> actors = new LinkedHashSet<>();
        for (String actor : list(o, "actors", null, true)) {
            if (!actor.equals("*") && !ACTOR.matcher(actor).matches()) throw new IllegalArgumentException("bad actor name");
            actors.add(actor.equals("*") ? "*" : actor.toLowerCase(Locale.ROOT));
        }
        Set<String> blocks = list(o, "blocks", RESOURCE, actions.contains("break_block") || actions.contains("place_block") || actions.contains("use_block"));
        Set<String> items = list(o, "items", RESOURCE, actions.contains("pickup_item"));
        return new Entry(id, uuid, Set.copyOf(actions), Set.copyOf(dimensions), Set.copyOf(actors), Set.copyOf(blocks),
                Set.copyOf(items), regions(o));
    }

    private static List<Region> regions(JsonObject o) {
        JsonElement e = o.get("regions");
        if (e == null || !e.isJsonArray() || e.getAsJsonArray().isEmpty() || e.getAsJsonArray().size() > MAX_REGIONS)
            throw new IllegalArgumentException("regions must be a non-empty array of at most " + MAX_REGIONS);
        List<Region> out = new ArrayList<>();
        for (JsonElement item : e.getAsJsonArray()) {
            if (!item.isJsonObject()) throw new IllegalArgumentException("region must be an object");
            JsonObject r = item.getAsJsonObject();
            String bad = unknownKeys(r, Set.of("min", "max"));
            if (bad != null) throw new IllegalArgumentException("unknown region key '" + bad + "'");
            int[] min = corner(r, "min"), max = corner(r, "max");
            for (int i = 0; i < 3; i++)
                if (min[i] > max[i]) throw new IllegalArgumentException("region min must not exceed max");
            out.add(new Region(min[0], min[1], min[2], max[0], max[1], max[2]));
        }
        return out;
    }

    private static int[] corner(JsonObject r, String key) {
        JsonElement e = r.get(key);
        if (e == null || !e.isJsonArray() || e.getAsJsonArray().size() != 3)
            throw new IllegalArgumentException("region " + key + " must be [x,y,z]");
        int[] out = new int[3];
        for (int i = 0; i < 3; i++) {
            JsonElement c = e.getAsJsonArray().get(i);
            if (!c.isJsonPrimitive() || !c.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("coordinate must be an integer");
            java.math.BigDecimal value = c.getAsBigDecimal();
            if (value.stripTrailingZeros().scale() > 0 || value.abs().compareTo(java.math.BigDecimal.valueOf(i == 1 ? 4096 : COORD)) > 0)
                throw new IllegalArgumentException("coordinate must be an integer in range");
            out[i] = value.intValueExact();
        }
        return out;
    }

    private static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException(key + " must be a string");
        return e.getAsString();
    }

    /** A list that must exist and be non-empty when {@code required}; when not required it may be absent but never present-and-wrong. */
    private static Set<String> list(JsonObject o, String key, Pattern each, boolean required) {
        JsonElement e = o.get(key);
        if (e == null && !required) return Set.of();
        if (e == null || !e.isJsonArray() || e.getAsJsonArray().isEmpty() || e.getAsJsonArray().size() > MAX_LIST)
            throw new IllegalArgumentException(key + " must be a non-empty array of at most " + MAX_LIST);
        Set<String> out = new LinkedHashSet<>();
        for (JsonElement item : e.getAsJsonArray()) {
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString())
                throw new IllegalArgumentException(key + " items must be strings");
            String value = item.getAsString();
            if (each != null && !each.matcher(value).matches()) throw new IllegalArgumentException(key + " item malformed");
            if (!out.add(value)) throw new IllegalArgumentException(key + " has a duplicate item");
        }
        return out;
    }

    private static String unknownKeys(JsonObject o, Set<String> allowed) {
        for (String key : o.keySet()) if (!allowed.contains(key)) return clip(key);
        return null;
    }

    private static void rejectDuplicateKeys(String text) throws IOException {
        JsonReader reader = new JsonReader(new StringReader(text));
        reader.setLenient(false);
        walk(reader, 0);
        if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("trailing data");
    }

    private static void walk(JsonReader reader, int depth) throws IOException {
        if (depth > 7) throw new IOException("nested too deeply");
        switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                reader.beginObject();
                Set<String> names = new HashSet<>();
                while (reader.hasNext()) {
                    if (!names.add(reader.nextName())) throw new IOException("duplicate key");
                    walk(reader, depth + 1);
                }
                reader.endObject();
            }
            case BEGIN_ARRAY -> {
                reader.beginArray();
                while (reader.hasNext()) walk(reader, depth + 1);
                reader.endArray();
            }
            default -> reader.skipValue();
        }
    }

    /** Reconcile / cancel gate: the requester is on the native allow-list for this dimension and NPC (any native action). */
    boolean allowsRequester(UUID requester, String dimension, String actorName) {
        if (loadProblem != null || requester == null || dimension == null || actorName == null) return false;
        String actor = actorName.toLowerCase(Locale.ROOT);
        for (Entry e : entries)
            if (e.requester().equals(requester) && e.dimensions().contains(dimension)
                    && (e.actors().contains("*") || e.actors().contains(actor))) return true;
        return false;
    }

    /** Fail-closed decision for one block break. {@code actorName} is the server-resolved NPC name, never client text. */
    Decision decideBreak(UUID requester, String dimension, String actorName, String blockId, int x, int y, int z) {
        return decide(requester, "break_block", dimension, actorName, blockId, x, y, z);
    }

    /** Fail-closed decision for one item pickup. {@code (x,y,z)} is the item entity's block position. */
    Decision decidePickup(UUID requester, String dimension, String actorName, String itemId, int x, int y, int z) {
        return decide(requester, "pickup_item", dimension, actorName, itemId, x, y, z);
    }

    private Decision decide(UUID requester, String action, String dimension, String actorName, String resource, int x, int y, int z) {
        if (loadProblem != null) return deny("policy unavailable: " + loadProblem);
        if (requester == null || dimension == null || actorName == null || resource == null) return deny("incomplete request");
        String actor = actorName.toLowerCase(Locale.ROOT);
        boolean sawRequester = false, sawAction = false, sawResource = false;
        for (Entry e : entries) {
            if (!e.requester().equals(requester)) continue;
            sawRequester = true;
            if (!e.actions().contains(action) || !e.dimensions().contains(dimension)
                    || !(e.actors().contains("*") || e.actors().contains(actor))) continue;
            sawAction = true;
            if (!(action.equals("break_block") ? e.blocks() : e.items()).contains(resource)) continue;
            sawResource = true;
            for (Region region : e.regions()) {
                boolean inside = action.equals("break_block") ? region.containsBox(x, y, z, 1) : region.contains(x, y, z);
                if (inside) return new Decision(true, "allowed by entry " + e.id(), e.id(), sha256);
            }
        }
        if (!sawRequester) return deny("requester is not on the native allow-list");
        if (!sawAction) return deny("no native allow-list entry covers this action, dimension and actor");
        if (!sawResource) return deny("that " + (action.equals("break_block") ? "block" : "item") + " is not on the allow-list");
        return deny(action.equals("break_block") ? "target and its 3x3x3 neighbourhood are not inside an allowed region"
                : "item is not inside an allowed region");
    }

    /** Fail-closed decision for one block placement: {@code blockId} is the block that must appear at (x,y,z), (sx,sy,sz) the clicked
     * support block. The whole 3x3x3 neighbourhood of the placed cell must lie inside an allowed region. */
    Decision decidePlace(UUID requester, String dimension, String actorName, String blockId, int x, int y, int z, int sx, int sy, int sz) {
        if (blockId != null && placeCeilingDenies(blockId)) return deny("placing " + clip(blockId) + " is refused by the built-in safety ceiling");
        return decideBlock("place_block", requester, dimension, actorName, blockId, x, y, z, 1, sx, sy, sz);
    }

    /** Fail-closed decision for one block use (lever or button only). The 5x5x5 neighbourhood must lie inside an allowed region,
     * because redstone power reaches two blocks (a strongly powered block powers its neighbours). Wiring beyond it is not bounded. */
    Decision decideUse(UUID requester, String dimension, String actorName, String blockId, int x, int y, int z) {
        if (blockId != null && !useCeilingAllows(blockId)) return deny("using " + clip(blockId) + " is refused by the built-in safety ceiling (lever and buttons only)");
        return decideBlock("use_block", requester, dimension, actorName, blockId, x, y, z, 2, x, y, z);
    }

    private Decision decideBlock(String action, UUID requester, String dimension, String actorName, String blockId, int x, int y, int z,
                                 int radius, int sx, int sy, int sz) {
        if (loadProblem != null) return deny("policy unavailable: " + loadProblem);
        if (requester == null || dimension == null || actorName == null || blockId == null) return deny("incomplete request");
        String actor = actorName.toLowerCase(Locale.ROOT);
        boolean sawRequester = false, sawAction = false, sawBlock = false;
        for (Entry e : entries) {
            if (!e.requester().equals(requester)) continue;
            sawRequester = true;
            if (!e.actions().contains(action) || !e.dimensions().contains(dimension)
                    || !(e.actors().contains("*") || e.actors().contains(actor))) continue;
            sawAction = true;
            if (!e.blocks().contains(blockId)) continue;
            sawBlock = true;
            for (Region region : e.regions())
                if (region.containsBox(x, y, z, radius) && region.contains(sx, sy, sz)) return new Decision(true, "allowed by entry " + e.id(), e.id(), sha256);
        }
        if (!sawRequester) return deny("requester is not on the native allow-list");
        if (!sawAction) return deny("no allow-list entry covers this action, dimension and actor");
        if (!sawBlock) return deny("that block is not on the allow-list for " + action);
        return deny("the target neighbourhood is not inside an allowed region");
    }

    private Decision deny(String reason) { return new Decision(false, reason, "", sha256); }

    private static String clip(String text) {
        if (text == null) return "";
        String flat = text.replaceAll("\\p{Cntrl}", " ");
        return flat.length() > 80 ? flat.substring(0, 80) : flat;
    }
}
