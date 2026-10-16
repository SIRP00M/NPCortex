package io.github.sirp00m.npcortex;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.UUID;

/** Bounded, synchronous, server-thread-only domain event stream.
 * Neither event subscriptions nor dispatch invoke HTTP, Forge mutation or model inference.
 * The sink can be expanded without changing the agent/session contracts.
 */
final class C2Events {
    enum Kind { REQUESTED, REGISTERED, OBSERVED, DECIDED, ACTION_STARTED,
        ACTION_FINISHED, VERIFIED, REJECTED, INTERRUPTED }
    record Event(Kind kind, UUID actor, UUID goal, UUID action, String detail) {}
    private static final int HISTORY_LIMIT = 128;
    private final ArrayDeque<Event> history = new ArrayDeque<>();
    private final List<Consumer<Event>> listeners = new ArrayList<>();
    void subscribe(Consumer<Event> listener) {
        if (listener == null || listeners.size() >= 8) throw new IllegalArgumentException("C2 event subscriber limit");
        listeners.add(listener);
    }

    void publish(Kind kind, UUID actor, UUID goal, UUID action, String detail) {
        String safe = detail == null ? "" : detail.replace('\n', ' ').replace('\r', ' ');
        Event e = new Event(kind, actor, goal, action, safe.substring(0, Math.min(safe.length(), 120)));
        if (history.size() == HISTORY_LIMIT) history.removeFirst();
        history.addLast(e);
        for (Consumer<Event> listener : listeners) {
            try { listener.accept(e); }
            catch (RuntimeException ignored) {
                // Observer bugs may not cancel/corrupt an authoritative action.
            }
        }
    }

    List<Event> recent() { return new ArrayList<>(history); }
    void clear() { history.clear(); }
}
