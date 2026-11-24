package com.kltyton.lumenfx.client.simulation.niagara.interfaces.chaos;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.world.phys.Vec3;

/** An immutable set of the source's independently sized event columns. */
public final class ChaosDestructionFrame {
    public enum Column {
        POSITION("PositionBuffer", 3), VELOCITY("VelocityBuffer", 3),
        EXTENT_MIN("ExtentMinBuffer", 1), EXTENT_MAX("ExtentMaxBuffer", 1), VOLUME("VolumeBuffer", 1),
        SOLVER_ID("SolverIDBuffer", 1), DENSITY("DensityBuffer", 1), FRICTION("FrictionBuffer", 1),
        RESTITUTION("RestitutionBuffer", 1), SURFACE("SurfaceTypeBuffer", 1), COLOR("ColorBuffer", 4),
        LOCATION("IncomingLocationBuffer", 3), IMPULSE("IncomingAccumulatedImpulseBuffer", 3),
        NORMAL("IncomingNormalBuffer", 3), VELOCITY_FIRST("IncomingVelocity1Buffer", 3),
        VELOCITY_SECOND("IncomingVelocity2Buffer", 3), ANGULAR_FIRST("IncomingAngularVelocity1Buffer", 3),
        ANGULAR_SECOND("IncomingAngularVelocity2Buffer", 3), MASS_FIRST("IncomingMass1Buffer", 1),
        MASS_SECOND("IncomingMass2Buffer", 1), TIME("IncomingTimeBuffer", 1),
        TRANSLATION("TransformTranslationBuffer", 3), ROTATION("TransformRotationBuffer", 4),
        SCALE("TransformScaleBuffer", 3), BOUNDS("BoundsBuffer", 3);

        private final String symbol;
        private final int components;

        Column(String symbol, int components) {
            this.symbol = symbol;
            this.components = components;
        }

        public String symbol() { return symbol; }
        int components() { return components; }
        int wordsPerElement() { return components == 3 ? 4 : components; }
    }

    private final int lastId;
    private final float time;
    private final int count;
    private final Map<Column, int[]> values;

    private ChaosDestructionFrame(int lastId, float time, Map<Column, int[]> values) {
        this.lastId = lastId;
        this.time = time;
        this.values = values;
        count = values.get(Column.POSITION).length / Column.POSITION.wordsPerElement();
    }

    public int count() { return count; }
    public int lastId() { return lastId; }
    public float time() { return time; }

    int read(Column column, int index, int component) {
        int[] array = values.get(column);
        int stride = column.wordsPerElement();
        if (index >= 0 && index < array.length / stride) return array[index * stride + component];
        return switch (column) {
            case COLOR, SCALE -> Float.floatToRawIntBits(1);
            case ROTATION -> component == 3 ? Float.floatToRawIntBits(1) : 0;
            default -> 0;
        };
    }

    public int bytes(Column column) {
        return Math.multiplyExact(count == 0 ? column.wordsPerElement()
                : Math.max(column.wordsPerElement(), values.get(column).length), Integer.BYTES);
    }

    public void write(Column column, ByteBuffer target) {
        int[] array = values.get(column);
        if (count != 0 && array.length != 0) {
            for (int word : array) target.putInt(word);
            return;
        }
        for (int i = 0; i < column.wordsPerElement(); i++) {
            boolean alpha = count != 0 && i == 3 && (column == Column.COLOR || column == Column.ROTATION);
            target.putFloat(alpha ? 1 : 0);
        }
    }

    static final class Builder {
        private final Map<Column, Words> columns = new EnumMap<>(Column.class);

        void vector(Column column, Vec3 value) {
            var words = column(column);
            words.add(Float.floatToRawIntBits((float) value.x));
            words.add(Float.floatToRawIntBits((float) value.y));
            words.add(Float.floatToRawIntBits((float) value.z));
            words.add(0);
        }

        void vector4(Column column, float x, float y, float z, float w) {
            var words = column(column);
            words.add(Float.floatToRawIntBits(x));
            words.add(Float.floatToRawIntBits(y));
            words.add(Float.floatToRawIntBits(z));
            words.add(Float.floatToRawIntBits(w));
        }

        void integer(Column column, int value) { column(column).add(value); }

        void scalar(Column column, float value) { integer(column, Float.floatToRawIntBits(value)); }

        private Words column(Column column) {
            return columns.computeIfAbsent(column, key -> new Words());
        }

        ChaosDestructionFrame finish(int id, float time) {
            var data = new EnumMap<Column, int[]>(Column.class);
            for (var column : Column.values()) {
                var words = columns.get(column);
                data.put(column, words == null ? Words.EMPTY : Arrays.copyOf(words.words, words.length));
            }
            return new ChaosDestructionFrame(id, time, data);
        }
    }

    private static final class Words {
        private static final int[] EMPTY = new int[0];
        private int[] words = new int[16];
        private int length;

        void add(int word) {
            if (length == words.length) words = Arrays.copyOf(words, Math.multiplyExact(length, 2));
            words[length++] = word;
        }
    }
}
