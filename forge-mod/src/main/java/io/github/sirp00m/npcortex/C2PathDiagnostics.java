package io.github.sirp00m.npcortex;

import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Mth;

import java.util.Locale;

/** Operator-only P1-B2 A* fixture. No NPC movement, no persistence, no AI. */
final class C2PathDiagnostics {
    private C2PathDiagnostics() {}

    static String diagnostic(MinecraftServer server, String name, int x, double feetY, int z,
                             int radius,int budget) {
        if (radius < 1 || radius > C2NavGrid.MAX_RADIUS)
            return "P1-B2 navplan radius must be 1.."+C2NavGrid.MAX_RADIUS;
        if (budget < 1 || budget > C2AStarPlanner.MAX_EXPANSION_BUDGET)
            return "P1-B2 navplan budget must be 1.."+C2AStarPlanner.MAX_EXPANSION_BUDGET;
        if (server == null || !server.isSameThread())
            return "P1-B2 navplan rejected: not on Minecraft server thread";
        C1IdentityData.Entry id=C1IdentityData.get(server).find(name);
        if (id == null) return "Unknown NPC: " + name;
        C1PlayerAvatar npc=C1P0Runtime.activeAvatar(server,name);
        if (npc == null || npc.isRemoved() || !npc.getUUID().equals(id.profileId())
                || !npc.serverLevel().dimension().location().toString().equals(id.dimension())
                || !C1P1CLifecycleRuntime.mayRestore(server,id.profileId()))
            return "P1-B2 navplan rejected: NPC not live/alive in registered dimension";
        if (C1P1MotionRuntime.c2Receipt(id.profileId()).active())
            return "P1-B2 navplan rejected while C1 movement is active";
        // y is the whole-block player FEET coordinate, not the support block.
        if (!Double.isFinite(feetY) || feetY < Integer.MIN_VALUE+2.0 || feetY > Integer.MAX_VALUE-2.0)
            return "P1-B2 navplan target feetY out of range";
        long t=System.nanoTime();
        C2GraphWorldProbe.Capture capture=C2GraphWorldProbe.captureWithSnapshot(npc,radius);
        int startSupportY=C2S3SupportY.at(npc.getY());
        var start=new C2AStarPlanner.Target(Mth.floor(npc.getX()),startSupportY,Mth.floor(npc.getZ()));
        var target=new C2AStarPlanner.Target(x,C2S3SupportY.at(feetY),z);
        C2AStarPlanner.Result result=C2AStarPlanner.plan(capture.graph(),capture.snapshot(),
                start,target,budget);
        double ms=(System.nanoTime()-t)/1_000_000.0;
        StringBuilder message=new StringBuilder("P1-B2 navplan ").append(name)
                .append(" status=").append(result.status())
                .append(" targetFeet=(").append(x).append(',').append(feetY).append(',').append(z).append(')')
                .append(" nodes=").append(capture.graph().nodes().size())
                .append(" edges=").append(capture.graph().edges().size())
                .append(" expanded=").append(result.expandedNodes())
                .append(" visited=").append(result.visitedNodes())
                .append(" budget=").append(result.expansionBudget());
        if (result.found()) {
            message.append(" waypoints=").append(result.path().size())
                    .append(" cost=").append(String.format(Locale.ROOT,"%.2f",result.cost()));
            if (result.path().size()>1) {
                var next=result.path().get(1);
                message.append(" firstStep=(").append(next.x()).append(',')
                        .append(String.format(Locale.ROOT,"%.2f",next.footY())).append(',')
                        .append(next.z()).append(')');
            }
        }
        return message.append(" elapsedMs=")
                .append(String.format(Locale.ROOT,"%.2f",ms))
                .append(" (READ-ONLY; B3/P1-C NOT enabled)").toString();
    }
}
