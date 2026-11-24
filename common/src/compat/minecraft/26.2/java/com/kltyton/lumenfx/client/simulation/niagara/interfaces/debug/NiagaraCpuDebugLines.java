package com.kltyton.lumenfx.client.simulation.niagara.interfaces.debug;

import com.kltyton.lumenfx.api.client.EffectHandle.Pose;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.NiagaraPositionParameters;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraParameterStore;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntBinaryOperator;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/** Merges CPU debug lines after all emitter jobs for the current simulation tick have finished. */
public final class NiagaraCpuDebugLines {
    public static final int WORDS_PER_LINE = 7;

    public static final class Snapshot {
        private final Vec3 sourceTile;
        private final int[] words;
        private Snapshot(Vec3 sourceTile, int[] words) { this.sourceTile = sourceTile; this.words = words; }
        public Vec3 sourceTile() { return sourceTile; }
        public int lineCount() { return words.length / WORDS_PER_LINE; }
        public void write(ByteBuffer data, int first, int count) {
            int offset = first * WORDS_PER_LINE, size = count * WORDS_PER_LINE;
            data.asIntBuffer().put(words, offset, size);
            data.position(data.position() + size * Integer.BYTES);
        }
    }

    private static final class Buffer implements NiagaraDebugGeometry.Lines {
        final IntArrayList data = new IntArrayList();
        int limit;
        boolean bound;

        @Override public boolean full() { return limit > 0 && data.size() / WORDS_PER_LINE >= limit; }
        @Override public void line(Vector3f first, Vector3f second, int rgba) {
            if (full()) return;
            data.add(Float.floatToRawIntBits(first.x)); data.add(Float.floatToRawIntBits(first.y)); data.add(Float.floatToRawIntBits(first.z));
            data.add(Float.floatToRawIntBits(second.x)); data.add(Float.floatToRawIntBits(second.y)); data.add(Float.floatToRawIntBits(second.z));
            data.add(rgba);
        }
    }

    private final Map<String, Buffer> buffers = new LinkedHashMap<>();
    private double x, y, z;
    private boolean enabled;
    private volatile @Nullable Snapshot published;

    public synchronized void begin(boolean enabled, double tileX, double tileY, double tileZ) {
        this.enabled = enabled;
        x = tileX; y = tileY; z = tileZ;
        for (Buffer buffer : buffers.values()) {
            buffer.data.clear();
            buffer.bound = false;
        }
    }

    public synchronized void draw(String id, String function, int inputCount, int count, int limit, IntBinaryOperator read) {
        if (!enabled || count == 0) return;
        Buffer buffer = buffers.computeIfAbsent(id, ignored -> new Buffer());
        buffer.limit = limit;
        buffer.bound = true;
        if (buffer.full()) return;
        int[] words = new int[inputCount];
        for (int lane = 0; lane < count && !buffer.full(); lane++) {
            if (read.applyAsInt(0, lane) == 0) continue;
            for (int word = 1; word < inputCount; word++) words[word] = read.applyAsInt(word, lane);
            NiagaraDebugGeometry.draw(function, words, buffer);
        }
    }

    public synchronized void drawPersistent(NiagaraPersistentDebugShapes shapes, NiagaraParameterStore overrides, Pose pose) {
        if (enabled) shapes.draw(this, overrides, pose);
    }

    NiagaraDebugGeometry.Lines persistentBuffer(String id, int limit) {
        Buffer buffer = buffers.computeIfAbsent(id, ignored -> new Buffer());
        if (!buffer.bound) buffer.limit = limit;
        return new NiagaraDebugGeometry.Lines() {
            @Override public boolean full() { return buffer.full(); }
            @Override public void line(Vector3f first, Vector3f second, int rgba) {
                buffer.line(relative(first), relative(second), rgba);
            }
        };
    }

    private Vector3f relative(Vector3f point) {
        double size = NiagaraPositionParameters.TILE_SIZE;
        return new Vector3f((float)(point.x - x * size), (float)(point.y - y * size), (float)(point.z - z * size));
    }

    public synchronized void publish() {
        int size = 0;
        for (Buffer buffer : buffers.values()) size = Math.addExact(size, buffer.data.size());
        if (size == 0) {
            published = null;
            return;
        }
        int[] packed = new int[size];
        int offset = 0;
        for (Buffer buffer : buffers.values()) {
            buffer.data.getElements(0, packed, offset, buffer.data.size());
            offset += buffer.data.size();
        }
        published = new Snapshot(new Vec3(x, y, z), packed);
    }

    public @Nullable Snapshot snapshot() { return published; }
    public synchronized void clear() { buffers.clear(); published = null; }
}
