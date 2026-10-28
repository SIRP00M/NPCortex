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
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Stage 4 deny-by-default world-action permission, pure Java (no Minecraft types).
 *
 * The Owner's allow-list file is the ONLY source of ALLOW. Anything unreadable, oversized, malformed, duplicated, unknown or
 * ambiguous yields a policy that denies everything. Permission level 2, the single-player owner flag and a backend token are
 * NOT inputs here: they are separate gates that must also pass. A decision is evidence for one request, never a standing grant.
 *
 * File shape (schema 1; unknown keys are rejected):
 * {"schema_version":1,"entries":[{"id":"owner-noop","requester":"<player uuid>","actions":["noop"],
 *   "dimensions":["minecraft:overworld"],"actors":["Alice"]}]}   ("*" in actors is an explicit, visible opt-in)
 */
final class WorldActionPolicy {
    static final int SCHEMA = 1;
    static final int MAX_BYTES = 16 * 1024;
    static final int MAX_ENTRIES = 32;
    static final int MAX_LIST = 8;
    /** The only action this milestone can ever authorize. Native mutation actions are deliberately NOT representable. */
    static final Set<String> KNOWN_ACTIONS = Set.of("noop");

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern DIMENSION = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Pattern ACTOR = Pattern.compile("[A-Za-z0-9_]{1,16}");

    record Entry(String id, UUID requester, Set<String> actions, Set<String> dimensions, Set<String> actors) {}

    record Decision(boolean allow, String reason, String entryId, String policySha256) {}

    private final List<Entry> entries;
    private final String sha256;
    private final String loadProblem;

    private WorldActionPolicy(List<Entry> entries, String sha256, String loadProblem) {
        this.entries = List.copyOf(entries);
        this.sha256 = sha256;
        this.loadProblem = loadProblem;
    }

    static WorldActionPolicy denyAll(String why) {
        return new WorldActionPolicy(List.of(), sha("DENY_ALL:" + why), why);
    }

    boolean loaded() { return loadProblem == null; }

    String problem() { return loadProblem; }

    String sha256() { return sha256; }

    int entryCount() { return entries.size(); }

    /** Strict parse. Never throws: every problem becomes a deny-all policy that says why. */
    static WorldActionPolicy parse(byte[] raw) {
        if (raw == null) return denyAll("allow-list file missing");
        if (raw.length > MAX_BYTES) return denyAll("allow-list larger than " + MAX_BYTES + " bytes");
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(raw)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            return denyAll("allow-list is not valid UTF-8");
        }
        try {
            rejectDuplicateKeys(text);
            JsonElement root = JsonParser.parseString(text);
            if (!root.isJsonObject()) return denyAll("allow-list root must be an object");
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
            return new WorldActionPolicy(parsed, sha(text), null);
        } catch (StackOverflowError e) {
            return denyAll("allow-list nested too deeply");
        } catch (IllegalArgumentException | IllegalStateException | IOException | com.google.gson.JsonParseException e) {
            return denyAll("allow-list rejected: " + clip(e.getMessage()));
        }
    }

    private static Entry entry(JsonObject o) {
        String bad = unknownKeys(o, Set.of("id", "requester", "actions", "dimensions", "actors"));
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
        Set<String> actions = list(o, "actions", null);
        for (String action : actions)
            if (!KNOWN_ACTIONS.contains(action)) throw new IllegalArgumentException("unknown action '" + clip(action) + "'");
        Set<String> dimensions = list(o, "dimensions", DIMENSION);
        Set<String> actors = new LinkedHashSet<>();
        for (String actor : list(o, "actors", null)) {
            if (!actor.equals("*") && !ACTOR.matcher(actor).matches()) throw new IllegalArgumentException("bad actor name");
            actors.add(actor.equals("*") ? "*" : actor.toLowerCase(Locale.ROOT));
        }
        return new Entry(id, uuid, Set.copyOf(actions), Set.copyOf(dimensions), Set.copyOf(actors));
    }

    private static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException(key + " must be a string");
        return e.getAsString();
    }

    private static Set<String> list(JsonObject o, String key, Pattern each) {
        JsonElement e = o.get(key);
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

    /** Gson silently keeps the LAST of two equal keys; an allow-list must not be ambiguous. */
    private static void rejectDuplicateKeys(String text) throws IOException {
        JsonReader reader = new JsonReader(new StringReader(text));
        reader.setLenient(false);
        walk(reader);
        if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("trailing data");
    }

    private static void walk(JsonReader reader) throws IOException {
        walk(reader, 0);
    }

    private static void walk(JsonReader reader, int depth) throws IOException {
        if (depth > 6) throw new IOException("nested too deeply");
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

    /** Fail-closed decision for one request. {@code actorName} is the server-resolved NPC name, never client text. */
    Decision decide(UUID requester, String action, String dimension, String actorName) {
        if (loadProblem != null) return deny("policy unavailable: " + loadProblem);
        if (requester == null || action == null || dimension == null || actorName == null)
            return deny("incomplete request");
        if (!KNOWN_ACTIONS.contains(action)) return deny("action not supported");
        String actor = actorName.toLowerCase(Locale.ROOT);
        boolean sawRequester = false;
        for (Entry e : entries) {
            if (!e.requester().equals(requester)) continue;
            sawRequester = true;
            if (e.actions().contains(action) && e.dimensions().contains(dimension)
                    && (e.actors().contains("*") || e.actors().contains(actor)))
                return new Decision(true, "allowed by entry " + e.id(), e.id(), sha256);
        }
        return deny(sawRequester ? "no allow-list entry covers this action, dimension and actor"
                : "requester is not on the allow-list");
    }

    private Decision deny(String reason) { return new Decision(false, reason, "", sha256); }

    private static String clip(String text) {
        if (text == null) return "";
        String flat = text.replaceAll("\\p{Cntrl}", " ");
        return flat.length() > 80 ? flat.substring(0, 80) : flat;
    }

    static String sha(String text) { return sha(text.getBytes(StandardCharsets.UTF_8)); }

    static String sha(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
