package io.github.sirp00m.npcortex.cplus;

import java.util.UUID;
import java.util.regex.Pattern;

/** C-F0 contract only. Does not register commands, schedule or mutate game state. */
public record CPlusIdentity(UUID worldId, String dimensionId, UUID actorId,
                            UUID goalId, UUID taskId, UUID actionId, UUID attemptId) {
    private static final Pattern RESOURCE = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]{1,120}");
    public CPlusIdentity {
        nonNil(worldId, "worldId");
        resource(dimensionId, "dimensionId");
        nonNil(actorId, "actorId");
        nonNil(goalId, "goalId");
        nonNil(taskId, "taskId");
        nonNil(actionId, "actionId");
        nonNil(attemptId, "attemptId");
        if (actionId.equals(attemptId)) throw new IllegalArgumentException("attemptId must differ from actionId");
    }
    public static UUID nonNil(UUID value, String field) {
        if (value == null || value.equals(new UUID(0, 0)))
            throw new IllegalArgumentException(field + " requires a non-nil UUID");
        return value;
    }
    public static String resource(String value, String field) {
        if (value == null || !RESOURCE.matcher(value).matches())
            throw new IllegalArgumentException(field + " requires a namespaced resource ID");
        return value;
    }
}
