package io.github.sirp00m.npcortex;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** Follow-only grounded/airborne target adaptation, pure Java and read-only.
 * The visual player's aerial Y is never a C1 motion destination. Ground nodes
 * come solely from the currently verified, directed, bounded navigation graph.
 * No chunk load, teleport, clearance override or C1 ownership is performed.
 */
final class C2FollowTargetProjection {
    private static final int ENTER_AIR_TICKS=2;
    private static final int LEAVE_AIR_TICKS=4;
    private static final double SUPPORT_SEARCH_RADIUS=1.6;
    private boolean airborne;
    private int pendingTicks;
    private String lastSource="GROUNDED";
    private double lastY=Double.NaN;

    /** Only actual flight (Creative/abilities or elytra) activates projection.
     * Ordinary jumps must retain grounded 3D target handling. Both transitions
     * are debounced to avoid mode oscillation during landing/takeoff. */
    boolean observe(boolean flightActive,boolean onGround) {
        boolean wantsAir=flightActive && !onGround;
        if(wantsAir==airborne) {pendingTicks=0;return false;}
        if(++pendingTicks < (wantsAir?ENTER_AIR_TICKS:LEAVE_AIR_TICKS)) return false;
        airborne=wantsAir;
        pendingTicks=0;
        lastSource=airborne?"AIRBORNE_UNRESOLVED":"GROUNDED";
        return true;
    }
    boolean airborne() {return airborne;}
    String mode() {return airborne?"AIRBORNE":"GROUNDED";}
    String source() {return lastSource;}
    double lastY() {return lastY;}

    /** Stop/resume uses XZ while a player is really flying; grounded follow
     * keeps its original three-dimensional distance contract. */
    double followingDistance(double horizontalDistance,double playerY,double actorY) {
        if(!Double.isFinite(horizontalDistance) || horizontalDistance<0
                || !Double.isFinite(playerY) || !Double.isFinite(actorY))
            throw new IllegalArgumentException("Invalid follow distance");
        return airborne?horizontalDistance:Math.hypot(horizontalDistance,playerY-actorY);
    }
    boolean outsideVerticalReach(double playerY,double actorY,double reach) {
        if(!Double.isFinite(playerY)||!Double.isFinite(actorY)
                || !Double.isFinite(reach)||reach<0)
            throw new IllegalArgumentException("Invalid vertical reach");
        return !airborne && Math.abs(playerY-actorY)>reach;
    }

    /** For airborne players, resolve a plausible landing height near target XZ
     * ONLY if it is physically reachable from the approved start node in the
     * fresh directed graph. Otherwise leave Y neutral at the NPC's verified
     * starting level; the normal 3D path planner still proves every edge.
     * Avoid extrapolating a far-away column outside the bounded scan.
     */
    double effectiveY(C2TraversabilityGraph graph,C2TraversabilityGraph.Node start,
            double playerX,double playerY,double playerZ) {
        Objects.requireNonNull(graph);Objects.requireNonNull(start);
        if(!Double.isFinite(playerX)||!Double.isFinite(playerY)||!Double.isFinite(playerZ))
            throw new IllegalArgumentException("Non-finite follow target");
        if(!graph.contains(start)) throw new IllegalArgumentException("Missing approved start node");
        if(!airborne) {
            lastSource="GROUNDED";lastY=playerY;return playerY;
        }
        // No support inference beyond the scan: use a neutral starting Y.
        if(Math.hypot(playerX-(start.x()+.5),playerZ-(start.z()+.5))
                > C2NavGrid.MAX_RADIUS + .5) {
            lastSource="AIRBORNE_REMOTE_NEUTRAL";lastY=start.footY();return lastY;
        }
        ArrayDeque<C2TraversabilityGraph.Node> pending=new ArrayDeque<>();
        Set<C2TraversabilityGraph.Node> visited=new HashSet<>();
        pending.add(start);visited.add(start);
        C2TraversabilityGraph.Node best=null;
        double bestScore=Double.POSITIVE_INFINITY;
        // Bounded by graph size; edges have already passed world collision,
        // hazards, clearance and support verification at capture time.
        while(!pending.isEmpty()) {
            var node=pending.removeFirst();
            double dist=Math.hypot(playerX-(node.x()+.5),playerZ-(node.z()+.5));
            if(dist<=SUPPORT_SEARCH_RADIUS && node.footY()<=playerY+.5) {
                // Prefer a near-XZ, reachable surface immediately below the
                // airborne player, not a roof above them or a disconnected ledge.
                double score=dist*3.0 + Math.max(0,playerY-node.footY())*.06;
                if(score<bestScore) {bestScore=score;best=node;}
            }
            for(var edge:graph.neighbors(node)) if(visited.add(edge.to())) pending.addLast(edge.to());
        }
        if(best==null) {
            lastSource="AIRBORNE_NO_REACHABLE_SUPPORT";
            lastY=start.footY();
        } else {
            lastSource="AIRBORNE_REACHABLE_SUPPORT";
            lastY=best.footY();
        }
        return lastY;
    }
}
