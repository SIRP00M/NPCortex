package io.github.sirp00m.npcortex;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.MinecraftServer;

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
        int y = net.minecraft.util.Mth.floor(npc.getY()) - 1;
        int z = net.minecraft.util.Mth.floor(npc.getZ());
        return C2NavGrid.scan(x, y, z, radius, new MinecraftProbe(level, npc, y));
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
            double defaultY = supportY + 1.0;
            if (supportY < level.getMinBuildHeight() || supportY + 3 >= level.getMaxBuildHeight())
                return new C2NavGrid.Cell(x,supportY,z,defaultY,C2NavGrid.Condition.OBSTRUCTED);
            BlockPos support = new BlockPos(x, supportY, z);
            BlockState floor = level.getBlockState(support);
            if (hazard(floor)) return new C2NavGrid.Cell(x,supportY,z,defaultY,C2NavGrid.Condition.HAZARDOUS);
            VoxelShape shape = floor.getCollisionShape(level,support);
            if (shape.isEmpty()) return new C2NavGrid.Cell(x,supportY,z,defaultY,C2NavGrid.Condition.UNSUPPORTED);

            double surface = shape.max(net.minecraft.core.Direction.Axis.Y);
            if (!Double.isFinite(surface) || surface < 0.45 || surface > 1.0)
                return new C2NavGrid.Cell(x,supportY,z,defaultY,C2NavGrid.Condition.PARTIAL_SUPPORT);
            double footY = supportY + surface;
            if (!supportsFootprint(shape, surface))
                return new C2NavGrid.Cell(x,supportY,z,footY,C2NavGrid.Condition.PARTIAL_SUPPORT);

            BlockState feet = level.getBlockState(support.above());
            BlockState head = level.getBlockState(support.above(2));
            if (hazard(feet) || hazard(head))
                return new C2NavGrid.Cell(x,supportY,z,footY,C2NavGrid.Condition.HAZARDOUS);
            // Actual bounding-box collision including partial slabs, stairs and other entities.
            AABB standing = new AABB(x+0.5-halfWidth,footY+0.001,z+0.5-halfWidth,
                    x+0.5+halfWidth,footY+npc.getBbHeight(),z+0.5+halfWidth);
            if (!level.noCollision(npc,standing))
                return new C2NavGrid.Cell(x,supportY,z,footY,C2NavGrid.Condition.OBSTRUCTED);
            return new C2NavGrid.Cell(x,supportY,z,footY,C2NavGrid.Condition.WALKABLE);
        }

        private boolean supportsFootprint(VoxelShape shape, double top) {
            // Conservative: one solid collision box must carry the whole player footprint.
            // Stair/sloped partial shapes may be classified as PARTIAL rather than unsafe WALKABLE.
            double min = 0.5-halfWidth, max = 0.5+halfWidth;
            for (AABB box : shape.toAabbs()) {
                if (Math.abs(box.maxY-top) < 0.001 && box.minX <= min && box.maxX >= max
                        && box.minZ <= min && box.maxZ >= max) return true;
            }
            return false;
        }

        private static boolean hazard(BlockState s) {
            FluidState fluid = s.getFluidState();
            return !fluid.isEmpty() || s.is(Blocks.CACTUS) || s.is(Blocks.MAGMA_BLOCK)
                    || s.is(Blocks.CAMPFIRE) || s.is(Blocks.SOUL_CAMPFIRE)
                    || s.is(Blocks.FIRE) || s.is(Blocks.SOUL_FIRE)
                    || s.is(Blocks.POWDER_SNOW) || s.is(Blocks.SWEET_BERRY_BUSH)
                    || s.is(Blocks.WITHER_ROSE);
        }
    }
}
