package io.github.sirp00m.npcortex;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Stage 4 READ-ONLY world perception: a bounded, deterministic scan for blocks of given ids around a centre.
 *
 * Pure Java (no Minecraft types) so every rule is unit-testable; the Minecraft binding is {@link CPlusWorldPerceptionRuntime}.
 * It observes only: the {@link View} it reads through has no mutating method, and unknown data is never reported as empty
 * (see {@link Outcome}). An observation is evidence at a game tick, not a permit and not a decision.
 */
final class CPlusWorldPerception {
    static final int SCHEMA = 1;
    static final int MAX_RADIUS = 16;            // horizontal half-width of the scan box
    static final int MAX_VERTICAL = 12;          // blocks below / above the centre
    static final int MAX_RESULTS = 64;
    static final int MAX_FILTER_IDS = 8;
    static final int MAX_EXAMINED_CAP = 32_768;  // hard ceiling of block positions probed per request
    static final int DEFAULT_EXAMINED = 8_192;
    static final int MAX_REACH = 32;             // how far the centre may be from the observing actor
    static final long DEFAULT_MAX_AGE_TICKS = 40;
    private static final int CANCEL_CHECK_MASK = 255;
    private static final int MAX_REPORTED_UNLOADED = 16;
    private static final int COORD_LIMIT = 30_000_000;
    private static final Pattern BLOCK_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    private CPlusWorldPerception() {}

    /** Read-only world access. Implementations must never load or generate a chunk and never write. */
    interface View {
        String dimension();
        int minY();
        int maxYExclusive();
        long gameTime();
        boolean chunkLoaded(int chunkX, int chunkZ);
        /** Resolves the filter once; throws IllegalArgumentException for an id that is not a known block. */
        Probe probe(List<String> blockIds);
    }

    interface Probe {
        /** Only called for positions inside a chunk for which {@link View#chunkLoaded} returned true. */
        boolean matches(int x, int y, int z);
        String idAt(int x, int y, int z);
    }

    enum Outcome {
        /** The whole box was examined, every chunk loaded: an empty result list is a verified "none found". */
        OK,
        /** The scan finished but part of the box lies in unloaded chunks: absence there is UNKNOWN, not empty. */
        PARTIAL_UNLOADED,
        /** The position budget ran out first: nearer columns were examined, farther ones were not. */
        PARTIAL_BUDGET,
        /** The cancel hook fired; results are withheld because an interrupted scan is not trustworthy. */
        CANCELLED,
        REJECTED
    }

    enum Reject {
        NOT_SERVER_THREAD, ACTOR_NOT_LIVE, DIMENSION_MISMATCH, INVALID_REQUEST, CENTER_OUT_OF_BUILD_HEIGHT,
        CENTER_OUT_OF_REACH, CENTER_NOT_LOADED, BAD_FILTER, RADIUS_TOO_LARGE, VERTICAL_TOO_LARGE, TOO_MANY_RESULTS,
        BUDGET_OUT_OF_RANGE
    }

    record Request(String dimension, int x, int y, int z, int radius, int below, int above,
                   List<String> blockIds, int maxResults, int maxExamined) {}

    record Hit(String blockId, int x, int y, int z) {}

    record Result(Outcome outcome, Reject reject, String detail, Request request, long captureTick, UUID observationId,
                  int volume, int examined, int skippedUnloaded, int matchesFound, boolean truncated, boolean verticalClipped,
                  List<int[]> unloadedChunks, List<Hit> hits) {
        boolean complete() { return outcome == Outcome.OK; }

        /** True when no hit could have been missed: only a finished scan of fully loaded chunks may claim "none". */
        boolean provenEmpty() { return outcome == Outcome.OK && hits.isEmpty(); }
    }

    static Result rejected(Reject reason, String detail, Request request) {
        return new Result(Outcome.REJECTED, reason, detail, request, 0L, null, 0, 0, 0, 0, false, false, List.of(), List.of());
    }

    /** Observation freshness: a consumer that is about to act must re-check against the CURRENT game tick. A clock that
     * went backwards (world reload) is stale, never fresh. */
    static boolean isFresh(long captureTick, long nowTick, long maxAgeTicks) {
        return maxAgeTicks >= 0 && nowTick >= captureTick && nowTick - captureTick <= maxAgeTicks;
    }

    static Result scan(View view, Request request, Supplier<UUID> ids, BooleanSupplier cancelled) {
        Result invalid = validate(view, request);
        if (invalid != null) return invalid;
        Probe probe;
        try {
            probe = view.probe(request.blockIds());
        } catch (IllegalArgumentException e) {
            return rejected(Reject.BAD_FILTER, "unknown block id", request);
        }
        int r = request.radius();
        int lo = Math.max(request.y() - request.below(), view.minY());
        int hi = Math.min(request.y() + request.above(), view.maxYExclusive() - 1);
        boolean clipped = lo != request.y() - request.below() || hi != request.y() + request.above();
        int height = hi - lo + 1;
        int volume = (2 * r + 1) * (2 * r + 1) * height;

        long[] best = new long[request.maxResults()];     // sorted keys of the nearest matches; no per-match allocation
        int kept = 0, found = 0, examined = 0, skipped = 0;
        TreeSet<Long> unloaded = new TreeSet<>();
        int cachedCx = Integer.MIN_VALUE, cachedCz = Integer.MIN_VALUE;
        boolean cachedLoaded = false;
        Outcome stop = null;

        scan:
        for (int d = 0; d <= r; d++) {
            for (int dx = -d; dx <= d; dx++) {
                int step = (Math.abs(dx) == d) ? 1 : 2 * d;       // interior columns of a ring touch only its two z edges
                for (int dz = -d; dz <= d; dz += step) {
                    if (cancelled.getAsBoolean()) { stop = Outcome.CANCELLED; break scan; }   // once per column
                    int x = request.x() + dx, z = request.z() + dz;
                    int cx = x >> 4, cz = z >> 4;
                    if (cx != cachedCx || cz != cachedCz) {
                        cachedCx = cx; cachedCz = cz; cachedLoaded = view.chunkLoaded(cx, cz);
                    }
                    if (!cachedLoaded) {
                        skipped += height;
                        unloaded.add(((long) cx << 32) | (cz & 0xffffffffL));
                        continue;
                    }
                    if (examined + height > request.maxExamined()) { stop = Outcome.PARTIAL_BUDGET; break scan; }
                    for (int y = lo; y <= hi; y++) {
                        if ((examined++ & CANCEL_CHECK_MASK) == CANCEL_CHECK_MASK && cancelled.getAsBoolean()) {
                            stop = Outcome.CANCELLED; break scan;
                        }
                        if (!probe.matches(x, y, z)) continue;
                        found++;
                        long key = key(dx, y - request.y(), dz);
                        kept = insert(best, kept, key);
                    }
                }
            }
        }
        if (stop == Outcome.CANCELLED)
            return new Result(Outcome.CANCELLED, null, "scan cancelled; results withheld", request, view.gameTime(), null,
                    volume, examined, skipped, found, false, clipped, List.of(), List.of());
        Outcome outcome = stop != null ? stop : (skipped > 0 ? Outcome.PARTIAL_UNLOADED : Outcome.OK);
        List<Hit> hits = new ArrayList<>(kept);
        for (int i = 0; i < kept; i++) {
            int[] rel = decode(best[i]);
            int x = request.x() + rel[0], y = request.y() + rel[1], z = request.z() + rel[2];
            hits.add(new Hit(probe.idAt(x, y, z), x, y, z));
        }
        List<int[]> chunks = new ArrayList<>();
        for (long packed : unloaded) {
            if (chunks.size() == MAX_REPORTED_UNLOADED) break;
            chunks.add(new int[]{(int) (packed >> 32), (int) packed});
        }
        return new Result(outcome, null, outcome.name(), request, view.gameTime(), ids.get(), volume, examined, skipped,
                found, found > kept, clipped, chunks, hits);
    }

    // ---- validation ------------------------------------------------------------------------------------------
    private static Result validate(View view, Request q) {
        if (q == null || q.dimension() == null || q.dimension().isBlank() || q.blockIds() == null)
            return rejected(Reject.INVALID_REQUEST, "request incomplete", q);
        if (!q.dimension().equals(view.dimension()))
            return rejected(Reject.DIMENSION_MISMATCH, "request dimension differs from the world being observed", q);
        if (Math.abs((long) q.x()) > COORD_LIMIT || Math.abs((long) q.z()) > COORD_LIMIT)
            return rejected(Reject.INVALID_REQUEST, "coordinate outside the world limit", q);
        if (q.y() < view.minY() || q.y() >= view.maxYExclusive())
            return rejected(Reject.CENTER_OUT_OF_BUILD_HEIGHT, "centre is outside the build height", q);
        if (q.radius() < 0 || q.radius() > MAX_RADIUS)
            return rejected(Reject.RADIUS_TOO_LARGE, "radius must be 0.." + MAX_RADIUS, q);
        if (q.below() < 0 || q.above() < 0 || q.below() > MAX_VERTICAL || q.above() > MAX_VERTICAL)
            return rejected(Reject.VERTICAL_TOO_LARGE, "below/above must be 0.." + MAX_VERTICAL, q);
        if (q.maxResults() < 1 || q.maxResults() > MAX_RESULTS)
            return rejected(Reject.TOO_MANY_RESULTS, "maxResults must be 1.." + MAX_RESULTS, q);
        if (q.maxExamined() < 1 || q.maxExamined() > MAX_EXAMINED_CAP)
            return rejected(Reject.BUDGET_OUT_OF_RANGE, "maxExamined must be 1.." + MAX_EXAMINED_CAP, q);
        if (q.blockIds().isEmpty() || q.blockIds().size() > MAX_FILTER_IDS)
            return rejected(Reject.BAD_FILTER, "1.." + MAX_FILTER_IDS + " block ids required", q);
        for (String id : q.blockIds())
            if (id == null || id.length() > 80 || !BLOCK_ID.matcher(id).matches())
                return rejected(Reject.BAD_FILTER, "malformed block id", q);
        // Conservative: never answer about a box whose centre column is not even loaded (no force-loading).
        if (!view.chunkLoaded(q.x() >> 4, q.z() >> 4))
            return rejected(Reject.CENTER_NOT_LOADED, "centre chunk is not loaded", q);
        return null;
    }

    // ---- deterministic ordering: (distance squared, y, x, z) packed into one comparable long ----------------------
    private static long key(int dx, int dy, int dz) {
        long dist = (long) dx * dx + (long) dy * dy + (long) dz * dz;
        return (dist << 17) | ((long) (dy + MAX_VERTICAL) << 12) | ((long) (dx + MAX_RADIUS) << 6) | (long) (dz + MAX_RADIUS);
    }

    private static int[] decode(long key) {
        return new int[]{(int) ((key >> 6) & 63) - MAX_RADIUS, (int) ((key >> 12) & 31) - MAX_VERTICAL, (int) (key & 63) - MAX_RADIUS};
    }

    /** Inserts into the sorted prefix of {@code best}, keeping at most best.length smallest keys; returns the new size. */
    private static int insert(long[] best, int size, long key) {
        if (size == best.length) {
            if (key >= best[size - 1]) return size;
            size--;                                         // drop the current worst
        }
        int i = size;
        while (i > 0 && best[i - 1] > key) { best[i] = best[i - 1]; i--; }
        best[i] = key;
        return size + 1;
    }

    // ---- typed JSON contract (fixed key set; see docs/contracts/world_observation_v1.json) -------------------------
    static JsonObject toJson(Result r, UUID worldId, UUID actorId, long maxAgeTicks) {
        JsonObject out = new JsonObject();
        out.addProperty("schema_version", SCHEMA);
        out.addProperty("kind", "world_observation");
        out.addProperty("read_only", true);
        out.addProperty("source", "SERVER_WORLD");
        out.addProperty("outcome", r.outcome().name());
        out.addProperty("complete", r.complete());
        out.addProperty("proven_empty", r.provenEmpty());
        if (r.reject() != null) out.addProperty("reject_code", r.reject().name());
        out.addProperty("detail", r.detail());
        out.addProperty("world_id", worldId == null ? null : worldId.toString());
        out.addProperty("actor_id", actorId == null ? null : actorId.toString());
        out.addProperty("observation_id", r.observationId() == null ? null : r.observationId().toString());
        Request q = r.request();
        out.addProperty("dimension_id", q == null ? null : q.dimension());
        if (q != null) {
            JsonObject c = new JsonObject();
            c.addProperty("x", q.x()); c.addProperty("y", q.y()); c.addProperty("z", q.z());
            out.add("center", c);
            out.addProperty("radius", q.radius()); out.addProperty("below", q.below()); out.addProperty("above", q.above());
            JsonArray filter = new JsonArray();
            q.blockIds().forEach(filter::add);
            out.add("block_ids", filter);
            out.addProperty("max_results", q.maxResults()); out.addProperty("max_examined", q.maxExamined());
        }
        out.addProperty("capture_tick", r.captureTick());
        out.addProperty("max_age_ticks", maxAgeTicks);
        out.addProperty("fresh_until_tick", r.captureTick() + maxAgeTicks);
        out.addProperty("volume", r.volume());
        out.addProperty("examined", r.examined());
        out.addProperty("skipped_unloaded", r.skippedUnloaded());
        out.addProperty("matches_found", r.matchesFound());
        out.addProperty("truncated", r.truncated());
        out.addProperty("vertical_clipped", r.verticalClipped());
        JsonArray unloaded = new JsonArray();
        for (int[] chunk : r.unloadedChunks()) {
            JsonObject o = new JsonObject();
            o.addProperty("chunk_x", chunk[0]); o.addProperty("chunk_z", chunk[1]);
            unloaded.add(o);
        }
        out.add("unloaded_chunks", unloaded);
        JsonArray hits = new JsonArray();
        for (Hit h : r.hits()) {
            JsonObject o = new JsonObject();
            o.addProperty("block_id", h.blockId());
            o.addProperty("x", h.x()); o.addProperty("y", h.y()); o.addProperty("z", h.z());
            hits.add(o);
        }
        out.add("results", hits);
        return out;
    }
}
