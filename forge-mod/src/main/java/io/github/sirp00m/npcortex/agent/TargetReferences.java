package io.github.sirp00m.npcortex.agent;

/** Shared typed reference contract, including material and exploration refs. */
public final class TargetReferences {
    private TargetReferences() {}
    public static boolean valid(SkillRegistry.Target kind,String ref) {
        if(kind==null || ref==null) return false;
        return switch(kind) {
            case NONE -> ref.isEmpty();
            case BLOCK -> ref.matches("b(?:[0-9]|1[0-5])");
            case DROP -> ref.matches("d(?:[0-9]|1[0-5])");
            case ITEM -> ref.matches("m[0-5]");
            case WAYPOINT -> ref.matches("e[0-7]");
        };
    }
}
