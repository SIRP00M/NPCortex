package io.github.sirp00m.npcortex;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/** Stage 4 read-only perception. FIXTURE-BASED: the world is an in-memory fake, so this proves the scan rules, limits,
 * ordering and failure outcomes; it does NOT prove behaviour against a live Minecraft world (that needs gameplay). */
public final class CPlusWorldPerceptionSelfTest {
    private static int checks;
    private static void ok(boolean pass, String message) { checks++; if (!pass) throw new AssertionError(message); }

    private record P(int x, int y, int z) {}

    /** In-memory world. Records every call so tests can prove it was only read and that unloaded chunks were never probed. */
    private static final class FakeWorld implements CPlusWorldPerception.View {
        String dimension = "minecraft:overworld";
        int minY = -64, maxY = 320;
        long tick = 1000;
        final Set<Long> unloaded = new HashSet<>();
        final Map<P, String> blocks = new HashMap<>();
        final Set<String> known = Set.of("minecraft:oak_log", "minecraft:spruce_log", "minecraft:stone", "minecraft:air", "minecraft:dirt");
        int probeCalls, idCalls, chunkChecks;
        boolean touchedUnloaded;
        static long ck(int cx, int cz) { return ((long) cx << 32) | (cz & 0xffffffffL); }
        boolean loaded(int cx, int cz) { return !unloaded.contains(ck(cx, cz)); }
        @Override public String dimension() { return dimension; }
        @Override public int minY() { return minY; }
        @Override public int maxYExclusive() { return maxY; }
        @Override public long gameTime() { return tick; }
        @Override public boolean chunkLoaded(int cx, int cz) { chunkChecks++; return loaded(cx, cz); }
        @Override public CPlusWorldPerception.Probe probe(List<String> ids) {
            for (String id : ids) if (!known.contains(id)) throw new IllegalArgumentException("unknown block " + id);
            Set<String> wanted = Set.copyOf(ids);
            return new CPlusWorldPerception.Probe() {
                @Override public boolean matches(int x, int y, int z) {
                    probeCalls++;
                    if (!loaded(x >> 4, z >> 4)) touchedUnloaded = true;
                    return wanted.contains(blocks.getOrDefault(new P(x, y, z), "minecraft:air"));
                }
                @Override public String idAt(int x, int y, int z) { idCalls++; return blocks.getOrDefault(new P(x, y, z), "minecraft:air"); }
            };
        }
    }

    private static final UUID FIXED = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static CPlusWorldPerception.Request req(int x, int y, int z, int r, int below, int above, String... ids) {
        return new CPlusWorldPerception.Request("minecraft:overworld", x, y, z, r, below, above, List.of(ids), 16, 8192);
    }
    private static CPlusWorldPerception.Result run(FakeWorld w, CPlusWorldPerception.Request q) {
        return CPlusWorldPerception.scan(w, q, () -> FIXED, () -> false);
    }
    private static CPlusWorldPerception.Request withLimits(CPlusWorldPerception.Request q, int maxResults, int maxExamined) {
        return new CPlusWorldPerception.Request(q.dimension(), q.x(), q.y(), q.z(), q.radius(), q.below(), q.above(), q.blockIds(), maxResults, maxExamined);
    }
    private static void rejects(FakeWorld w, CPlusWorldPerception.Request q, CPlusWorldPerception.Reject code, String why) {
        CPlusWorldPerception.Result r = run(w, q);
        ok(r.outcome() == CPlusWorldPerception.Outcome.REJECTED && r.reject() == code, why + " -> " + r.outcome() + "/" + r.reject());
        ok(r.hits().isEmpty() && r.examined() == 0 && !r.complete() && !r.provenEmpty(), why + ": a rejection has no data and never claims 'empty'");
        ok(w.probeCalls == 0, why + ": nothing was read for a rejected request");
    }

    public static void main(String[] args) throws Exception {
        // ---- 1. normal observation, ordering and metadata
        FakeWorld w = new FakeWorld();
        w.blocks.put(new P(3, 64, 0), "minecraft:oak_log");
        w.blocks.put(new P(0, 70, 0), "minecraft:oak_log");
        w.blocks.put(new P(-1, 64, 0), "minecraft:oak_log");       // distance 1: nearest
        w.blocks.put(new P(5, 64, 5), "minecraft:spruce_log");      // not in the filter
        w.blocks.put(new P(2, 64, 2), "minecraft:stone");
        CPlusWorldPerception.Result r = run(w, req(0, 64, 0, 8, 4, 12, "minecraft:oak_log"));
        ok(r.outcome() == CPlusWorldPerception.Outcome.OK && r.complete() && r.reject() == null, "normal scan completes");
        ok(r.hits().size() == 3 && r.matchesFound() == 3 && !r.truncated(), "three oak logs found");
        ok(r.hits().get(0).equals(new CPlusWorldPerception.Hit("minecraft:oak_log", -1, 64, 0)), "nearest first");
        ok(r.hits().get(1).equals(new CPlusWorldPerception.Hit("minecraft:oak_log", 3, 64, 0)) && r.hits().get(2).y() == 70, "then by distance squared");
        ok(r.captureTick() == 1000 && r.observationId().equals(FIXED), "observation metadata: tick and id");
        ok(r.examined() == r.volume() && r.volume() == 17 * 17 * 17 && r.skippedUnloaded() == 0, "every position of the box examined");
        ok(!r.provenEmpty() && r.unloadedChunks().isEmpty(), "not empty, nothing unloaded");
        ok(w.idCalls == 3, "block identity is resolved only for the returned hits");

        // ---- 2. empty result on a fully loaded, completely scanned box is a verified 'none'
        w = new FakeWorld();
        r = run(w, req(0, 64, 0, 4, 2, 2, "minecraft:oak_log"));
        ok(r.outcome() == CPlusWorldPerception.Outcome.OK && r.hits().isEmpty() && r.provenEmpty(), "complete empty scan proves 'none found'");

        // ---- 3. unloaded chunks are UNKNOWN, never empty, never probed
        w = new FakeWorld();
        w.unloaded.add(FakeWorld.ck(1, 0));                          // x 16..31
        r = run(w, req(10, 64, 0, 10, 0, 0, "minecraft:oak_log"));   // box x 0..20 spans chunks 0 and 1
        ok(r.outcome() == CPlusWorldPerception.Outcome.PARTIAL_UNLOADED && !r.complete(), "unloaded part => PARTIAL_UNLOADED");
        ok(r.hits().isEmpty() && !r.provenEmpty(), "no hits in a partially unknown box is NOT 'empty'");
        ok(r.skippedUnloaded() > 0 && r.examined() + r.skippedUnloaded() == r.volume(), "examined + skipped == volume");
        ok(r.unloadedChunks().size() == 1 && r.unloadedChunks().get(0)[0] == 1 && r.unloadedChunks().get(0)[1] == 0, "unloaded chunk is reported");
        ok(!w.touchedUnloaded, "an unloaded chunk was never probed (no force-load, no fake air)");
        w = new FakeWorld(); w.unloaded.add(FakeWorld.ck(0, 0));
        rejects(w, req(1, 64, 1, 4, 1, 1, "minecraft:oak_log"), CPlusWorldPerception.Reject.CENTER_NOT_LOADED, "centre chunk unloaded");

        // ---- 4. request validation (range, budget, ids, dimension, coordinates)
        w = new FakeWorld();
        CPlusWorldPerception.Request base = req(0, 64, 0, 4, 2, 2, "minecraft:oak_log");
        rejects(w, req(0, 64, 0, 17, 2, 2, "minecraft:oak_log"), CPlusWorldPerception.Reject.RADIUS_TOO_LARGE, "radius 17");
        rejects(w, req(0, 64, 0, -1, 2, 2, "minecraft:oak_log"), CPlusWorldPerception.Reject.RADIUS_TOO_LARGE, "negative radius");
        rejects(w, req(0, 64, 0, 4, 13, 2, "minecraft:oak_log"), CPlusWorldPerception.Reject.VERTICAL_TOO_LARGE, "below 13");
        rejects(w, req(0, 64, 0, 4, 2, 13, "minecraft:oak_log"), CPlusWorldPerception.Reject.VERTICAL_TOO_LARGE, "above 13");
        rejects(w, req(0, 64, 0, 4, -1, 2, "minecraft:oak_log"), CPlusWorldPerception.Reject.VERTICAL_TOO_LARGE, "negative below");
        rejects(w, withLimits(base, 65, 8192), CPlusWorldPerception.Reject.TOO_MANY_RESULTS, "65 results");
        rejects(w, withLimits(base, 0, 8192), CPlusWorldPerception.Reject.TOO_MANY_RESULTS, "0 results");
        rejects(w, withLimits(base, 16, 0), CPlusWorldPerception.Reject.BUDGET_OUT_OF_RANGE, "budget 0");
        rejects(w, withLimits(base, 16, 32_769), CPlusWorldPerception.Reject.BUDGET_OUT_OF_RANGE, "budget above the hard cap");
        rejects(w, req(0, 64, 0, 4, 2, 2), CPlusWorldPerception.Reject.BAD_FILTER, "empty filter");
        rejects(w, req(0, 64, 0, 4, 2, 2, "a:1", "a:2", "a:3", "a:4", "a:5", "a:6", "a:7", "a:8", "a:9"), CPlusWorldPerception.Reject.BAD_FILTER, "nine ids");
        rejects(w, req(0, 64, 0, 4, 2, 2, "OAK LOG"), CPlusWorldPerception.Reject.BAD_FILTER, "malformed id");
        rejects(w, req(0, 64, 0, 4, 2, 2, "minecraft:not_a_block"), CPlusWorldPerception.Reject.BAD_FILTER, "unknown id");
        rejects(w, new CPlusWorldPerception.Request("minecraft:the_nether", 0, 64, 0, 4, 2, 2, List.of("minecraft:oak_log"), 16, 8192),
                CPlusWorldPerception.Reject.DIMENSION_MISMATCH, "wrong dimension");
        rejects(w, new CPlusWorldPerception.Request("", 0, 64, 0, 4, 2, 2, List.of("minecraft:oak_log"), 16, 8192),
                CPlusWorldPerception.Reject.INVALID_REQUEST, "blank dimension");
        rejects(w, req(0, -65, 0, 4, 2, 2, "minecraft:oak_log"), CPlusWorldPerception.Reject.CENTER_OUT_OF_BUILD_HEIGHT, "below the world");
        rejects(w, req(0, 320, 0, 4, 2, 2, "minecraft:oak_log"), CPlusWorldPerception.Reject.CENTER_OUT_OF_BUILD_HEIGHT, "above the world");
        rejects(w, req(30_000_001, 64, 0, 4, 2, 2, "minecraft:oak_log"), CPlusWorldPerception.Reject.INVALID_REQUEST, "x beyond the world limit");
        rejects(w, req(0, 64, -30_000_001, 4, 2, 2, "minecraft:oak_log"), CPlusWorldPerception.Reject.INVALID_REQUEST, "z beyond the world limit");
        CPlusWorldPerception.Result nullRequest = CPlusWorldPerception.scan(w, null, () -> FIXED, () -> false);
        ok(nullRequest.reject() == CPlusWorldPerception.Reject.INVALID_REQUEST, "null request is rejected, not a crash");
        CPlusWorldPerception.Request nullIds = new CPlusWorldPerception.Request("minecraft:overworld", 0, 64, 0, 4, 2, 2, null, 16, 8192);
        ok(CPlusWorldPerception.scan(w, nullIds, () -> FIXED, () -> false).reject() == CPlusWorldPerception.Reject.INVALID_REQUEST, "null filter rejected");
        // the hard limits themselves are accepted
        ok(run(w, withLimits(req(0, 64, 0, 16, 12, 12, "minecraft:oak_log"), 64, 32_768)).outcome() == CPlusWorldPerception.Outcome.OK,
                "maximum radius/vertical/results/budget is accepted and finishes (27,225 positions)");
        ok(w.probeCalls == 33 * 33 * 25, "the maximum box probes exactly its volume once");

        // ---- 5. budget: nearer columns first, explicit PARTIAL_BUDGET, never a claim of 'none'
        w = new FakeWorld();
        w.blocks.put(new P(1, 64, 0), "minecraft:oak_log");          // ring 1
        w.blocks.put(new P(7, 64, 0), "minecraft:oak_log");          // ring 7
        r = run(w, withLimits(req(0, 64, 0, 8, 0, 0, "minecraft:oak_log"), 16, 25));   // 1 + 8 + 16 columns = rings 0..2
        ok(r.outcome() == CPlusWorldPerception.Outcome.PARTIAL_BUDGET && !r.complete() && !r.provenEmpty(), "budget exhausted => PARTIAL_BUDGET");
        ok(r.examined() == 25 && r.hits().size() == 1 && r.hits().get(0).x() == 1, "only the nearer rings were examined");
        ok(r.examined() < r.volume(), "coverage is reported (examined < volume)");
        w = new FakeWorld();
        r = run(w, withLimits(req(0, 64, 0, 8, 0, 0, "minecraft:oak_log"), 16, 25));
        ok(r.outcome() == CPlusWorldPerception.Outcome.PARTIAL_BUDGET && !r.provenEmpty(), "an empty but truncated scan is still not 'none found'");
        w = new FakeWorld(); w.unloaded.add(FakeWorld.ck(-1, 0));
        r = run(w, withLimits(req(0, 64, 0, 12, 0, 0, "minecraft:oak_log"), 16, 40));
        ok(r.outcome() == CPlusWorldPerception.Outcome.PARTIAL_BUDGET && r.skippedUnloaded() > 0, "budget outranks unloaded in the outcome but the unknown area is still reported");

        // ---- 6. truncation by maxResults keeps the true nearest and says so
        w = new FakeWorld();
        for (int i = 1; i <= 10; i++) w.blocks.put(new P(i, 64, 0), "minecraft:oak_log");
        r = run(w, withLimits(req(0, 64, 0, 12, 0, 0, "minecraft:oak_log"), 3, 8192));
        ok(r.outcome() == CPlusWorldPerception.Outcome.OK && r.truncated() && r.matchesFound() == 10 && r.hits().size() == 3, "truncated results are flagged");
        ok(r.hits().get(0).x() == 1 && r.hits().get(1).x() == 2 && r.hits().get(2).x() == 3, "truncation keeps the nearest, in order");

        // ---- 7. determinism: ordering ties, insertion order and a brute-force reference over random worlds
        for (long seed = 1; seed <= 40; seed++) {
            Random random = new Random(seed);
            FakeWorld a = new FakeWorld(), b = new FakeWorld();
            List<P> placed = new ArrayList<>();
            for (int i = 0; i < 150; i++)
                placed.add(new P(random.nextInt(21) - 10, 60 + random.nextInt(9), random.nextInt(21) - 10));
            for (P p : placed) a.blocks.put(p, "minecraft:oak_log");
            List<P> shuffled = new ArrayList<>(placed);
            java.util.Collections.shuffle(shuffled, new Random(seed * 7919));
            for (P p : shuffled) b.blocks.put(p, "minecraft:oak_log");
            for (long chunk : new long[]{FakeWorld.ck(-1, 0), FakeWorld.ck(-1, -1), FakeWorld.ck(0, -1)})   // the centre chunk (0,0) stays loaded
                if (random.nextInt(3) == 0) { a.unloaded.add(chunk); b.unloaded.add(chunk); }
            CPlusWorldPerception.Request q = withLimits(req(0, 64, 0, 10, 4, 4, "minecraft:oak_log"), 1 + random.nextInt(40), 32_768);
            CPlusWorldPerception.Result ra = run(a, q), rb = run(b, q);
            ok(CPlusWorldPerception.toJson(ra, FIXED, FIXED, 40).equals(CPlusWorldPerception.toJson(rb, FIXED, FIXED, 40)),
                    "identical results regardless of how the world was populated (seed " + seed + ")");
            List<CPlusWorldPerception.Hit> reference = new ArrayList<>();
            for (P p : new HashSet<>(placed)) {
                if (Math.abs(p.x()) > 10 || Math.abs(p.z()) > 10 || p.y() < 60 || p.y() > 68 || !a.loaded(p.x() >> 4, p.z() >> 4)) continue;
                reference.add(new CPlusWorldPerception.Hit("minecraft:oak_log", p.x(), p.y(), p.z()));
            }
            reference.sort((h1, h2) -> {
                int d1 = h1.x() * h1.x() + (h1.y() - 64) * (h1.y() - 64) + h1.z() * h1.z();
                int d2 = h2.x() * h2.x() + (h2.y() - 64) * (h2.y() - 64) + h2.z() * h2.z();
                if (d1 != d2) return Integer.compare(d1, d2);
                if (h1.y() != h2.y()) return Integer.compare(h1.y(), h2.y());
                if (h1.x() != h2.x()) return Integer.compare(h1.x(), h2.x());
                return Integer.compare(h1.z(), h2.z());
            });
            List<CPlusWorldPerception.Hit> expected = reference.subList(0, Math.min(reference.size(), q.maxResults()));
            ok(ra.hits().equals(expected), "scan equals the brute-force (distance, y, x, z) reference (seed " + seed + ")");
            ok(ra.matchesFound() == reference.size() && ra.truncated() == (reference.size() > q.maxResults()), "match count and truncation flag (seed " + seed + ")");
        }

        // ---- 8. vertical clipping at the world floor is reported
        w = new FakeWorld();
        r = run(w, req(0, -62, 0, 2, 12, 2, "minecraft:oak_log"));
        ok(r.verticalClipped() && r.outcome() == CPlusWorldPerception.Outcome.OK && r.volume() == 5 * 5 * 5, "clipped to the build height and said so (-64..-60)");
        ok(!run(w, req(0, 64, 0, 2, 2, 2, "minecraft:oak_log")).verticalClipped(), "no clipping in the middle of the world");

        // ---- 9. cancellation / interruption
        w = new FakeWorld();
        CPlusWorldPerception.Request big = req(0, 64, 0, 6, 12, 12, "minecraft:oak_log");
        AtomicInteger calls = new AtomicInteger();
        CPlusWorldPerception.scan(w, big, () -> FIXED, () -> { calls.incrementAndGet(); return false; });
        int totalChecks = calls.get();
        ok(totalChecks > 169, "the cancel hook is polled at least once per column");
        for (int fireAt : new int[]{1, 2, 17, 100, totalChecks}) {
            AtomicInteger n = new AtomicInteger();
            FakeWorld fresh = new FakeWorld();
            fresh.blocks.put(new P(1, 64, 0), "minecraft:oak_log");
            CPlusWorldPerception.Result c = CPlusWorldPerception.scan(fresh, big, () -> FIXED, () -> n.incrementAndGet() >= fireAt);
            ok(c.outcome() == CPlusWorldPerception.Outcome.CANCELLED && !c.complete() && !c.provenEmpty(), "cancel at poll " + fireAt);
            ok(c.hits().isEmpty() && c.observationId() == null, "an interrupted scan withholds its results (poll " + fireAt + ")");
        }
        CPlusWorldPerception.Result notCancelled = CPlusWorldPerception.scan(new FakeWorld(), big, () -> FIXED, () -> false);
        ok(notCancelled.outcome() == CPlusWorldPerception.Outcome.OK, "a hook that never fires does not change the outcome");
        FakeWorld tall = new FakeWorld();
        AtomicInteger m = new AtomicInteger();
        CPlusWorldPerception.Result mid = CPlusWorldPerception.scan(tall, withLimits(req(0, 64, 0, 16, 12, 12, "minecraft:oak_log"), 8, 32_768),
                () -> FIXED, () -> m.incrementAndGet() > 400);
        ok(mid.outcome() == CPlusWorldPerception.Outcome.CANCELLED && tall.probeCalls < 33 * 33 * 25, "a late cancel stops the scan early");

        // ---- 10. freshness
        ok(CPlusWorldPerception.isFresh(1000, 1000, 40) && CPlusWorldPerception.isFresh(1000, 1040, 40), "fresh at capture and at the age limit");
        ok(!CPlusWorldPerception.isFresh(1000, 1041, 40), "stale one tick past the limit");
        ok(!CPlusWorldPerception.isFresh(1000, 999, 40), "a clock that went backwards (reload) is stale");
        ok(!CPlusWorldPerception.isFresh(1000, 1000, -1), "a negative age limit is never fresh");
        ok(CPlusWorldPerception.isFresh(Long.MAX_VALUE - 5, Long.MAX_VALUE, 10), "no overflow near the long range");

        // ---- 11. no world mutation and no loading: structure, behaviour, source
        for (Class<?> iface : new Class<?>[]{CPlusWorldPerception.View.class, CPlusWorldPerception.Probe.class}) {
            Set<String> names = java.util.Arrays.stream(iface.getDeclaredMethods()).map(java.lang.reflect.Method::getName).collect(Collectors.toSet());
            ok(Set.of("dimension", "minY", "maxYExclusive", "gameTime", "chunkLoaded", "probe", "matches", "idAt").containsAll(names),
                    iface.getSimpleName() + " exposes only read operations: " + names);
        }
        w = new FakeWorld();
        w.unloaded.add(FakeWorld.ck(3, 3));
        w.blocks.put(new P(2, 64, 2), "minecraft:oak_log");
        Map<P, String> before = new HashMap<>(w.blocks);
        Set<Long> unloadedBefore = new HashSet<>(w.unloaded);
        run(w, req(0, 64, 0, 16, 12, 12, "minecraft:oak_log"));
        ok(w.blocks.equals(before) && w.unloaded.equals(unloadedBefore), "the world fixture is unchanged and no chunk became loaded");
        String core = Files.readString(source("CPlusWorldPerception.java")), runtime = Files.readString(source("CPlusWorldPerceptionRuntime.java"));
        for (String forbidden : new String[]{"setBlock", "destroyBlock", "removeBlock", "setBlockAndUpdate", "useItemOn", "addFreshEntity",
                "setChunkForced", "forceChunk", "addTicket", "getChunkSource().getChunk(", ".getChunk(", "level.getBlockState", "ChunkStatus"}) {
            ok(!core.contains(forbidden) && !runtime.contains(forbidden), "perception sources never use " + forbidden);
        }
        ok(runtime.contains("getChunkNow") && runtime.contains("chunk.getBlockState"), "the Minecraft binding reads only already-loaded chunks");
        ok(!core.contains("net.minecraft"), "the scan core has no Minecraft dependency");

        // ---- 12. thread ownership: the Minecraft binding refuses to run off the server thread (null server or wrong thread)
        try {
            CPlusWorldPerceptionRuntime.observe(null, "yui", 4, List.of("minecraft:oak_log"), 2, 2, 16, 8192);
            ok(false, "observe without a server thread must throw");
        } catch (IllegalStateException expected) {
            ok(expected.getMessage().contains("requires the Minecraft server thread"), "thread guard message");
        }
        ok(runtime.indexOf("ServerThread.require(server, \"world perception\")") > 0
                && runtime.indexOf("ServerThread.require") < runtime.indexOf("C1IdentityData.get(server)"), "the guard runs before any world or identity access");

        // ---- 13. typed contract: the JSON key set is fixed and documented
        w = new FakeWorld();
        w.blocks.put(new P(1, 64, 0), "minecraft:oak_log");
        w.unloaded.add(FakeWorld.ck(-1, 0));
        CPlusWorldPerception.Result sample = run(w, withLimits(req(0, 64, 0, 4, 1, 1, "minecraft:oak_log"), 4, 8192));
        JsonObject json = CPlusWorldPerception.toJson(sample, FIXED, FIXED, CPlusWorldPerception.DEFAULT_MAX_AGE_TICKS);
        JsonObject doc = JsonParser.parseString(Files.readString(contract())).getAsJsonObject().getAsJsonObject("example");
        ok(json.keySet().equals(doc.keySet()), "top-level keys match docs/contracts/world_observation_v1.json: " + json.keySet());
        ok(json.getAsJsonObject("center").keySet().equals(doc.getAsJsonObject("center").keySet()), "center keys");
        ok(json.getAsJsonArray("results").size() == 1 && json.getAsJsonArray("results").get(0).getAsJsonObject().keySet()
                .equals(doc.getAsJsonArray("results").get(0).getAsJsonObject().keySet()), "result keys");
        ok(json.getAsJsonArray("unloaded_chunks").get(0).getAsJsonObject().keySet()
                .equals(doc.getAsJsonArray("unloaded_chunks").get(0).getAsJsonObject().keySet()), "unloaded chunk keys");
        ok(json.get("read_only").getAsBoolean() && "SERVER_WORLD".equals(json.get("source").getAsString()) && json.get("schema_version").getAsInt() == 1, "constants");
        ok(json.get("fresh_until_tick").getAsLong() == sample.captureTick() + 40, "fresh_until_tick = capture + max age");
        JsonObject rejectedJson = CPlusWorldPerception.toJson(CPlusWorldPerception.rejected(CPlusWorldPerception.Reject.ACTOR_NOT_LIVE, "x", null), null, null, 40);
        ok(rejectedJson.get("outcome").getAsString().equals("REJECTED") && rejectedJson.get("reject_code").getAsString().equals("ACTOR_NOT_LIVE")
                && !rejectedJson.get("complete").getAsBoolean() && !rejectedJson.get("proven_empty").getAsBoolean(), "rejection JSON never claims completeness");

        System.out.println("PASS CPlusWorldPerceptionSelfTest checks=" + checks + "; FIXTURE world only, NOT a live Minecraft observation, NOT performance acceptance");
    }

    private static Path source(String name) {
        for (String dir : new String[]{"src/main/java/io/github/sirp00m/npcortex/", "forge-mod/src/main/java/io/github/sirp00m/npcortex/"}) {
            Path p = Path.of(dir + name);
            if (Files.exists(p)) return p;
        }
        throw new AssertionError("source not found: " + name);
    }

    private static Path contract() {
        for (String dir : new String[]{"../docs/contracts/", "docs/contracts/"}) {
            Path p = Path.of(dir + "world_observation_v1.json");
            if (Files.exists(p)) return p;
        }
        throw new AssertionError("contract not found");
    }
}
