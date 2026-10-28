package io.github.sirp00m.npcortex;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/** D3 regression: the M03 tick-event timer must never turn a missing or stale START into a duration.
 * Pure JVM (no Minecraft world); the Runtime wiring is pinned by a source-shape check at the end. */
public final class CPlusM03TickClockSelfTest {
    private static int checks;
    private static void ok(boolean pass, String message) {
        checks++;
        if (!pass) throw new AssertionError(message);
    }
    private static boolean near(double a, double b) { return Math.abs(a - b) < 1e-9; }

    public static void main(String[] args) throws Exception {
        // 1. normal START/END
        CPlusM03TickClock c = new CPlusM03TickClock();
        c.start(1_000_000_000L);
        ok(near(c.end(1_001_500_000L), 1.5), "normal pair = (end-start)/1e6 ms");
        ok(c.unmatchedEnds() == 0, "a matched pair is not counted as unmatched");

        // 2. first measurement: the command that began it ran mid-tick, so its END has no START at all.
        //    The Rev5 defect produced (nanoTime-0)/1e6 here (207,341,813 ms in the p19 evidence).
        c = new CPlusM03TickClock();
        double first = c.end(41_000_000_000_000L);
        ok(Double.isNaN(first), "an END without a START is not a sample");
        ok(c.unmatchedEnds() == 1, "the unmatched END is counted");
        c.start(41_000_050_000_000L);
        ok(near(c.end(41_000_052_000_000L), 2.0), "the next matched pair is measured normally after the dropped one");

        // 3. consecutive measurements: a START left over from measurement 1 must not be reused by measurement 2
        c = new CPlusM03TickClock();
        c.start(5_000_000_000L);                                   // last START of measurement 1
        c.reset();                                                  // boundary of measurement 2 (measure())
        ok(Double.isNaN(c.end(9_000_000_000L)), "stale START from the previous measurement is forgotten at reset");
        ok(c.unmatchedEnds() == 1, "stale-start END is counted");

        // 4. session reset in the middle of an open pair
        c = new CPlusM03TickClock();
        c.start(100L);
        c.reset();
        ok(Double.isNaN(c.end(200L)), "reset discards an open START");

        // 5. missing START after a consumed pair (END, END)
        c = new CPlusM03TickClock();
        c.start(10_000_000L);
        ok(near(c.end(13_000_000L), 3.0), "first END consumes the START");
        ok(Double.isNaN(c.end(14_000_000L)), "a second END has no START");
        ok(c.unmatchedEnds() == 1, "second END counted");

        // 6. unexpected ordering: START, START, END -> the END pairs with the most recent START, never the older one
        c = new CPlusM03TickClock();
        c.start(1_000_000L);
        c.start(8_000_000L);
        ok(near(c.end(9_000_000L), 1.0), "latest START wins");

        // 7. END earlier than START (non-monotonic input) is rejected, not reported as a negative duration
        c = new CPlusM03TickClock();
        c.start(500L);
        ok(Double.isNaN(c.end(100L)), "negative elapsed time is not a sample");
        ok(Double.isNaN(c.end(900L)), "and the rejected pair is consumed");

        // 8. zero-length and large-but-valid durations are valid samples
        c = new CPlusM03TickClock();
        c.start(77L);
        ok(c.end(77L) == 0.0, "zero elapsed is a valid nonnegative sample");
        c.start(Long.MIN_VALUE / 2);
        double big = c.end(Long.MAX_VALUE / 2);
        ok(!Double.isNaN(big) && Double.isFinite(big) && big > 0, "large span stays finite");

        // 9. property: over a randomized event stream every produced sample is finite, nonnegative and equals
        //    the duration of ITS OWN START/END pair; every other END yields NaN (no fabricated value).
        Random random = new Random(20261028L);
        c = new CPlusM03TickClock();
        long clock = 1_000_000_000L;
        boolean open = false;
        long startedAt = 0;
        int expectedUnmatched = 0;
        for (int i = 0; i < 20_000; i++) {
            clock += 1 + random.nextInt(2_000_000);
            switch (random.nextInt(4)) {
                case 0 -> { c.start(clock); open = true; startedAt = clock; }
                case 1, 2 -> {
                    double ms = c.end(clock);
                    if (open) {
                        ok(near(ms, (clock - startedAt) / 1e6) && ms >= 0 && Double.isFinite(ms), "sample belongs to its own window");
                        open = false;
                    } else {
                        ok(Double.isNaN(ms), "END with no open START must be NaN");
                        expectedUnmatched++;
                    }
                }
                default -> { c.reset(); open = false; }
            }
        }
        ok(c.unmatchedEnds() == expectedUnmatched, "unmatched END count matches the model");

        // 10. wiring: CPlusM03Runtime.tick must route through the clock (no raw tickStart arithmetic) and must
        //     return before touching the samples or the countdown when the END is unmatched.
        String runtime = Files.readString(findSource("CPlusM03Runtime.java"));
        ok(!runtime.contains("tickStart"), "the stale-prone tickStart field is gone");
        ok(runtime.contains("s.clock.start(System.nanoTime())") && runtime.contains("double ms=s.clock.end(System.nanoTime());"),
                "tick() uses the paired clock");
        int endAt = runtime.indexOf("double ms=s.clock.end(System.nanoTime());");
        int guardAt = runtime.indexOf("if(Double.isNaN(ms))return;", endAt);
        int addAt = runtime.indexOf("s.ticks.add(ms);", endAt);
        int countdownAt = runtime.indexOf("if(--s.remaining>0)return;", endAt);
        ok(guardAt > endAt && guardAt < addAt && addAt < countdownAt, "unmatched END returns before the sample and the countdown");
        ok(runtime.contains("s.clock.reset();"), "measure() resets the clock at the measurement boundary");
        for (String name : new String[]{"tick_event_mean_ms", "tick_event_p95_ms", "tick_event_max_ms", "server_average_tick_ms", "MEASURED_NOT_ACCEPTED"})
            ok(runtime.contains(name), "metric/status name preserved: " + name);

        System.out.println("PASS CPlusM03TickClockSelfTest checks=" + checks + "; pure JVM, NOT Forge MSPT, NOT G4 acceptance");
    }

    private static Path findSource(String name) {
        for (String dir : new String[]{"src/main/java/io/github/sirp00m/npcortex/", "forge-mod/src/main/java/io/github/sirp00m/npcortex/"}) {
            Path p = Path.of(dir + name);
            if (Files.exists(p)) return p;
        }
        throw new AssertionError("source not found: " + name);
    }
}
