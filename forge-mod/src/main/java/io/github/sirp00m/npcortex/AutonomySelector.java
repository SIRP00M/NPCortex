package io.github.sirp00m.npcortex;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/** Pure selector policy shared by server commands; ambiguous character IDs never pick at random. */
public final class AutonomySelector {
    private AutonomySelector() {}

    public record Match<T>(T value, boolean ambiguous) {}

    public static <T> Match<T> resolve(List<T> owned, String selector,
                                      Function<T, String> characterId, Function<T, UUID> entityId) {
        if (selector == null || selector.isBlank() || selector.length() > 64) return new Match<>(null, false);
        String query = selector.trim();
        // Full UUID always identifies one specific NPC, including duplicates of a character.
        try {
            UUID id = UUID.fromString(query);
            return new Match<>(owned.stream().filter(n -> id.equals(entityId.apply(n)))
                    .findFirst().orElse(null), false);
        } catch (IllegalArgumentException ignored) {
            // Otherwise match Character Pack ID, never fuzzy-match display names.
        }
        T selected = null;
        for (T npc : owned) {
            if (!query.equalsIgnoreCase(characterId.apply(npc))) continue;
            if (selected != null) return new Match<>(null, true);
            selected = npc;
        }
        return new Match<>(selected, false);
    }
}
