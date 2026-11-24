package com.kltyton.lumenfx.effect.niagara.cache;

import com.kltyton.lumenfx.effect.niagara.NiagaraBounds;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** One fixed Niagara system's saved simulation frames, layouts and playback options. */
public record NiagaraSimulationCache(String sourceSystem, float start, float duration, boolean interpolation,
                                      boolean extrapolation, Layout systemLayout, List<Layout> emitterLayouts,
                                      List<Frame> frames, List<NiagaraCacheInterfaceData> interfaces,
                                      @Nullable NiagaraCacheDebugData debugData, CaptureMode captureMode) {
    public static final float AGE_RESOLUTION = 10000;
    public enum CaptureMode { UNKNOWN, ALL, RENDERING_ONLY, EXPLICIT }

    public NiagaraSimulationCache(String sourceSystem, float start, float duration, boolean interpolation,
                                  boolean extrapolation, Layout systemLayout, List<Layout> emitterLayouts, List<Frame> frames,
                                  List<NiagaraCacheInterfaceData> interfaces, @Nullable NiagaraCacheDebugData debugData) {
        this(sourceSystem, start, duration, interpolation, extrapolation, systemLayout, emitterLayouts, frames,
                interfaces, debugData, CaptureMode.UNKNOWN);
    }

    public NiagaraSimulationCache(String sourceSystem, float start, float duration, boolean interpolation,
                                  boolean extrapolation, Layout systemLayout, List<Layout> emitterLayouts, List<Frame> frames) {
        this(sourceSystem, start, duration, interpolation, extrapolation, systemLayout, emitterLayouts, frames, List.of());
    }

    public NiagaraSimulationCache(String sourceSystem, float start, float duration, boolean interpolation,
                                  boolean extrapolation, Layout systemLayout, List<Layout> emitterLayouts, List<Frame> frames,
                                  List<NiagaraCacheInterfaceData> interfaces) {
        this(sourceSystem, start, duration, interpolation, extrapolation, systemLayout, emitterLayouts, frames, interfaces, null);
    }

    public NiagaraSimulationCache {
        Objects.requireNonNull(captureMode);
        sourceSystem = SourceNames.canonical(sourceSystem);
        emitterLayouts = List.copyOf(emitterLayouts);
        frames = List.copyOf(frames);
        interfaces = List.copyOf(interfaces);
        if (sourceSystem.isEmpty() || !Float.isFinite(start) || !Float.isFinite(duration) || duration < 0 || frames.isEmpty())
            throw new IllegalArgumentException("Invalid Niagara simulation cache");
        if (debugData != null && debugData.frames().size() != frames.size())
            throw new IllegalArgumentException("Niagara cache debug frames do not match the simulation frames");
        float prior = Float.NEGATIVE_INFINITY;
        for (var frame : frames) {
            if (!Float.isFinite(frame.age()) || frame.age() < prior || frame.emitters().size() != emitterLayouts.size())
                throw new IllegalArgumentException("Niagara simulation cache frames are out of order or lack emitter data");
            prior = frame.age();
            if (frame.system().count() != 1) throw new IllegalArgumentException("A Niagara cache frame must contain one system row");
            frame.system().validate(systemLayout);
            for (int index = 0; index < emitterLayouts.size(); index++) frame.emitters().get(index).particles().validate(emitterLayouts.get(index));
        }
        var interfaceKeys = new HashSet<NiagaraCacheInterfaceData.Key>();
        for (var data : interfaces) {
            if (data.frames().size() != frames.size() || !interfaceKeys.add(data.key()))
                throw new IllegalArgumentException("Invalid Niagara cache data interface " + data.key().name());
        }
    }

    public record Range(int start, int count) {
        public Range {
            if (count < 0 || count > 0 && start < 0) throw new IllegalArgumentException("Invalid Niagara cache component range");
        }
    }
    public record Field(String name, String sourceType, Range floats, Range integers, Range halves, boolean rebase, boolean interpolate) {
        public Field {
            name = SourceNames.canonical(name);
            sourceType = SourceNames.canonical(sourceType);
        }
    }
    public record Layout(String name, boolean localSpace, boolean gpu, int floatCount, int integerCount, int halfCount,
                         int velocityComponent, List<Field> fields) {
        public Layout {
            name = SourceNames.canonical(name);
            fields = List.copyOf(fields);
            if (floatCount < 0 || integerCount < 0 || halfCount < 0 || velocityComponent < -1
                    || velocityComponent >= 0 && velocityComponent + 3L > floatCount)
                throw new IllegalArgumentException("Invalid Niagara cache layout");
            var identities = new HashSet<List<String>>();
            for (var field : fields) {
                if (!identities.add(List.of(field.name(), field.sourceType())))
                    throw new IllegalArgumentException("Duplicate Niagara cache variable " + field.name());
                range(field.floats(), floatCount);
                range(field.integers(), integerCount);
                range(field.halves(), halfCount);
            }
        }
        private static void range(Range field, int maximum) {
            if (field.count() > 0 && field.start() + (long)field.count() > maximum)
                throw new IllegalArgumentException("Niagara cache variable exceeds its layout");
        }

        public int uniqueIdComponent() {
            for (var field : fields) {
                if (field.name().equals("uniqueid") && field.sourceType().equals("/script/niagara.niagaraint32")
                        && field.integers().count() == 1) return field.integers().start();
            }
            return -1;
        }

        public int[] captureIds(int[] integers, int count) {
            int component = uniqueIdComponent();
            if (component < 0) return new int[0];
            if (count < 0 || integers.length != Math.multiplyExact(count, integerCount))
                throw new IllegalArgumentException("Niagara cache integer data does not match layout " + name);
            var ids = new int[count];
            System.arraycopy(integers, Math.multiplyExact(component, count), ids, 0, count);
            return ids;
        }
    }

    public static final class Buffer {
        private final int count, acquireTag;
        private final int[] floats, integers, idTable, nextRows, captureIds;
        private final short[] halves;
        public Buffer(int count, int acquireTag, int[] floats, int[] integers, short[] halves, int[] idTable, int[] nextRows) {
            this(count, acquireTag, floats, integers, halves, idTable, nextRows, new int[0]);
        }
        public Buffer(int count, int acquireTag, int[] floats, int[] integers, short[] halves, int[] idTable, int[] nextRows, int[] captureIds) {
            if (count < 0 || nextRows.length != 0 && nextRows.length != count || captureIds.length != 0 && captureIds.length != count)
                throw new IllegalArgumentException("Invalid Niagara cache buffer size");
            this.count = count;
            this.acquireTag = acquireTag;
            this.floats = floats.clone();
            this.integers = integers.clone();
            this.halves = halves.clone();
            this.idTable = idTable.clone();
            this.nextRows = nextRows.clone();
            this.captureIds = captureIds.clone();
        }
        public int count() { return count; }
        public int acquireTag() { return acquireTag; }
        public boolean hasIdTable() { return idTable.length != 0; }
        public int[] idTable() { return idTable.clone(); }
        public int nextRowCount() { return nextRows.length; }
        public int nextRow(int row) { return nextRows.length == 0 ? -1 : nextRows[row]; }
        public int[] captureIds() { return captureIds.clone(); }
        public Buffer withNextRows(int[] rows) { return new Buffer(count, acquireTag, floats, integers, halves, idTable, rows, captureIds); }
        public Buffer withCaptureIds(int[] ids) { return new Buffer(count, acquireTag, floats, integers, halves, idTable, nextRows, ids); }
        public int word(int type, int component, int row) {
            int index = Math.addExact(Math.multiplyExact(component, count), Objects.checkIndex(row, count));
            return switch (type) {
                case 0 -> floats[index];
                case 1 -> integers[index];
                case 2 -> Short.toUnsignedInt(halves[index]);
                default -> throw new IllegalArgumentException("Unknown Niagara cache register type");
            };
        }
        public void validate(Layout layout) {
            if (floats.length != (long)count * layout.floatCount() || integers.length != (long)count * layout.integerCount()
                    || halves.length != (long)count * layout.halfCount())
                throw new IllegalArgumentException("Niagara cache data does not match layout " + layout.name());
        }
    }

    /** Transform is the source component pose in the recorded simulation tile. */
    public record Transform(Vec3 position, double qx, double qy, double qz, double qw, Vec3 scale, Vec3 tile) {
        public Transform {
            if (!finite(position) || !finite(scale) || !finite(tile) || !Double.isFinite(qx)
                    || !Double.isFinite(qy) || !Double.isFinite(qz) || !Double.isFinite(qw))
                throw new IllegalArgumentException("Invalid Niagara cache frame transform");
        }
        private static boolean finite(Vec3 value) {
            return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
        }
    }
    public record EmitterFrame(@Nullable NiagaraBounds bounds, int totalSpawned, Buffer particles) { }
    public record Frame(float age, int tick, Transform transform, @Nullable NiagaraBounds bounds, Buffer system,
                        List<EmitterFrame> emitters) {
        public Frame { emitters = List.copyOf(emitters); }
    }
    public record Sample(int index, Frame first, Frame second, float fraction) { }

    public record Playback(NiagaraSimulationCache cache, @Nullable Float age) {
        public Playback {
            Objects.requireNonNull(cache);
            if (age != null && !Float.isFinite(age)) throw new IllegalArgumentException("Invalid Niagara cache playback age");
        }
    }

    public static float quantizedAge(float seconds) {
        return (float)Math.floor(seconds * AGE_RESOLUTION + .5f) / AGE_RESOLUTION;
    }

    public @Nullable Sample sample(float seconds) {
        if (!Float.isFinite(seconds)) throw new IllegalArgumentException("Invalid Niagara cache playback age");
        float age = quantizedAge(seconds);
        // UE stores the unrounded capture duration, while its frame timestamps are quantized.
        if (age > quantizedAge(start + duration)) return null;
        int lower = 0, upper = frames.size();
        while (lower < upper) {
            int middle = (lower + upper) >>> 1;
            if (frames.get(middle).age() <= age) lower = middle + 1;
            else upper = middle;
        }
        int index = Math.max(0, lower - 1);
        var a = frames.get(index);
        var b = frames.get(Math.min(index + 1, frames.size() - 1));
        float delta = b.age() - a.age();
        float fraction = Math.abs(delta) <= 1.e-8f ? 0 : (age - a.age()) / delta;
        if (!interpolation && !extrapolation && fraction > .5f) {
            int nearest = index + 1;
            return new Sample(nearest, b, frames.get(Math.min(nearest + 1, frames.size() - 1)), 0);
        }
        return new Sample(index, a, b, fraction);
    }
}
