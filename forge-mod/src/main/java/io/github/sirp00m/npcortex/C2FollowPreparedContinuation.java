package io.github.sirp00m.npcortex;

import java.util.List;
import java.util.UUID;

/** S2: precomputed PATH HINT only. Holding a hint never owns motion or proves
 * a fresh terrain snapshot. The C1 bridge MUST revalidate geometry, collision,
 * hazard, support, landing and token ownership at consumption time. */
final class C2FollowPreparedContinuation {
    static final long MAX_AGE_TICKS=100;
    private UUID token;
    private C2TraversabilityGraph.Node anchor;
    private List<C2TraversabilityGraph.Node> nodes;
    private long preparedAt;
    private double targetX,targetZ;
    private String decision;

    boolean offer(UUID token,C2TraversabilityGraph.Node anchor,
                  List<C2TraversabilityGraph.Node> nodes,long tick,
                  double targetX,double targetZ,String decision) {
        if(token==null || anchor==null || nodes==null || nodes.size()<2
                || nodes.size()>C2WaypointExecution.MAX_WAYPOINTS
                || !anchor.equals(nodes.get(0)) || !Double.isFinite(targetX)
                || !Double.isFinite(targetZ)) return false;
        for(var node:nodes) if(node==null) return false;
        this.token=token;this.anchor=anchor;this.nodes=List.copyOf(nodes);
        this.preparedAt=tick;this.targetX=targetX;this.targetZ=targetZ;
        this.decision=decision==null?"UNKNOWN":decision;
        return true;
    }
    boolean pending() {return nodes!=null;}
    String decision() {return pending()?decision:"NONE";}
    /** The receipt for this exact token MUST first be physically VERIFIED;
     * caller does not invoke this for blocked/foreign/cancelled terminals. */
    List<C2TraversabilityGraph.Node> consume(UUID verifiedToken,
                  C2TraversabilityGraph.Node physicalEndpoint,
                  long now,double playerX,double playerZ) {
        var result=nodes;
        boolean valid=result!=null && token!=null && token.equals(verifiedToken)
                && anchor.equals(physicalEndpoint)
                && now>=preparedAt && now-preparedAt<=MAX_AGE_TICKS
                && Math.hypot(playerX-targetX,playerZ-targetZ)
                      <C2FollowPolicy.TARGET_MOVE_THRESHOLD;
        clear();
        return valid?result:null;
    }
    void clear() {token=null;anchor=null;nodes=null;decision=null;}
}
