package io.github.sirp00m.npcortex;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.MinecraftServer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** P1-A: bounded on-demand Minecraft SERVER-thread terrain probe.
 * It never loads/generates chunks and never mutates C1 movement or C0 decisions.
 * Conservative support: complex partial surfaces are classified separately until P1-B.
 */
final class C2NavigationSensor {
    private C2NavigationSensor() {}

    static String diagnostic(MinecraftServer server, String name, int radius) {
        if (radius < 1 || radius > C2NavGrid.MAX_RADIUS) return "P1-A navscan radius must be 1.." + C2NavGrid.MAX_RADIUS;
        C1IdentityData.Entry id = C1IdentityData.get(server).find(name);
        if (id == null) return "Unknown NPC: " + name;
        C1PlayerAvatar npc = C1P0Runtime.activeAvatar(server, name);
        if (npc == null || npc.isRemoved() || !npc.getUUID().equals(id.profileId())
                || !npc.serverLevel().dimension().location().toString().equals(id.dimension())
                || !C1P1CLifecycleRuntime.mayRestore(server, id.profileId()))
            return "P1-A scan rejected: NPC not live/alive in registered dimension";
        if (C1P1MotionRuntime.c2Receipt(id.profileId()).active())
            return "P1-A scan rejected while C1 movement is active";
        long t = System.nanoTime();
        C2NavGrid.Snapshot scan = capture(npc, radius);
        long nanos = System.nanoTime() - t;
        long loaded = scan.columnCount() - scan.count(C2NavGrid.Condition.UNLOADED);
        // The output is informational; no data is forwarded into C0's 6 KiB snapshot.
        return "P1-A navscan " + name + " r=" + radius + " columns=" + loaded + "/" + scan.columnCount()
                + " candidates=" + scan.cells().size() + " walkable=" + scan.count(C2NavGrid.Condition.WALKABLE)
                + " blocked=" + scan.count(C2NavGrid.Condition.OBSTRUCTED)
                + " stairTransitions=" + scan.count(C2NavGrid.Condition.STAIR_TRANSITION)
                + " partial=" + scan.count(C2NavGrid.Condition.PARTIAL_SUPPORT)
                + " hazard=" + scan.count(C2NavGrid.Condition.HAZARDOUS)
                + " unloaded=" + scan.count(C2NavGrid.Condition.UNLOADED)
                + " elapsedMs=" + String.format(java.util.Locale.ROOT, "%.2f", nanos / 1_000_000.0)
                + " (read-only; P1-B planner NOT enabled)";
    }

    /** Reusable P1-A server-world terrain representation for future path planners.
     * Caller MUST be on the Minecraft server thread with a valid live C1 avatar.
     * One bounded observation per explicit request; never call per NPC tick.
     */
    static C2NavGrid.Snapshot capture(C1PlayerAvatar npc, int radius) {
        if (npc == null || npc.isRemoved()) throw new IllegalArgumentException("P1-A actor unavailable");
        ServerLevel level = npc.serverLevel();
        int x = net.minecraft.util.Mth.floor(npc.getX());
        int y = C2S3SupportY.at(npc.getY());
        int z = net.minecraft.util.Mth.floor(npc.getZ());
        return C2NavGrid.scan(x, y, z, radius, new MinecraftProbe(level, npc, y));
    }

    /** Operator-only exact support-block inspection. x/y/z are SUPPORT block
     * coordinates, not target FEET coordinates. Bounded to current navscan window.
     */
    static String inspect(MinecraftServer server, String name, int x, int supportY, int z) {
        if (server == null || !server.isSameThread())
            return "S1 terraininspect rejected: Minecraft server thread required";
        C1IdentityData.Entry id = C1IdentityData.get(server).find(name);
        if (id == null) return "Unknown NPC: " + name;
        C1PlayerAvatar npc = C1P0Runtime.activeAvatar(server,name);
        if (npc == null || npc.isRemoved() || !npc.getUUID().equals(id.profileId())
                || !npc.serverLevel().dimension().location().toString().equals(id.dimension())
                || !C1P1CLifecycleRuntime.mayRestore(server,id.profileId()))
            return "S1 terraininspect rejected: NPC not live/alive in registered dimension";
        if (C1P1MotionRuntime.c2Receipt(id.profileId()).active())
            return "S1 terraininspect rejected while C1 movement is active";
        int centerX = net.minecraft.util.Mth.floor(npc.getX());
        int centerY = C2S3SupportY.at(npc.getY());
        int centerZ = net.minecraft.util.Mth.floor(npc.getZ());
        if (Math.abs((long)x-centerX)>C2NavGrid.MAX_RADIUS
                || Math.abs((long)z-centerZ)>C2NavGrid.MAX_RADIUS
                || Math.abs((long)supportY-centerY)>C2NavGrid.VERTICAL_RADIUS)
            return "S1 terraininspect OUT_OF_SCAN (maximum X/Z radius 6 and supportY +/-2)";
        MinecraftProbe probe = new MinecraftProbe(npc.serverLevel(),npc,centerY);
        C2TerrainObservation observation = probe.loaded(x,z)
                ? probe.sampleDetailed(x,supportY,z)
                : new C2TerrainObservation(new C2NavGrid.Cell(x,supportY,z,supportY+1.0,
                    C2NavGrid.Condition.UNLOADED),C2TerrainGeometry.Support.MISSING,
                    C2TerrainObservation.Clearance.NOT_CHECKED,C2TerrainObservation.Risk.UNKNOWN,
                    C2TerrainObservation.Reason.UNKNOWN_CHUNK,0,0);
        return String.format(Locale.ROOT,
                "S1 terraininspect %s support=(%d,%d,%d) footY=%.3f condition=%s support=%s coverage=%.3f headroom=%s risk=%s riskPenalty=%.2f reason=%s shapeBoxes=%d traversal=NOT_VERIFIED (read-only)",
                name,x,supportY,z,observation.cell().footY(),observation.cell().condition(),
                observation.support(),observation.supportedFraction(),observation.clearance(),
                observation.risk(),observation.cell().riskPenalty(),observation.reason(),observation.collisionBoxes());
    }

    /** S2: server-thread exact landing footprint evidence, without scanning an area.
     * This is read-only; any unloaded neighbor needed for the player AABB fails closed.
     */
    static C2TerrainObservation landingAt(C1PlayerAvatar npc, int x, int supportY, int z) {
        ServerLevel level = npc.serverLevel();
        BlockPos center = new BlockPos(x, supportY, z);
        if (supportY < level.getMinBuildHeight() || supportY + 3 >= level.getMaxBuildHeight())
            return null;
        if (!level.getWorldBorder().isWithinBounds(center)) return null;
        // The collision query spans block boundaries: every nearby chunk it may
        // touch must already exist. hasChunkAt is a non-loading query.
        for (int ox = -1; ox <= 1; ox++) for (int oz = -1; oz <= 1; oz++)
            if (!level.hasChunkAt(center.offset(ox, 0, oz))) return null;
        return new MinecraftProbe(level, npc, supportY).sampleDetailed(x, supportY, z);
    }

    private static final class MinecraftProbe implements C2NavGrid.Probe {
        private final ServerLevel level;
        private final C1PlayerAvatar npc;
        private final int centerY;
        private final double halfWidth;
        MinecraftProbe(ServerLevel level, C1PlayerAvatar npc, int centerY) {
            this.level = level;
            this.npc = npc;
            this.centerY = centerY;
            this.halfWidth = npc.getBbWidth() * 0.5 + 0.01;
        }

        @Override public boolean loaded(int x, int z) {
            // Checking a BlockPos does not obtain/generate an unloaded chunk.
            if (Math.abs((long)x) >= 29_999_984 || Math.abs((long)z) >= 29_999_984) return false;
            BlockPos p = new BlockPos(x, centerY, z);
            return level.getWorldBorder().isWithinBounds(p) && level.hasChunkAt(p);
        }

        @Override public C2NavGrid.Cell sample(int x, int supportY, int z) {
            return sampleDetailed(x,supportY,z).cell();
        }

        private C2TerrainObservation sampleDetailed(int x,int supportY,int z) {
            // Never query chunk contents before confirming the column is loaded.
            double defaultY = supportY+1.0;
            if (!loaded(x,z)) return result(x,supportY,z,defaultY,
                    C2NavGrid.Condition.UNLOADED,C2TerrainGeometry.Support.MISSING,
                    C2TerrainObservation.Clearance.NOT_CHECKED,C2TerrainObservation.Risk.UNKNOWN,
                    C2TerrainObservation.Reason.UNKNOWN_CHUNK,0,0);
            if (supportY < level.getMinBuildHeight() || supportY + 3 >= level.getMaxBuildHeight())
                return result(x,supportY,z,defaultY,
                        C2NavGrid.Condition.OBSTRUCTED,C2TerrainGeometry.Support.MISSING,
                        C2TerrainObservation.Clearance.NOT_CHECKED,C2TerrainObservation.Risk.NONE,
                        C2TerrainObservation.Reason.OUT_OF_BUILD_HEIGHT,0,0);
            BlockPos support = new BlockPos(x,supportY,z);
            BlockState floor = level.getBlockState(support);
            var floorRisk=C2BlockTraversalWorld.assess(level,
                    new AABB(x,supportY,z,x+1,supportY+1,z+1));
            if (!floorRisk.passable())
                return result(x,supportY,z,defaultY,
                    C2NavGrid.Condition.HAZARDOUS,C2TerrainGeometry.Support.MISSING,
                    C2TerrainObservation.Clearance.NOT_CHECKED,C2TerrainObservation.Risk.HAZARD,
                    C2TerrainObservation.Reason.HAZARDOUS_BLOCK,0,0);
            VoxelShape shape = floor.getCollisionShape(level,support);
            List<C2TerrainGeometry.Box> boxes = new ArrayList<>();
            for (AABB b: shape.toAabbs())
                boxes.add(new C2TerrainGeometry.Box(b.minX,b.minY,b.minZ,b.maxX,b.maxY,b.maxZ));
            C2TerrainGeometry.Assessment evidence = C2TerrainGeometry.assess(boxes,halfWidth);
            if (evidence.support() == C2TerrainGeometry.Support.MISSING)
                return result(x,supportY,z,defaultY,C2NavGrid.Condition.UNSUPPORTED,
                        evidence.support(),C2TerrainObservation.Clearance.NOT_CHECKED,
                        C2TerrainObservation.Risk.NONE,C2TerrainObservation.Reason.NO_SUPPORT,0,0);
            double footY = supportY+evidence.localHeight();
            if (evidence.support() != C2TerrainGeometry.Support.FULL) {
                // Explicit stair-only exception: the upper half-step normally
                // supports just 50% of the full player footprint. Only a
                // Vanilla StairBlock with >=50% proven support, no hazards and
                // a collision-free player-sized *landing pose* may become an
                // execution candidate. It is NEVER claimed FULL/WALKABLE.
                if (floor.getBlock() instanceof StairBlock
                        && evidence.support() == C2TerrainGeometry.Support.PARTIAL
                        && evidence.coveredFraction()>=0.5
                        && evidence.localHeight()>=0.5 && evidence.localHeight()<=1.0
                        && evidence.collisionBoxes()>=2) {
                    AABB stairLanding=new AABB(x+.5-halfWidth,footY+.001,z+.5-halfWidth,
                            x+.5+halfWidth,footY+npc.getBbHeight(),z+.5+halfWidth);
                    var stairRisk=C2BlockTraversalWorld.assess(level,stairLanding);
                    if (!stairRisk.passable())
                        return result(x,supportY,z,footY,C2NavGrid.Condition.HAZARDOUS,
                                evidence.support(),C2TerrainObservation.Clearance.NOT_CHECKED,
                                C2TerrainObservation.Risk.HAZARD,C2TerrainObservation.Reason.HAZARDOUS_BLOCK,
                                evidence.coveredFraction(),evidence.collisionBoxes());
                    double combinedStairPenalty=Math.max(stairRisk.penalty(),floorRisk.penalty());
                    if (level.noCollision(npc,stairLanding))
                        return result(x,supportY,z,footY,C2NavGrid.Condition.STAIR_TRANSITION,
                                evidence.support(),C2TerrainObservation.Clearance.CLEAR,
                                combinedStairPenalty>0 ? C2TerrainObservation.Risk.AVOIDABLE : C2TerrainObservation.Risk.NONE,
                                C2TerrainObservation.Reason.PARTIAL_FOOTPRINT,
                                evidence.coveredFraction(),evidence.collisionBoxes(),combinedStairPenalty);
                }
                C2TerrainObservation.Reason reason = evidence.localHeight()<.45 || evidence.localHeight()>1.0
                        ? C2TerrainObservation.Reason.UNSUPPORTED_SURFACE_HEIGHT
                        : C2TerrainObservation.Reason.PARTIAL_FOOTPRINT;
                return result(x,supportY,z,footY,C2NavGrid.Condition.PARTIAL_SUPPORT,
                        evidence.support(),C2TerrainObservation.Clearance.NOT_CHECKED,
                        C2TerrainObservation.Risk.NONE,reason,evidence.coveredFraction(),evidence.collisionBoxes());
            }
            AABB standing = new AABB(x+.5-halfWidth,footY+.001,z+.5-halfWidth,
                    x+.5+halfWidth,footY+npc.getBbHeight(),z+.5+halfWidth);
            var standingRisk=C2BlockTraversalWorld.assess(level,standing);
            if (!standingRisk.passable())
                return result(x,supportY,z,footY,C2NavGrid.Condition.HAZARDOUS,
                        evidence.support(),C2TerrainObservation.Clearance.NOT_CHECKED,
                        C2TerrainObservation.Risk.HAZARD,C2TerrainObservation.Reason.HAZARDOUS_BLOCK,
                        evidence.coveredFraction(),evidence.collisionBoxes());
            if (!level.noCollision(npc,standing)) {
                double headStart = Math.min(footY+npc.getBbHeight()-.001, footY+.9);
                AABB upper = new AABB(standing.minX,headStart,standing.minZ,
                        standing.maxX,standing.maxY,standing.maxZ);
                C2TerrainObservation.Reason reason = !level.noCollision(npc,upper)
                        ? C2TerrainObservation.Reason.HEADROOM_BLOCKED
                        : C2TerrainObservation.Reason.COLLISION_BLOCKED;
                return result(x,supportY,z,footY,C2NavGrid.Condition.OBSTRUCTED,
                        evidence.support(),C2TerrainObservation.Clearance.BLOCKED,
                        C2TerrainObservation.Risk.NONE,reason,
                        evidence.coveredFraction(),evidence.collisionBoxes());
            }
            double combinedPenalty=Math.max(floorRisk.penalty(),standingRisk.penalty());
            return result(x,supportY,z,footY,C2NavGrid.Condition.WALKABLE,
                    evidence.support(),C2TerrainObservation.Clearance.CLEAR,
                    combinedPenalty>0 ? C2TerrainObservation.Risk.AVOIDABLE : C2TerrainObservation.Risk.NONE,
                    C2TerrainObservation.Reason.STANDING_SURFACE,
                    evidence.coveredFraction(),evidence.collisionBoxes(),combinedPenalty);
        }

        private C2TerrainObservation result(int x,int y,int z,double footY,
                C2NavGrid.Condition condition,C2TerrainGeometry.Support support,
                C2TerrainObservation.Clearance clearance,C2TerrainObservation.Risk risk,
                C2TerrainObservation.Reason reason,double fraction,int boxes) {
            return result(x,y,z,footY,condition,support,clearance,risk,reason,fraction,boxes,0.0);
        }

        private C2TerrainObservation result(int x,int y,int z,double footY,
                C2NavGrid.Condition condition,C2TerrainGeometry.Support support,
                C2TerrainObservation.Clearance clearance,C2TerrainObservation.Risk risk,
                C2TerrainObservation.Reason reason,double fraction,int boxes,double penalty) {
            return new C2TerrainObservation(new C2NavGrid.Cell(x,y,z,footY,condition,penalty),
                    support,clearance,risk,reason,fraction,boxes);
        }

    }
}
