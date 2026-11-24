package com.kltyton.lumenfx.effect.niagara.cache;

import com.kltyton.lumenfx.runtime.niagara.HalfFloat;
import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import javax.annotation.Nullable;
import net.minecraft.world.phys.Vec3;

/**
 * Full binary {@code .lfsc} (versions 1–6, magic {@code 0x4353464c}) Niagara Simulation Cache
 * matching LumenFX 26.2 {@code NiagaraSimulationCache} & {@code NiagaraSimCacheLoader}.
 */
public record NiagaraSimulationCache(String sourceSystem, float start, float duration,
                                     boolean interpolation, boolean extrapolation,
                                     Layout systemLayout, List<Layout> emitterLayouts,
                                     List<Frame> frames, CaptureMode captureMode) {
    public enum CaptureMode { UNKNOWN, ALL, RENDERING_ONLY, EXPLICIT }

    public record Playback(NiagaraSimulationCache cache, @Nullable Float age) {
        public Playback {
            Objects.requireNonNull(cache, "cache");
        }
    }

    public record Range(int start, int count) { }

    public record Field(String name, String sourceType, Range floats, Range integers, Range halves,
                        boolean rebase, boolean interpolate) { }

    public record Layout(String name, boolean localSpace, boolean gpu,
                         int floatCount, int integerCount, int halfCount,
                         int velocityComponent, List<Field> fields) {
        public @Nullable Field findField(String shortName) {
            String target = shortName.toLowerCase(Locale.ROOT);
            for (Field f : fields) {
                String n = f.name().toLowerCase(Locale.ROOT);
                if (n.equals(target) || n.equals("particles." + target) || n.endsWith("." + target)) {
                    return f;
                }
            }
            return null;
        }
    }

    public record Transform(Vec3 position, double qx, double qy, double qz, double qw, Vec3 scale, Vec3 tile) { }

    public record Bounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) { }

    public record Buffer(int count, int acquireTag, int[] floats, int[] integers, short[] halves,
                         int[] idTable, int[] nextRows, int[] captureIds) {
        public float floating(int component, int row) {
            return Float.intBitsToFloat(floats[component * count + row]);
        }

        public int integer(int component, int row) {
            return integers[component * count + row];
        }

        public float half(int component, int row) {
            return HalfFloat.toFloat(halves[component * count + row]);
        }
    }

    public record EmitterFrame(@Nullable Bounds bounds, int totalSpawned, Buffer particles) { }

    public record Frame(float age, int tick, Transform transform, @Nullable Bounds bounds,
                        Buffer system, List<EmitterFrame> emitters) { }

    public static NiagaraSimulationCache decode(String id, byte[] raw) throws IOException {
        if (raw == null || raw.length < 24) throw new IOException("Truncated Niagara simulation cache " + id);
        try {
            ByteBuffer data = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            int magic = data.getInt();
            int version = data.getInt();
            if (magic != 0x4353464c || version < 1 || version > 6) {
                throw new IOException("Invalid Niagara simulation cache header " + id);
            }
            String sourceSystem = text(data);
            float start = data.getFloat();
            float duration = data.getFloat();
            int flags = data.getInt();
            int emitterCount = count(data, 28);
            int frameCount = count(data, 84);
            CaptureMode mode = version >= 6 ? CaptureMode.values()[(flags >>> 2) & 3] : CaptureMode.UNKNOWN;
            if (version >= 3) {
                int materials = count(data, Integer.BYTES);
                for (int i = 0; i < materials; i++) text(data);
            }
            Layout systemLayout = readLayout(data);
            List<Layout> emitterLayouts = new ArrayList<>(emitterCount);
            for (int i = 0; i < emitterCount; i++) emitterLayouts.add(readLayout(data));

            List<Frame> frames = new ArrayList<>(frameCount);
            for (int f = 0; f < frameCount; f++) {
                float age = data.getFloat();
                int tick = data.getInt();
                Transform tr = new Transform(vector(data), data.getDouble(), data.getDouble(),
                        data.getDouble(), data.getDouble(), vector(data), vector(data));
                Bounds sysBounds = readBounds(data);
                Buffer sysBuf = readBuffer(data, systemLayout, version);
                List<EmitterFrame> emFrames = new ArrayList<>(emitterCount);
                for (Layout layout : emitterLayouts) {
                    Bounds eb = readBounds(data);
                    int totalSpawned = data.getInt();
                    Buffer pb = readBuffer(data, layout, version);
                    emFrames.add(new EmitterFrame(eb, totalSpawned, pb));
                }
                frames.add(new Frame(age, tick, tr, sysBounds, sysBuf, List.copyOf(emFrames)));
            }
            return new NiagaraSimulationCache(sourceSystem, start, duration,
                    (flags & 1) != 0, (flags & 2) != 0,
                    systemLayout, List.copyOf(emitterLayouts), List.copyOf(frames), mode);
        } catch (BufferUnderflowException | ArithmeticException | IllegalArgumentException ex) {
            throw new IOException("Invalid Niagara simulation cache " + id, ex);
        }
    }

    private static Layout readLayout(ByteBuffer data) throws IOException {
        String name = text(data);
        int flags = data.getInt();
        int floats = data.getInt();
        int integers = data.getInt();
        int halves = data.getInt();
        int velocity = data.getInt();
        int size = count(data, 32);
        List<Field> fields = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            String field = text(data);
            String type = text(data);
            Range f = new Range(data.getInt(), data.getInt());
            Range n = new Range(data.getInt(), data.getInt());
            Range h = new Range(data.getInt(), data.getInt());
            int ff = data.getInt();
            fields.add(new Field(field, type, f, n, h, (ff & 1) != 0, (ff & 2) != 0));
        }
        return new Layout(name, (flags & 1) != 0, (flags & 2) != 0, floats, integers, halves, velocity, List.copyOf(fields));
    }

    private static Buffer readBuffer(ByteBuffer data, Layout layout, int version) throws IOException {
        int count = data.getInt();
        int tag = data.getInt();
        int ids = count(data, Integer.BYTES);
        int next = count(data, Integer.BYTES);
        int captureIds = version >= 4 ? count(data, Integer.BYTES) : 0;
        if (count < 0 || (next != 0 && next != count) || (captureIds != 0 && captureIds != count)) {
            throw new IOException("Invalid Niagara simulation cache buffer dimensions");
        }
        int[] floats = words(data, Math.multiplyExact(count, layout.floatCount()));
        int[] integers = words(data, Math.multiplyExact(count, layout.integerCount()));
        int halfLen = Math.multiplyExact(count, layout.halfCount());
        if (halfLen > data.remaining() / Short.BYTES) throw new IOException("Truncated Niagara cache half data");
        short[] halves = new short[halfLen];
        for (int i = 0; i < halfLen; i++) halves[i] = data.getShort();
        int[] idTable = words(data, ids);
        int[] nextRows = words(data, next);
        int[] capIds = words(data, captureIds);
        return new Buffer(count, tag, floats, integers, halves, idTable, nextRows, capIds);
    }

    private static int[] words(ByteBuffer data, int n) throws IOException {
        if (n < 0 || n > data.remaining() / Integer.BYTES) throw new IOException("Truncated Niagara cache data");
        int[] out = new int[n];
        for (int i = 0; i < n; i++) out[i] = data.getInt();
        return out;
    }

    private static @Nullable Bounds readBounds(ByteBuffer data) throws IOException {
        int flag = data.getInt();
        if (flag == 0) return null;
        if (flag != 1) throw new IOException("Invalid Niagara cache bounds flag");
        return new Bounds(data.getDouble(), data.getDouble(), data.getDouble(),
                data.getDouble(), data.getDouble(), data.getDouble());
    }

    private static String text(ByteBuffer data) throws IOException {
        byte[] bytes = new byte[count(data, 1)];
        data.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static Vec3 vector(ByteBuffer data) {
        return new Vec3(data.getDouble(), data.getDouble(), data.getDouble());
    }

    private static int count(ByteBuffer data, int elemBytes) throws IOException {
        int n = data.getInt();
        if (n < 0 || n > data.remaining() / elemBytes) throw new IOException("Invalid Niagara cache array size");
        return n;
    }
}
