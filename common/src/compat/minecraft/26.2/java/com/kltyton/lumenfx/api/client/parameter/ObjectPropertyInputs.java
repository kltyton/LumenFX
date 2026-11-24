package com.kltyton.lumenfx.api.client.parameter;

import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.kltyton.lumenfx.effect.niagara.parameter.store.NiagaraParameterLayout.Key;
import com.kltyton.lumenfx.effect.niagara.parameter.store.NiagaraParameterLayout.Storage;
import java.util.Collections;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/** Typed host publications for Niagara's object, actor and attached component readers. */
public record ObjectPropertyInputs(Map<String, Source> objects, @Nullable Source parentActor, @Nullable Source parentComponent) {
    public static final ObjectPropertyInputs EMPTY = new ObjectPropertyInputs(Map.of(), null, null);

    public ObjectPropertyInputs { objects = named(objects); }

    public enum Kind {
        FLOAT(1), VEC2(2), VEC3(3), VEC4(4), COLOR(4), POSITION(3), INT(1), BOOL(1);

        private final int width;
        Kind(int width) { this.width = width; }
        public int width() { return width; }
    }

    public record Value(Kind kind, double[] components) {
        public Value {
            Objects.requireNonNull(kind);
            if (components.length != kind.width()) throw new IllegalArgumentException("Invalid Niagara object property width");
            if (kind == Kind.INT && components[0] != (int)components[0])
                throw new IllegalArgumentException("Niagara integer property requires a signed 32-bit value");
            components = components.clone();
        }
        @Override public double[] components() { return components.clone(); }
        @Override public boolean equals(Object other) {
            return other instanceof Value value && kind == value.kind && Arrays.equals(components, value.components);
        }
        @Override public int hashCode() { return 31 * kind.hashCode() + Arrays.hashCode(components); }
    }

    /** Position is absolute, in source axes and centimeters. */
    public record Transform(double x, double y, double z, double qx, double qy, double qz, double qw,
                            double scaleX, double scaleY, double scaleZ) { }

    /** One output-only host getter. It is sampled on the client thread before Niagara execution. */
    public record Getter(Kind kind, Supplier<Value> read) {
        public Getter {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(read);
        }
        public Value sample() {
            var value = Objects.requireNonNull(read.get(), "Niagara object getter returned no value");
            if (value.kind() != kind) throw new IllegalStateException("Niagara object getter changed its declared type");
            return value;
        }
    }

    public record Source(Map<String, Value> properties, @Nullable Transform transform,
                         @Nullable Source root, Map<String, Source> components, Map<String, Getter> getters) {
        public Source {
            properties = named(properties);
            components = named(components);
            getters = named(getters);
        }
        public Source(Map<String, Value> properties, @Nullable Transform transform,
                      @Nullable Source root, Map<String, Source> components) {
            this(properties, transform, root, components, Map.of());
        }
        public Source(Map<String, Value> properties, @Nullable Transform transform) {
            this(properties, transform, null, Map.of());
        }
    }

    public record Remap(Key reader, String graphName, String propertyName) {
        public Remap {
            Objects.requireNonNull(reader);
            if (reader.storage() != Storage.INTERFACE || graphName.isBlank() || propertyName.isBlank())
                throw new IllegalArgumentException("Invalid Niagara object reader remap");
        }
    }

    private static <T> Map<String, T> named(Map<String, T> source) {
        if (source.isEmpty()) return Map.of();
        var result = new TreeMap<String, T>(SourceNames.ASCII_CASE_INSENSITIVE);
        source.forEach((key, value) -> {
            if (key.isBlank() || result.putIfAbsent(key, Objects.requireNonNull(value)) != null)
                throw new IllegalArgumentException("Invalid or duplicate Niagara object property name: " + key);
        });
        return Collections.unmodifiableMap(result);
    }
}
