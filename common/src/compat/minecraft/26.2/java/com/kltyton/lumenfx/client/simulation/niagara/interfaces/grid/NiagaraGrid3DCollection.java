package com.kltyton.lumenfx.client.simulation.niagara.interfaces.grid;

import com.kltyton.lumenfx.effect.niagara.grid.Grid3DCollectionDefinition;
import com.kltyton.lumenfx.effect.niagara.grid.GridBufferFormat;
import com.kltyton.lumenfx.effect.niagara.grid.Grid3DCollectionDefinition.Resolution;
import com.kltyton.lumenfx.effect.niagara.grid.VolumeGridLayout;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraPlatformEnvironment;
import com.kltyton.lumenfx.math.SourceFloatMath;
import java.util.function.IntBinaryOperator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Per-instance resolution and VM coordinates for Niagara Grid3D Collection. */
public final class NiagaraGrid3DCollection {
    private static final String RESOLUTION_MULTIPLIER = "fx.Niagara.Grid3D.ResolutionMultiplier";
    private static final String PACKED_GRID = "fx.Niagara.Grid3D.UseRGBAGrid";
    private static final String FORMAT_OVERRIDE = "fx.Niagara.Grid3D.OverrideFormat";
    public enum Function {
        GetNumCells, GetWorldBBoxSize, GetCellSize, UnitToFloatIndex, SetNumCells,
        GetVector4AttributeIndex, GetVectorAttributeIndex, GetVector2DAttributeIndex, GetFloatAttributeIndex
    }
    @FunctionalInterface public interface Output { void set(int word, int row, int value); }

    private record Extent(int x, int y, int z) {
        Extent scaled(float multiplier) {
            if (Math.abs(multiplier - 1f) <= 1e-8f) return this;
            return new Extent(scale(x, multiplier), scale(y, multiplier), scale(z, multiplier));
        }
        private static int scale(int cells, float multiplier) {
            return Math.max(1, SourceFloatMath.truncToInt(cells * multiplier));
        }
    }
    public record Size(double x, double y, double z) {
        Size divide(Extent cells) { return new Size(x / cells.x(), y / cells.y(), z / cells.z()); }
    }
    public record Attribute(int components, int offset) { }
    public record Snapshot(VolumeGridLayout layout, Size bounds, Size cellSize, GridBufferFormat format,
                           int packedComponents, int namedChannels, boolean clearOutput, Map<String, Attribute> attributes,
                           long revision) {
        public int index(String name, int width) {
            var attribute = attributes.get(SourceNames.canonical(name));
            return attribute != null && attribute.components() == width ? attribute.offset() : -1;
        }
    }
    private record State(Extent cells, Size cellSize) { }

    private final Grid3DCollectionDefinition definition;
    private final Size bounds;
    private final GridBufferFormat format;
    private final boolean packedGridEnabled;
    private Map<String, Attribute> attributes = Map.of();
    private int totalChannels;
    private volatile Snapshot renderState;
    private volatile @Nullable NiagaraGrid3DCollection readerTarget;
    private volatile State state;
    private boolean needsReallocation;

    public NiagaraGrid3DCollection(Grid3DCollectionDefinition definition, NiagaraPlatformEnvironment environment) {
        this.definition = definition;
        var globalFormat = environment.console(FORMAT_OVERRIDE);
        int formatIndex = globalFormat == null ? -1 : globalFormat.bits();
        format = formatIndex >= 0 && formatIndex < GridBufferFormat.values().length
                ? GridBufferFormat.values()[formatIndex] : definition.bufferFormat();
        var packed = environment.console(PACKED_GRID);
        packedGridEnabled = packed == null || packed.bits() != 0;
        totalChannels = definition.reader() ? 0 : definition.unnamedAttributes();
        if (definition.reader()) {
            bounds = new Size(0, 0, 0);
            state = new State(new Extent(0, 0, 0), bounds);
            renderState = new Snapshot(new VolumeGridLayout(0, 0, 0, 0, 0, 0, 0), bounds, bounds, format,
                    0, 0, false, Map.of(), 0);
            return;
        }
        var authored = new Size(definition.worldX(), definition.worldY(), definition.worldZ());
        Extent cells;
        Size cellSize;
        if (definition.resolution() == Resolution.INDEPENDENT) {
            cells = new Extent(definition.x(), definition.y(), definition.z());
            bounds = authored;
            cellSize = bounds.divide(cells);
        } else {
            double cell = definition.resolution() == Resolution.MAXAXIS
                    ? Math.max(authored.x(), Math.max(authored.y(), authored.z())) / definition.maxAxis() : definition.cellSize();
            if (!(cell > 0.0) || !Double.isFinite(cell)) throw new IllegalArgumentException("Invalid Niagara Grid3D Collection cell size");
            cells = new Extent(cells(authored.x(), authored.y(), authored.z(), cell),
                    cells(authored.y(), authored.x(), authored.z(), cell), cells(authored.z(), authored.x(), authored.y(), cell));
            bounds = new Size(cells.x() * cell, cells.y() * cell, cells.z() * cell);
            cellSize = new Size(cell, cell, cell);
        }
        if (cells.x() <= 0 || cells.y() <= 0 || cells.z() <= 0)
            throw new IllegalArgumentException("Niagara Grid3D Collection has a nonpositive resolution");
        Extent resolution = cells.scaled(multiplier(environment));
        state = new State(resolution, cellSize);
        renderState = renderSnapshot(state, 0);
    }

    public synchronized void configureAttributes(Map<String, Integer> named) {
        var columns = new LinkedHashMap<String, Attribute>();
        int offset = definition.reader() ? 0 : definition.unnamedAttributes();
        for (var entry : named.entrySet()) {
            String name = SourceNames.canonical(entry.getKey());
            int width = entry.getValue();
            if (columns.containsKey(name)) continue;
            columns.put(name, new Attribute(width, offset));
            offset = Math.addExact(offset, width);
        }
        var next = Map.copyOf(columns);
        if (attributes.equals(next)) return;
        if (definition.reader()) {
            attributes = next;
            totalChannels = offset;
            return;
        }
        var previous = attributes;
        int previousChannels = totalChannels;
        attributes = next;
        totalChannels = offset;
        try {
            renderState = renderSnapshot(state, renderState.revision() + 1);
        } catch (RuntimeException | Error failure) {
            attributes = previous;
            totalChannels = previousChannels;
            throw failure;
        }
    }

    public synchronized Snapshot capture() { return renderState; }
    public String outputBinding() { return definition.outputBinding(); }
    public Grid3DCollectionDefinition definition() { return definition; }
    public void readerTarget(NiagaraGrid3DCollection target) {
        boolean expectsPacked = packedGridEnabled && attributes.size() == 1 && totalChannels <= 4;
        if (totalChannels > 0 && expectsPacked != (target.capture().packedComponents() > 0))
            throw new IllegalStateException("Niagara Grid3D Collection Reader and source disagree on packed storage");
        readerTarget = target;
    }

    public void execute(Function function, int count, NiagaraPlatformEnvironment environment, String attribute,
                        IntBinaryOperator in, Output out) {
        if (function == Function.SetNumCells) {
            boolean permitted = count == 1 && definition.x() >= 0 && definition.y() >= 0 && definition.z() >= 0;
            for (int row = 0; row < count; row++) {
                if (permitted) resize(in.applyAsInt(0, row), in.applyAsInt(1, row), in.applyAsInt(2, row), multiplier(environment));
                out.set(0, row, permitted ? -1 : 0);
            }
            return;
        }
        State active = state;
        Extent cells = active.cells();
        var target = readerTarget;
        var reported = target == null ? cells : target.state.cells();
        for (int row = 0; row < count; row++) switch (function) {
            case GetNumCells -> {
                out.set(0, row, reported.x());
                out.set(1, row, reported.y());
                out.set(2, row, reported.z());
            }
            case GetWorldBBoxSize -> size(out, row, bounds);
            case GetCellSize -> size(out, row, active.cellSize());
            case UnitToFloatIndex -> {
                out.set(0, row, bits(Float.intBitsToFloat(in.applyAsInt(0, row)) * cells.x() - 0.5f));
                out.set(1, row, bits(Float.intBitsToFloat(in.applyAsInt(1, row)) * cells.y() - 0.5f));
                out.set(2, row, bits(Float.intBitsToFloat(in.applyAsInt(2, row)) * cells.z() - 0.5f));
            }
            case GetVector4AttributeIndex -> out.set(0, row, attributeIndex(attribute, 4));
            case GetVectorAttributeIndex -> out.set(0, row, attributeIndex(attribute, 3));
            case GetVector2DAttributeIndex -> out.set(0, row, attributeIndex(attribute, 2));
            case GetFloatAttributeIndex -> out.set(0, row, attributeIndex(attribute, 1));
            case SetNumCells -> throw new IllegalStateException("SetNumCells requires the scalar path");
        }
    }

    private int attributeIndex(String name, int width) {
        return definition.reader() ? -1 : renderState.index(name, width);
    }

    private synchronized void resize(int x, int y, int z, float multiplier) {
        var next = new Extent(Math.max(1, x), Math.max(1, y), Math.max(1, z)).scaled(multiplier);
        needsReallocation = !next.equals(state.cells());
        if (needsReallocation) state = new State(next, state.cellSize());
    }

    public synchronized void postSimulate() {
        if (!needsReallocation) return;
        Extent cells = state.cells();
        state = new State(cells, bounds.divide(cells));
        needsReallocation = false;
        var layout = VolumeGridLayout.tiled(cells.x(), cells.y(), cells.z(), totalChannels);
        if (!definition.reader() && (renderState.packedComponents() > 0 || layout.fits()))
            renderState = renderSnapshot(state, renderState.revision() + 1);
    }

    private Snapshot renderSnapshot(State value, long revision) {
        if (totalChannels == 0) {
            var zero = new Size(0, 0, 0);
            return new Snapshot(new VolumeGridLayout(1, 1, 1, 0, 1, 1, 1), zero, zero, format,
                    0, 0, true, Map.of(), revision);
        }
        Extent cells = value.cells();
        boolean packed = packedGridEnabled && attributes.size() == 1 && totalChannels <= 4;
        if (packed && definition.unnamedAttributes() > 0)
            throw new IllegalArgumentException("Niagara packed Grid3D Collection cannot combine named and unnamed channels");
        var layout = packed ? new VolumeGridLayout(cells.x(), cells.y(), cells.z(), totalChannels, 1, 1, 1)
                : VolumeGridLayout.tiled(cells.x(), cells.y(), cells.z(), totalChannels);
        if (!packed && totalChannels > 0 && !layout.fits())
            throw new IllegalArgumentException("Niagara Grid3D Collection attributes exceed the tiled volume extent");
        return new Snapshot(layout, bounds, value.cellSize(), format, packed ? totalChannels : 0,
                totalChannels - definition.unnamedAttributes(), definition.clearOutput(), attributes, revision);
    }

    private static void size(Output out, int row, Size value) {
        out.set(0, row, bits((float)value.x()));
        out.set(1, row, bits((float)value.y()));
        out.set(2, row, bits((float)value.z()));
    }

    private static int cells(double axis, double otherA, double otherB, double size) {
        int count = (int)Math.floor(axis / size);
        boolean pad = otherA > axis && otherA > otherB || otherB > axis && otherB > otherA;
        return pad && Math.abs(size * count - axis) > 1e-8 ? count + 1 : count;
    }

    private static int bits(float value) { return Float.floatToRawIntBits(value); }

    private static float multiplier(NiagaraPlatformEnvironment environment) {
        var value = environment.console(RESOLUTION_MULTIPLIER);
        return value == null ? 1f : Float.intBitsToFloat(value.bits());
    }
}
