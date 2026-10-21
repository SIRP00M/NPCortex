package io.github.sirp00m.npcortex;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;

/** C2-P1-B1: server-thread-only read-only clearance adapter for the pure graph.
 * Every WALK edge verifies the collision-free swept player-sized corridor.
 * A path is NOT executed, persisted or sent to Ollama here.
 */
final class C2GraphWorldProbe {
    private C2GraphWorldProbe() {}

    record Capture(C2NavGrid.Snapshot snapshot, C2TraversabilityGraph graph) { }

    static C2TraversabilityGraph capture(C1PlayerAvatar npc, int radius) {
        return captureWithSnapshot(npc,radius).graph();
    }

    /** Coherent P1-B2 graph and source snapshot from a single server-thread read. */
    static Capture captureWithSnapshot(C1PlayerAvatar npc, int radius) {
        if (npc == null || npc.isRemoved()) throw new IllegalArgumentException("P1-B1 actor unavailable");
        MinecraftServer server = npc.getServer();
        if (server == null || !server.isSameThread())
            throw new IllegalStateException("P1-B1 terrain capture requires Minecraft server thread");
        ServerLevel level = npc.serverLevel();
        C2NavGrid.Snapshot snapshot = C2NavigationSensor.capture(npc, radius);
        double halfWidth = npc.getBbWidth() * 0.5 + 0.01;
        double height = npc.getBbHeight();
        C2TraversabilityGraph graph = C2TraversabilityGraph.build3DWithRisk(snapshot, (from,to,kind) -> {
            // The swept AABB can straddle a chunk boundary. Probe only the
            // four chunk-corner columns of its bounding rectangle, without
            // force-loading neighbors on every graph edge.
            double fromY=from.footY(), toY=to.footY();
            double high=Math.max(fromY,toY);
            double minX = Math.min(from.x(),to.x()) + 0.5 - halfWidth;
            double maxX = Math.max(from.x(),to.x()) + 0.5 + halfWidth;
            double minZ = Math.min(from.z(),to.z()) + 0.5 - halfWidth;
            double maxZ = Math.max(from.z(),to.z()) + 0.5 + halfWidth;
            int minBX=net.minecraft.util.Mth.floor(minX);
            int maxBX=net.minecraft.util.Mth.floor(maxX);
            int minBZ=net.minecraft.util.Mth.floor(minZ);
            int maxBZ=net.minecraft.util.Mth.floor(maxZ);
            for (int bxIndex=0;bxIndex<2;bxIndex++) for (int bzIndex=0;bzIndex<2;bzIndex++) {
                int bx=bxIndex==0?minBX:maxBX;
                int bz=bzIndex==0?minBZ:maxBZ;
                BlockPos corner = new BlockPos(bx,from.supportY(),bz);
                if (!level.hasChunkAt(corner) || !level.getWorldBorder().isWithinBounds(corner))
                    return C2TraversalCost.Assessment.blocked();
            }
            // At the higher foot level, the horizontal corridor must be free:
            // walking/jumping may never be treated as passing THROUGH the ledge.
            AABB swept = new AABB(minX,high+.001,minZ,maxX,high+height,maxZ);
            if (!level.noCollision(npc,swept)) return C2TraversalCost.Assessment.blocked();
            if (kind!=C2TraversabilityGraph.MoveKind.WALK) {
                // Verify both full-body standing poses. A graph edge is only a
                // conservative *candidate*: C1 will recheck real landing and
                // execute physics, and only a grounded receipt may complete it.
                AABB startBox = new AABB(from.x()+.5-halfWidth,fromY+.001,from.z()+.5-halfWidth,
                        from.x()+.5+halfWidth,fromY+height,from.z()+.5+halfWidth);
                AABB endBox = new AABB(to.x()+.5-halfWidth,toY+.001,to.z()+.5-halfWidth,
                        to.x()+.5+halfWidth,toY+height,to.z()+.5+halfWidth);
                if (!level.noCollision(npc,startBox) || !level.noCollision(npc,endBox)) return C2TraversalCost.Assessment.blocked();
                if (kind==C2TraversabilityGraph.MoveKind.JUMP_UP) {
                    // Conservative airborne clearance. This is not a dynamic
                    // jump arc proof; Vanilla C1 still owns collision/landing.
                    if (!level.noCollision(npc,startBox.move(0,.5,0))
                            || !level.noCollision(npc,endBox.move(0,.5,0))) return C2TraversalCost.Assessment.blocked();
                }
            }
            return C2BlockTraversalWorld.assess(level,swept);
        });
        return new Capture(snapshot,graph);
    }

    /** Operator-only read-only gameplay fixture; guards match P1-A navscan. */
    static String diagnostic(MinecraftServer server, String name, int radius) {
        if (radius < 1 || radius > C2NavGrid.MAX_RADIUS)
            return "P1-B1 navgraph radius must be 1.." + C2NavGrid.MAX_RADIUS;
        C1IdentityData.Entry id = C1IdentityData.get(server).find(name);
        if (id == null) return "Unknown NPC: " + name;
        C1PlayerAvatar npc = C1P0Runtime.activeAvatar(server, name);
        if (npc == null || npc.isRemoved() || !npc.getUUID().equals(id.profileId())
                || !npc.serverLevel().dimension().location().toString().equals(id.dimension())
                || !C1P1CLifecycleRuntime.mayRestore(server,id.profileId()))
            return "P1-B1 graph rejected: NPC not live/alive in registered dimension";
        if (C1P1MotionRuntime.c2Receipt(id.profileId()).active())
            return "P1-B1 graph rejected while C1 movement is active";
        long started = System.nanoTime();
        C2TraversabilityGraph graph = capture(npc,radius);
        double ms = (System.nanoTime()-started)/1_000_000.0;
        long walkEdges=graph.edges().stream()
                .filter(edge -> edge.kind()==C2TraversabilityGraph.MoveKind.WALK).count();
        return "P1-B1 navgraph " + name + " r=" + radius + " nodes=" + graph.nodes().size()
                + " directedWalkEdges=" + walkEdges
                + " directed3DEdges=" + (graph.edges().size()-walkEdges)
                + " elapsedMs=" + String.format(java.util.Locale.ROOT,"%.2f",ms)
                + " (read-only; no A*, region planner or waypoint execution)";
    }
}
