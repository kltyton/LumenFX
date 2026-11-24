package com.kltyton.lumenfx.client.simulation.niagara.cache;

import com.kltyton.lumenfx.api.client.EffectHandle.Pose;
import com.kltyton.lumenfx.client.simulation.niagara.VectorDataSet;
import com.kltyton.lumenfx.client.transform.NiagaraComponentMatrices;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache.Buffer;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache.Field;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache.Layout;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache.Range;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache.Transform;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/** Maps saved typed cache columns into one live Niagara data set. */
public final class NiagaraCachePlayback {
    private static final String POSITION = "/script/niagara.niagaraposition";
    private static final String QUATERNION = "/script/coreuobject.quat4f";
    private static final String MATRIX = "/script/niagara.niagaramatrix";
    private static final Range ABSENT = new Range(-1, 0);
    private record Key(String name, String type) {
        Key { name = SourceNames.canonical(name); type = SourceNames.canonical(type); }
    }
    private record Target(Range floats, Range integers, Range halves) { }
    private record Mapping(Field source, Target target) { }
    private record Rebase(Matrix4d matrix, Quaternionf rotation) {
        static Rebase between(Transform recorded, Pose owner, Vec3 tile) {
            var matrix = rebaseMatrix(recorded, owner, tile);
            var recordedOrientation = new Quaterniond(recorded.qx(), recorded.qy(), recorded.qz(), recorded.qw());
            var orientation = new Quaterniond(-owner.x(), -owner.z(), -owner.y(), owner.w()).mul(recordedOrientation.invert());
            return new Rebase(matrix, new Quaternionf((float)orientation.x(), (float)orientation.y(), (float)orientation.z(), (float)orientation.w()));
        }
    }

    static Matrix4d rebaseMatrix(Transform recorded, Pose owner, Vec3 tile) {
        var from = NiagaraComponentMatrices.srt(recorded.qx(), recorded.qy(), recorded.qz(), recorded.qw(), 1, 1, 1,
                recorded.position().x, recorded.position().y, recorded.position().z, new Matrix4d());
        var to = NiagaraComponentMatrices.source(owner, tile.x, tile.y, tile.z, false, new Matrix4d());
        return to.mul(NiagaraComponentMatrices.inverse(from, new Matrix4d()));
    }

    private static void rebasePosition(Matrix4d transform, Vector3f position) {
        var world = transform.transformPosition(new Vector3d(position.x, position.y, position.z));
        position.set((float)world.x, (float)world.y, (float)world.z);
    }

    private final Layout sourceLayout;
    private final List<Mapping> mappings;
    private final Map<Key, Mapping> mapped;
    private final boolean completeLayout;

    public static NiagaraCachePlayback system(Layout layout, List<NiagaraDefinition.Variable> variables) {
        var targets = new LinkedHashMap<Key, Target>();
        for (var variable : variables) targets.put(new Key(variable.name(), variable.sourceType()),
                new Target(new Range(variable.floatStart(), variable.floatCount()), new Range(variable.intStart(), variable.intCount()),
                        new Range(variable.halfStart(), variable.halfCount())));
        return new NiagaraCachePlayback(layout, targets);
    }

    public static NiagaraCachePlayback emitter(Layout layout, List<NiagaraDefinition.Attribute> attributes) {
        var targets = new LinkedHashMap<Key, Target>();
        for (var attribute : attributes) {
            var key = new Key(attribute.name(), attribute.sourceType());
            var range = new Range(attribute.component(), attribute.count());
            var prior = targets.getOrDefault(key, new Target(ABSENT, ABSENT, ABSENT));
            targets.put(key, switch (attribute.type()) {
                case 0 -> new Target(range, prior.integers(), prior.halves());
                case 1 -> new Target(prior.floats(), range, prior.halves());
                case 2 -> new Target(prior.floats(), prior.integers(), range);
                default -> throw new IllegalArgumentException("Unknown Niagara emitter cache column type");
            });
        }
        return new NiagaraCachePlayback(layout, targets);
    }

    private NiagaraCachePlayback(Layout layout, Map<Key, Target> targets) {
        sourceLayout = layout;
        var values = new ArrayList<Mapping>();
        var names = new HashMap<Key, Mapping>();
        for (var field : layout.fields()) {
            var key = new Key(field.name(), field.sourceType());
            var target = targets.get(key);
            if (target == null) continue;
            if (field.floats().count() != target.floats().count() || field.integers().count() != target.integers().count()
                    || field.halves().count() != target.halves().count())
                throw new IllegalArgumentException("Niagara cache field width changed: " + layout.name() + "." + field.name());
            int transformWidth = switch (field.sourceType()) {
                case POSITION -> 3;
                case QUATERNION -> 4;
                case MATRIX -> 16;
                default -> field.floats().count();
            };
            if (field.floats().count() != transformWidth)
                throw new IllegalArgumentException("Invalid Niagara cache transform width: " + layout.name() + "." + field.name());
            var entry = new Mapping(field, target);
            values.add(entry);
            names.put(key, entry);
        }
        mappings = List.copyOf(values);
        mapped = Map.copyOf(names);
        completeLayout = mapped.size() == targets.size();
    }

    boolean completeLayout() { return completeLayout; }

    boolean dependsOnPose(boolean interpolate) {
        for (var mapping : mappings) {
            var field = mapping.source();
            boolean blend = interpolate && field.interpolate();
            if (blend && hasPairedCurrent(field)) continue;
            if (field.rebase() && !sourceLayout.localSpace()
                    || blend && field.sourceType().equals(QUATERNION) && interpolatedPrevious(field) == null) return true;
        }
        return false;
    }

    private boolean hasPairedCurrent(Field field) {
        String prefix = "previous.";
        return field.name().startsWith(prefix) && (field.sourceType().equals(POSITION) || field.sourceType().equals(QUATERNION))
                && mapped.containsKey(new Key(field.name().substring(prefix.length()), field.sourceType()));
    }

    private @Nullable Mapping interpolatedPrevious(Field field) {
        var previous = mapped.get(new Key("previous." + field.name(), field.sourceType()));
        return previous != null && previous.source().interpolate() ? previous : null;
    }

    int[] particleIndex(Buffer saved, VectorDataSet data) {
        if (saved.hasIdTable()) {
            int[] ids = saved.idTable();
            if (data.count() < saved.count()) for (int index = 0; index < ids.length; index++) {
                if (ids[index] >= data.count()) ids[index] = -1;
            }
            return ids;
        }
        var id = mapped.get(new Key("ID", "/Script/Niagara.NiagaraID"));
        if (id == null) id = mapped.get(new Key("Particles.ID", "/Script/Niagara.NiagaraID"));
        if (id == null || id.target().integers().count() != 2) return new int[0];
        int component = id.target().integers().start();
        int maximum = -1;
        for (int row = 0; row < data.count(); row++) maximum = Math.max(maximum, data.integer(component, row));
        var ids = new int[Math.addExact(maximum, 1)];
        Arrays.fill(ids, -1);
        for (int row = 0; row < data.count(); row++) {
            int index = data.integer(component, row);
            if (index < 0) continue;
            if (ids[index] != -1) throw new IllegalArgumentException("Duplicate persistent Niagara ID " + index + " in " + sourceLayout.name());
            ids[index] = row;
        }
        return ids;
    }

    public void copy(Buffer first, Buffer second, float fraction, float frameDelta, float simDelta,
                     boolean extrapolate, boolean interpolate, Transform firstTransform, Transform secondTransform,
                     Pose owner, Vec3 tile, VectorDataSet output, int maximumRows) {
        output.clear();
        output.count(Math.min(first.count(), maximumRows));
        Rebase a = Rebase.between(firstTransform, owner, tile), b = Rebase.between(secondTransform, owner, tile);
        for (var entry : mappings) {
            var source = entry.source();
            var target = entry.target();
            copyColumn(first, output, 1, source.integers(), target.integers());
            copyColumn(first, output, 2, source.halves(), target.halves());
            copyColumn(first, output, 0, source.floats(), target.floats());
        }
        for (var entry : mappings) {
            var source = entry.source();
            if (source.floats().count() == 0) continue;
            boolean blend = interpolate && source.interpolate();
            if (blend && hasPairedCurrent(source)) continue;
            boolean rebase = source.rebase() && !sourceLayout.localSpace();
            var prev = blend ? interpolatedPrevious(source) : null;
            float previousDelta = frameDelta * (1 + fraction);
            float previousFraction = previousDelta <= 0 ? 0 : 1 - Math.clamp(simDelta / previousDelta, 0, 1);
            for (int row = 0; row < output.count(); row++) {
                int next = blend ? first.nextRow(row) : -1;
                if (next >= second.count()) throw new IllegalArgumentException("Niagara cache particle mapping exceeds the next frame");
                int from = source.floats().start(), to = entry.target().floats().start();
                switch (source.sourceType()) {
                    case POSITION -> {
                        if (blend || rebase)
                            position(first, second, row, next, from, to, prev, blend, rebase, extrapolate, a, b,
                                    fraction, frameDelta, previousFraction, output);
                    }
                    case QUATERNION -> {
                        // UE selects the rebased interpolation callback when there is no paired previous quaternion.
                        boolean rotate = rebase || blend && prev == null;
                        if (blend || rotate) quaternion(first, second, row, next, from, to, prev, blend, rotate,
                                a, b, fraction, previousFraction, output);
                    }
                    case MATRIX -> {
                        if (rebase) {
                            var raw = new double[16];
                            for (int index = 0; index < raw.length; index++) raw[index] = value(first, from + index, row);
                            var rebased = new Matrix4d(a.matrix()).mul(new Matrix4d().set(raw));
                            var result = new float[16];
                            new Matrix4f(rebased).get(result);
                            for (int index = 0; index < result.length; index++) output.floating(to + index, row, result[index]);
                        }
                    }
                    case "/script/niagara.niagarafloat", "/script/coreuobject.vector2f",
                         "/script/coreuobject.vector3f", "/script/coreuobject.vector4f", "/script/coreuobject.linearcolor" -> {
                        if (blend && next >= 0) for (int index = 0; index < source.floats().count(); index++) {
                            float x = value(first, from + index, row), y = value(second, from + index, next);
                            output.floating(to + index, row, x + (y - x) * fraction);
                        }
                    }
                    default -> { }
                }
            }
        }
    }

    private void position(Buffer first, Buffer second, int row, int next, int from, int to, @Nullable Mapping previous,
                          boolean blend, boolean rebase, boolean extrapolate, Rebase a, Rebase b,
                          float fraction, float frameDelta, float prevFraction, VectorDataSet output) {
        var start = vector(first, from, row);
        if (!blend && extrapolate && sourceLayout.velocityComponent() >= 0)
            start.fma(fraction * frameDelta, vector(first, sourceLayout.velocityComponent(), row));
        var end = next < 0 ? new Vector3f(start) : vector(second, from, next);
        if (rebase) {
            rebasePosition(a.matrix(), start);
            rebasePosition(b.matrix(), end);
        }
        var current = blend ? start.lerp(end, fraction) : start;
        write(output, to, row, current);
        if (previous != null) {
            var prior = vector(first, previous.source().floats().start(), row);
            if (rebase) rebasePosition(a.matrix(), prior);
            write(output, previous.target().floats().start(), row, prior.lerp(current, prevFraction));
        }
    }

    private static void quaternion(Buffer first, Buffer second, int row, int next, int from, int to, @Nullable Mapping previous,
                                   boolean blend, boolean rebase, Rebase a, Rebase b,
                                   float fraction, float prevFraction, VectorDataSet output) {
        var start = quat(first, from, row);
        var end = next < 0 ? new Quaternionf(start) : quat(second, from, next);
        if (rebase) { start.mul(a.rotation()); end.mul(b.rotation()); }
        var current = blend ? start.slerp(end, fraction).normalize() : start;
        write(output, to, row, current);
        if (previous != null) {
            var prior = quat(first, previous.source().floats().start(), row);
            if (rebase) prior.mul(a.rotation());
            write(output, previous.target().floats().start(), row, prior.slerp(current, prevFraction).normalize());
        }
    }

    private static void copyColumn(Buffer source, VectorDataSet output, int type, Range from, Range to) {
        for (int column = 0; column < from.count(); column++) {
            var dest = output.component(type, to.start() + column);
            for (int row = 0; row < output.count(); row++) dest[row] = source.word(type, from.start() + column, row);
        }
    }

    private static float value(Buffer source, int component, int row) { return Float.intBitsToFloat(source.word(0, component, row)); }
    private static Vector3f vector(Buffer source, int component, int row) {
        return new Vector3f(value(source, component, row), value(source, component + 1, row), value(source, component + 2, row));
    }
    private static Quaternionf quat(Buffer source, int component, int row) {
        return new Quaternionf(value(source, component, row), value(source, component + 1, row),
                value(source, component + 2, row), value(source, component + 3, row));
    }
    private static void write(VectorDataSet output, int component, int row, Vector3f value) {
        output.floating(component, row, value.x);
        output.floating(component + 1, row, value.y);
        output.floating(component + 2, row, value.z);
    }
    private static void write(VectorDataSet output, int component, int row, Quaternionf value) {
        output.floating(component, row, value.x);
        output.floating(component + 1, row, value.y);
        output.floating(component + 2, row, value.z);
        output.floating(component + 3, row, value.w);
    }
}
