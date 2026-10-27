package io.github.sirp00m.npcortex;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiPredicate;

/** Pure-JVM Option D contract checks. Does not certify Minecraft gameplay or MSPT. */
public final class C2LongGoalPolicySelfTest {
    private static int checks;
    private static void ok(boolean pass, String message) {
        checks++; if (!pass) throw new AssertionError(message);
    }

    /** blocked(x,z) = no walkable surface there; everything else is flat ground at supportY 0. */
    private static C2TraversabilityGraph graphAt(int ox, int oz, BiPredicate<Integer, Integer> blocked) {
        var snap = C2NavGrid.scan(ox, 0, oz, C2NavGrid.MAX_RADIUS, new C2NavGrid.Probe() {
            public boolean loaded(int x, int z) { return true; }
            public C2NavGrid.Cell sample(int x, int y, int z) {
                boolean walk = y == 0 && !blocked.test(x, z);
                return new C2NavGrid.Cell(x, y, z, y + 1, walk ? C2NavGrid.Condition.WALKABLE
                        : C2NavGrid.Condition.UNSUPPORTED);
            }
        });
        return C2TraversabilityGraph.build(snap, (a, b) -> true);
    }
    private static C2TraversabilityGraph.Node nodeAt(C2TraversabilityGraph g, int x, int z) {
        for (var n : g.nodes()) if (n.x() == x && n.z() == z && n.supportY() == 0) return n;
        return null;
    }

    /** Teleporting leg simulation: scan, select, arrive, judge. Returns "FINAL", a Verdict name or "NO_CANDIDATE". */
    private static String simulate(BiPredicate<Integer, Integer> blocked, int sx, int sz, int fx, int fz, int[] legsOut) {
        var lg = new C2LongGoalPolicy.LongGoal(sx, 0, sz, fx, 0, fz);
        int x = sx, z = sz;
        for (int guard = 0; guard < C2LongGoalPolicy.MAX_LEGS + 5; guard++) {
            var g = graphAt(x, z, blocked);
            var sel = lg.select(g, nodeAt(g, x, z));
            if (sel == null) { legsOut[0] = lg.legs(); return "NO_CANDIDATE"; }
            lg.legStarted(sel.target());
            if (sel.isFinal()) { legsOut[0] = lg.legs(); return "FINAL"; }
            x = sel.target().x(); z = sel.target().z();
            var v = lg.arrived(x, 0, z);
            if (v != C2LongGoalPolicy.Verdict.CONTINUE) { legsOut[0] = lg.legs(); return v.name(); }
        }
        throw new AssertionError("simulation not bounded by MAX_LEGS");
    }

    public static void main(String[] args) {
        // Leg completion never means goal completion.
        ok(C2LongGoalPolicy.onRouteComplete(C2LongGoalPolicy.LongState.NONE) == C2LongGoalPolicy.Completion.NOT_LONG, "NONE");
        ok(C2LongGoalPolicy.onRouteComplete(C2LongGoalPolicy.LongState.LEG_ACTIVE) == C2LongGoalPolicy.Completion.LEG_DONE, "leg done");
        ok(C2LongGoalPolicy.onRouteComplete(C2LongGoalPolicy.LongState.FINAL_ACTIVE) == C2LongGoalPolicy.Completion.FINAL_VERIFY, "final verify");
        try { C2LongGoalPolicy.onRouteComplete(C2LongGoalPolicy.LongState.LEG_QUIESCING); throw new AssertionError("quiescing completes"); }
        catch (IllegalStateException expected) { checks++; }

        // Bounded budgets.
        ok(C2LongGoalPolicy.totalTickLimit(0) == C2LongGoalPolicy.BASE_TOTAL_TICKS, "base ticks");
        ok(C2LongGoalPolicy.totalTickLimit(150) == 7200, "150 blocks");
        ok(C2LongGoalPolicy.totalTickLimit(C2LongGoalPolicy.MAX_DISTANCE) <= C2LongGoalPolicy.MAX_TOTAL_TICKS, "capped");
        try { new C2LongGoalPolicy.LongGoal(0, 0, 0, C2LongGoalPolicy.MAX_DISTANCE + 1, 0, 0); throw new AssertionError("distance"); }
        catch (IllegalArgumentException expected) { checks++; }
        var budget = new C2LongGoalPolicy.LongGoal(0, 0, 0, 20, 0, 0);
        ok(budget.chargeSearch(C2LongGoalPolicy.TOTAL_SEARCH_BUDGET), "budget exactly spent");
        ok(!budget.chargeSearch(1), "budget exceeded");

        // Final inside the window and reachable wins; far goal picks the window edge toward it.
        var open = graphAt(0, 0, (a, b) -> false);
        var near = new C2LongGoalPolicy.LongGoal(0, 0, 0, 4, 0, 3).select(open, nodeAt(open, 0, 0));
        ok(near != null && near.isFinal() && near.target().x() == 4 && near.target().z() == 3, "final reachable");
        var far = new C2LongGoalPolicy.LongGoal(0, 0, 0, 100, 0, 0).select(open, nodeAt(open, 0, 0));
        ok(far != null && !far.isFinal() && far.target().x() == 6 && far.target().z() == 0, "edge toward goal");
        ok(new C2LongGoalPolicy.LongGoal(0, 0, 0, 100, 0, 0).select(open, null) == null, "start required");

        // Small obstacle inside the window is bypassed by the reachable set.
        BiPredicate<Integer, Integer> smallWall = (x, z) -> x == 3 && Math.abs(z) <= 4;
        var wallG = graphAt(0, 0, smallWall);
        var past = new C2LongGoalPolicy.LongGoal(0, 0, 0, 100, 0, 0).select(wallG, nodeAt(wallG, 0, 0));
        ok(past != null && past.target().x() == 6, "detours a small wall via reachable set");

        // Open field: reaches the final target, no premature verdict.
        int[] legs = new int[1];
        ok("FINAL".equals(simulate((x, z) -> false, 0, 0, 60, 0, legs)), "open field reaches final");
        ok(legs[0] <= 12, "open-field legs bounded: " + legs[0]);
        ok("FINAL".equals(simulate((x, z) -> false, 0, 0, 150, 0, legs)), "150 blocks reaches final");
        ok(legs[0] <= 27, "150-block legs bounded: " + legs[0]);

        // Barrier larger than the window: must terminate safely, never claim the final.
        BiPredicate<Integer, Integer> longWall = (x, z) -> x == 3;
        String barrier = simulate(longWall, 0, 0, 60, 0, legs);
        ok(!"FINAL".equals(barrier) && legs[0] <= C2LongGoalPolicy.MAX_LEGS, "barrier ends non-final: " + barrier);
        ok(barrier.equals(simulate(longWall, 0, 0, 60, 0, new int[1])), "deterministic");

        // Dead-end pocket (closed on three sides, exit behind): bounded, deterministic outcome.
        BiPredicate<Integer, Integer> cup = (x, z) -> (x == 4 && Math.abs(z) <= 5)
                || (Math.abs(z) == 4 && x >= -5 && x <= 4);
        String pocket = simulate(cup, 0, 0, 60, 0, legs);
        ok("FINAL".equals(pocket) && legs[0] <= C2LongGoalPolicy.MAX_LEGS, "pocket inside the window is escaped: " + pocket);
        ok(pocket.equals(simulate(cup, 0, 0, 60, 0, new int[1])), "pocket deterministic");

        // Visit penalty: a visited cell is penalized but never excluded.
        var lg = new C2LongGoalPolicy.LongGoal(0, 0, 0, 100, 0, 0);
        double base = lg.select(open, nodeAt(open, 0, 0)).score();
        lg.arrived(6, 0, 0);
        double penalized = lg.select(open, nodeAt(open, 0, 0)).score();
        ok(penalized > base, "visit raises score");
        ok(lg.select(open, nodeAt(open, 0, 0)) != null, "visited cell stays selectable");
        var osc = new C2LongGoalPolicy.LongGoal(0, 0, 0, 100, 0, 0);
        C2LongGoalPolicy.Verdict last = C2LongGoalPolicy.Verdict.CONTINUE;
        for (int i = 0; i < 4 && last == C2LongGoalPolicy.Verdict.CONTINUE; i++) last = osc.arrived(2, 0, 0);
        ok(last == C2LongGoalPolicy.Verdict.OSCILLATION, "revisit cap detects oscillation");
        var stall = new C2LongGoalPolicy.LongGoal(0, 0, 0, 100, 0, 0);
        C2LongGoalPolicy.Verdict sv = C2LongGoalPolicy.Verdict.CONTINUE;
        for (int i = 0; i < C2LongGoalPolicy.NO_BEST_LEGS && sv == C2LongGoalPolicy.Verdict.CONTINUE; i++)
            sv = stall.arrived(-4 * (i + 1), 0, 0); // distinct cells, never a new best
        ok(sv == C2LongGoalPolicy.Verdict.NO_PROGRESS, "no new best distance ends the goal");

        // Timeouts are bounded per leg and in total.
        ok(!C2LongGoalPolicy.timedOut(600, 0, 7200), "per-leg limit inclusive");
        ok(C2LongGoalPolicy.timedOut(601, 0, 7200), "per-leg limit exceeded");
        ok(!C2LongGoalPolicy.timedOut(5000, 4500, 7200) && C2LongGoalPolicy.timedOut(7201, 7000, 7200), "total limit");

        // Unloaded / unwalkable terrain is never selected as a leg target and never crossed.
        var gate = new BiPredicate<Integer, Integer>() { public boolean test(Integer x, Integer z) { return x >= 4; } };
        var wallLg = new C2LongGoalPolicy.LongGoal(0, 0, 0, 100, 0, 0);
        var unloadedG = graphAt(0, 0, gate);
        var selUn = wallLg.select(unloadedG, nodeAt(unloadedG, 0, 0));
        ok(selUn != null && selUn.target().x() <= 3, "leg target stays in loaded terrain");
        ok("NO_PROGRESS".equals(simulate(gate, 0, 0, 100, 0, legs)) || "OSCILLATION".equals(simulate(gate, 0, 0, 100, 0, legs)),
                "unloaded half-plane ends safely");
        ok(!wallLg.finalInWindow(0, 0, 0), "far final is not in window");
        var nearBlocked = new C2LongGoalPolicy.LongGoal(0, 0, 0, 4, 0, 0);
        ok(nearBlocked.finalInWindow(0, 0, 0) && !nearBlocked.finalWalkable(graphAt(0, 0, (x, z) -> x == 4 && z == 0)),
                "unsafe final inside the window is detected");
        ok(nearBlocked.finalWalkable(open), "walkable final accepted");

        // C1 terminals that must never become autonomous motion.
        for (String phase : new String[]{"CHUNK_UNLOADED", "CANCELLED", "UNKNOWN"}) {
            ok(C2S4RecoveryPolicy.classify(C2TraversabilityGraph.MoveKind.WALK, true, false, phase, false)
                    == C2S4RecoveryPolicy.Verdict.FAIL_CLOSED, "WALK " + phase + " fails closed");
            ok(C2S4RecoveryPolicy.classify(C2TraversabilityGraph.MoveKind.JUMP_UP, true, false, phase, false)
                    == C2S4RecoveryPolicy.Verdict.FAIL_CLOSED, "vertical " + phase + " fails closed");
        }
        ok(C2S4RecoveryPolicy.classify(C2TraversabilityGraph.MoveKind.WALK, false, false, "COMPLETE", true)
                == C2S4RecoveryPolicy.Verdict.FOREIGN_TICKET, "foreign ticket never reused");

        // FIFO: round-robin wait is bounded by N-1 and independent of iteration order.
        var queue = new C2PlanQueue();
        UUID[] actors = new UUID[10];
        for (int i = 0; i < actors.length; i++) actors[i] = new UUID(0, i + 1);
        Map<UUID, Long> lastServed = new HashMap<>();
        long maxWait = 0;
        for (long tick = 0; tick < 200; tick++) {
            for (int i = 0; i < actors.length; i++) queue.touch(actors[(int) ((i + tick * 3) % actors.length)], tick); // rotating order
            UUID granted = null;
            for (UUID a : actors) if (queue.isHead(a, tick)) { ok(granted == null, "one head"); granted = a; }
            ok(granted != null, "a head exists");
            queue.done(granted);
            maxWait = Math.max(maxWait, tick - lastServed.getOrDefault(granted, 0L));
            lastServed.put(granted, tick);
        }
        ok(maxWait <= actors.length, "max wait " + maxWait + " <= N");
        var stale = new C2PlanQueue();
        stale.touch(actors[0], 0); stale.touch(actors[1], 5);
        ok(stale.isHead(actors[1], 5) && !stale.isHead(actors[0], 5), "stale entry cannot block");
        stale.clear();
        ok(!stale.isHead(actors[1], 6), "cleared");
        System.out.println("PASS C2 Long Goal Policy: " + checks + " checks");
    }
}
