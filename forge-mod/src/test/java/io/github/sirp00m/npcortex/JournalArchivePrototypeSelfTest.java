package io.github.sirp00m.npcortex;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/** PAP scalable-journal PROTOTYPE (test tree only; production persistence is untouched and unmigrated).
 *
 * Models option "bounded hot window + archive-before-evict": the C1 SavedData keeps the newest {@code cap} receipts; the oldest is
 * moved to a durable archive (the backend ledger in the real design) BEFORE it leaves the window, and a lookup that misses the window
 * asks the archive. A lookup NEVER answers NEW when the archive cannot be consulted (fail closed), and nothing is ever evicted
 * silently. The self-test enumerates crash points between the durable steps and checks the one asymmetry that matters:
 * a committed id may answer REPLAY or CONFLICT (a phantom REPLAY for a lost commit degrades to the accepted HOLD path), but it must
 * never answer NEW. This proves the ORDERING rule only; it says nothing about the real archive transport, latency or migration.
 */
public final class JournalArchivePrototypeSelfTest {
    enum Lookup { NEW, REPLAY, CONFLICT, UNAVAILABLE }

    /** Durable archive: put returns only after the entry is durable; get may be unavailable (backend down). */
    static final class Archive {
        final Map<UUID, String> entries = new HashMap<>();
        boolean up = true;
        boolean put(UUID id, String fp) { if (!up) return false; entries.put(id, fp); return true; }
    }

    /** The hot window as SavedData persists it: one atomic snapshot (window + revision), saved at world-save checkpoints. */
    static final class Window {
        final int cap;
        LinkedHashMap<UUID, String> map = new LinkedHashMap<>();
        Window(int cap) { this.cap = cap; }
        Window copy() { Window w = new Window(cap); w.map = new LinkedHashMap<>(map); return w; }
    }

    static Lookup lookup(Window w, Archive a, UUID id, String fp) {
        String hot = w.map.get(id);
        if (hot != null) return hot.equals(fp) ? Lookup.REPLAY : Lookup.CONFLICT;
        if (!a.up) return Lookup.UNAVAILABLE;                        // fail closed: never NEW when the archive cannot answer
        String cold = a.entries.get(id);
        if (cold != null) return cold.equals(fp) ? Lookup.REPLAY : Lookup.CONFLICT;
        return Lookup.NEW;
    }

    /** @return true when committed. The oldest entry is archived (durably) BEFORE it is dropped from the window. */
    static boolean commit(Window w, Archive a, UUID id, String fp) {
        if (lookup(w, a, id, fp) != Lookup.NEW) return false;
        if (w.map.size() >= w.cap) {
            Map.Entry<UUID, String> oldest = w.map.entrySet().iterator().next();
            if (!a.put(oldest.getKey(), oldest.getValue())) return false;   // cannot archive: refuse, evict nothing
            w.map.remove(oldest.getKey());
        }
        w.map.put(id, fp);
        return true;
    }

    private static int n;

    private static void check(boolean ok, String what) {
        n++;
        if (!ok) throw new AssertionError(what);
    }

    public static void main(String[] args) {
        // ---- 1. exact lookup for every historical id, far beyond the window
        Window w = new Window(8);
        Archive a = new Archive();
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            UUID id = UUID.randomUUID();
            ids.add(id);
            check(commit(w, a, id, "fp" + i), "commit " + i);
        }
        check(w.map.size() == 8 && a.entries.size() == 192, "window stays bounded, the rest is archived: " + w.map.size() + "/" + a.entries.size());
        for (int i = 0; i < 200; i++) {
            check(lookup(w, a, ids.get(i), "fp" + i) == Lookup.REPLAY, "historical id " + i + " answers REPLAY");
            check(lookup(w, a, ids.get(i), "other") == Lookup.CONFLICT, "historical id " + i + " answers CONFLICT for another fingerprint");
        }
        check(lookup(w, a, UUID.randomUUID(), "x") == Lookup.NEW, "an unseen id is NEW");
        check(!commit(w, a, ids.get(0), "fp0"), "re-committing an archived id is refused");

        // ---- 2. fail closed: an unreachable archive never turns a historical id into NEW
        a.up = false;
        check(lookup(w, a, ids.get(0), "fp0") == Lookup.UNAVAILABLE, "archive down: UNAVAILABLE, never NEW");
        check(lookup(w, a, ids.get(199), "fp199") == Lookup.REPLAY, "a hot id is still answered");
        UUID fresh = UUID.randomUUID();
        check(!commit(w, a, fresh, "f") && !w.map.containsKey(fresh), "archive down: no commit when the window is full, nothing is evicted");
        check(w.map.size() == 8, "the window was not touched by the refused commit");
        a.up = true;

        // ---- 3. crash enumeration: a restart reloads the LAST SAVED window and the archive as it is
        Random rnd = new Random(7);
        int phantom = 0, checked = 0;
        for (int trial = 0; trial < 300; trial++) {
            int cap = 2 + rnd.nextInt(5);
            Window live = new Window(cap), saved = new Window(cap);
            Archive arc = new Archive();
            Map<UUID, String> acknowledged = new LinkedHashMap<>();       // what the system said "committed" to
            Map<UUID, String> durable = new LinkedHashMap<>();            // acknowledged AND covered by a save or the archive
            int steps = 5 + rnd.nextInt(30), crashAt = rnd.nextInt(steps);
            for (int s = 0; s < steps; s++) {
                UUID id = UUID.randomUUID();
                String fp = "t" + trial + "s" + s;
                if (s == crashAt && rnd.nextBoolean()) arc.up = false;     // the archive may be down at the crash point
                if (commit(live, arc, id, fp)) acknowledged.put(id, fp);
                arc.up = true;
                if (rnd.nextInt(4) == 0) saved = live.copy();              // a world save checkpoint
                if (s == crashAt) break;
            }
            // CRASH: only `saved` (SavedData on disk) and the archive survive
            for (Map.Entry<UUID, String> e : acknowledged.entrySet()) {
                boolean onDisk = saved.map.containsKey(e.getKey()) || arc.entries.containsKey(e.getKey());
                Lookup r = lookup(saved, arc, e.getKey(), e.getValue());
                checked++;
                if (onDisk) check(r == Lookup.REPLAY, "a durable committed id must answer REPLAY after a crash, never NEW (trial " + trial + ")");
                else check(r == Lookup.NEW, "a commit that never reached disk is simply gone (its inventory change is gone with it): trial " + trial);
                if (r == Lookup.REPLAY && !saved.map.containsKey(e.getKey())) {
                    // archived but absent from the saved window: the commit may have been lost with the unsaved snapshot, so this is a
                    // phantom REPLAY. It leads to the accepted HOLD path (repair refuses without a matching snapshot), never to a duplicate.
                    phantom++;
                }
            }
            check(saved.map.size() <= cap, "the saved window respects its bound");
        }
        check(checked > 1000, "enough crash cases were exercised: " + checked);
        System.out.println("JournalArchivePrototypeSelfTest OK (" + n + " checks; crash cases " + checked + ", archive-only REPLAYs (may be commits whose snapshot was never saved) " + phantom + ", never a NEW for a durable id)");
    }
}
