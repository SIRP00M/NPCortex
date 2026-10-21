package io.github.sirp00m.npcortex;

import java.util.List;
import java.util.ArrayList;

/**
 * F2 Fix #1: bounded, dependency-free WALK steering state. The caller owns
 * one C1 Motion Ticket for the ENTIRE verified horizontal chain. No teleport,
 * new physics owner, async replanning or world access occurs in this class.
 */
final class C1F2ContinuousWalk {
    record Point(double x, double y, double z) {
        Point {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
                throw new IllegalArgumentException("Non-finite walk waypoint");
        }
    }
    record Intent(float yaw, double forward, double targetX, double targetZ) { }
    private static final double TOLERANCE = 0.28;
    static final int MAX_TARGETS = 6;
    static final double MAX_TOTAL_LENGTH = 7.75;
    // C-S1: bounded persistent C1 lease; not an unlimited physics command.
    // Renewing a certified corridor is handled by C2, never in C1 itself.
    private static final int MAX_TICKS = 600;
    static final int MAX_SESSION_TARGETS = 64;
    static final double MAX_SESSION_LENGTH = 96.0;
    // An early blend toward the next leg creates an unproven corner-cut chord.
    // Aim at the verified current waypoint instead; C1 still sweeps each tick.
    private static final double ARRIVAL_SWEEP = .28;
    private static final int STALL_TICKS = 22;
    private final List<Point> points;
    private final Point startingPoint;
    private double committedLength;
    private final C1F2MotionTelemetry telemetry = new C1F2MotionTelemetry();
    private final C1VanillaMoveControlAdapter vanillaDrive = new C1VanillaMoveControlAdapter();
    private double lastX=Double.NaN,lastZ=Double.NaN;
    private int index;
    private int ticks;
    private int stalled;
    private double lastDistance = Double.NaN;
    private String terminal = "";

    C1F2ContinuousWalk(double startX, double startY, double startZ, List<Point> targets) {
        if (!Double.isFinite(startX) || !Double.isFinite(startY) || !Double.isFinite(startZ)
                || targets == null || targets.isEmpty() || targets.size() > MAX_TARGETS)
            throw new IllegalArgumentException("Invalid bounded continuous walk targets");
        points = new ArrayList<>(targets);
        startingPoint = new Point(startX,startY,startZ);
        double sum = 0, x=startX, z=startZ;
        for (Point p : points) {
            if (Math.abs(p.y()-startY)>0.02) throw new IllegalArgumentException("Vertical walk forbidden");
            double distance=Math.hypot(p.x()-x,p.z()-z);
            if (distance<0.03 || distance>4.5) throw new IllegalArgumentException("Invalid walk edge length");
            sum+=distance; x=p.x();z=p.z();
        }
        if (sum>MAX_TOTAL_LENGTH) throw new IllegalArgumentException("Unbounded WALK chain");
        committedLength=sum;
    }

    boolean moving() { return terminal.isEmpty(); }
    String terminal() { return terminal; }
    int consumed() { return index; }
    int queued() { return points.size(); }
    int remaining() { return points.size()-index; }
    C1F2MotionTelemetry.Summary metrics() { return telemetry.summary(); }

    /** Prevalidated points can extend the SAME C1 token. Only C2 owns this
     * mutation, and every new edge must first pass a fresh directed graph and
     * swept player-sized collision check. Bounded even during rolling follow. */
    boolean extend(List<Point> additional) {
        // Leave an 80-tick drain window so the final approved leg can finish
        // cleanly before the bounded C1 lease expires. At the lease boundary,
        // normal verified terminal handoff is safer than a synthetic timeout.
        if (!moving() || ticks>=MAX_TICKS-80 || additional==null || additional.isEmpty()
                || additional.size()>MAX_TARGETS
                || points.size()+additional.size()>MAX_SESSION_TARGETS || remaining()>2) return false;
        Point last=points.get(points.size()-1);
        double extra=0,x=last.x(),z=last.z();
        for(Point point:additional) {
            if(point==null || Math.abs(point.y()-last.y())>.02) return false;
            double length=Math.hypot(point.x()-x,point.z()-z);
            if(length<.03 || length>4.5) return false;
            extra+=length;x=point.x();z=point.z();
        }
        if(committedLength+extra>MAX_SESSION_LENGTH) return false;
        points.addAll(additional);
        committedLength+=extra;
        telemetry.extension();
        return true;
    }
    Point target() { return points.get(Math.min(index,points.size()-1)); }
    void cancel() { if(moving()) terminal="CANCELLED"; }
    void blocked(String reason) { if(moving()) terminal=reason; }

    /** A sampled position may pass a waypoint between Vanilla physics ticks.
     * Count only a tightly bounded intersection with the *actual* travelled
     * segment, not an extrapolated turn/chord. The final goal still requires
     * an observed near-position so C2 receipt verification cannot be forged. */
    private boolean reached(Point point,double x,double z,double distance) {
        if(distance<=TOLERANCE) return true;
        if(index==points.size()-1 || !Double.isFinite(lastX)) return false;
        double dx=x-lastX,dz=z-lastZ,travel=dx*dx+dz*dz;
        if(travel<1e-8 || travel>.49) return false;
        double t=Math.max(0,Math.min(1,((point.x()-lastX)*dx+(point.z()-lastZ)*dz)/travel));
        return t>0 && t<1 && Math.hypot(lastX+t*dx-point.x(),lastZ+t*dz-point.z())<=ARRIVAL_SWEEP;
    }
    private boolean cornerAhead() {
        if(index+1>=points.size()) return false;
        Point from=index==0?null:points.get(index-1);
        if(from==null && Double.isFinite(lastX)) from=new Point(lastX,target().y(),lastZ);
        if(from==null) return true; // conservative first-corner approach
        Point current=target(),next=points.get(index+1);
        double ax=current.x()-from.x(),az=current.z()-from.z();
        double bx=next.x()-current.x(),bz=next.z()-current.z();
        double al=Math.hypot(ax,az),bl=Math.hypot(bx,bz);
        return al<.05 || bl<.05 || (ax*bx+az*bz)/(al*bl)<.85;
    }

    /** Called at most once per C1 authoritative physics tick, after combat priority. */
    Intent next(double x, double y, double z, float yaw) {
        if (!moving()) return null;
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Float.isFinite(yaw)) { terminal="BLOCKED";return null; }
        if (++ticks>MAX_TICKS) { terminal="TIMED_OUT";return null; }
        Point point=target();
        double distance=Math.hypot(point.x()-x,point.z()-z);
        if(Math.abs(y-point.y())>.35) { terminal="BLOCKED";return null; }
        // No velocity reset at intermediate waypoints. The next point is
        // selected inside the same C1 travel session and verified by caller.
        while(reached(point,x,z,distance)) {
            index++;stalled=0;lastDistance=Double.NaN;
            if(index>=points.size()) {terminal="COMPLETE";return null;}
            point=target();distance=Math.hypot(point.x()-x,point.z()-z);
        }
        if(Double.isFinite(lastDistance) && distance>=lastDistance-.002) stalled++;
        else stalled=0;
        lastDistance=distance;
        if(stalled>=STALL_TICKS) {terminal="BLOCKED";return null;}
        // Do not aim through a curve that has not been swept/graph-validated.
        // Slow on the verified approach to a sharp corner, then turn within
        // this single C1 motion ticket at the committed waypoint.
        // H3: look along the CURRENT certified flat leg, not toward its
        // block-center endpoint on every physics tick. Never skip a corner.
        Point anchor=index==0?startingPoint:points.get(index-1);
        var aim=C1F2AdaptiveSteering.onSegment(anchor.x(),anchor.z(),
                point.x(),point.z(),x,z);
        float heading=(float)Math.toDegrees(Math.atan2(-(aim.x()-x),aim.z()-z));
        // Vanilla-inspired input shaping only; C1 retains full authority over
        // Player.travel, grounded safety, swept collision and combat priority.
        // In particular, this MUST NOT install a Mob navigation/controller.
        C1VanillaMoveControlAdapter.Drive drive=vanillaDrive.tick(
                yaw,heading,distance,index==points.size()-1,cornerAhead());
        float nextYaw=drive.yaw();
        double forward=drive.forward();
        double moved=Double.isFinite(lastX)?Math.hypot(x-lastX,z-lastZ):0;
        telemetry.sample(moved,forward,C2F2Locomotion.angularDelta(yaw,nextYaw),index);
        lastX=x;lastZ=z;
        return new Intent(nextYaw,forward,point.x(),point.z());
    }
}
