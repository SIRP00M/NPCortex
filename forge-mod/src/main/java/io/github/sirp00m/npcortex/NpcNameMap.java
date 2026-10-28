package io.github.sirp00m.npcortex;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Client-side lookup NPC GameProfile UUID -> logical name. Only UUIDs the server announced are ever present,
 * so a real player (or any other entity) is never renamed. Confined to the client main thread.
 */
final class NpcNameMap {
    private final Map<UUID, String> names = new HashMap<>();

    /** @return the UUIDs whose displayed name may have changed (the caller refreshes those entities). */
    Set<UUID> apply(NpcNameCodec.Sync sync) {
        Map<UUID, String> before = new HashMap<>(names);
        if (sync.replace()) names.clear();
        names.putAll(sync.names());
        Set<UUID> changed = new HashSet<>();
        for (Map.Entry<UUID, String> entry : sync.names().entrySet()) {
            if (!entry.getValue().equals(before.get(entry.getKey()))) changed.add(entry.getKey());
        }
        if (sync.replace()) {
            for (UUID gone : before.keySet()) if (!sync.names().containsKey(gone)) changed.add(gone);
        }
        return changed;
    }

    /** @return null for anything that is not a known NPC */
    String nameFor(UUID profile) {
        return profile == null ? null : names.get(profile);
    }

    /** World/connection change: forget everything. @return the UUIDs that had a mapping. */
    Set<UUID> clear() {
        Set<UUID> old = new HashSet<>(names.keySet());
        names.clear();
        return old;
    }

    int size() { return names.size(); }
}
