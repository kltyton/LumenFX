package com.kltyton.lumenfx.client.simulation.niagara.interfaces.grid;

import com.kltyton.lumenfx.effect.niagara.grid.Grid2DCollectionDefinition;
import com.kltyton.lumenfx.effect.niagara.grid.GridBufferFormat;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraPlatformEnvironment;
import com.kltyton.lumenfx.math.SourceFloatMath;
import java.util.HashMap;
import java.util.Map;
import java.util.function.IntBinaryOperator;
import org.jspecify.annotations.Nullable;

/** Grid2D's per-instance VM geometry and the parameters published to its array-texture proxy. */
public final class NiagaraGrid2DCollection {
    private static final String RESOLUTION_MULTIPLIER = "fx.Niagara.Grid2D.ResolutionMultiplier";
    private static final String FORMAT_OVERRIDE = "fx.Niagara.Grid2D.OverrideFormat";

    public enum Function {
        GetNumCells, GetWorldBBoxSize, GetCellSize, SetNumCells,
        GetVector4AttributeIndex, GetVectorAttributeIndex, GetVector2DAttributeIndex, GetFloatAttributeIndex
    }
    @FunctionalInterface public interface Output { void set(int word, int row, int value); }

    private record Extent(int x, int y) {
        Extent scaled(NiagaraPlatformEnvironment environment) {
            var value = environment.console(RESOLUTION_MULTIPLIER);
            if (value == null) return this;
            float scale = Float.intBitsToFloat(value.bits());
            if (Math.abs(scale - 1f) <= 1e-8f) return this;
            return new Extent(Math.max(1, SourceFloatMath.truncToInt(x * scale)), Math.max(1, SourceFloatMath.truncToInt(y * scale)));
        }
    }
    private record Geometry(Extent cells, double cellX, double cellY) { }
    public record Attribute(int channels, int offset) { }
    public record Snapshot(int x, int y, double cellX, double cellY, double worldX, double worldY, int layers,
                           GridBufferFormat format, boolean clearOutput, Map<String, Attribute> attributes, long revision) {
        public int index(String name, int channels) {
            var attribute = attributes.get(SourceNames.canonical(name));
            return attribute != null && attribute.channels() == channels ? attribute.offset() : -1;
        }
    }

    private final Grid2DCollectionDefinition definition;
    private final GridBufferFormat format;
    private final double worldX, worldY;
    private final Extent authoredCells;
    private volatile Geometry state;
    private volatile Snapshot renderState;
    private volatile @Nullable NiagaraGrid2DCollection readerTarget;
    private boolean needsReallocation;

    NiagaraGrid2DCollection(Grid2DCollectionDefinition definition, NiagaraPlatformEnvironment environment) {
        this.definition = definition;
        var override = environment.console(FORMAT_OVERRIDE);
        int ordinal = override == null ? -1 : override.bits();
        format = ordinal >= 0 && ordinal < GridBufferFormat.values().length ? GridBufferFormat.values()[ordinal] : definition.format();
        var authored = new Extent(definition.x(), definition.y());
        if (definition.reader()) {
            authoredCells = authored;
            worldX = 0;
            worldY = 0;
            state = new Geometry(new Extent(0, 0), 0, 0);
            renderState = new Snapshot(0, 0, 0, 0, 0, 0, 0, format, true, Map.of(), 0);
            return;
        }
        double x = definition.worldX(), y = definition.worldY();
        var cells = authored.scaled(environment);
        if (definition.sizeFromMaxAxis()) {
            double size = Math.max(x, y) / definition.maxAxis();
            if (!(size > 0) || !Double.isFinite(size)) throw new IllegalArgumentException("Invalid Niagara Grid2D Collection cell size");
            cells = new Extent(cells(x, y, size), cells(y, x, size));
            x = cells.x() * size;
            y = cells.y() * size;
            authored = cells;
        }
        authoredCells = authored;
        worldX = x;
        worldY = y;
        state = geometry(cells);
        renderState = snapshot(state, Math.max(1, definition.unnamedAttributes()), Map.of(), 0);
    }

    private static int cells(double axis, double other, double cellSize) {
        int count = (int)Math.floor(axis / cellSize);
        return other > axis && Math.abs(cellSize * count - axis) > 1e-8 ? count + 1 : count;
    }

    private Geometry geometry(Extent cells) {
        return new Geometry(cells, worldX / cells.x(), worldY / cells.y());
    }

    private Snapshot snapshot(Geometry value, int layers, Map<String, Attribute> attributes, long revision) {
        return new Snapshot(value.cells().x(), value.cells().y(), value.cellX(), value.cellY(), worldX, worldY,
                layers, format, definition.clearOutput(), attributes, revision);
    }

    synchronized void configureAttributes(Map<String, Integer> attributes) {
        if (definition.reader()) return;
        var offsets = new HashMap<String, Attribute>();
        int nextOffset = definition.unnamedAttributes();
        for (var entry : attributes.entrySet()) {
            offsets.put(entry.getKey(), new Attribute(entry.getValue(), nextOffset));
            nextOffset = Math.addExact(nextOffset, entry.getValue());
        }
        if (!renderState.attributes().equals(offsets))
            renderState = snapshot(state, Math.max(1, nextOffset), Map.copyOf(offsets), renderState.revision() + 1);
    }

    Grid2DCollectionDefinition definition() { return definition; }
    Snapshot capture() { return renderState; }
    void readerTarget(@Nullable NiagaraGrid2DCollection target) { readerTarget = target; }

    public void execute(Function function, int count, NiagaraPlatformEnvironment environment, String attribute,
                        IntBinaryOperator input, Output output) {
        if (function == Function.SetNumCells) {
            boolean single = count == 1 && authoredCells.x() >= 0 && authoredCells.y() >= 0;
            for (int row = 0; row < count; row++) {
                boolean apply = single && input.applyAsInt(2, row) != 0;
                if (apply) resize(new Extent(input.applyAsInt(0, row), input.applyAsInt(1, row)).scaled(environment));
                output.set(0, row, apply ? -1 : 0);
            }
            return;
        }
        var value = state;
        var target = readerTarget;
        var cells = target == null ? value.cells() : target.state.cells();
        var snapshot = renderState;
        for (int row = 0; row < count; row++) switch (function) {
            case GetNumCells -> { output.set(0, row, cells.x()); output.set(1, row, cells.y()); }
            case GetWorldBBoxSize -> { output.set(0, row, bits(worldX)); output.set(1, row, bits(worldY)); }
            case GetCellSize -> { output.set(0, row, bits(value.cellX())); output.set(1, row, bits(value.cellY())); }
            case GetVector4AttributeIndex -> output.set(0, row, snapshot.index(attribute, 4));
            case GetVectorAttributeIndex -> output.set(0, row, snapshot.index(attribute, 3));
            case GetVector2DAttributeIndex -> output.set(0, row, snapshot.index(attribute, 2));
            case GetFloatAttributeIndex -> output.set(0, row, snapshot.index(attribute, 1));
            case SetNumCells -> throw new IllegalStateException("SetNumCells requires the scalar path");
        }
    }

    private synchronized void resize(Extent next) {
        needsReallocation = !next.equals(state.cells());
        if (needsReallocation) state = new Geometry(next, state.cellX(), state.cellY());
    }

    synchronized void postSimulate() {
        var cells = state.cells();
        if (!needsReallocation || cells.x() <= 0 || cells.y() <= 0) return;
        needsReallocation = false;
        state = geometry(cells);
        if (!definition.reader())
            renderState = snapshot(state, renderState.layers(), renderState.attributes(), renderState.revision() + 1);
    }

    private static int bits(double value) { return Float.floatToRawIntBits((float)value); }
}
