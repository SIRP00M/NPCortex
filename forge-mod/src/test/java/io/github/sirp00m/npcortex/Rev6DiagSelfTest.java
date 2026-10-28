package io.github.sirp00m.npcortex;

import java.util.List;
import java.util.UUID;

/** Rev6: D1 log formats and the TAB name resolution (pure JVM; the in-game TAB rendering is Owner-visual). */
public final class Rev6DiagSelfTest {
    private static int n;

    private static void check(boolean ok, String what) { n++; if (!ok) throw new AssertionError(what); }

    private static C1IdentityData.Entry npc(String name, UUID profile) {
        return new C1IdentityData.Entry(name, UUID.randomUUID(), profile, "N" + name, "minecraft:overworld", 0, 0, 0,
                C1RotationState.legacy(0f), true, "", "");
    }

    public static void main(String[] args) {
        UUID actor = UUID.randomUUID(), goal = UUID.randomUUID();
        // ---- D1-B cancel line: state at invocation, fixed field order the collector parses
        String c = C2Diagnostics.cancel(actor, goal, "REPLANNING", C2LongGoalPolicy.LongState.LEG_QUIESCING);
        check(c.equals("NPCortex C2 diag cancel actor=" + actor + " goal=" + goal + " phase=REPLANNING long=LEG_QUIESCING"), "cancel line: " + c);
        check(C2Diagnostics.cancel(actor, null, "MOVING", C2LongGoalPolicy.LongState.NONE).contains("goal=none "), "null goal is printed, never thrown");
        // ---- D1-A result line
        String r = C2Diagnostics.result(actor, goal, false, "MOVING", C2LongGoalPolicy.LongState.LEG_ACTIVE, "LONG_NO_PROGRESS legs=6 best-effort heuristic; no path claim");
        check(r.equals("NPCortex C2 diag result actor=" + actor + " goal=" + goal + " succeeded=false phase=MOVING long=LEG_ACTIVE reason=LONG_NO_PROGRESS legs=6 best-effort heuristic; no path claim"), "result line: " + r);
        String nasty = C2Diagnostics.result(actor, goal, false, "P\nH", C2LongGoalPolicy.LongState.NONE, "a\r\nb\u0000c" + "x".repeat(400));
        check(nasty.indexOf('\n') < 0 && nasty.indexOf('\r') < 0 && nasty.indexOf('\u0000') < 0, "control characters never reach the log");
        check(nasty.substring(nasty.indexOf("reason=") + 7).length() == C2Diagnostics.MAX_REASON, "reason is bounded");
        check(C2Diagnostics.result(actor, goal, true, "x", C2LongGoalPolicy.LongState.NONE, null).endsWith("reason=none"), "null reason");
        // ---- TAB name: only a known NPC's profile UUID resolves, to its logical name
        UUID reikaProfile = UUID.randomUUID(), mikaProfile = UUID.randomUUID(), player = UUID.randomUUID();
        List<C1IdentityData.Entry> world = List.of(npc("Reika", reikaProfile), npc("Mika", mikaProfile), npc("bad name", UUID.randomUUID()));
        check("Reika".equals(NpcTabName.resolve(reikaProfile, world)), "Reika resolves");
        check("Mika".equals(NpcTabName.resolve(mikaProfile, world)), "second NPC resolves to its own name");
        check(NpcTabName.resolve(player, world) == null, "a real player's UUID never resolves");
        check(NpcTabName.resolve(world.get(2).profileId(), world) == null, "an invalid logical name falls back to the profile name");
        check(NpcTabName.resolve(null, world) == null && NpcTabName.resolve(reikaProfile, null) == null && NpcTabName.resolve(reikaProfile, List.of()) == null, "null / empty input falls back");
        System.out.println("Rev6DiagSelfTest PASS assertions=" + n);
    }
}
