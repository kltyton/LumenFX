package com.kltyton.lumenfx.client.render.niagara.gpu.table;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ComputeRuntime;
import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.compute.ShaderProgram;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.table.NiagaraDataTable;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.kltyton.lumenfx.effect.niagara.table.NiagaraDataTableAsset;
import com.kltyton.lumenfx.effect.niagara.table.NiagaraDataTableAsset.Column;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

public final class NiagaraGpuDataTableBindings implements AutoCloseable {
    private static final String ROWS = "_TableDataBuffer";
    private static final String FILTER = "_FilteredRowDataOffsetBuffer";
    private record Binding(String compileName, String fallback, List<Column> columns) { }
    private record Active(NiagaraDataTable table, byte[] columnOffsets) { }
    private record Upload(GpuBuffer rows, GpuBuffer filter) implements AutoCloseable {
        @Override public void close() { try { rows.close(); } finally { filter.close(); } }
    }
    private final Map<String, Binding> symbols;
    private final Map<String, Active> active = new HashMap<>();
    private final Map<NiagaraDataTable, Upload> uploads = new IdentityHashMap<>();
    private final ComputeRuntime runtime;

    public NiagaraGpuDataTableBindings(ComputeRuntime runtime, JsonObject program) {
        this.runtime = runtime;
        var bindings = new HashMap<String, Binding>();
        for (var item : program.getAsJsonArray("interfaces")) {
            var value = item.getAsJsonObject();
            if (!value.has("data_table") || !value.has("shader_symbol")) continue;
            String symbol = value.get("shader_symbol").getAsString();
            var columns = NiagaraDataTableAsset.readColumns(value.getAsJsonArray("data_table_columns"));
            bindings.put(symbol, new Binding(SourceNames.canonical(value.get("compile_name").getAsString()),
                    value.get("interface_id").getAsString(), columns));
        }
        symbols = Map.copyOf(bindings);
    }

    public void beginTick(Map<String, String> selections, Map<String, NiagaraDataTable> tables) {
        if (symbols.isEmpty()) return;
        var used = new HashSet<NiagaraDataTable>();
        symbols.forEach((symbol, binding) -> {
            String identity = selections.getOrDefault(binding.compileName(), binding.fallback());
            var table = Objects.requireNonNull(tables.get(identity), "Missing Niagara Data Table step: " + identity);
            var offsets = ByteBuffer.allocate(Math.max(1, Math.ceilDiv(binding.columns().size(), 4)) * 16).order(ByteOrder.LITTLE_ENDIAN);
            for (var column : binding.columns()) offsets.putInt(table.columnByteOffset(column.name()));
            active.put(symbol, new Active(table, offsets.array()));
            used.add(table);
        });
        uploads.entrySet().removeIf(entry -> {
            if (used.contains(entry.getKey())) return false;
            entry.getValue().close();
            return true;
        });
    }

    public @Nullable byte[] value(String qualified) {
        if (active.isEmpty()) return null;
        String name = qualified.substring(qualified.lastIndexOf('.') + 1);
        int split = name.lastIndexOf('_');
        if (split < 0) return null;
        var state = active.get(name.substring(0, split));
        if (state == null) return null;
        if (name.endsWith("_AttributeReadOffset")) return state.columnOffsets();
        int value = switch (name.substring(split + 1)) {
            case "NumRows" -> state.table().rows();
            case "RowStride" -> state.table().byteStride();
            case "InvalidRowReadOffset" -> state.table().invalidByteOffset();
            case "NumFilteredRows" -> state.table().filteredRows();
            default -> -1;
        };
        return value < 0 ? null : ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
    }

    public void bind(ShaderProgram shader, ComputeBindings.Builder resources) {
        if (active.isEmpty()) return;
        for (var descriptor : shader.bindings()) if (descriptor instanceof ShaderBinding.StorageBuffer buffer) {
            String name = buffer.name();
            boolean filter = name.endsWith(FILTER);
            String suffix = filter ? FILTER : ROWS;
            if (!name.endsWith(suffix)) continue;
            var state = active.get(name.substring(0, name.length() - suffix.length()));
            if (state == null) continue;
            var data = uploads.computeIfAbsent(state.table(), this::upload);
            resources.storage(name, (filter ? data.filter() : data.rows()).slice(), buffer.access());
        }
    }

    private Upload upload(NiagaraDataTable table) {
        var rows = upload("Niagara.DataTable.Rows", table.rowBytes(), table::writeRows);
        try {
            return new Upload(rows, upload("Niagara.DataTable.Filter", table.filterBytes(), table::writeFilter));
        } catch (RuntimeException | Error failure) {
            rows.close();
            throw failure;
        }
    }

    private GpuBuffer upload(String name, int bytes, Consumer<ByteBuffer> write) {
        if (bytes > runtime.maxStorageBufferBytes())
            throw new IllegalArgumentException("Niagara Data Table exceeds the device storage buffer limit: " + bytes);
        var data = MemoryUtil.memAlloc(bytes).order(ByteOrder.LITTLE_ENDIAN);
        try {
            write.accept(data);
            data.flip();
            return RenderSystem.getDevice().createBuffer(() -> name, ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_DST), data);
        } finally {
            MemoryUtil.memFree(data);
        }
    }

    public void clearSnapshots() { active.clear(); }

    @Override public void close() {
        active.clear();
        uploads.values().forEach(Upload::close);
        uploads.clear();
    }
}
