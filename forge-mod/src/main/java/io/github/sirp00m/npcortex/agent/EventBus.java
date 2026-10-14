package io.github.sirp00m.npcortex.agent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** Server-thread, bounded diagnostic bus. Subscriber failures cannot abort a world edit. */
public final class EventBus {
    public record Event(UUID npc, String goal, String type, String detail, long tick) {}
    private final ArrayDeque<Event> history = new ArrayDeque<>();
    private final List<Consumer<Event>> listeners = new ArrayList<>();
    private int listenerErrors;
    public AutoCloseable subscribe(Consumer<Event> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }
    public void publish(Event event) {
        if (history.size() >= 128) history.removeFirst();
        history.addLast(event);
        for (var listener : List.copyOf(listeners)) {
            try { listener.accept(event); }
            catch (RuntimeException ignored) { listenerErrors++; }
        }
    }
    public List<Event> history() { return List.copyOf(history); }
    public int listenerErrors() { return listenerErrors; }
    public void clear() { history.clear(); listenerErrors = 0; }
}
