package com.kltyton.lumenfx.client.simulation.niagara.interfaces.grid;

import com.kltyton.lumenfx.effect.niagara.grid.NeighborGridDefinition;
import com.kltyton.lumenfx.effect.niagara.grid.NeighborGridDefinition.Resolution;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraPlatformEnvironment;
import java.util.function.IntBinaryOperator;

/** Per-system data for one physical Neighbor Grid3D interface. */
public final class NiagaraNeighborGrid {
    public static final int DEFAULT_MAXIMUM_ELEMENTS = 128 * 128 * 128 * 64;
    private static final String MAXIMUM_CELLS_VARIABLE = "fx.MaxNiagaraNeighborGridCells";
    public enum Function { GetWorldBBoxSize, GetNumCells, MaxNeighborsPerCell, SetNumCells }
    @FunctionalInterface public interface Output { void set(int word, int row, int value); }

    public record Snapshot(int x, int y, int z, int neighbors, double worldX, double worldY, double worldZ, long revision) {
        public int cells() { return Math.multiplyExact(Math.multiplyExact(x, y), z); }
        public int elements() { return Math.multiplyExact(cells(), neighbors); }
    }

    private final NeighborGridDefinition source;
    private final int deviceMaximum;
    private volatile Snapshot state;

    public NiagaraNeighborGrid(NeighborGridDefinition source, int deviceMaximum, NiagaraPlatformEnvironment environment) {
        this.source = source;
        this.deviceMaximum = deviceMaximum;
        state = initial(source);
        int maximum = maximumElements(environment);
        if (!fits(state.x(), state.y(), state.z(), state.neighbors(), maximum))
            throw new IllegalArgumentException("Niagara Neighbor Grid3D exceeds the active buffer limit of " + maximum + " elements");
    }

    public static long sourceMaximum(NiagaraPlatformEnvironment environment) {
        var configured = environment.console(MAXIMUM_CELLS_VARIABLE);
        return configured == null ? DEFAULT_MAXIMUM_ELEMENTS : Integer.toUnsignedLong(configured.bits());
    }

    private int maximumElements(NiagaraPlatformEnvironment environment) {
        return (int)Math.min(deviceMaximum, sourceMaximum(environment));
    }

    public Snapshot snapshot() { return state; }

    public void execute(Function function, int count, NiagaraPlatformEnvironment environment, IntBinaryOperator in, Output out) {
        var value = state;
        if (function == Function.SetNumCells) {
            int maximum = maximumElements(environment);
            boolean permitted = count == 1 && source.x() >= 0 && source.y() >= 0 && source.z() >= 0 && source.neighbors() >= 0;
            for (int row = 0; row < count; row++) {
                int x = in.applyAsInt(0, row), y = in.applyAsInt(1, row), z = in.applyAsInt(2, row), n = in.applyAsInt(3, row);
                out.set(0, row, permitted && resize(x, y, z, n, maximum) ? -1 : 0);
            }
            return;
        }
        for (int row = 0; row < count; row++) switch (function) {
            case GetWorldBBoxSize -> {
                out.set(0, row, Float.floatToRawIntBits((float)source.worldX()));
                out.set(1, row, Float.floatToRawIntBits((float)source.worldY()));
                out.set(2, row, Float.floatToRawIntBits((float)source.worldZ()));
            }
            case GetNumCells -> {
                out.set(0, row, value.x());
                out.set(1, row, value.y());
                out.set(2, row, value.z());
            }
            case MaxNeighborsPerCell -> out.set(0, row, value.neighbors());
            case SetNumCells -> throw new IllegalStateException("Neighbor Grid3D resize requires the scalar path");
        };
    }

    private synchronized boolean resize(int x, int y, int z, int neighbors, int maximum) {
        if (!fits(x, y, z, neighbors, maximum)) return false;
        var prior = state;
        if (x != prior.x() || y != prior.y() || z != prior.z() || neighbors != prior.neighbors())
            state = new Snapshot(x, y, z, neighbors, prior.worldX(), prior.worldY(), prior.worldZ(), prior.revision() + 1);
        return true;
    }

    private static boolean fits(int x, int y, int z, int neighbors, int maximum) {
        if (x <= 0 || y <= 0 || z <= 0 || neighbors <= 0) return false;
        return x <= maximum / y / z / neighbors;
    }

    private static Snapshot initial(NeighborGridDefinition source) {
        double worldX = source.worldX(), worldY = source.worldY(), worldZ = source.worldZ();
        int x = source.x(), y = source.y(), z = source.z();
        if (source.resolution() != Resolution.INDEPENDENT) {
            double cell = source.resolution() == Resolution.MAXAXIS
                    ? Math.max(Math.max(worldX, worldY), worldZ) / source.maxAxis() : source.cellSize();
            if (!(cell > 0) || !Double.isFinite(cell)) throw new IllegalArgumentException("Invalid Niagara Neighbor Grid3D cell size");
            x = cells(worldX, cell, worldX < worldY && worldY > worldZ || worldX < worldZ && worldZ > worldY);
            y = cells(worldY, cell, worldY < worldX && worldX > worldZ || worldY < worldZ && worldZ > worldX);
            z = cells(worldZ, cell, worldZ < worldX && worldX > worldY || worldZ < worldY && worldY > worldX);
            worldX = x * cell;
            worldY = y * cell;
            worldZ = z * cell;
        }
        return new Snapshot(Math.max(x, 1), Math.max(y, 1), Math.max(z, 1), source.neighbors(), worldX, worldY, worldZ,
                0);
    }

    private static int cells(double world, double cell, boolean pad) {
        double whole = Math.floor(world / cell);
        if (whole < Integer.MIN_VALUE || whole >= Integer.MAX_VALUE || !Double.isFinite(whole))
            throw new IllegalArgumentException("Niagara Neighbor Grid3D axis exceeds the native integer range");
        int count = (int)whole;
        return pad && Math.abs(cell * count - world) > 1e-8 ? count + 1 : count;
    }
}
