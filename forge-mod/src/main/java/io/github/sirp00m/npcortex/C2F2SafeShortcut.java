package io.github.sirp00m.npcortex;

/** H2: certified level, low-risk any-angle segment using a bounded
 * player-width corridor rather than an oversized enclosing rectangle.
 * C2WaypointBridge independently rechecks the actual world swept collision,
 * chunk safety and hazards before committing C1 movement.
 */
final class C2F2SafeShortcut {
    private C2F2SafeShortcut() {}
    static boolean clear(C2TraversabilityGraph graph,
            C2TraversabilityGraph.Node from,C2TraversabilityGraph.Node to) {
        return C2BPlusCorridor.clear(graph,from,to);
    }
}
