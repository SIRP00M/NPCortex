package io.github.sirp00m.npcortex;

import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Mth;

import java.util.Locale;

/** P1-B3 operator-only, server-thread read-only region/path diagnostics. */
final class C2RegionDiagnostics {
    private C2RegionDiagnostics() { }

    static String regions(MinecraftServer server,String name,int radius,int tileSize) {
        String guard=guard(server,name,radius);
        if (guard!=null) return guard;
        if (tileSize < C2RegionGraph.MIN_TILE_SIZE || tileSize > C2RegionGraph.MAX_TILE_SIZE)
            return "P1-B3 region tile size must be 2..6";
        var npc=C1P0Runtime.activeAvatar(server,name);
        long start=System.nanoTime();
        var capture=C2GraphWorldProbe.captureWithSnapshot(npc,radius);
        var regions=C2RegionGraph.build(capture.graph(),capture.snapshot(),tileSize);
        double ms=(System.nanoTime()-start)/1_000_000.0;
        return "P1-B3 navregion "+name+" regions="+regions.regions().size()
                +" directedPortals="+regions.portals().size()
                +" graphNodes="+capture.graph().nodes().size()
                +" graphEdges="+capture.graph().edges().size()
                +" tileSize="+tileSize
                +" elapsedMs="+String.format(Locale.ROOT,"%.2f",ms)
                +" (READ-ONLY; P1-C NOT enabled)";
    }

    static String hierarchical(MinecraftServer server,String name,int x,double feetY,int z,
                               int radius,int tileSize,int budget) {
        String guard=guard(server,name,radius);
        if (guard!=null) return guard;
        if (tileSize < C2RegionGraph.MIN_TILE_SIZE || tileSize > C2RegionGraph.MAX_TILE_SIZE)
            return "P1-B3 region tile size must be 2..6";
        if (budget < 1 || budget > C2HierarchicalPlanner.MAX_BUDGET)
            return "P1-B3 search budget must be 1.."+C2HierarchicalPlanner.MAX_BUDGET;
        if (!Double.isFinite(feetY) || feetY < Integer.MIN_VALUE+2.0 || feetY > Integer.MAX_VALUE-2.0) return "P1-B3 target feetY out of range";
        var npc=C1P0Runtime.activeAvatar(server,name);
        long start=System.nanoTime();
        var capture=C2GraphWorldProbe.captureWithSnapshot(npc,radius);
        var a=new C2AStarPlanner.Target(Mth.floor(npc.getX()),C2S3SupportY.at(npc.getY()),Mth.floor(npc.getZ()));
        var b=new C2AStarPlanner.Target(x,C2S3SupportY.at(feetY),z);
        var result=C2HierarchicalPlanner.plan(capture.graph(),capture.snapshot(),a,b,tileSize,budget);
        double ms=(System.nanoTime()-start)/1_000_000.0;
        StringBuilder msg=new StringBuilder("P1-B3 navhier ").append(name)
                .append(" status=").append(result.status())
                .append(" source=").append(result.source())
                .append(" targetFeet=(").append(x).append(',').append(feetY).append(',').append(z).append(')')
                .append(" regions=").append(result.regionCount())
                .append(" directedPortals=").append(result.portalCount())
                .append(" expanded=").append(result.expandedNodes())
                .append(" visited=").append(result.visitedNodes())
                .append(" budget=").append(result.budget());
        if (result.found()) {
            msg.append(" waypoints=").append(result.path().size())
               .append(" cost=").append(String.format(Locale.ROOT,"%.2f",result.cost()));
            if(result.path().size()>1) {
                var next=result.path().get(1);
                msg.append(" firstStep=(").append(next.x()).append(',')
                        .append(String.format(Locale.ROOT,"%.2f",next.footY()))
                        .append(',').append(next.z()).append(')');
            }
        }
        return msg.append(" elapsedMs=").append(String.format(Locale.ROOT,"%.2f",ms))
                .append(" (READ-ONLY; P1-C NOT enabled)").toString();
    }

    private static String guard(MinecraftServer server,String name,int radius) {
        if (radius < 1 || radius > C2NavGrid.MAX_RADIUS)
            return "P1-B3 radius must be 1.."+C2NavGrid.MAX_RADIUS;
        if (server==null || !server.isSameThread())
            return "P1-B3 rejected: not on Minecraft server thread";
        C1IdentityData.Entry id=C1IdentityData.get(server).find(name);
        if (id==null) return "Unknown NPC: "+name;
        C1PlayerAvatar npc=C1P0Runtime.activeAvatar(server,name);
        if (npc==null || npc.isRemoved() || !npc.getUUID().equals(id.profileId())
                || !npc.serverLevel().dimension().location().toString().equals(id.dimension())
                || !C1P1CLifecycleRuntime.mayRestore(server,id.profileId()))
            return "P1-B3 rejected: NPC not live/alive in registered dimension";
        if (C1P1MotionRuntime.c2Receipt(id.profileId()).active())
            return "P1-B3 rejected while C1 movement is active";
        return null;
    }
}
