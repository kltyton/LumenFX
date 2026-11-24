package com.kltyton.lumenfx.client.simulation.niagara.interfaces.table;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.kltyton.lumenfx.effect.niagara.table.NiagaraDataTableAsset;
import com.kltyton.lumenfx.effect.niagara.table.NiagaraDataTableAsset.Column;
import com.kltyton.lumenfx.effect.niagara.table.NiagaraDataTableAsset.Row;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntUnaryOperator;
import org.jspecify.annotations.Nullable;

/** Per-instance ordered rows and filtered-row byte offsets shared by VM and render upload. */
public final class NiagaraDataTable {
    public enum Function { IsValid, GetNumRows, GetNumFilteredRows, GetRow, GetFilteredRow }
    public record Definition(String parameter, List<String> filter, boolean filterTable, @Nullable NiagaraDataTableAsset defaults) {
        public Definition { filter = List.copyOf(filter); }
        public static Definition read(JsonObject data) {
            var names = new ArrayList<String>();
            for (var item : data.getAsJsonArray("filter_rows")) names.add(item.getAsString());
            var defaults = data.get("default");
            return new Definition(data.get("binding").getAsString(), names, data.get("filtered_table").getAsBoolean(),
                    defaults == null || defaults.isJsonNull() ? null : NiagaraDataTableAsset.read(defaults.getAsJsonObject()));
        }
    }
    @FunctionalInterface public interface Output { void write(int word, int lane, int value); }
    private record TypedColumn(String name, String type, int width) { }
    private final int rows, stride, invalid;
    private final int[] rowData, filteredOffsets;
    private final Map<TypedColumn, Integer> typedOffsets;
    private final Map<String, Integer> namedOffsets;
    private final Map<JsonArray, int[]> vmReadOffsets = new ConcurrentHashMap<>();

    public NiagaraDataTable(Definition definition, @Nullable NiagaraDataTableAsset override) {
        var asset = override == null ? definition.defaults() : override;
        var ordered = new ArrayList<Row>();
        var typed = new HashMap<TypedColumn, Integer>();
        var named = new HashMap<String, Integer>();
        stride = asset == null ? 0 : asset.stride();
        if (asset != null) {
            if (definition.filterTable()) {
                for (String name : definition.filter()) {
                    var row = asset.byName().get(SourceNames.canonical(name));
                    if (row != null) ordered.add(row);
                }
            } else ordered.addAll(asset.rows());
            int offset = 0;
            for (var column : asset.columns()) {
                String name = SourceNames.canonical(column.name());
                typed.putIfAbsent(new TypedColumn(name, SourceNames.canonical(column.type()), column.width()), offset);
                named.putIfAbsent(name, offset);
                offset += column.width();
            }
        }
        rows = ordered.size();
        invalid = Math.multiplyExact(rows, stride);
        rowData = new int[Math.addExact(invalid, Math.max(stride, 4))];
        var rowOffsets = new HashMap<String, Integer>();
        for (int index = 0; index < rows; index++) {
            var row = ordered.get(index);
            int offset = index * stride;
            rowOffsets.putIfAbsent(SourceNames.canonical(row.name()), offset);
            for (int word = 0; word < stride; word++) rowData[offset + word] = row.word(word);
        }
        filteredOffsets = new int[definition.filter().size() + 1];
        for (int index = 0; index < filteredRows(); index++)
            filteredOffsets[index] = rowOffsets.getOrDefault(SourceNames.canonical(definition.filter().get(index)), invalid);
        filteredOffsets[filteredRows()] = invalid;
        typedOffsets = Map.copyOf(typed);
        namedOffsets = Map.copyOf(named);
    }

    public int rows() { return rows; }
    public int filteredRows() { return filteredOffsets.length - 1; }
    public int byteStride() { return Math.multiplyExact(stride, Integer.BYTES); }
    public int invalidByteOffset() { return Math.multiplyExact(invalid, Integer.BYTES); }
    public int rowBytes() { return Math.multiplyExact(rowData.length, Integer.BYTES); }
    public int filterBytes() { return Math.multiplyExact(filteredOffsets.length, Integer.BYTES); }
    public void writeRows(ByteBuffer output) { for (int word : rowData) output.putInt(word); }
    public void writeFilter(ByteBuffer output) { for (int offset : filteredOffsets) output.putInt(Math.multiplyExact(offset, Integer.BYTES)); }
    public int columnByteOffset(String name) {
        Integer offset = namedOffsets.get(SourceNames.canonical(name));
        return offset == null ? -1 : Math.multiplyExact(offset, Integer.BYTES);
    }

    public void execute(Function function, int lanes, JsonArray columns, IntUnaryOperator rowIndices, Output output) {
        if (function != Function.GetRow && function != Function.GetFilteredRow) {
            int value = switch (function) {
                case IsValid -> rows > 0 ? -1 : 0;
                case GetNumRows -> rows;
                case GetNumFilteredRows -> filteredRows();
                default -> throw new IllegalStateException("Unexpected Niagara Data Table query " + function);
            };
            for (int lane = 0; lane < lanes; lane++) output.write(0, lane, value);
            return;
        }
        int[] offsets = vmReadOffsets.computeIfAbsent(columns, this::readOffsets);
        boolean filter = function == Function.GetFilteredRow;
        int count = filter ? filteredRows() : rows;
        for (int lane = 0; lane < lanes; lane++) {
            int row = rowIndices.applyAsInt(lane);
            boolean valid = row >= 0 && row < count;
            int base = valid ? filter ? filteredOffsets[row] : row * stride : invalid;
            output.write(0, lane, valid ? -1 : 0);
            for (int index = 0; index < offsets.length; index++)
                output.write(index + 1, lane, offsets[index] < 0 ? 0 : rowData[base + offsets[index]]);
        }
    }

    private int[] readOffsets(JsonArray source) {
        List<Column> columns = NiagaraDataTableAsset.readColumns(source);
        int[] offsets = new int[columns.stream().mapToInt(Column::width).sum()];
        int word = 0;
        for (var column : columns) {
            var key = new TypedColumn(SourceNames.canonical(column.name()), SourceNames.canonical(column.type()), column.width());
            Integer offset = typedOffsets.get(key);
            for (int part = 0; part < column.width(); part++) offsets[word++] = offset == null ? -1 : offset + part;
        }
        return offsets;
    }
}
