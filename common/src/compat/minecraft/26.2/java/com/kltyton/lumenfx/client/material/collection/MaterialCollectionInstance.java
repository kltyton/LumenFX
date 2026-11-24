package com.kltyton.lumenfx.client.material.collection;

import com.kltyton.lumenfx.effect.material.collection.MaterialCollectionDefinition;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.lang.ref.WeakReference;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jspecify.annotations.Nullable;

/** World-owned MPC values preserve native comparison and update sequencing before frame publication. */
public final class MaterialCollectionInstance {
    public record ScalarValue(int bits, long revision) { }
    public record VectorValue(MaterialCollectionDefinition.Vector value, long revision) { }
    public record Snapshot(MaterialCollectionDefinition definition, Map<String, ScalarValue> scalars,
                           Map<String, VectorValue> vectors, Map<String, ScalarValue> scalarUpdates,
                           Map<String, VectorValue> vectorUpdates, long revision, long definitionRevision) {
        public Snapshot(MaterialCollectionDefinition definition, Map<String, ScalarValue> scalars,
                        Map<String, VectorValue> vectors, long revision) {
            this(definition, scalars, vectors, scalars, vectors, revision, 0);
        }
        public Snapshot {
            scalars = immutableNames(scalars); vectors = immutableNames(vectors);
            scalarUpdates = immutableNames(scalarUpdates); vectorUpdates = immutableNames(vectorUpdates);
        }
        public @Nullable Integer scalar(String name) {
            MaterialCollectionDefinition.Scalar parameter = definition.scalar(name);
            if (parameter == null) return null;
            ScalarValue value = scalars.get(name);
            return value == null ? parameter.defaultBits() : value.bits();
        }
        public MaterialCollectionDefinition.@Nullable Vector vector(String name) {
            MaterialCollectionDefinition.VectorParameter parameter = definition.vector(name);
            if (parameter == null) return null;
            VectorValue value = vectors.get(name);
            return value == null ? parameter.defaults() : value.value();
        }
        public int[] packed() {
            Map<String, Integer> scalarValues = new TreeMap<>(SourceNames.ASCII_CASE_INSENSITIVE);
            Map<String, MaterialCollectionDefinition.Vector> vectorValues = new TreeMap<>(SourceNames.ASCII_CASE_INSENSITIVE);
            scalars.forEach((name, value) -> scalarValues.put(name, value.bits()));
            vectors.forEach((name, value) -> vectorValues.put(name, value.value()));
            return definition.pack(scalarValues, vectorValues);
        }
    }

    private MaterialCollectionDefinition definition;
    private final WeakReference<ClientLevel> world;
    private final Map<String, ScalarValue> scalars = new TreeMap<>(SourceNames.ASCII_CASE_INSENSITIVE);
    private final Map<String, VectorValue> vectors = new TreeMap<>(SourceNames.ASCII_CASE_INSENSITIVE);
    private final Map<String, ScalarValue> scalarUpdates = new TreeMap<>(SourceNames.ASCII_CASE_INSENSITIVE);
    private final Map<String, VectorValue> vectorUpdates = new TreeMap<>(SourceNames.ASCII_CASE_INSENSITIVE);
    private long revision, definitionRevision;
    private boolean closed;

    public MaterialCollectionInstance(MaterialCollectionDefinition definition) { this(definition, null); }
    public MaterialCollectionInstance(MaterialCollectionDefinition definition, @Nullable ClientLevel world) {
        this.definition = Objects.requireNonNull(definition); this.world = new WeakReference<>(world);
    }

    public synchronized boolean setScalar(String name, float value) {
        if (closed || world.get() == null) return false;
        if (definition.scalar(name) == null) return false;
        ScalarValue previous = scalars.get(name);
        if (previous == null || Float.intBitsToFloat(previous.bits()) != value) {
            ScalarValue updated = new ScalarValue(Float.floatToRawIntBits(value), ++revision);
            scalars.put(name, updated); scalarUpdates.put(name, updated);
        }
        return true;
    }
    public synchronized boolean setVector(String name, MaterialCollectionDefinition.Vector value) {
        Objects.requireNonNull(value);
        if (closed || world.get() == null) return false;
        if (definition.vector(name) == null) return false;
        VectorValue previous = vectors.get(name);
        if (previous == null || !previous.value().sameValue(value)) {
            VectorValue updated = new VectorValue(value, ++revision);
            vectors.put(name, updated); vectorUpdates.put(name, updated);
        }
        return true;
    }
    public synchronized void reset() {
        scalars.clear(); vectors.clear(); ++revision;
    }
    public synchronized void definition(MaterialCollectionDefinition value) {
        Objects.requireNonNull(value);
        if (!SourceNames.equal(definition.path(), value.path())) throw new IllegalArgumentException("MPC instance cannot change source object");
        if (!definition.equals(value)) { definition = value; definitionRevision = ++revision; }
    }
    public synchronized Snapshot snapshot() { return new Snapshot(definition, scalars, vectors, scalarUpdates, vectorUpdates, revision, definitionRevision); }
    public synchronized void close() { closed = true; }

    private static <T> Map<String, T> immutableNames(Map<String, T> source) {
        Map<String, T> result = new TreeMap<>(SourceNames.ASCII_CASE_INSENSITIVE);
        result.putAll(source);
        return Collections.unmodifiableMap(result);
    }
}
