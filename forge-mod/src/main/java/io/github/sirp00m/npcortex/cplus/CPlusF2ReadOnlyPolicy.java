package io.github.sirp00m.npcortex.cplus;

/** C-F2 contract-only authorization evidence. Never grants an executor capability. */
public final class CPlusF2ReadOnlyPolicy {
    private CPlusF2ReadOnlyPolicy() {}
    public enum Status { OBSERVED, UNKNOWN_CHUNK, OUT_OF_HEIGHT, OUT_OF_BORDER }
    public enum Verdict { ALLOW, DENY, UNKNOWN }
    public record Decision(Verdict verdict, String reason) {}

    /** 'observe' is a read permission, NOT permission to act on the observed block. */
    public static Decision decide(Status status, String operation) {
        if (status == null || operation == null) throw new IllegalArgumentException("missing status/operation");
        if (!operation.equals("observe") && !operation.equals("break_block")
                && !operation.equals("place_block") && !operation.equals("use_block"))
            return new Decision(Verdict.DENY, "unsupported_operation");
        if (status == Status.OUT_OF_BORDER || status == Status.OUT_OF_HEIGHT)
            return new Decision(Verdict.DENY, "outside_world_bounds");
        if (status == Status.UNKNOWN_CHUNK)
            return new Decision(Verdict.UNKNOWN, "chunk_not_loaded");
        if (operation.equals("observe"))
            return new Decision(Verdict.ALLOW, "server_read_only_observation");
        // Vanilla success and Navigation traversability are not proof of claim-mod rights.
        return new Decision(Verdict.UNKNOWN, "mutation_claim_adapter_unverified");
    }

    /** An old permission result can NEVER authorize a mutation, even if ALLOW. */
    public static boolean usableEvidence(String world, String dimension, String actor, String session,
                                         String expectedWorld, String expectedDimension, String expectedActor,
                                         String expectedSession, long captureTick, long currentTick, long maxAge) {
        return world != null && world.equals(expectedWorld)
                && dimension != null && dimension.equals(expectedDimension)
                && actor != null && actor.equals(expectedActor)
                && session != null && session.equals(expectedSession)
                && captureTick >= 0 && currentTick >= captureTick && maxAge >= 0
                && currentTick - captureTick <= maxAge;
    }
}
