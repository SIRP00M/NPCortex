package io.github.sirp00m.npcortex;

/** S1 diagnostic-only counters: updates once per owning server END tick.
 * No timing telemetry can authorize any physics, route or recovery action. */
final class C2FollowSmoothMetrics {
    private long motionTicks,waitTicks,holdTicks,unknownTicks;
    private long currentWait,maxWait;
    private long turnTicks,cooldownTicks,replanTicks,recoveryTicks,groundTicks,combatTicks;
    private long terminalHandoffs,preparedHits,preparedMisses;
    private long terrainProbes,terrainProbeHits;

    void sample(String phase) {
        if("MOVING".equals(phase)) {motionTicks++;currentWait=0;}
        else if("HOLD".equals(phase) || "PAUSED".equals(phase)) {
            holdTicks++;currentWait=0;
        } else if("WAIT_GROUND".equals(phase) || "TURNING".equals(phase)
                || "REPLANNING".equals(phase) || "COOLDOWN".equals(phase)
                || "PAUSED_UNREACHABLE".equals(phase)
                || "PAUSED_COMBAT".equals(phase)) {
            waitTicks++;currentWait++;maxWait=Math.max(currentWait,maxWait);
            switch(phase) {
                case "TURNING" -> turnTicks++;
                case "COOLDOWN" -> cooldownTicks++;
                case "REPLANNING" -> replanTicks++;
                case "PAUSED_UNREACHABLE" -> recoveryTicks++;
                case "WAIT_GROUND" -> groundTicks++;
                case "PAUSED_COMBAT" -> combatTicks++;
                default -> { }
            }
        } else {unknownTicks++;currentWait=0;}
    }
    void terminalHandoff() {terminalHandoffs++;}
    void preparedHit() {preparedHits++;}
    void preparedMiss() {preparedMisses++;}
    void preplanAttempt(boolean found) {terrainProbes++;if(found)terrainProbeHits++;}
    String summary() {
        return " smoothMotionTicks="+motionTicks+" smoothWaitTicks="+waitTicks
                +" smoothHoldTicks="+holdTicks+" maxWaitTicks="+maxWait
                +" turnWait="+turnTicks+" cooldownWait="+cooldownTicks
                +" replanWait="+replanTicks+" recoveryWait="+recoveryTicks
                +" groundWait="+groundTicks+" combatWait="+combatTicks
                +" ticketHandoffs="+terminalHandoffs
                +" preparedHits="+preparedHits+" preparedMisses="+preparedMisses
                +" preplanProbes="+terrainProbes+" preplanFound="+terrainProbeHits;
    }
}
