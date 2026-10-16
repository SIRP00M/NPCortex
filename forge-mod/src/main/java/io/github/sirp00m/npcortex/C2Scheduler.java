package io.github.sirp00m.npcortex;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Server-thread-confined exclusive actor goal scheduler; future scheduling policy
 * can be added independently of C1 without prioritizing an NPC owner/player.
 * P0 explicitly rejects contention: multiplayer FIFO is a later authorized scope.
 */
final class C2Scheduler<T> {
    private final int capacity;
    private final Map<UUID,T> entries=new HashMap<>();
    C2Scheduler(int capacity) {
        if (capacity<1) throw new IllegalArgumentException("capacity");
        this.capacity=capacity;
    }
    boolean claim(UUID actor,T run) {
        if (actor==null || run==null || entries.containsKey(actor) || entries.size()>=capacity) return false;
        entries.put(actor,run);
        return true;
    }
    T get(UUID actor) { return entries.get(actor); }
    boolean containsKey(UUID actor) { return entries.containsKey(actor); }
    boolean release(UUID actor,T owner) {
        if (entries.get(actor)!=owner) return false;
        entries.remove(actor);
        return true;
    }
    int size() { return entries.size(); }
    boolean isEmpty() { return entries.isEmpty(); }
    List<T> values() { return new ArrayList<>(entries.values()); }
}
