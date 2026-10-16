package io.github.sirp00m.npcortex;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** P1-A: bounded immutable 3-D terrain observation, independent from Forge and the AI planner.
 * A cell is a *candidate standing surface* at support block (x, supportY, z), not an
 * assertion that a player can reach it. Only WALKABLE cells may become planner vertices.
 * No unloaded/unknown cell is ever implicitly treated as air or safe.
 */
final class C2NavGrid {
    static final int DEFAULT_RADIUS = 4;
    static final int MAX_RADIUS = 6;
    static final int VERTICAL_RADIUS = 2;
    static final int MAX_CANDIDATES = (2 * MAX_RADIUS + 1) * (2 * MAX_RADIUS + 1)
            * (2 * VERTICAL_RADIUS + 1);

    enum Condition { WALKABLE, OBSTRUCTED, PARTIAL_SUPPORT, UNSUPPORTED, HAZARDOUS, UNLOADED }
    record Cell(int x, int supportY, int z, double footY, Condition condition) {
        Cell {
            Objects.requireNonNull(condition, "condition");
            if (!Double.isFinite(footY)) throw new IllegalArgumentException("Non-finite standing height");
        }
    }

    /** loadCheck must NOT force-generate a chunk. sample is called ONLY on loaded columns. */
    interface Probe {
        boolean loaded(int x, int z);
        Cell sample(int x, int supportY, int z);
    }

    record Snapshot(int originX, int originY, int originZ, int radius, List<Cell> cells) {
        Snapshot {
            cells = List.copyOf(cells);
            if (cells.size() > MAX_CANDIDATES) throw new IllegalArgumentException("Unbounded scan");
        }
        long count(Condition condition) {
            return cells.stream().filter(c -> c.condition() == condition).count();
        }
        long columnCount() {
            return (long) (2 * radius + 1) * (2 * radius + 1);
        }
        /** Multiple standing heights may be present in one X/Z column. */
        List<Cell> walkableAt(int x, int z) {
            return cells.stream().filter(c -> c.x() == x && c.z() == z
                    && c.condition() == Condition.WALKABLE).toList();
        }
    }

    static Snapshot scan(int originX, int supportY, int originZ, int radius, Probe probe) {
        if (radius < 1 || radius > MAX_RADIUS) throw new IllegalArgumentException("P1-A radius must be 1.." + MAX_RADIUS);
        Objects.requireNonNull(probe, "probe");
        List<Cell> cells = new ArrayList<>((2 * radius + 1) * (2 * radius + 1) * (2 * VERTICAL_RADIUS + 1));
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int x = safeAdd(originX, dx), z = safeAdd(originZ, dz);
                if (!probe.loaded(x,z)) {
                    // One explicit unknown sentinel per unloaded column, not 5 fake air blocks.
                    cells.add(new Cell(x, supportY, z, supportY + 1.0, Condition.UNLOADED));
                    continue;
                }
                for (int dy = -VERTICAL_RADIUS; dy <= VERTICAL_RADIUS; dy++) {
                    int y = safeAdd(supportY, dy);
                    Cell cell = probe.sample(x, y, z);
                    if (cell == null) throw new IllegalArgumentException("P1-A probe returned null cell");
                    if (cell.x() != x || cell.supportY() != y || cell.z() != z)
                        throw new IllegalArgumentException("Probe returned coordinates for another cell");
                    if (cell.condition() == Condition.UNLOADED)
                        throw new IllegalArgumentException("Loaded probe cannot silently return unloaded");
                    cells.add(cell);
                }
            }
        }
        return new Snapshot(originX, supportY, originZ, radius, cells);
    }

    private static int safeAdd(int base, int delta) {
        try { return Math.addExact(base, delta); }
        catch (ArithmeticException ex) { throw new IllegalArgumentException("P1-A scan coordinates overflow",ex); }
    }

    private C2NavGrid() {}
}
