package com.kltyton.lumenfx.client.simulation.niagara.cache;

import com.kltyton.lumenfx.api.client.NiagaraCacheCapture.AttributeMode;
import com.kltyton.lumenfx.api.client.NiagaraCacheCapture.Options;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition.Emitter;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition.GpuSimulation;
import com.kltyton.lumenfx.effect.niagara.groom.HairStrandsDefinition;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache.Buffer;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache.Field;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache.Layout;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache.Range;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Source attribute selection and compact component layout used by runtime cache capture. */
final class NiagaraCacheAttributeSelection {
    private static final String POSITION = SourceNames.canonical(NiagaraDefinition.POSITION_TYPE);
    private static final String QUATERNION = "/script/coreuobject.quat4f";
    private static final String MATRIX = "/script/niagara.niagaramatrix";
    private static final String VECTOR = "/script/coreuobject.vector3f";
    private static final String INTEGER = "/script/niagara.niagaraint32";
    private static final Range EMPTY = new Range(-1, 0);

    private final NiagaraDefinition definition;
    private final Options options;
    private final Set<String> names;
    private final Set<String> rendererParticles;

    NiagaraCacheAttributeSelection(NiagaraDefinition definition, Options options) {
        this.definition = definition;
        this.options = options;
        var bound = new HashSet<String>();
        var rendering = new HashSet<String>();
        rendering.add("system.executionstate");
        for (var emitter : definition.emitters()) {
            String root = SourceNames.canonical(emitter.sourceName()) + ".";
            rendering.add(root + "executionstate");
            for (var variable : emitter.rendererBindings().variables()) rendering.add(SourceNames.canonical(variable.name()));
            var compiled = emitter.rendererBindings().compiledLayout();
            if (compiled != null)
                for (var variable : compiled.variables()) rendering.add(SourceNames.canonical(variable.key().name()));
            if (emitter.simulation() instanceof GpuSimulation gpu && HairStrandsDefinition.gpuUsesHairStrands(gpu.metadata())) {
                bound.add(root + "particles.nodeposition");
                bound.add(root + "particles.restposition");
            }
            if (emitter.simulation() instanceof NiagaraDefinition.StatelessSimulation) continue;
            for (var renderer : emitter.renderers()) {
                for (var entry : renderer.attributeBindings().values())
                    rendererAttribute(bound, rendering, root, entry.name(), entry.particle());
                rendererAttribute(bound, rendering, root, renderer.subImageBinding(), renderer.subImageParticleBinding());
                rendererAttribute(bound, rendering, root, renderer.uvScaleBinding(), renderer.uvScaleParticleBinding());
                rendererAttribute(bound, rendering, root, renderer.dynamicParameterBinding(), renderer.dynamicParameterParticleBinding());
                rendererAttribute(bound, rendering, root, renderer.customSortBinding(), renderer.customSortParticleBinding());
                rendererAttribute(bound, rendering, root, renderer.visibilityBinding(), renderer.visibilityParticleBinding());
                rendererAttribute(bound, rendering, root, renderer.ribbonLinkBinding(), true);
                rendererAttribute(bound, rendering, root, renderer.ribbonIdBinding(), true);
                rendererAttribute(bound, rendering, root, renderer.enabledBinding(), false);
            }
        }
        rendererParticles = Set.copyOf(bound);
        rendering.addAll(bound);
        names = switch (options.attributes().mode()) {
            case ALL -> Set.of();
            case RENDERING_ONLY -> Set.copyOf(rendering);
            case EXPLICIT -> options.attributes().names();
        };
    }

    private static void rendererAttribute(Set<String> particles, Set<String> parameters, String root,
                                          @Nullable String name, boolean particle) {
        if (name == null || name.isEmpty() || SourceNames.equal(name, "None")) return;
        String source = SourceNames.canonical(name);
        if (particle) particles.add(root + "particles." + particleName(source));
        else parameters.add(source.startsWith("emitter.") ? root + source.substring("emitter.".length()) : source);
    }

    private static String particleName(String name) {
        return name.startsWith("particles.") ? name.substring("particles.".length()) : name;
    }

    boolean capturesInterface(String name) { return names.isEmpty() || names.contains(SourceNames.canonical(name)); }

    Projection layout(Layout source, @Nullable Emitter emitter) {
        String root = emitter == null ? "" : SourceNames.canonical(emitter.sourceName()) + ".";
        String prefix = emitter == null ? "" : root + "particles.";
        var selected = new ArrayList<Field>();
        boolean unique = source.fields().stream().anyMatch(field -> field.name().equals("uniqueid")
                && field.sourceType().equals(INTEGER) && field.integers().count() == 1);
        Field velocity = null;
        boolean position = false;
        for (var field : source.fields()) {
            if (field.name().equals("velocity") && field.sourceType().equals(VECTOR) && field.floats().count() == 3)
                velocity = field;
            String fullName = emitter == null ? field.name() : prefix + particleName(field.name());
            if (options.attributes().mode() != AttributeMode.ALL && !names.contains(fullName)) continue;
            selected.add(field);
            position |= field.sourceType().equals(POSITION);
        }
        if (position && options.extrapolate() && velocity != null && !selected.contains(velocity)) selected.add(velocity);
        return Projection.compact(source, selected, field -> {
            String fieldName = particleName(field.name()), full = prefix + fieldName;
            String type = field.sourceType();
            var attributes = options.attributes();
            boolean positionType = type.equals(POSITION);
            boolean rotationType = type.equals(QUATERNION) || type.equals(MATRIX);
            boolean local = emitter != null ? source.localSpace() : definition.emitters().stream().anyMatch(entry -> entry.localSpace()
                    && field.name().startsWith(SourceNames.canonical(entry.sourceName()) + "."));
            boolean excluded = matches(attributes.rebaseExclude(), full, root + fieldName);
            boolean forced = matches(attributes.rebaseInclude(), full, root + fieldName);
            boolean rebase = options.rebase() && !local && !excluded
                    && (positionType || rotationType && (forced || rendererParticles.contains(full)));
            boolean interpolate = unique && options.interpolate() && (positionType || type.equals(QUATERNION)
                    || options.interpolateAllTypes() && interpolatable(type))
                    && !matches(attributes.interpolationExclude(), full, fieldName)
                    && (attributes.interpolationInclude().isEmpty() || matches(attributes.interpolationInclude(), full, fieldName));
            return new Field(field.name(), type, field.floats(), field.integers(), field.halves(), rebase, interpolate);
        }, options.extrapolate());
    }

    private static boolean matches(Set<String> names, String first, String second) { return names.contains(first) || names.contains(second); }
    private static boolean interpolatable(String type) {
        return switch (type) {
            case "/script/coreuobject.vector2f", VECTOR, "/script/coreuobject.vector4f",
                    "/script/coreuobject.linearcolor", "/script/niagara.niagarafloat" -> true;
            default -> false;
        };
    }

    record Projection(Layout source, Layout output, List<Field> retained) {
        static Projection reuse(Layout source, Layout output) {
            if (source.localSpace() != output.localSpace() || source.gpu() != output.gpu())
                throw new IllegalArgumentException("Niagara cache append changed the source simulation target for " + output.name());
            var retained = new ArrayList<Field>(output.fields().size());
            for (var field : output.fields()) {
                var match = source.fields().stream().filter(value -> value.name().equals(field.name()) && value.sourceType().equals(field.sourceType())
                        && value.floats().count() == field.floats().count() && value.integers().count() == field.integers().count()
                        && value.halves().count() == field.halves().count()).findFirst().orElseThrow(() ->
                                new IllegalArgumentException("Niagara cache append requires saved attribute " + output.name() + "." + field.name()));
                retained.add(match);
            }
            return new Projection(source, output, List.copyOf(retained));
        }

        static Projection compact(Layout source, List<Field> selected, java.util.function.UnaryOperator<Field> flags, boolean extrapolate) {
            var fields = new ArrayList<Field>(selected.size());
            int floats = 0, integers = 0, halves = 0, velocity = -1;
            for (var original : selected) {
                var field = flags.apply(original);
                int f = field.floats().count(), i = field.integers().count(), h = field.halves().count();
                if (extrapolate && field.name().equals("velocity") && field.sourceType().equals(VECTOR) && f == 3) velocity = floats;
                fields.add(new Field(field.name(), field.sourceType(), range(floats, f), range(integers, i), range(halves, h),
                        field.rebase(), field.interpolate()));
                floats += f;
                integers += i;
                halves += h;
            }
            return new Projection(source, new Layout(source.name(), source.localSpace(), source.gpu(), floats, integers, halves, velocity, fields),
                    List.copyOf(selected));
        }

        private static Range range(int start, int count) { return count == 0 ? EMPTY : new Range(start, count); }

        Buffer project(Buffer input) {
            if (source.equals(output)) return input;
            int count = input.count();
            int[] floats = new int[Math.multiplyExact(count, output.floatCount())];
            int[] integers = new int[Math.multiplyExact(count, output.integerCount())];
            short[] halves = new short[Math.multiplyExact(count, output.halfCount())];
            for (int index = 0; index < retained.size(); index++) {
                var original = retained.get(index);
                var destination = output.fields().get(index);
                copy(input, 0, original.floats(), destination.floats(), floats);
                copy(input, 1, original.integers(), destination.integers(), integers);
                for (int column = 0; column < original.halves().count(); column++) for (int row = 0; row < count; row++)
                    halves[(destination.halves().start() + column) * count + row] = (short)input.word(2, original.halves().start() + column, row);
            }
            int[] next = new int[input.nextRowCount()];
            for (int row = 0; row < next.length; row++) next[row] = input.nextRow(row);
            return new Buffer(count, input.acquireTag(), floats, integers, halves, input.idTable(), next, input.captureIds());
        }

        private static void copy(Buffer source, int type, Range original, Range destination, int[] values) {
            int count = source.count();
            for (int column = 0; column < original.count(); column++) for (int row = 0; row < count; row++)
                values[(destination.start() + column) * count + row] = source.word(type, original.start() + column, row);
        }
    }
}
