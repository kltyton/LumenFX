package com.kltyton.lumenfx.api.client.parameter;

import com.kltyton.lumenfx.effect.MaterialDefinition;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.kltyton.lumenfx.effect.render.material.MaterialInstanceParameters;
import com.kltyton.lumenfx.effect.render.material.MaterialInstanceParameters.Association;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** The mutable values of one material instance, shared by its render variants. */
public final class DynamicMaterialValues {
    public record Vector(float x, float y, float z, float w) { }
    public record DoubleVector(double x, double y, double z, double w) { }
    /** A source offline font's actual texture providers, keyed by its original page numbers. */
    public record OfflineFont(Map<Integer, TextureParameter> pages) {
        public OfflineFont {
            pages = Map.copyOf(pages);
            for (int page : pages.keySet())
                if (page < 0) throw new IllegalArgumentException("An offline font cannot have a negative texture page");
        }
    }
    public record ParameterInfo(String name, Association association, int index) {
        public ParameterInfo(String name) { this(name, Association.GLOBAL, -1); }
        public ParameterInfo {
            name = SourceNames.canonical(name);
            Objects.requireNonNull(association);
            if (name.isBlank() || (association == Association.GLOBAL ? index != -1 : index < 0))
                throw new IllegalArgumentException("Invalid dynamic material parameter identity");
        }
    }
    public record Snapshot(Map<String, Float> scalars, Map<String, Vector> vectors,
                           Map<ParameterInfo, NiagaraRuntimeVirtualTexture> runtimeTextures,
                           Map<ParameterInfo, NiagaraStreamingVirtualTexture> streamedTextures,
                           Map<ParameterInfo, TextureParameter> fontPages, Set<ParameterInfo> inheritedFonts,
                           Map<ParameterInfo, TextureParameter> textures,
                           Map<ParameterInfo, Float> associatedScalars, Map<ParameterInfo, Vector> associatedVectors,
                           Map<String, DoubleVector> doubleVectors,
                           Map<ParameterInfo, DoubleVector> associatedDoubleVectors,
                           Map<ParameterInfo, String> toonProfiles) {
        public Snapshot(Map<String, Float> scalars, Map<String, Vector> vectors) {
            this(scalars, vectors, Map.of(), Map.of());
        }
        public Snapshot(Map<String, Float> scalars, Map<String, Vector> vectors,
                        Map<ParameterInfo, NiagaraRuntimeVirtualTexture> runtimeTextures,
                        Map<ParameterInfo, NiagaraStreamingVirtualTexture> streamedTextures) {
            this(scalars, vectors, runtimeTextures, streamedTextures, Map.of());
        }
        public Snapshot(Map<String, Float> scalars, Map<String, Vector> vectors,
                        Map<ParameterInfo, NiagaraRuntimeVirtualTexture> runtimeTextures,
                        Map<ParameterInfo, NiagaraStreamingVirtualTexture> streamedTextures,
                        Map<ParameterInfo, TextureParameter> fontPages) {
            this(scalars, vectors, runtimeTextures, streamedTextures, fontPages, Set.of());
        }
        public Snapshot(Map<String, Float> scalars, Map<String, Vector> vectors,
                        Map<ParameterInfo, NiagaraRuntimeVirtualTexture> runtimeTextures,
                        Map<ParameterInfo, NiagaraStreamingVirtualTexture> streamedTextures,
                        Map<ParameterInfo, TextureParameter> fontPages, Set<ParameterInfo> inheritedFonts) {
            this(scalars, vectors, runtimeTextures, streamedTextures, fontPages, inheritedFonts, Map.of());
        }
        public Snapshot(Map<String, Float> scalars, Map<String, Vector> vectors,
                        Map<ParameterInfo, NiagaraRuntimeVirtualTexture> runtimeTextures,
                        Map<ParameterInfo, NiagaraStreamingVirtualTexture> streamedTextures,
                        Map<ParameterInfo, TextureParameter> fontPages, Set<ParameterInfo> inheritedFonts,
                        Map<ParameterInfo, TextureParameter> textures) {
            this(scalars, vectors, runtimeTextures, streamedTextures, fontPages, inheritedFonts, textures, Map.of(), Map.of(), Map.of());
        }
        public Snapshot(Map<String, Float> scalars, Map<String, Vector> vectors,
                        Map<ParameterInfo, NiagaraRuntimeVirtualTexture> runtimeTextures,
                        Map<ParameterInfo, NiagaraStreamingVirtualTexture> streamedTextures,
                        Map<ParameterInfo, TextureParameter> fontPages, Set<ParameterInfo> inheritedFonts,
                        Map<ParameterInfo, TextureParameter> textures,
                        Map<ParameterInfo, Float> associatedScalars, Map<ParameterInfo, Vector> associatedVectors,
                        Map<String, DoubleVector> doubleVectors) {
            this(scalars, vectors, runtimeTextures, streamedTextures, fontPages, inheritedFonts, textures,
                    associatedScalars, associatedVectors, doubleVectors, Map.of(), Map.of());
        }
        public Snapshot {
            scalars = Map.copyOf(scalars);
            vectors = Map.copyOf(vectors);
            runtimeTextures = Map.copyOf(runtimeTextures);
            streamedTextures = Map.copyOf(streamedTextures);
            fontPages = Map.copyOf(fontPages);
            inheritedFonts = Set.copyOf(inheritedFonts);
            textures = Map.copyOf(textures);
            associatedScalars = Map.copyOf(associatedScalars);
            associatedVectors = Map.copyOf(associatedVectors);
            doubleVectors = Map.copyOf(doubleVectors);
            associatedDoubleVectors = Map.copyOf(associatedDoubleVectors);
            toonProfiles = Map.copyOf(toonProfiles);
        }
        public @Nullable Float scalar(ParameterInfo info) {
            return info.association() == Association.GLOBAL ? scalars.get(info.name()) : associatedScalars.get(info);
        }
        public @Nullable Vector vector(ParameterInfo info) {
            return info.association() == Association.GLOBAL ? vectors.get(info.name()) : associatedVectors.get(info);
        }
        public @Nullable DoubleVector doubleVector(ParameterInfo info) {
            return info.association() == Association.GLOBAL ? doubleVectors.get(info.name()) : associatedDoubleVectors.get(info);
        }
        public @Nullable String toonProfile(ParameterInfo info) {
            return toonProfiles.get(info);
        }
        public boolean hasNumericValues() {
            return !scalars.isEmpty() || !vectors.isEmpty() || !associatedScalars.isEmpty() || !associatedVectors.isEmpty()
                    || !doubleVectors.isEmpty() || !associatedDoubleVectors.isEmpty() || !toonProfiles.isEmpty();
        }
    }
    private static final Snapshot EMPTY = new Snapshot(Map.of(), Map.of());
    private volatile Snapshot snapshot = EMPTY;

    public Snapshot snapshot() { return snapshot; }

    public synchronized void setScalar(String name, float value) {
        var current = snapshot;
        var updated = new HashMap<>(current.scalars());
        updated.put(SourceNames.canonical(name), value);
        snapshot = new Snapshot(updated, current.vectors(), current.runtimeTextures(), current.streamedTextures(),
                current.fontPages(), current.inheritedFonts(), current.textures(), current.associatedScalars(),
                current.associatedVectors(), current.doubleVectors(), current.associatedDoubleVectors(), current.toonProfiles());
    }

    public synchronized void setVector(String name, Vector value) {
        Objects.requireNonNull(value);
        var current = snapshot;
        var updated = new HashMap<>(current.vectors());
        updated.put(SourceNames.canonical(name), value);
        snapshot = new Snapshot(current.scalars(), updated, current.runtimeTextures(), current.streamedTextures(),
                current.fontPages(), current.inheritedFonts(), current.textures(), current.associatedScalars(),
                current.associatedVectors(), current.doubleVectors(), current.associatedDoubleVectors(), current.toonProfiles());
    }

    public synchronized void setScalar(ParameterInfo parameter, float value) {
        Objects.requireNonNull(parameter);
        if (parameter.association() == Association.GLOBAL) {
            setScalar(parameter.name(), value);
            return;
        }
        var current = snapshot;
        var updated = new HashMap<>(current.associatedScalars());
        updated.put(parameter, value);
        snapshot = new Snapshot(current.scalars(), current.vectors(), current.runtimeTextures(), current.streamedTextures(),
                current.fontPages(), current.inheritedFonts(), current.textures(), updated, current.associatedVectors(),
                current.doubleVectors(), current.associatedDoubleVectors(), current.toonProfiles());
    }

    public synchronized void setVector(ParameterInfo parameter, Vector value) {
        Objects.requireNonNull(parameter);
        Objects.requireNonNull(value);
        if (parameter.association() == Association.GLOBAL) {
            setVector(parameter.name(), value);
            return;
        }
        var current = snapshot;
        var updated = new HashMap<>(current.associatedVectors());
        updated.put(parameter, value);
        snapshot = new Snapshot(current.scalars(), current.vectors(), current.runtimeTextures(), current.streamedTextures(),
                current.fontPages(), current.inheritedFonts(), current.textures(), current.associatedScalars(), updated,
                current.doubleVectors(), current.associatedDoubleVectors(), current.toonProfiles());
    }

    public synchronized void setDoubleVector(String parameter, DoubleVector value) {
        Objects.requireNonNull(value);
        var current = snapshot;
        var updated = new HashMap<>(current.doubleVectors());
        updated.put(SourceNames.canonical(parameter), value);
        snapshot = new Snapshot(current.scalars(), current.vectors(), current.runtimeTextures(), current.streamedTextures(),
                current.fontPages(), current.inheritedFonts(), current.textures(), current.associatedScalars(),
                current.associatedVectors(), updated, current.associatedDoubleVectors(), current.toonProfiles());
    }

    public synchronized void setDoubleVector(ParameterInfo parameter, DoubleVector value) {
        Objects.requireNonNull(parameter);
        Objects.requireNonNull(value);
        if (parameter.association() == Association.GLOBAL) {
            setDoubleVector(parameter.name(), value);
            return;
        }
        var current = snapshot;
        var updated = new HashMap<>(current.associatedDoubleVectors());
        updated.put(parameter, value);
        snapshot = new Snapshot(current.scalars(), current.vectors(), current.runtimeTextures(), current.streamedTextures(),
                current.fontPages(), current.inheritedFonts(), current.textures(), current.associatedScalars(),
                current.associatedVectors(), current.doubleVectors(), updated, current.toonProfiles());
    }

    public void setToonProfile(String parameter, @Nullable String profilePath) {
        setToonProfile(new ParameterInfo(parameter), profilePath);
    }

    /** Rebinds a Substrate Toon profile parameter; null restores the inherited material default. */
    public synchronized void setToonProfile(ParameterInfo parameter, @Nullable String profilePath) {
        Objects.requireNonNull(parameter);
        String normalized = normalizeToonProfile(profilePath);
        var current = snapshot;
        if (Objects.equals(current.toonProfiles().get(parameter), normalized)) return;
        var updated = new HashMap<>(current.toonProfiles());
        if (normalized == null) updated.remove(parameter);
        else updated.put(parameter, normalized);
        snapshot = new Snapshot(current.scalars(), current.vectors(), current.runtimeTextures(), current.streamedTextures(),
                current.fontPages(), current.inheritedFonts(), current.textures(), current.associatedScalars(),
                current.associatedVectors(), current.doubleVectors(), current.associatedDoubleVectors(), updated);
    }

    private static @Nullable String normalizeToonProfile(@Nullable String source) {
        if (source == null) return null;
        String value = source.replace(':', '.');
        if (SourceNames.equal(value, "None")) return "None";
        if (!value.startsWith("/") || !value.contains(".") || value.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Invalid Unreal Toon profile object path: " + source);
        return value;
    }

    public void setTexture(String parameter, @Nullable TextureParameter value) {
        setTexture(new ParameterInfo(parameter), value);
    }

    /** Rebinds an ordinary UTexture parameter by its source name, association and index. */
    public synchronized void setTexture(ParameterInfo parameter, @Nullable TextureParameter value) {
        Objects.requireNonNull(parameter);
        if (value == null) return;
        var current = snapshot;
        if (Objects.equals(current.textures().get(parameter), value)) return;
        var updated = new HashMap<>(current.textures());
        updated.put(parameter, value);
        snapshot = new Snapshot(current.scalars(), current.vectors(), current.runtimeTextures(), current.streamedTextures(),
                current.fontPages(), current.inheritedFonts(), updated, current.associatedScalars(),
                current.associatedVectors(), current.doubleVectors(), current.associatedDoubleVectors(), current.toonProfiles());
    }

    /** Restores a texture parameter override back to its inherited material default. */
    public synchronized void resetTexture(ParameterInfo parameter) {
        Objects.requireNonNull(parameter);
        var current = snapshot;
        if (!current.textures().containsKey(parameter)) return;
        var updated = new HashMap<>(current.textures());
        updated.remove(parameter);
        snapshot = new Snapshot(current.scalars(), current.vectors(), current.runtimeTextures(), current.streamedTextures(),
                current.fontPages(), current.inheritedFonts(), updated, current.associatedScalars(),
                current.associatedVectors(), current.doubleVectors(), current.associatedDoubleVectors(), current.toonProfiles());
    }

    /** Matches UMaterialInstanceDynamic's RVT setter. A null value leaves the current parameter intact. */
    public synchronized void setRuntimeVirtualTexture(ParameterInfo parameter, @Nullable NiagaraRuntimeVirtualTexture value) {
        Objects.requireNonNull(parameter);
        if (value == null) return;
        var current = snapshot;
        var updated = new HashMap<>(current.runtimeTextures());
        updated.put(parameter, value);
        snapshot = new Snapshot(current.scalars(), current.vectors(), updated, current.streamedTextures(),
                current.fontPages(), current.inheritedFonts(), current.textures(), current.associatedScalars(),
                current.associatedVectors(), current.doubleVectors(), current.associatedDoubleVectors(), current.toonProfiles());
    }

    /** Sets a streaming UTexture-style producer on a material's virtual sampler. */
    public synchronized void setStreamedVirtualTexture(ParameterInfo parameter, @Nullable NiagaraStreamingVirtualTexture value) {
        Objects.requireNonNull(parameter);
        if (value == null) return;
        var current = snapshot;
        var updated = new HashMap<>(current.streamedTextures());
        updated.put(parameter, value);
        snapshot = new Snapshot(current.scalars(), current.vectors(), current.runtimeTextures(), updated,
                current.fontPages(), current.inheritedFonts(), current.textures(), current.associatedScalars(),
                current.associatedVectors(), current.doubleVectors(), current.associatedDoubleVectors(), current.toonProfiles());
    }

    /** Sets the actual texture page. A null font or missing page restores the inherited material value. */
    public synchronized void setFontParameter(ParameterInfo parameter, @Nullable OfflineFont font, int page) {
        Objects.requireNonNull(parameter);
        TextureParameter texture = font == null ? null : font.pages().get(page);
        var current = snapshot;
        if (Objects.equals(current.fontPages().get(parameter), texture)
                && current.inheritedFonts().contains(parameter) == (texture == null)) return;
        var updated = new HashMap<>(current.fontPages());
        var inherited = new HashSet<>(current.inheritedFonts());
        if (texture == null) {
            updated.remove(parameter);
            inherited.add(parameter);
        } else {
            updated.put(parameter, texture);
            inherited.remove(parameter);
        }
        snapshot = new Snapshot(current.scalars(), current.vectors(), current.runtimeTextures(), current.streamedTextures(),
                updated, inherited, current.textures(), current.associatedScalars(), current.associatedVectors(),
                current.doubleVectors(), current.associatedDoubleVectors(), current.toonProfiles());
    }

    /** Matches UMaterialInstanceDynamic::ClearParameterValues. */
    public synchronized void clearParameterValues() {
        snapshot = EMPTY;
    }

    /** Matches UMaterialInstance::ClearParameterValuesInternal numeric and texture category flags. */
    public synchronized void clearParameterValues(boolean numeric, boolean textures) {
        if (!numeric && !textures) return;
        if (numeric && textures) {
            snapshot = EMPTY;
            return;
        }
        var current = snapshot;
        snapshot = new Snapshot(
                numeric ? Map.of() : current.scalars(),
                numeric ? Map.of() : current.vectors(),
                textures ? Map.of() : current.runtimeTextures(),
                textures ? Map.of() : current.streamedTextures(),
                textures ? Map.of() : current.fontPages(),
                textures ? Set.of() : current.inheritedFonts(),
                textures ? Map.of() : current.textures(),
                numeric ? Map.of() : current.associatedScalars(),
                numeric ? Map.of() : current.associatedVectors(),
                numeric ? Map.of() : current.doubleVectors(),
                numeric ? Map.of() : current.associatedDoubleVectors(),
                numeric ? Map.of() : current.toonProfiles());
    }

    /** Matches UMaterialInstanceDynamic::CopyParameterOverrides: clears existing overrides then copies source. */
    public synchronized void copyParameterOverrides(@Nullable DynamicMaterialValues source) {
        if (source == this) return;
        snapshot = source == null ? EMPTY : source.snapshot();
    }

    /** Matches UMaterialInstanceDynamic::CopyInterpParameters: merges source overrides onto this instance. */
    public synchronized void copyInterpParameters(@Nullable DynamicMaterialValues source) {
        if (source == null || source == this) return;
        var other = source.snapshot();
        var current = snapshot;
        var scalars = new HashMap<>(current.scalars());
        scalars.putAll(other.scalars());
        var vectors = new HashMap<>(current.vectors());
        vectors.putAll(other.vectors());
        var runtimeTextures = new HashMap<>(current.runtimeTextures());
        runtimeTextures.putAll(other.runtimeTextures());
        var streamedTextures = new HashMap<>(current.streamedTextures());
        streamedTextures.putAll(other.streamedTextures());
        var fontPages = new HashMap<>(current.fontPages());
        var inheritedFonts = new HashSet<>(current.inheritedFonts());
        for (var inherited : other.inheritedFonts()) {
            fontPages.remove(inherited);
            inheritedFonts.add(inherited);
        }
        for (var entry : other.fontPages().entrySet()) {
            fontPages.put(entry.getKey(), entry.getValue());
            inheritedFonts.remove(entry.getKey());
        }
        var textures = new HashMap<>(current.textures());
        textures.putAll(other.textures());
        var associatedScalars = new HashMap<>(current.associatedScalars());
        associatedScalars.putAll(other.associatedScalars());
        var associatedVectors = new HashMap<>(current.associatedVectors());
        associatedVectors.putAll(other.associatedVectors());
        var doubleVectors = new HashMap<>(current.doubleVectors());
        doubleVectors.putAll(other.doubleVectors());
        var associatedDoubleVectors = new HashMap<>(current.associatedDoubleVectors());
        associatedDoubleVectors.putAll(other.associatedDoubleVectors());
        var toonProfiles = new HashMap<>(current.toonProfiles());
        toonProfiles.putAll(other.toonProfiles());
        snapshot = new Snapshot(scalars, vectors, runtimeTextures, streamedTextures, fontPages, inheritedFonts,
                textures, associatedScalars, associatedVectors, doubleVectors, associatedDoubleVectors, toonProfiles);
    }

    /** Matches UMaterialInstanceDynamic::K2_InterpolateMaterialInstanceParams using base material defaults when absent. */
    public void interpolateParameters(@Nullable DynamicMaterialValues sourceA, @Nullable DynamicMaterialValues sourceB, float alpha) {
        interpolateParameters(sourceA, sourceB, alpha, null);
    }

    public synchronized void interpolateParameters(@Nullable DynamicMaterialValues sourceA, @Nullable DynamicMaterialValues sourceB,
                                                   float alpha, @Nullable MaterialDefinition baseMaterial) {
        if (sourceA == null || sourceB == null) return;
        var a = sourceA.snapshot();
        var b = sourceB.snapshot();
        var current = snapshot;
        var scalars = new HashMap<>(current.scalars());
        var scalarNames = new HashSet<>(a.scalars().keySet());
        scalarNames.addAll(b.scalars().keySet());
        for (String name : scalarNames) {
            Float valueA = a.scalars().get(name);
            Float valueB = b.scalars().get(name);
            float fallback = (valueA == null || valueB == null)
                    ? defaultScalar(baseMaterial, new ParameterInfo(name)) : 0f;
            float from = valueA != null ? valueA : fallback;
            float to = valueB != null ? valueB : fallback;
            scalars.put(name, from + (to - from) * alpha);
        }
        var associatedScalars = new HashMap<>(current.associatedScalars());
        var assocScalarKeys = new HashSet<>(a.associatedScalars().keySet());
        assocScalarKeys.addAll(b.associatedScalars().keySet());
        for (ParameterInfo info : assocScalarKeys) {
            Float valueA = a.associatedScalars().get(info);
            Float valueB = b.associatedScalars().get(info);
            float fallback = (valueA == null || valueB == null) ? defaultScalar(baseMaterial, info) : 0f;
            float from = valueA != null ? valueA : fallback;
            float to = valueB != null ? valueB : fallback;
            associatedScalars.put(info, from + (to - from) * alpha);
        }
        var vectors = new HashMap<>(current.vectors());
        var vectorNames = new HashSet<>(a.vectors().keySet());
        vectorNames.addAll(b.vectors().keySet());
        for (String name : vectorNames) {
            Vector valueA = a.vectors().get(name);
            Vector valueB = b.vectors().get(name);
            Vector fallback = (valueA == null || valueB == null)
                    ? defaultVector(baseMaterial, new ParameterInfo(name)) : new Vector(0f, 0f, 0f, 0f);
            vectors.put(name, lerpVector(valueA != null ? valueA : fallback, valueB != null ? valueB : fallback, alpha));
        }
        var associatedVectors = new HashMap<>(current.associatedVectors());
        var assocVectorKeys = new HashSet<>(a.associatedVectors().keySet());
        assocVectorKeys.addAll(b.associatedVectors().keySet());
        for (ParameterInfo info : assocVectorKeys) {
            Vector valueA = a.associatedVectors().get(info);
            Vector valueB = b.associatedVectors().get(info);
            Vector fallback = (valueA == null || valueB == null)
                    ? defaultVector(baseMaterial, info) : new Vector(0f, 0f, 0f, 0f);
            associatedVectors.put(info, lerpVector(valueA != null ? valueA : fallback, valueB != null ? valueB : fallback, alpha));
        }
        snapshot = new Snapshot(scalars, vectors, current.runtimeTextures(), current.streamedTextures(),
                current.fontPages(), current.inheritedFonts(), current.textures(), associatedScalars,
                associatedVectors, current.doubleVectors(), current.associatedDoubleVectors(), current.toonProfiles());
    }

    private static Vector lerpVector(Vector a, Vector b, float alpha) {
        return new Vector(
                a.x() + (b.x() - a.x()) * alpha,
                a.y() + (b.y() - a.y()) * alpha,
                a.z() + (b.z() - a.z()) * alpha,
                a.w() + (b.w() - a.w()) * alpha);
    }

    private static float defaultScalar(@Nullable MaterialDefinition baseMaterial, ParameterInfo info) {
        if (baseMaterial == null) return 0f;
        for (var slot : baseMaterial.instanceParameters().layout()) {
            if (slot.kind() == MaterialInstanceParameters.Kind.SCALAR && slotMatches(slot, info))
                return slot.defaults()[0];
        }
        return 0f;
    }

    private static Vector defaultVector(@Nullable MaterialDefinition baseMaterial, ParameterInfo info) {
        if (baseMaterial == null) return new Vector(0f, 0f, 0f, 0f);
        for (var slot : baseMaterial.instanceParameters().layout()) {
            if (slot.kind() == MaterialInstanceParameters.Kind.VECTOR && slotMatches(slot, info)) {
                float[] d = slot.defaults();
                return new Vector(d[0], d[1], d[2], d[3]);
            }
        }
        return new Vector(0f, 0f, 0f, 0f);
    }

    private static boolean slotMatches(MaterialInstanceParameters.Slot slot, ParameterInfo info) {
        return slot.association() == info.association() && slot.parameterIndex() == info.index()
                && SourceNames.equal(slot.name(), info.name());
    }
}
