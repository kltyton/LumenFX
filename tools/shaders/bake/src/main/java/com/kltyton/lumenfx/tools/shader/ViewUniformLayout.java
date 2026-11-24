package com.kltyton.lumenfx.tools.shader;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Packs the reflected view struct after unused shader fields have been removed. */
final class ViewUniformLayout {
    private static final int OP_NAME = 5;
    private static final int OP_TYPE_INT = 21;
    private static final int OP_TYPE_FLOAT = 22;
    private static final int OP_TYPE_VECTOR = 23;
    private static final int OP_TYPE_MATRIX = 24;
    private static final int OP_TYPE_ARRAY = 28;
    private static final int OP_TYPE_STRUCT = 30;
    private static final int OP_MEMBER_DECORATE = 72;
    private static final int OFFSET = 35;
    private static final int MATRIX_STRIDE = 7;

    private record Type(int opcode, int[] operands) {}
    private record Layout(int alignment, int size) {}

    private ViewUniformLayout() {}

    static byte[] compact(final byte[] binary) {
        final ByteBuffer words = ByteBuffer.wrap(binary).order(ByteOrder.LITTLE_ENDIAN);
        final Map<Integer, Type> types = new HashMap<>();
        final Map<Integer, String> names = new HashMap<>();
        final Map<Long, Integer> offsets = new HashMap<>();
        final Map<Long, Integer> matrixStrides = new HashMap<>();
        for (int pos = 20; pos < binary.length;) {
            final int instruction = words.getInt(pos);
            final int opcode = instruction & 0xffff;
            final int count = instruction >>> 16;
            if (count == 0 || pos + count * 4 > binary.length) {
                throw new BakeException("Malformed SPIR-V while packing view uniforms");
            }
            if (opcode == OP_NAME && count >= 3) {
                final int id = words.getInt(pos + 4);
                int end = pos + 8;
                while (end < pos + count * 4 && binary[end] != 0) end++;
                names.put(id, new String(binary, pos + 8, end - pos - 8, StandardCharsets.UTF_8));
            } else if ((opcode >= OP_TYPE_INT && opcode <= OP_TYPE_MATRIX || opcode == OP_TYPE_ARRAY || opcode == OP_TYPE_STRUCT)
                    && count >= 2) {
                final int id = words.getInt(pos + 4);
                final int[] operands = new int[count - 2];
                for (int i = 0; i < operands.length; i++) operands[i] = words.getInt(pos + 8 + i * 4);
                types.put(id, new Type(opcode, operands));
            } else if (opcode == OP_MEMBER_DECORATE && count >= 5) {
                final int type = words.getInt(pos + 4);
                final int member = words.getInt(pos + 8);
                final int decoration = words.getInt(pos + 12);
                if (decoration == OFFSET) offsets.put(key(type, member), pos + 16);
                if (decoration == MATRIX_STRIDE) matrixStrides.put(key(type, member), words.getInt(pos + 16));
            }
            pos += count * 4;
        }
        for (Map.Entry<Integer, String> entry : names.entrySet()) {
            if (entry.getValue().equals("FViewUniformShaderParameters") && !containsArray(entry.getKey(), types)) {
                pack(entry.getKey(), words, types, offsets, matrixStrides, new HashMap<>());
            }
        }
        return binary;
    }

    private static boolean containsArray(final int id, final Map<Integer, Type> types) {
        final Type type = types.get(id);
        if (type == null) throw new BakeException("Missing view uniform SPIR-V type " + id);
        if (type.opcode() == OP_TYPE_ARRAY) return true;
        if (type.opcode() == OP_TYPE_STRUCT) {
            for (int member : type.operands()) if (containsArray(member, types)) return true;
        }
        return false;
    }

    private static Layout pack(final int id, final ByteBuffer words, final Map<Integer, Type> types,
                               final Map<Long, Integer> offsets, final Map<Long, Integer> strides,
                               final Map<Integer, Layout> packed) {
        final Layout existing = packed.get(id);
        if (existing != null) return existing;
        final Type type = types.get(id);
        if (type == null) throw new BakeException("Missing view uniform SPIR-V type " + id);
        final int[] operands = type.operands();
        final Layout result;
        switch (type.opcode()) {
            case OP_TYPE_INT, OP_TYPE_FLOAT -> {
                if (operands.length < 1 || operands[0] != 32) {
                    throw new BakeException("Unsupported view uniform scalar width");
                }
                result = new Layout(4, 4);
            }
            case OP_TYPE_VECTOR -> {
                if (operands.length != 2 || operands[1] < 2 || operands[1] > 4) {
                    throw new BakeException("Unsupported view uniform vector");
                }
                final Layout scalar = pack(operands[0], words, types, offsets, strides, packed);
                if (scalar.size() != 4) throw new BakeException("Unsupported view uniform vector element");
                result = new Layout(operands[1] == 2 ? 8 : 16, operands[1] * 4);
            }
            case OP_TYPE_MATRIX -> {
                if (operands.length != 2 || operands[1] != 4) {
                    throw new BakeException("Unsupported view uniform matrix");
                }
                final Layout column = pack(operands[0], words, types, offsets, strides, packed);
                if (column.alignment() != 16 || column.size() != 16) {
                    throw new BakeException("Unsupported view uniform matrix column");
                }
                result = new Layout(16, 64);
            }
            case OP_TYPE_STRUCT -> {
                int current = 0;
                for (int i = 0; i < operands.length; i++) {
                    final Layout member = pack(operands[i], words, types, offsets, strides, packed);
                    current = align(current, member.alignment());
                    final Integer location = offsets.get(key(id, i));
                    if (location == null || words.getInt(location) < current) {
                        throw new BakeException("Unsupported view uniform member offset for type " + id + ", member " + i);
                    }
                    final Type memberType = types.get(operands[i]);
                    if (memberType.opcode() == OP_TYPE_MATRIX && strides.getOrDefault(key(id, i), 0) != 16) {
                        throw new BakeException("Unsupported view uniform matrix stride");
                    }
                    words.putInt(location, current);
                    current += member.size();
                }
                result = new Layout(16, align(current, 16));
            }
            default -> throw new BakeException("Unsupported view uniform SPIR-V type " + type.opcode());
        }
        packed.put(id, result);
        return result;
    }

    private static int align(final int value, final int alignment) {
        return (value + alignment - 1) & -alignment;
    }

    private static long key(final int type, final int member) {
        return ((long) type << 32) | (member & 0xffffffffL);
    }
}
