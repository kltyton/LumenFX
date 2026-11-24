package com.kltyton.lumenfx.client.render.niagara.gpu;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/** Packs source-ordered values into the reflected uniform layout of a baked GPU stage. */
public final class NiagaraGpuUniformLayout {
    private record Field(String name, int offset, int scalarBytes, int rows, int columns,
                         int count, int arrayStride, int matrixStride, boolean rowMajor) { }

    private final int size;
    private final List<Field> fields;

    public NiagaraGpuUniformLayout(JsonObject buffer) {
        size = buffer.get("size").getAsInt();
        if (size <= 0) throw new IllegalArgumentException("Invalid Niagara GPU uniform block size");
        List<Field> parsed = new ArrayList<>();
        readMembers(buffer.getAsJsonArray("members"), "", 0, parsed);
        fields = List.copyOf(parsed);
    }

    public int size() { return size; }

    void addFieldNames(Set<String> names) {
        for (var field : fields) {
            var name = field.name();
            names.add(name.substring(name.lastIndexOf('.') + 1));
        }
    }

    /** Values contain tightly packed scalars; matrices are supplied in column-major order. */
    public void write(ByteBuffer target, Function<String, byte[]> source) {
        if (target.remaining() < size) throw new IllegalArgumentException("Niagara GPU uniform buffer is too small");
        int base = target.position();
        for (Field field : fields) {
            byte[] value = source.apply(field.name());
            int vectorBytes = Math.multiplyExact(field.rows(), field.scalarBytes());
            int matrixBytes = Math.multiplyExact(vectorBytes, field.columns());
            int required = Math.multiplyExact(matrixBytes, field.count());
            if (value == null || value.length != required)
                throw new IllegalArgumentException("Missing or invalid Niagara GPU uniform " + field.name());
            for (int element = 0; element < field.count(); element++) {
                int destination = Math.addExact(base + field.offset(), element * field.arrayStride());
                int input = element * matrixBytes;
                if (field.columns() == 1) {
                    put(target, destination, value, input, vectorBytes);
                } else {
                    for (int column = 0; column < field.columns(); column++) {
                        for (int row = 0; row < field.rows(); row++) {
                            int output = field.rowMajor()
                                    ? destination + row * field.matrixStride() + column * field.scalarBytes()
                                    : destination + column * field.matrixStride() + row * field.scalarBytes();
                            int from = input + (column * field.rows() + row) * field.scalarBytes();
                            put(target, output, value, from, field.scalarBytes());
                        }
                    }
                }
            }
        }
        target.position(base + size);
    }

    private static void readMembers(JsonArray members, String prefix, int base, List<Field> result) {
        if (members == null) throw new IllegalArgumentException("Missing Niagara GPU uniform members");
        for (var item : members) {
            JsonObject member = item.getAsJsonObject();
            String name = prefix + member.get("name").getAsString();
            int offset = Math.addExact(base, member.get("offset").getAsInt());
            JsonArray children = member.getAsJsonArray("members");
            JsonArray dimensions = member.getAsJsonArray("arrayDimensions");
            if (dimensions == null || dimensions.size() > 1)
                throw new IllegalArgumentException("Unsupported Niagara GPU uniform array layout " + name);
            int count = dimensions.isEmpty() ? 1 : dimensions.get(0).getAsInt();
            if (count <= 0) throw new IllegalArgumentException("Invalid Niagara GPU uniform array " + name);
            if (children != null && !children.isEmpty()) {
                if (count > 1 && !member.has("arrayStride"))
                    throw new IllegalArgumentException("Missing Niagara GPU uniform structure stride " + name);
                int stride = count > 1 ? member.get("arrayStride").getAsInt() : 0;
                for (int i = 0; i < count; i++)
                    readMembers(children, name + (!dimensions.isEmpty() ? "[" + i + "]" : "") + ".",
                            Math.addExact(offset, Math.multiplyExact(i, stride)), result);
                continue;
            }
            int bits = member.get("bitWidth").getAsInt();
            int rows = member.get("vectorSize").getAsInt(), columns = member.get("columns").getAsInt();
            if (bits <= 0 || bits % 8 != 0 || rows <= 0 || columns <= 0)
                throw new IllegalArgumentException("Invalid Niagara GPU uniform type " + name);
            if (count > 1 && !member.has("arrayStride") || columns > 1 && !member.has("matrixStride"))
                throw new IllegalArgumentException("Missing Niagara GPU uniform stride " + name);
            int scalarBytes = bits / 8;
            int matrixStride = member.has("matrixStride") ? member.get("matrixStride").getAsInt() : rows * scalarBytes;
            int arrayStride = member.has("arrayStride") ? member.get("arrayStride").getAsInt()
                    : columns * rows * scalarBytes;
            boolean rowMajor = member.has("rowMajor") && member.get("rowMajor").getAsBoolean();
            result.add(new Field(name, offset, scalarBytes, rows, columns, count, arrayStride, matrixStride, rowMajor));
        }
    }

    private static void put(ByteBuffer target, int offset, byte[] source, int sourceOffset, int length) {
        for (int i = 0; i < length; i++) target.put(offset + i, source[sourceOffset + i]);
    }
}
