package io.github.sirp00m.npcortex.cplus;

import java.util.List;
import java.util.Map;

/** Proposed user/public command surface. Never registered with Brigadier in C-F0. */
public final class CPlusCommands {
    private CPlusCommands() {}
    public enum Route { C1_PLAYER, C2_NAVIGATION, CPLUS_ACTION, STATUS_AGGREGATOR, DEBUG }
    public record Definition(String subcommand, Route route, int minimumPermission, boolean visibleToPlayers) {
        public Definition {
            if (subcommand == null || subcommand.isBlank() || subcommand.length()>64 || route == null
                    || minimumPermission<0 || minimumPermission>4)
                throw new IllegalArgumentException("invalid command contract");
        }
    }
    private static final Map<String, Definition> PUBLIC = Map.of(
            "spawn", new Definition("spawn",Route.C1_PLAYER,2,true),
            "follow", new Definition("follow",Route.C2_NAVIGATION,2,true),
            "stop", new Definition("stop",Route.CPLUS_ACTION,2,true),
            "status", new Definition("status",Route.STATUS_AGGREGATOR,2,true));
    private static final Map<String,String> LEGACY = Map.of(
            "c1 spawn","spawn", "c2 follow","follow", "c2 unfollow","stop",
            "c2 followstatus","status");
    public static List<Definition> proposedPublicCommands() { return List.copyOf(PUBLIC.values()); }
    public static Definition proposed(String root) { return PUBLIC.get(root); }
    public static String legacyAliasDestination(String legacy) { return LEGACY.get(legacy); }
    public static Definition debug(String module) {
        if (!List.of("c1","c2","action").contains(module))
            throw new IllegalArgumentException("unsupported diagnostics namespace");
        return new Definition("debug " + module, Route.DEBUG, 2, false);
    }
}
