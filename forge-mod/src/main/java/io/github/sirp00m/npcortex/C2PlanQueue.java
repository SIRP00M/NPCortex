package io.github.sirp00m.npcortex;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Pure FIFO grant order for the one-plan-per-tick limiter. A run "touches" the
 * queue on every tick it is ready to plan; the oldest continuously-ready run is
 * the head, independent of HashMap iteration order. An entry not touched for more
 * than one tick is dropped, so a run that stopped being ready cannot block others.
 * With at most N ready runs, a ready run waits behind at most N-1 older ones.
 */
final class C2PlanQueue {
    private record Entry(long firstTick, long seq, long lastSeen) { }
    private final Map<UUID, Entry> entries = new HashMap<>();
    private long seq;

    void touch(UUID key, long tick) {
        Entry old = entries.get(key);
        if (old != null && old.lastSeen() >= tick - 1) entries.put(key, new Entry(old.firstTick(), old.seq(), tick));
        else entries.put(key, new Entry(tick, ++seq, tick));
    }

    boolean isHead(UUID key, long tick) {
        UUID best = null;
        Entry bestEntry = null;
        for (Iterator<Map.Entry<UUID, Entry>> it = entries.entrySet().iterator(); it.hasNext();) {
            var e = it.next();
            if (e.getValue().lastSeen() < tick - 1) { it.remove(); continue; }
            if (bestEntry == null || e.getValue().firstTick() < bestEntry.firstTick()
                    || e.getValue().firstTick() == bestEntry.firstTick() && e.getValue().seq() < bestEntry.seq()) {
                best = e.getKey();
                bestEntry = e.getValue();
            }
        }
        return key.equals(best);
    }

    void done(UUID key) { entries.remove(key); }
    void clear() { entries.clear(); seq = 0; }
}
