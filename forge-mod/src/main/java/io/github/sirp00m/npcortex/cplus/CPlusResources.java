package io.github.sirp00m.npcortex.cplus;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.regex.Pattern;
import java.util.List;
import java.util.UUID;

/** Resource keys and deterministic acquire-order contract; NOT an active lock manager. */
public final class CPlusResources {
    private CPlusResources() {}
    public enum Kind { ACTOR_MOTION, ACTOR_INVENTORY, BLOCK, CONTAINER, ENTITY }
    private static final Pattern BLOCK_LOCATOR = Pattern.compile("-?(?:0|[1-9][0-9]*),-?(?:0|[1-9][0-9]*),-?(?:0|[1-9][0-9]*)");
    public record Key(UUID worldId, String dimensionId, Kind kind, String locator) implements Comparable<Key> {
        public Key {
            CPlusIdentity.nonNil(worldId, "resource worldId");
            CPlusIdentity.resource(dimensionId, "resource dimensionId");
            if (kind == null || locator == null || locator.isBlank() || locator.length() > 160)
                throw new IllegalArgumentException("invalid resource key");
            if (kind == Kind.ACTOR_MOTION || kind == Kind.ACTOR_INVENTORY || kind == Kind.ENTITY) {
                UUID parsed;
                try { parsed = UUID.fromString(locator); }
                catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("actor/entity key requires UUID", invalid); }
                if (!parsed.toString().equals(locator)) throw new IllegalArgumentException("noncanonical UUID key");
                CPlusIdentity.nonNil(parsed, "resource target UUID");
            } else {
                if (!BLOCK_LOCATOR.matcher(locator).matches()) throw new IllegalArgumentException("invalid block locator");
                String[] coords = locator.split(",");
                try {
                    for (String coord : coords) if (Math.abs(Long.parseLong(coord)) > 30000000L)
                        throw new IllegalArgumentException("out of bounds resource coordinate");
                } catch (NumberFormatException invalid) {
                    throw new IllegalArgumentException("invalid resource coordinate", invalid);
                }
            }
        }
        public static Key actor(UUID world, String dim, Kind kind, UUID actor) {
            if (kind != Kind.ACTOR_MOTION && kind != Kind.ACTOR_INVENTORY)
                throw new IllegalArgumentException("actor resource kind required");
            return new Key(world, dim, kind, CPlusIdentity.nonNil(actor, "actor").toString());
        }
        public static Key block(UUID world, String dim, Kind kind, int x, int y, int z) {
            if (kind != Kind.BLOCK && kind != Kind.CONTAINER) throw new IllegalArgumentException("block resource kind required");
            if (Math.abs((long)x) > 30000000 || Math.abs((long)y) > 30000000 || Math.abs((long)z) > 30000000)
                throw new IllegalArgumentException("resource position outside bounded world contract");
            return new Key(world, dim, kind, x + "," + y + "," + z);
        }
        @Override public int compareTo(Key other) {
            int c=worldId.toString().compareTo(other.worldId.toString());
            if (c!=0) return c;
            c=dimensionId.compareTo(other.dimensionId);
            if (c!=0) return c;
            c=Integer.compare(kind.ordinal(), other.kind.ordinal());
            return c!=0 ? c : locator.compareTo(other.locator);
        }
    }
    public static List<Key> acquireOrder(Collection<Key> resources) {
        if (resources == null || resources.size() > 16)
            throw new IllegalArgumentException("invalid bounded resource request");
        if (resources.stream().anyMatch(item -> item == null))
            throw new IllegalArgumentException("null resource key");
        if (new HashSet<>(resources).size() != resources.size())
            throw new IllegalArgumentException("duplicate resource lock request");
        ArrayList<Key> sorted = new ArrayList<>(resources);
        Collections.sort(sorted);
        return List.copyOf(sorted);
    }
    /** FIFO policy for a single NPC: accepted sequence decides order, not owner priority. */
    public record QueueTicket(UUID actorId, UUID taskId, long acceptedSequence) implements Comparable<QueueTicket> {
        public QueueTicket {
            CPlusIdentity.nonNil(actorId, "queue actorId");
            CPlusIdentity.nonNil(taskId, "queue taskId");
            if (acceptedSequence < 0) throw new IllegalArgumentException("invalid sequence");
        }
        @Override public int compareTo(QueueTicket next) {
            if (!actorId.equals(next.actorId)) throw new IllegalArgumentException("FIFO tickets from different actors");
            return Long.compare(acceptedSequence, next.acceptedSequence);
        }
    }
}
