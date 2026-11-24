package com.kltyton.lumenfx.effect.material.collection;

import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** MPC inheritance keeps name-based getters separate from GUID-based uniform packing. */
public final class MaterialCollectionDefinition {
    public record Vector(int x, int y, int z, int w) {
        public static Vector of(float x, float y, float z, float w) {
            return new Vector(Float.floatToRawIntBits(x), Float.floatToRawIntBits(y), Float.floatToRawIntBits(z), Float.floatToRawIntBits(w));
        }
        public int component(int index) { return switch (index) { case 0 -> x; case 1 -> y; case 2 -> z; case 3 -> w; default -> throw new IndexOutOfBoundsException(index); }; }
        public boolean sameValue(Vector other) {
            for (int index = 0; index < 4; index++) if (Float.intBitsToFloat(component(index)) != Float.intBitsToFloat(other.component(index))) return false;
            return true;
        }
    }
    public record Scalar(String name, String id, int defaultBits) {
        public Scalar { Objects.requireNonNull(name); Objects.requireNonNull(id); }
    }
    public record VectorParameter(String name, String id, Vector defaults) {
        public VectorParameter { Objects.requireNonNull(name); Objects.requireNonNull(id); Objects.requireNonNull(defaults); }
    }
    public record Location(int vector, int component) { }

    private final String path, stateId;
    private final @Nullable MaterialCollectionDefinition base;
    private final List<Scalar> scalars;
    private final List<VectorParameter> vectors;
    private final Map<String, Integer> scalarOverrides;
    private final Map<String, Vector> vectorOverrides;
    private final int vectorCount;

    public MaterialCollectionDefinition(String path, String stateId, @Nullable MaterialCollectionDefinition base,
                                        List<Scalar> scalars, List<VectorParameter> vectors,
                                        Map<String, Integer> scalarOverrides, Map<String, Vector> vectorOverrides) {
        this.path = Objects.requireNonNull(path); this.stateId = Objects.requireNonNull(stateId); this.base = base;
        this.scalars = List.copyOf(scalars); this.vectors = List.copyOf(vectors);
        this.scalarOverrides = Collections.unmodifiableMap(new LinkedHashMap<>(scalarOverrides));
        this.vectorOverrides = Collections.unmodifiableMap(new LinkedHashMap<>(vectorOverrides));
        vectorCount = Math.addExact(base == null ? 0 : base.vectorCount, Math.addExact(Math.addExact(scalars.size(), 3) / 4, vectors.size()));
    }
    public String path() { return path; }
    public String stateId() { return stateId; }
    public @Nullable MaterialCollectionDefinition base() { return base; }
    public List<Scalar> scalars() { return scalars; }
    public List<VectorParameter> vectors() { return vectors; }
    public int vectorCount() { return vectorCount; }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof MaterialCollectionDefinition value)) return false;
        return path.equals(value.path) && stateId.equals(value.stateId) && Objects.equals(base, value.base)
                && scalars.equals(value.scalars) && vectors.equals(value.vectors)
                && scalarOverrides.equals(value.scalarOverrides) && vectorOverrides.equals(value.vectorOverrides);
    }
    @Override public int hashCode() { return Objects.hash(path, stateId, base, scalars, vectors, scalarOverrides, vectorOverrides); }

    public @Nullable Scalar scalar(String name) {
        for (MaterialCollectionDefinition collection = this; collection != null; collection = collection.base)
            for (Scalar value : collection.scalars) if (SourceNames.equal(value.name(), name)) return value;
        return null;
    }
    public @Nullable VectorParameter vector(String name) {
        for (MaterialCollectionDefinition collection = this; collection != null; collection = collection.base)
            for (VectorParameter value : collection.vectors) if (SourceNames.equal(value.name(), name)) return value;
        return null;
    }
    public @Nullable String id(String name) {
        for (MaterialCollectionDefinition collection = this; collection != null; collection = collection.base) {
            for (Scalar value : collection.scalars) if (SourceNames.equal(value.name(), name)) return value.id();
            for (VectorParameter value : collection.vectors) if (SourceNames.equal(value.name(), name)) return value.id();
        }
        return null;
    }
    public Location location(String id) {
        int scalarBase = vectorCount;
        for (MaterialCollectionDefinition collection = this; collection != null; collection = collection.base) {
            int vectorBase = scalarBase - collection.vectors.size();
            scalarBase = vectorBase - (collection.scalars.size() + 3) / 4;
            for (int index = 0; index < collection.scalars.size(); index++)
                if (collection.scalars.get(index).id().equals(id)) return new Location(scalarBase + index / 4, index % 4);
            for (int index = 0; index < collection.vectors.size(); index++)
                if (collection.vectors.get(index).id().equals(id)) return new Location(vectorBase + index, -1);
        }
        return new Location(-1, -1);
    }

    public int[] pack(Map<String, Integer> scalarInstance, Map<String, Vector> vectorInstance) {
        int[] data = new int[Math.multiplyExact(vectorCount, 4)];
        int scalarBase = vectorCount;
        for (MaterialCollectionDefinition collection = this; collection != null; collection = collection.base) {
            int vectorBase = scalarBase - collection.vectors.size();
            scalarBase = vectorBase - (collection.scalars.size() + 3) / 4;
            for (int index = 0; index < collection.scalars.size(); index++) {
                Scalar parameter = collection.scalars.get(index);
                Integer value = named(scalarInstance, parameter.name());
                if (value == null) {
                    value = parameter.defaultBits();
                    for (MaterialCollectionDefinition derived = this; derived != collection; derived = derived.base) {
                        Integer inherited = derived.scalarOverrides.get(parameter.id());
                        if (inherited != null) { value = inherited; break; }
                    }
                }
                data[scalarBase * 4 + index] = value;
            }
            for (int index = 0; index < collection.vectors.size(); index++) {
                VectorParameter parameter = collection.vectors.get(index);
                Vector value = named(vectorInstance, parameter.name());
                if (value == null) {
                    value = parameter.defaults();
                    for (MaterialCollectionDefinition derived = this; derived != collection; derived = derived.base) {
                        Vector inherited = derived.vectorOverrides.get(parameter.id());
                        if (inherited != null) { value = inherited; break; }
                    }
                }
                for (int component = 0; component < 4; component++) data[(vectorBase + index) * 4 + component] = value.component(component);
            }
        }
        return data;
    }
    private static <T> @Nullable T named(Map<String, T> values, String name) {
        T value = values.get(name);
        if (value != null) return value;
        for (var entry : values.entrySet()) if (SourceNames.equal(entry.getKey(), name)) return entry.getValue();
        return null;
    }
}
