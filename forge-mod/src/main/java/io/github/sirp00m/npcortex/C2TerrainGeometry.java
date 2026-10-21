package io.github.sirp00m.npcortex;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/** Sprint 1: deterministic, dependency-free support geometry used by the Forge sensor.
 * Does not infer traversability (STEP/JUMP/DROP) from a standing surface.
 */
final class C2TerrainGeometry {
    static final double EPS = 1.0e-6;
    enum Support { FULL, PARTIAL, MISSING }

    /** Box in support-block-local coordinates (VoxelShape.toAabbs). */
    record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        Box {
            if (!Double.isFinite(minX) || !Double.isFinite(minY) || !Double.isFinite(minZ)
                    || !Double.isFinite(maxX) || !Double.isFinite(maxY) || !Double.isFinite(maxZ)
                    || minX > maxX || minY > maxY || minZ > maxZ)
                throw new IllegalArgumentException("Invalid collision box");
        }
    }
    record Assessment(double localHeight, Support support, double coveredFraction, int collisionBoxes) {
        Assessment {
            if (!Double.isFinite(localHeight) || !Double.isFinite(coveredFraction)
                    || coveredFraction < 0 || coveredFraction > 1 || collisionBoxes < 0)
                throw new IllegalArgumentException("Invalid terrain assessment");
            Objects.requireNonNull(support);
        }
    }

    /** Evaluate the highest shape surface using the WHOLE player footprint.
     * Multiple same-height boxes may jointly support the full footprint. A higher
     * unsupported stair ledge is never silently replaced with its lower tread.
     */
    static Assessment assess(List<Box> source, double halfWidth) {
        Objects.requireNonNull(source);
        if (!Double.isFinite(halfWidth) || halfWidth <= 0 || halfWidth >= .5)
            throw new IllegalArgumentException("Invalid player half-width");
        List<Box> boxes = List.copyOf(source);
        if (boxes.isEmpty()) return new Assessment(0,Support.MISSING,0,0);
        double height = Double.NEGATIVE_INFINITY;
        for (Box box : boxes) {
            Objects.requireNonNull(box);
            height = Math.max(height,box.maxY());
        }
        double low = .5-halfWidth, high = .5+halfWidth;
        final double topHeight = height;
        List<Box> top = boxes.stream().filter(b -> Math.abs(b.maxY()-topHeight) <= EPS
                && b.maxX() > low && b.minX() < high && b.maxZ() > low && b.minZ() < high).toList();
        double fraction = coverage(top,low,high);
        Support support = (height >= .45-EPS && height <= 1.0+EPS && fraction >= 1.0-EPS)
                ? Support.FULL : Support.PARTIAL;
        return new Assessment(height,support,fraction,boxes.size());
    }

    /** Exact rectangular-union coverage by partitioning at all box boundaries.
     * Avoids single-AABB false negatives and sampling-resolution false positives.
     */
    private static double coverage(List<Box> boxes, double lo, double hi) {
        TreeSet<Double> xs = new TreeSet<>(), zs = new TreeSet<>();
        xs.add(lo); xs.add(hi); zs.add(lo); zs.add(hi);
        for (Box b: boxes) {
            xs.add(Math.max(lo,Math.min(hi,b.minX())));
            xs.add(Math.max(lo,Math.min(hi,b.maxX())));
            zs.add(Math.max(lo,Math.min(hi,b.minZ())));
            zs.add(Math.max(lo,Math.min(hi,b.maxZ())));
        }
        List<Double> xCuts = new ArrayList<>(xs), zCuts = new ArrayList<>(zs);
        double area = 0;
        for (int i=0;i<xCuts.size()-1;i++) {
            double x0=xCuts.get(i), x1=xCuts.get(i+1);
            if (x1-x0 <= EPS) continue;
            double mx=(x0+x1)*.5;
            for (int j=0;j<zCuts.size()-1;j++) {
                double z0=zCuts.get(j),z1=zCuts.get(j+1);
                if (z1-z0 <= EPS) continue;
                double mz=(z0+z1)*.5;
                for (Box b: boxes) {
                    if (mx >= b.minX()-EPS && mx <= b.maxX()+EPS
                            && mz >= b.minZ()-EPS && mz <= b.maxZ()+EPS) {
                        area+=(x1-x0)*(z1-z0);
                        break;
                    }
                }
            }
        }
        return Math.min(1,Math.max(0,area/((hi-lo)*(hi-lo))));
    }

    private C2TerrainGeometry() {}
}
