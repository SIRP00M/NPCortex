package io.github.sirp00m.npcortex;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * P1-C transactional waypoint protocol, PURE JAVA. No world access or physics.
 * One C0 action owns the route. Each segment accepts exactly one C1 motion token.
 * The owner must re-check each edge against a fresh Forge graph before starting.
 * Never resumes automatically after process restart.
 */
final class C2WaypointExecution {
    static final int MAX_WAYPOINTS = 64;
    static final int FOLLOW_MAX_RUN = 3;
    static final int FOLLOW_CHAIN_LIMIT = 6;
    static final double WAYPOINT_TOLERANCE = 0.35;
    enum Update { ACTIVE, NEXT, COMPLETE, FAILED }

    private final List<C2TraversabilityGraph.Node> path;
    private final List<C2TraversabilityGraph.MoveKind> segmentKinds;
    private int targetIndex;
    private UUID ticket;
    private boolean centering;
    private boolean mixedChain;
    private int chainSegments=1;
    private boolean finished;
    private final boolean followSmooth;
    private final boolean allowDiagonal;

    C2WaypointExecution(List<C2TraversabilityGraph.Node> nodes) {
        this(nodes,false,false);
    }
    /** F2 typed local navigation: diagonals only after fresh graph proof. */
    static C2WaypointExecution navigation3D(List<C2TraversabilityGraph.Node> nodes) {
        return new C2WaypointExecution(nodes,false,true);
    }
    /** F2 only: coalesced WALK requires fresh graph proof for EVERY omitted edge. */
    static C2WaypointExecution followSmoothed(List<C2TraversabilityGraph.Node> nodes) {
        return new C2WaypointExecution(nodes,true,true);
    }
    private C2WaypointExecution(List<C2TraversabilityGraph.Node> nodes,boolean smooth,boolean diagonal) {
        followSmooth=smooth;
        allowDiagonal=diagonal;
        // Follow-only list can grow under a verified, same-token rolling extension.
        // Static C2 Navigation remains immutable; this list never persists to disk.
        path = smooth ? new java.util.ArrayList<>(Objects.requireNonNull(nodes))
                : List.copyOf(Objects.requireNonNull(nodes));
        if (path.isEmpty() || path.size() > MAX_WAYPOINTS)
            throw new IllegalArgumentException("P1-C route must contain 1..64 nodes");
        for (C2TraversabilityGraph.Node node : path) Objects.requireNonNull(node);
        java.util.ArrayList<C2TraversabilityGraph.MoveKind> kinds = new java.util.ArrayList<>();
        for (int i=1;i<path.size();i++) {
            var from=path.get(i-1); var to=path.get(i);
            long dx=Math.abs((long)from.x()-to.x()), dz=Math.abs((long)from.z()-to.z());
            boolean flat=Math.abs(to.footY()-from.footY())<=0.0001;
            boolean adjacent=Math.max(dx,dz)==1 && dx+dz>=1;
            boolean straightRun=flat && Math.max(dx,dz)>1 && Math.max(dx,dz)<=FOLLOW_MAX_RUN;
            if ((!adjacent && (!followSmooth || !straightRun))
                    || (dx==1 && dz==1 && (!flat || !allowDiagonal)))
                throw new IllegalArgumentException("F2 requires adjacent flat-diagonal or cardinal 3D waypoints");
            var kind=C2TraversabilityGraph.kind(to.footY()-from.footY());
            if (kind==null) throw new IllegalArgumentException("S3 unsupported vertical transition");
            kinds.add(kind);
        }
        segmentKinds=smooth ? kinds : List.copyOf(kinds);
        targetIndex = path.size() == 1 ? 0 : 1;
    }

    List<C2TraversabilityGraph.Node> path() { return followSmooth ? List.copyOf(path) : path; }
    C2TraversabilityGraph.Node currentTarget() { return path.get(targetIndex); }
    C2TraversabilityGraph.Node previousNode() { return path.get(Math.max(0,targetIndex-1)); }
    C2TraversabilityGraph.MoveKind currentKind() {
        return targetIndex==0 ? C2TraversabilityGraph.MoveKind.WALK : segmentKinds.get(targetIndex-1);
    }
    int currentIndex() { return targetIndex; }
    int totalSegments() { return Math.max(1,path.size()-1); }
    UUID expectedTicket() { return ticket; }
    boolean waiting() { return ticket != null; }
    boolean centering() { return centering; }
    C2TraversabilityGraph.Node receiptTarget() {
        return centering ? previousNode() : path.get(Math.min(path.size()-1,targetIndex+chainSegments-1));
    }
    boolean done() { return finished; }

    void started(UUID motionTicket) {
        if (finished || ticket != null || motionTicket == null)
            throw new IllegalStateException("P1-C duplicate/invalid motion ownership");
        ticket=motionTicket;
    }

    /** One C1 ticket may consume several independently verified flat WALK segments.
     *  Legacy navigation always uses a single segment. Follow owns this extension.
     */
    void startedWalkChain(UUID token,int count) {
        if (!followSmooth || count<1 || count> FOLLOW_CHAIN_LIMIT
                || targetIndex+count>path.size())
            throw new IllegalArgumentException("Invalid F2 follow WALK chain");
        for (int i=targetIndex;i<targetIndex+count;i++)
            if (segmentKinds.get(i-1)!=C2TraversabilityGraph.MoveKind.WALK)
                throw new IllegalArgumentException("F2 WALK chain contains vertical traversal");
        started(token);
        chainSegments=count;
    }

    /** C-3D: one mixed physical C1 session, with each stage revalidated
     * and its landing verified before the next stage can be armed. */
    void startedMixedChain(UUID token,int count) {
        if(!followSmooth || done() || waiting() || count<2 || count>6
                || targetIndex+count>path.size())
            throw new IllegalArgumentException("Invalid mixed 3D follow chain");
        boolean vertical=false;
        for(int i=targetIndex;i<targetIndex+count;i++)
            vertical|=segmentKinds.get(i-1)!=C2TraversabilityGraph.MoveKind.WALK;
        if(!vertical) throw new IllegalArgumentException("Mixed chain requires 3D edge");
        started(token);
        chainSegments=count;
        mixedChain=true;
    }

    /** Verify every directed graph edge in a candidate mixed lease. */
    boolean mixedChainAllowed(C2TraversabilityGraph graph,int count) {
        if(!followSmooth || done() || waiting() || graph==null || count<2
                || count>6 || targetIndex+count>path.size()) return false;
        boolean vertical=false;
        for(int i=targetIndex;i<targetIndex+count;i++) {
            var kind=segmentKinds.get(i-1);
            vertical|=kind!=C2TraversabilityGraph.MoveKind.WALK;
            if(!edgeAllowedBetween(graph,path.get(i-1),path.get(i),kind)) return false;
        }
        return vertical;
    }

    /** Extend a live F2 WALK receipt without changing its C1 token. Each
     * extension is verified against a fresh graph before this method runs. */
    void extendedWalkChain(UUID expected,int extra) {
        if(!followSmooth || finished || ticket==null || !ticket.equals(expected)
                || centering || extra<1 || extra>FOLLOW_CHAIN_LIMIT
                || targetIndex+chainSegments+extra>path.size())
            throw new IllegalArgumentException("Invalid F2 owned WALK extension");
        for(int i=targetIndex+chainSegments;i<targetIndex+chainSegments+extra;i++)
            if(segmentKinds.get(i-1)!=C2TraversabilityGraph.MoveKind.WALK)
                throw new IllegalArgumentException("F2 extension crossed vertical primitive");
        chainSegments+=extra;
    }
    /** C-S2: extra nodes are NOT a new route owner. The caller must
     * prove this continuation against a freshly captured graph and swept
     * player body, then extend the matching C1 token before committing it.
     * Only a WALK continuation at the existing approved endpoint is legal. */
    boolean canAppendWalk(java.util.List<C2TraversabilityGraph.Node> nodes) {
        if (!followSmooth || !waiting() || finished || centering || nodes==null
                || nodes.isEmpty() || nodes.size()>FOLLOW_CHAIN_LIMIT
                || targetIndex+chainSegments!=path.size()
                || path.size()+nodes.size()>MAX_WAYPOINTS) return false;
        var prev=path.get(path.size()-1);
        for (var next:nodes) {
            if (next==null || Math.abs(next.footY()-prev.footY())>.0001
                    || C2TraversabilityGraph.kind(next.footY()-prev.footY())
                    !=C2TraversabilityGraph.MoveKind.WALK) return false;
            long dx=Math.abs((long)next.x()-prev.x());
            long dz=Math.abs((long)next.z()-prev.z());
            if (Math.max(dx,dz)<1 || Math.max(dx,dz)>FOLLOW_MAX_RUN) return false;
            prev=next;
        }
        return true;
    }
    void appendWalkAfterC1Extension(UUID expected,java.util.List<C2TraversabilityGraph.Node> nodes) {
        if(!acceptsTicket(expected) || !canAppendWalk(nodes))
            throw new IllegalStateException("C-S2 refused unverified/stale follow continuation");
        for (var node:nodes) {
            path.add(node);
            segmentKinds.add(C2TraversabilityGraph.MoveKind.WALK);
        }
        chainSegments+=nodes.size();
    }
    int lastCommittedIndex() {return targetIndex+chainSegments-1;}
    int segmentsCommitted() {return chainSegments;}
    /** Fresh-graph proof for the currently WAITING F2 ticket's uncommitted
     * edges; never used by C0, operator or a terminal receipt. */
    boolean walkExtensionAllowed(C2TraversabilityGraph graph,int count) {
        if(!followSmooth || !waiting() || done() || centering || graph==null
                || count<1 || count>FOLLOW_CHAIN_LIMIT
                || targetIndex+chainSegments+count>path.size()) return false;
        for(int i=targetIndex+chainSegments;i<targetIndex+chainSegments+count;i++) {
            if(segmentKinds.get(i-1)!=C2TraversabilityGraph.MoveKind.WALK
                    || !edgeAllowedBetween(graph,path.get(i-1),path.get(i),
                        C2TraversabilityGraph.MoveKind.WALK)) return false;
        }
        return true;
    }

    /** A short WALK-only position adjustment within the current verified node.
     * It never consumes a navigation edge; a second, fresh C1 ticket will own
     * the subsequent vertical primitive after the first terminal receipt.
     */
    void startedCentering(UUID token) {
        started(token);
        if (targetIndex==0 || currentKind()==C2TraversabilityGraph.MoveKind.WALK)
            throw new IllegalStateException("Centering reserved for typed 3D traversal");
        centering=true;
    }

    /** Correlation guard: recoverable failure is legal ONLY for this route's ticket. */
    boolean acceptsTicket(UUID actual) { return !finished && ticket!=null && ticket.equals(actual); }

    static boolean recoverableTerminal(String phase,boolean reached) {
        return "BLOCKED".equals(phase) || "TIMED_OUT".equals(phase)
                || "TRAVERSAL_BLOCKED".equals(phase) || "TRAVERSAL_TIMEOUT".equals(phase)
                || ("COMPLETE".equals(phase) && !reached);
    }

    Update observe(UUID actualTicket, boolean active, String phase, boolean reached) {
        if (ticket == null || finished || !ticket.equals(actualTicket)) return Update.FAILED;
        if (active) return Update.ACTIVE;
        ticket = null;
        if (centering) {
            centering=false;
            return "COMPLETE".equals(phase) && reached ? Update.NEXT : Update.FAILED;
        }
        String successful = mixedChain ? "MIXED_COMPLETE"
                : currentKind()==C2TraversabilityGraph.MoveKind.WALK
                ? "COMPLETE" : "TRAVERSAL_COMPLETE_"+currentKind().name();
        if (!successful.equals(phase) || !reached) { chainSegments=1; return Update.FAILED; }
        targetIndex+=chainSegments-1;
        chainSegments=1;
        mixedChain=false;
        if (targetIndex == path.size()-1) { finished=true; return Update.COMPLETE; }
        targetIndex++;
        return Update.NEXT;
    }

    /** F2 follow-only proof: ALL source→destination links in a chained
     * WALK ticket must exist in one fresh directed graph snapshot. */
    boolean walkChainAllowed(C2TraversabilityGraph graph,int count) {
        if(!followSmooth || waiting() || done() || graph==null || count<1
                || count>FOLLOW_CHAIN_LIMIT || targetIndex+count>path.size()) return false;
        for(int i=targetIndex;i<targetIndex+count;i++) {
            if(segmentKinds.get(i-1)!=C2TraversabilityGraph.MoveKind.WALK
                    || !edgeAllowedBetween(graph,path.get(i-1),path.get(i),
                    C2TraversabilityGraph.MoveKind.WALK)) return false;
        }
        return true;
    }

    /** Fresh graph must contain this precise *directed, typed* edge. */
    boolean edgeAllowed(C2TraversabilityGraph fresh) {
        if (ticket != null || finished || fresh==null) return false;
        var from = previousNode(); var to=currentTarget();
        if (targetIndex==0) return fresh.contains(from); // same-node centering
        return edgeAllowedBetween(fresh,from,to,currentKind());
    }

    private boolean edgeAllowedBetween(C2TraversabilityGraph fresh,
            C2TraversabilityGraph.Node from,C2TraversabilityGraph.Node to,
            C2TraversabilityGraph.MoveKind kind) {
        if(!fresh.contains(from) || !fresh.contains(to)) return false;
        long dx=(long)to.x()-from.x(),dz=(long)to.z()-from.z();
        long count=Math.max(Math.abs(dx),Math.abs(dz));
        if(count==1) return fresh.neighbors(from).stream().anyMatch(edge -> edge.to().equals(to)
                && edge.kind()==kind);
        // F2 coalesced WALK: every omitted directed edge must still exist in
        // THIS fresh graph. No path shortcut and no stale collision assumption.
        if(kind!=C2TraversabilityGraph.MoveKind.WALK || count>FOLLOW_MAX_RUN
                || Math.abs(to.footY()-from.footY())>0.0001) return false;
        // Non-45-degree shortcuts are Follow-only and must prove every
        // supporting cell and cheap directed corridor on the FRESH graph.
        if(dx!=0 && dz!=0 && Math.abs(dx)!=Math.abs(dz))
            return C2F2SafeShortcut.clear(fresh,from,to);
        int sx=Long.signum(dx), sz=Long.signum(dz);
        if(dx!=0 && dz!=0 && Math.abs(dx)!=Math.abs(dz)) return false;
        var here=from;
        for(int i=0;i<count;i++) {
            final int x=here.x()+sx,z=here.z()+sz;
            var next=fresh.nodes().stream().filter(n->n.x()==x && n.z()==z
                    && Math.abs(n.footY()-from.footY())<=0.0001).findFirst().orElse(null);
            if(next==null) return false;
            final var candidate=next;
            if(fresh.neighbors(here).stream().noneMatch(e->e.to().equals(candidate)
                    && e.kind()==C2TraversabilityGraph.MoveKind.WALK)) return false;
            here=next;
        }
        return here.equals(to);
    }
}
