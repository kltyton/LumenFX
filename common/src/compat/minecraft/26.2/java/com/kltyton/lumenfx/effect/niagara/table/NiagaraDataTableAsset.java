package com.kltyton.lumenfx.effect.niagara.table;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.kltyton.lumenfx.effect.niagara.parameter.store.ScriptParameterStoreDefinition.ObjectValue;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Authored row order, typed column definitions and lossless VM words. */
public final class NiagaraDataTableAsset {
    public record Column(String name, String type, int width) {
        public Column {
            if (width < 1) throw new IllegalArgumentException("A Niagara table column requires at least one word");
        }
    }
    public record Row(String name, int[] words) {
        public Row { words = words.clone(); }
        @Override public int[] words() { return words.clone(); }
        public int word(int index) { return words[index]; }
    }
    private final List<Column> columns;
    private final List<Row> rows;
    private final Map<String, Row> byName;
    private final int stride;

    public NiagaraDataTableAsset(List<Column> columns, List<Row> rows) {
        this.columns = List.copyOf(columns);
        this.rows = List.copyOf(rows);
        int words = 0;
        for (var column : columns) words = Math.addExact(words, column.width());
        stride = words;
        var names = new LinkedHashMap<String, Row>();
        for (var row : this.rows) {
            if (row.words.length != stride) throw new IllegalArgumentException("Invalid Niagara table row width: " + row.name());
            if (names.putIfAbsent(SourceNames.canonical(row.name()), row) != null)
                throw new IllegalArgumentException("Duplicate Niagara table row: " + row.name());
        }
        byName = Map.copyOf(names);
    }

    public List<Column> columns() { return columns; }
    public List<Row> rows() { return rows; }
    public Map<String, Row> byName() { return byName; }
    public int stride() { return stride; }

    /** Prepare this table for an exposed UObject parameter on an EffectHandle. */
    public ObjectValue objectValue(String identity) {
        var data = new JsonObject();
        var columnData = new JsonArray();
        for (var column : columns) {
            var value = new JsonObject();
            value.addProperty("name", column.name());
            value.addProperty("type", column.type());
            value.addProperty("width", column.width());
            columnData.add(value);
        }
        var rowData = new JsonArray();
        for (var row : rows) {
            var value = new JsonObject();
            value.addProperty("name", row.name());
            var words = new JsonArray();
            for (int word : row.words) words.add(word);
            value.add("words", words);
            rowData.add(value);
        }
        data.add("columns", columnData);
        data.add("rows", rowData);
        var object = new JsonObject();
        object.add("data_table_asset", data);
        return new ObjectValue(identity, object);
    }

    public static List<Column> readColumns(JsonArray values) {
        var columns = new ArrayList<Column>();
        for (var item : values) {
            var data = item.getAsJsonObject();
            columns.add(new Column(data.get("name").getAsString(), data.get("type").getAsString(), data.get("width").getAsInt()));
        }
        return List.copyOf(columns);
    }

    public static NiagaraDataTableAsset read(JsonObject value) {
        var rows = new ArrayList<Row>();
        for (var item : value.getAsJsonArray("rows")) {
            var data = item.getAsJsonObject();
            var stored = data.getAsJsonArray("words");
            int[] words = new int[stored.size()];
            for (int index = 0; index < words.length; index++) words[index] = stored.get(index).getAsInt();
            rows.add(new Row(data.get("name").getAsString(), words));
        }
        return new NiagaraDataTableAsset(readColumns(value.getAsJsonArray("columns")), rows);
    }
}
