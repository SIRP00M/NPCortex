package io.github.sirp00m.npcortex;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Static boundary checks for Option D over the Java source (no Forge runtime):
 * one Run/goal/action, no extra C0 calls per leg, no new journal states,
 * leg completion never reaches a result, shutdown order, F1 invariant, E3/navigate untouched.
 * This proves source shape only; it is NOT runtime or gameplay evidence.
 */
public final class C2LongGoalBoundarySelfTest {
    private static int checks;
    private static void ok(boolean pass, String message) {
        checks++; if (!pass) throw new AssertionError(message);
    }
    private static final String DIR = "src/main/java/io/github/sirp00m/npcortex/";

    private static String read(String name) throws IOException {
        Path p = Path.of(DIR + name);
        if (!Files.exists(p)) p = Path.of("forge-mod/" + DIR + name);
        return Files.readString(p);
    }

    /** Body of the first method whose declaration line contains {@code signature}. */
    private static String body(String src, String signature) {
        int at = src.indexOf(signature);
        ok(at >= 0, "method present: " + signature);
        int open = src.indexOf('{', at);
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return src.substring(open, i + 1);
        }
        throw new AssertionError("unbalanced: " + signature);
    }

    private static void none(String body, String what, String... tokens) {
        for (String t : tokens) ok(!body.contains(t), what + " must not contain " + t);
    }

    public static void main(String[] args) throws IOException {
        String rt = read("C2AgentRuntime.java");
        String quiesce = body(rt, "private static void enterLegQuiesce(");
        String legPlan = body(rt, "private static void tickLegPlan(");
        String route = body(rt, "private static void tickRoute(");
        String stop = body(rt, "static void beforeServerStop(");

        // No extra C0 decision, goal, action, Run or terminal receipt per leg.
        String[] c0 = {"send(", "decide(", "createGoal(", "registerActor(", "observe(run", "new Run(", "UUID.randomUUID",
                "startInternal(", "ACTIVE.claim", "ACTIVE.release", "finish(", "C2CancellationRecovery"};
        none(quiesce, "enterLegQuiesce", c0);
        none(legPlan, "tickLegPlan", c0);
        none(quiesce, "enterLegQuiesce", "result(");
        Matcher results = Pattern.compile("result\\(").matcher(legPlan);
        while (results.find()) ok(legPlan.startsWith("result(run,false,", results.start()),
                "tickLegPlan only reports failed results");
        ok(count(rt, "new Run(") == 1, "exactly one Run constructor call site");
        ok(count(rt, "UUID.randomUUID()") >= 1 && rt.contains("this.goal = UUID.randomUUID();"), "goal id assigned in Run constructor only");
        ok(count(rt, "run.action = actionId") == 1, "action id assigned once");

        // Leg completion is classified before any result can be produced.
        String complete = route.substring(route.indexOf("Update.COMPLETE"));
        int classify = complete.indexOf("onRouteComplete(run.longState)");
        int quiesceCall = complete.indexOf("enterLegQuiesce(run)");
        int firstResult = complete.indexOf("result(run,finalReached");
        ok(classify >= 0 && quiesceCall > classify && firstResult > quiesceCall, "leg completion precedes any final result");

        // Journal: no new state strings anywhere; new methods use only MOVING/REPLANNING.
        Set<String> allowed = Set.of("REQUESTED", "HTTP_PENDING", "DECIDED", "MOVING", "REPLANNING", "RESULT_PENDING",
                "CANCELLED", "COMPLETED", "FAILED", "INTERRUPTED");
        Matcher w = Pattern.compile("write\\(run,\\s*\"([A-Z_]+)\"").matcher(rt);
        Set<String> seen = new HashSet<>();
        while (w.find()) seen.add(w.group(1));
        ok(allowed.containsAll(seen), "journal states unchanged: " + seen);
        for (String m : new String[]{quiesce, legPlan}) {
            Matcher s = Pattern.compile("write\\(run,\\s*\"([A-Z_]+)\"").matcher(m);
            while (s.find()) ok(s.group(1).equals("MOVING") || s.group(1).equals("REPLANNING"), "leg state strings: " + s.group(1));
        }

        // Existing entry points and E3 do not know about LongGoal; cancel has no LongGoal special case.
        none(body(rt, "static String navigate("), "navigate", "longGoal", "LongGoal", "navigateLong");
        none(body(rt, "static NavigationAdmission guardedNavigate("), "guardedNavigate", "longGoal", "LongGoal", "navigateLong");
        none(body(rt, "static String cancel("), "cancel", "longState", "longGoal");
        none(read("CPlusE3G2Runtime.java"), "E3", "navigateLong", "LongGoal", "navlong");
        ok(read("C2Commands.java").contains("\"navlong\"") && read("C2Commands.java").contains("C2AgentRuntime.navigateLong("), "navlong wired");

        // FIFO only gates planning; the existing global one-plan-per-tick limiter is kept.
        ok(count(rt, "lastPlannerTick==serverTicks") == 2, "limiter kept at both plan sites");
        ok(count(rt, "PLAN_QUEUE.isHead") == 2 && count(rt, "PLAN_QUEUE.touch") == 2, "FIFO at both plan sites");

        // Shutdown: F1 invariant (no C1 stop branch) and statement order preserved, queue cleared last-but-one.
        none(stop, "beforeServerStop", "C1P1MotionRuntime.stop");
        String[] order = {"ServerThread.require", "replanning.stop()", "ACTIVE.release", "C2CancellationRecovery.begin",
                "C2CancellationRecovery.beforeStop()", "serverTicks=0", "VERIFIED_WITNESSES.clear()", "lastPlannerTick=-1",
                "PLAN_QUEUE.clear()", "EVENTS.clear()"};
        int last = -1;
        for (String t : order) {
            int at = stop.indexOf(t);
            ok(at > last, "shutdown order: " + t);
            last = at;
        }

        // Timeouts: long runs use per-leg/total bounds; others keep the original constant.
        String timed = body(rt, "private static boolean routeTimedOut(");
        ok(timed.contains("ROUTE_TIMEOUT_TICKS") && timed.contains("C2LongGoalPolicy.timedOut("), "timeout split");

        // Policy has no Minecraft or persistence dependency.
        String pol = read("C2LongGoalPolicy.java") + read("C2PlanQueue.java");
        none(pol, "policy", "net.minecraft", "C2ActionJournal", "SavedData", "java.io", "java.sql", "HttpClient");
        System.out.println("PASS C2 Long Goal Boundary: " + checks + " checks");
    }

    private static int count(String s, String t) {
        int n = 0;
        for (int i = s.indexOf(t); i >= 0; i = s.indexOf(t, i + t.length())) n++;
        return n;
    }
}
