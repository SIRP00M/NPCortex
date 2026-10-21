package io.github.sirp00m.npcortex;

import java.util.ArrayList;
import java.util.List;

/** F2 purely deterministic local steering/route reduction. No physics, no world access. */
final class C2F2Locomotion {
    private C2F2Locomotion() { }
    static final int MAX_RUN = C2WaypointExecution.FOLLOW_MAX_RUN; // every skipped edge is revalidated
    static final float MAX_TURN_DEGREES = 18f;
    static final float TURN_EPSILON = 1.0f;

    static float angularDelta(float from,float to) {
        if(!Float.isFinite(from)||!Float.isFinite(to)) throw new IllegalArgumentException("Invalid yaw");
        return (float)Math.toDegrees(Math.atan2(Math.sin(Math.toRadians(to-from)),
                Math.cos(Math.toRadians(to-from))));
    }
    static float wrapYaw(float yaw) {
        if(!Float.isFinite(yaw)) throw new IllegalArgumentException("Non-finite yaw");
        return (float)Math.toDegrees(Math.atan2(Math.sin(Math.toRadians(yaw)),
                Math.cos(Math.toRadians(yaw))));
    }
    static float approach(float from,float to) {
        float difference=angularDelta(from,to);
        float step=Math.max(-MAX_TURN_DEGREES,Math.min(MAX_TURN_DEGREES,difference));
        float yaw=from+step;
        return (float)Math.toDegrees(Math.atan2(Math.sin(Math.toRadians(yaw)),
                Math.cos(Math.toRadians(yaw))));
    }
    static boolean aligned(float from,float to) {
        return Math.abs(angularDelta(from,to))<=TURN_EPSILON;
    }
    /** B: greedily remove zigzag on verified level, low-risk open ground.
     * Every chosen shortcut is proved again against a FRESH world graph and
     * actual swept player AABB before any C1 movement ticket is committed.
     * Fall back to the exact original neighboring node if proof is absent.
     */
    static List<C2TraversabilityGraph.Node> smooth(C2TraversabilityGraph graph,
            List<C2TraversabilityGraph.Node> path) {
        if(graph==null || path==null || path.isEmpty())
            throw new IllegalArgumentException("Graph and nonempty path required");
        List<C2TraversabilityGraph.Node> reduced=new ArrayList<>();
        reduced.add(path.get(0));
        int i=0;
        while(i<path.size()-1) {
            int end=i+1;
            var from=path.get(i);
            // A bounded local proof: a shortcut crosses at most 3 grid columns
            // per axis and never compresses a vertical move into flat WALK.
            for(int j=i+2;j<path.size();j++) {
                var target=path.get(j);
                if(j-i>6 || Math.max(Math.abs((long)target.x()-from.x()),
                        Math.abs((long)target.z()-from.z()))>MAX_RUN) break;
                boolean flat=true;
                for(int k=i+1;k<=j;k++) {
                    var a=path.get(k-1);var b=path.get(k);
                    if(Math.abs(a.footY()-b.footY())>0.0001 ||
                            C2TraversabilityGraph.kind(b.footY()-a.footY())
                                    !=C2TraversabilityGraph.MoveKind.WALK) {flat=false;break;}
                }
                if(flat && C2F2SafeShortcut.clear(graph,from,target)) end=j;
            }
            reduced.add(path.get(end));i=end;
        }
        return List.copyOf(reduced);
    }
    /** Combine collinear, SAME-HEIGHT walk steps only. The waypoint owner
     * must re-check EVERY omitted directed graph edge before each ticket. */
    static List<C2TraversabilityGraph.Node> smooth(List<C2TraversabilityGraph.Node> path) {
        if(path==null || path.isEmpty()) throw new IllegalArgumentException("Empty route");
        if(path.size()==1) return List.copyOf(path);
        List<C2TraversabilityGraph.Node> reduced=new ArrayList<>();
        reduced.add(path.get(0));
        int i=0;
        while(i<path.size()-1) {
            int j=i+1;
            var first=path.get(i);
            var next=path.get(j);
            long dx=(long)next.x()-first.x(),dz=(long)next.z()-first.z();
            boolean walk=Math.abs(next.footY()-first.footY())<0.0001
                    && Math.max(Math.abs(dx),Math.abs(dz))==1 && (dx!=0||dz!=0);
            if(walk) while(j+1<path.size() && j-i<MAX_RUN) {
                var a=path.get(j);var b=path.get(j+1);
                if(b.x()-a.x()!=dx || b.z()-a.z()!=dz
                        || Math.abs(a.footY()-b.footY())>0.0001) break;
                j++;
            }
            reduced.add(path.get(j));i=j;
        }
        return List.copyOf(reduced);
    }
}
