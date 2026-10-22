package io.github.sirp00m.npcortex.cplus;

import java.util.UUID;

/** Immutable proposed cross-boundary envelope; no World access or execution permission. */
public record CPlusActionContract(int protocolVersion, CPlusIdentity identity, String skillId,
                                  Target target, ExpectedOutcome expectedOutcome) {
    public static final int VERSION = 1;
    public CPlusActionContract {
        if (protocolVersion != VERSION) throw new IllegalArgumentException("unsupported protocol version");
        if (identity == null || target == null || expectedOutcome == null)
            throw new IllegalArgumentException("missing required action contract");
        CPlusIdentity.resource(skillId, "skillId");
    }
    public enum TargetKind { BLOCK, ENTITY, POSITION, NONE }
    public record Target(TargetKind kind, Integer x, Integer y, Integer z, UUID entityId) {
        public Target {
            if (kind == null) throw new IllegalArgumentException("target kind missing");
            boolean position = x != null && y != null && z != null;
            if (x != null || y != null || z != null) {
                if (!position || Math.abs((long)x) > 30000000L || Math.abs((long)y) > 30000000L || Math.abs((long)z) > 30000000L)
                    throw new IllegalArgumentException("invalid target coordinates");
            }
            if (kind == TargetKind.ENTITY) {
                CPlusIdentity.nonNil(entityId, "entityId");
                if (position) throw new IllegalArgumentException("entity targets cannot have block coordinates");
            } else if (kind == TargetKind.BLOCK || kind == TargetKind.POSITION) {
                if (!position || entityId != null) throw new IllegalArgumentException("position target requires coordinates only");
            } else if (entityId != null || position) throw new IllegalArgumentException("NONE target must have no locator");
        }
        public static Target block(int x, int y, int z) { return new Target(TargetKind.BLOCK, x, y, z, null); }
        public static Target position(int x, int y, int z) { return new Target(TargetKind.POSITION, x, y, z, null); }
        public static Target entity(UUID id) { return new Target(TargetKind.ENTITY, null, null, null, id); }
        public static Target none() { return new Target(TargetKind.NONE, null, null, null, null); }
    }
    /** An expected outcome describes a check; it is NOT proof that it occurred. */
    public record ExpectedOutcome(String kind, String key, String expectedValue) {
        public ExpectedOutcome {
            CPlusIdentity.resource(kind, "outcome kind");
            if (key == null || key.isBlank() || key.length() > 120 ||
                expectedValue == null || expectedValue.isBlank() || expectedValue.length() > 240)
                throw new IllegalArgumentException("invalid expected outcome");
        }
    }
}
