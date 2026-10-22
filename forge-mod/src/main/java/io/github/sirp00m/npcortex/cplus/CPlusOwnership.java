package io.github.sirp00m.npcortex.cplus;

/** Responsibility/authority boundary; no runtime execution access is granted by this class. */
public final class CPlusOwnership {
    private CPlusOwnership() {}
    public enum Owner { C0_BRAIN, C1_PLAYER, C2_NAVIGATION, CPLUS_ACTION, SERVER_WORLD }
    public enum Operation { DECIDE, PLAYER_PHYSICS, INVENTORY_MUTATION, PATH_REQUEST, ACTION_COORDINATION,
                            WORLD_MUTATION, WORLD_VERIFY }
    public static Owner authoritativeOwner(Operation op) {
        if (op == null) throw new IllegalArgumentException("operation required");
        return switch (op) {
            case DECIDE -> Owner.C0_BRAIN;
            case PLAYER_PHYSICS, INVENTORY_MUTATION -> Owner.C1_PLAYER;
            case PATH_REQUEST -> Owner.C2_NAVIGATION;
            case ACTION_COORDINATION -> Owner.CPLUS_ACTION;
            case WORLD_MUTATION, WORLD_VERIFY -> Owner.SERVER_WORLD;
        };
    }
    public static boolean directMutationAllowed(Owner caller, Operation operation) {
        return caller != null && operation != null && authoritativeOwner(operation) == caller;
    }
}
