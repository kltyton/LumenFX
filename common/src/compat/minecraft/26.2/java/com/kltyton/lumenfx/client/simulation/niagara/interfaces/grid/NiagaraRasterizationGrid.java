package com.kltyton.lumenfx.client.simulation.niagara.interfaces.grid;

import com.kltyton.lumenfx.effect.niagara.grid.RasterizationGridDefinition;
import com.kltyton.lumenfx.effect.niagara.grid.VolumeGridLayout;
import com.kltyton.lumenfx.math.SourceFloatMath;
import java.util.function.IntBinaryOperator;

/** CPU-owned instance parameters and the post-simulation copy consumed by the render proxy. */
public final class NiagaraRasterizationGrid {
    public static final int DEFAULT_MAXIMUM_CELLS = 1024 * 1024 * 1024;
    public static final String MAXIMUM_CELLS_VARIABLE = "fx.MaxNiagaraRasterizationGridCells";
    public enum Function { GetNumCells, SetNumCells, SetNumAttributes, SetFloatResetValue }
    @FunctionalInterface public interface Output { void set(int word, int row, int value); }

    public record Snapshot(VolumeGridLayout layout, float precision, int resetValue, boolean clearOutput, long revision) {
        public boolean hasStorage(int limit) {
            return layout.fits() && layout.x() <= limit / layout.y() / layout.z() / layout.attributes();
        }
    }

    private final RasterizationGridDefinition definition;
    private int x, y, z, attributes, resetValue;
    private boolean needsReallocation;
    private Snapshot renderState;

    public NiagaraRasterizationGrid(RasterizationGridDefinition definition, int maximum) {
        this.definition = definition;
        x = Math.max(definition.x(), 1);
        y = Math.max(definition.y(), 1);
        z = Math.max(definition.z(), 1);
        if (x > maximum / y / z)
            throw new IllegalArgumentException("Niagara Rasterization Grid3D exceeds the active limit of " + maximum + " cells");
        attributes = definition.attributes();
        resetValue = definition.resetValue();
        renderState = renderState(0);
    }

    public synchronized Snapshot capture() {
        if (needsReallocation && x > 0 && y > 0 && z > 0 && attributes > 0) {
            renderState = renderState(renderState.revision() + 1);
            needsReallocation = false;
        }
        return renderState;
    }

    public synchronized void execute(Function function, int count, IntBinaryOperator input, Output output) {
        if (function == Function.GetNumCells) {
            for (int row = 0; row < count; row++) {
                output.set(0, row, x);
                output.set(1, row, y);
                output.set(2, row, z);
            }
            return;
        }
        boolean success = count == 1 && (function == Function.SetFloatResetValue
                || definition.x() >= 0 && definition.y() >= 0 && definition.z() >= 0);
        for (int row = 0; row < count; row++) {
            if (success) switch (function) {
                case SetNumCells -> {
                    int nextX = input.applyAsInt(0, row), nextY = input.applyAsInt(1, row), nextZ = input.applyAsInt(2, row);
                    needsReallocation = x != nextX || y != nextY || z != nextZ;
                    x = nextX; y = nextY; z = nextZ;
                }
                case SetNumAttributes -> {
                    int next = input.applyAsInt(0, row);
                    needsReallocation = attributes != next;
                    attributes = next;
                }
                case SetFloatResetValue ->
                    resetValue = SourceFloatMath.floorToInt(Float.intBitsToFloat(input.applyAsInt(0, row)) * definition.precision());
                case GetNumCells -> throw new IllegalStateException("GetNumCells has no setter result");
            }
            output.set(0, row, success ? -1 : 0);
        }
    }

    private Snapshot renderState(long revision) {
        return new Snapshot(VolumeGridLayout.tiled(x, y, z, attributes), definition.precision(), resetValue,
                definition.clearOutput(), revision);
    }
}
