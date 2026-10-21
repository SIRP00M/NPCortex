package io.github.sirp00m.npcortex;

/** Pure-Java bounded per-C1-ticket diagnostics. Never stores player identity,
 * talks to backend or logs at 20 TPS. Counts server-controlled WALK ticks.
 * Real client-render metrics are explicitly a separate Windows quality gate. */
final class C1F2MotionTelemetry {
    record Summary(int samples,int nearStationary,int commandedZero,int turns,
            int extensions,int distinctWaypoints,double travelled) {
        @Override public String toString() {
            return "samples="+samples+" nearStationary="+nearStationary
                    +" commandedZero="+commandedZero+" turns="+turns
                    +" extensions="+extensions+" waypoints="+distinctWaypoints
                    +" distance="+String.format(java.util.Locale.ROOT,"%.2f",travelled);
        }
    }
    private int samples,stops,zeros,turns,extensions,waypoints,lastIndex=-1;
    private double moved;
    void sample(double delta,double input,double yawChange,int index) {
        if(!Double.isFinite(delta)||delta<0 || !Double.isFinite(input)
                || !Double.isFinite(yawChange)) throw new IllegalArgumentException("Invalid F2 telemetry");
        samples++;
        moved+=delta;
        if(delta<.008) stops++;
        if(input<.001) zeros++;
        if(Math.abs(yawChange)>1.0) turns++;
        if(index!=lastIndex) {waypoints++;lastIndex=index;}
    }
    void extension() {extensions++;}
    Summary summary() {return new Summary(samples,stops,zeros,turns,extensions,waypoints,moved);}
}
