package com.kltyton.lumenfx.effect.render.mesh;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import com.kltyton.lumenfx.effect.MaterialDefinition;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.kltyton.lumenfx.effect.render.mesh.StaticMeshGeometry.Section;
import com.kltyton.lumenfx.effect.render.mesh.nanite.NaniteMeshAsset;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Immutable mesh LODs and source material/LOD selection metadata. */
public record StaticMeshAsset(List<MaterialSlot> materials, List<Lod> lods, IntSetting minimumLod,
                              boolean overrideMinimumLod, String lodGroup, boolean automaticLodScreenSizes,
                              Bounds renderBounds, Bounds extendedBounds, @Nullable MaterialDefinition fallbackMaterial,
                              QualitySetting qualityMinimumLod, Sampling sampling, @Nullable Streaming streaming,
                              @Nullable NaniteMeshAsset nanite) {
    public StaticMeshAsset(List<MaterialSlot> materials, List<Lod> lods, IntSetting minimumLod,
                           boolean overrideMinimumLod, String lodGroup, boolean automaticLodScreenSizes,
                           Bounds renderBounds, Bounds extendedBounds, @Nullable MaterialDefinition fallbackMaterial,
                           QualitySetting qualityMinimumLod, Sampling sampling, @Nullable Streaming streaming) {
        this(materials, lods, minimumLod, overrideMinimumLod, lodGroup, automaticLodScreenSizes, renderBounds, extendedBounds,
                fallbackMaterial, qualityMinimumLod, sampling, streaming, null);
    }

    public StaticMeshAsset(List<MaterialSlot> materials, List<Lod> lods, IntSetting minimumLod,
                           boolean overrideMinimumLod, String lodGroup, boolean automaticLodScreenSizes,
                           Bounds renderBounds, Bounds extendedBounds, @Nullable MaterialDefinition fallbackMaterial,
                           QualitySetting qualityMinimumLod, Sampling sampling) {
        this(materials, lods, minimumLod, overrideMinimumLod, lodGroup, automaticLodScreenSizes, renderBounds, extendedBounds,
                fallbackMaterial, qualityMinimumLod, sampling, null);
    }
    public StaticMeshAsset(List<MaterialSlot> materials, List<Lod> lods, IntSetting minimumLod,
                           boolean overrideMinimumLod, String lodGroup, boolean automaticLodScreenSizes,
                           Bounds renderBounds, Bounds extendedBounds, @Nullable MaterialDefinition fallbackMaterial, QualitySetting qualityMinimumLod) {
        this(materials, lods, minimumLod, overrideMinimumLod, lodGroup, automaticLodScreenSizes, renderBounds, extendedBounds,
                fallbackMaterial, qualityMinimumLod, Sampling.EMPTY);
    }
    public StaticMeshAsset(List<MaterialSlot> materials, List<Lod> lods, IntSetting minimumLod,
                           boolean overrideMinimumLod, String lodGroup, boolean automaticLodScreenSizes,
                           Bounds renderBounds, Bounds extendedBounds, @Nullable MaterialDefinition fallbackMaterial) {
        this(materials, lods, minimumLod, overrideMinimumLod, lodGroup, automaticLodScreenSizes, renderBounds, extendedBounds,
                fallbackMaterial, new QualitySetting(0, Map.of()));
    }
    public StaticMeshAsset(List<MaterialSlot> materials, List<Lod> lods, IntSetting minimumLod,
                           boolean overrideMinimumLod, String lodGroup, boolean automaticLodScreenSizes,
                           Bounds renderBounds, Bounds extendedBounds) {
        this(materials, lods, minimumLod, overrideMinimumLod, lodGroup, automaticLodScreenSizes, renderBounds, extendedBounds, null);
    }
    public StaticMeshAsset(List<MaterialSlot> materials, List<Lod> lods, IntSetting minimumLod,
                           boolean overrideMinimumLod, String lodGroup, boolean automaticLodScreenSizes) {
        this(materials, lods, minimumLod, overrideMinimumLod, lodGroup, automaticLodScreenSizes,
                lods.getFirst().bounds(), lods.getFirst().bounds());
    }
    public record MaterialSlot(String name, String importedName, @Nullable MaterialDefinition material) {
        public MaterialSlot(String name, String importedName) { this(name, importedName, null); }
        public MaterialSlot { Objects.requireNonNull(name); Objects.requireNonNull(importedName); }
    }
    public record Socket(String name, Vec3 location, Vec3 pitchYawRoll, Vec3 scale) {
        public Socket {
            Objects.requireNonNull(name);
            for (var vector : List.of(location, pitchYawRoll, scale))
                if (!Double.isFinite(vector.x) || !Double.isFinite(vector.y) || !Double.isFinite(vector.z))
                    throw new IllegalArgumentException("Non-finite static mesh socket transform");
        }
    }
    public record Sampling(List<Socket> sockets, boolean cpuAccess, boolean areaWeighted, boolean gpuAreaWeighted,
                           @Nullable StaticMeshDistanceField distanceField, boolean naniteFallbackStripped,
                           boolean omitsDefaultMeshBufferViews, int firstInlineLod, int firstAvailableLod,
                           boolean cookedFallbackUnavailable, Map<String, ShaderAccess> shaderAccess) {
        public static final Sampling EMPTY = new Sampling(List.of(), false, false);
        public Sampling(List<Socket> sockets, boolean cpuAccess, boolean areaWeighted, boolean gpuAreaWeighted,
                        @Nullable StaticMeshDistanceField distanceField, boolean naniteFallbackStripped,
                        boolean omitsDefaultMeshBufferViews, int firstInlineLod, int firstAvailableLod) {
            this(sockets, cpuAccess, areaWeighted, gpuAreaWeighted, distanceField, naniteFallbackStripped,
                    omitsDefaultMeshBufferViews, firstInlineLod, firstAvailableLod, false, Map.of());
        }
        public Sampling(List<Socket> sockets, boolean cpuAccess, boolean areaWeighted, boolean gpuAreaWeighted,
                        @Nullable StaticMeshDistanceField distanceField) {
            this(sockets, cpuAccess, areaWeighted, gpuAreaWeighted, distanceField, false, false, 0, 0);
        }
        public Sampling(List<Socket> sockets, boolean cpuAccess, boolean areaWeighted) { this(sockets, cpuAccess, areaWeighted, false, null); }
        public Sampling(List<Socket> sockets, boolean cpuAccess, boolean areaWeighted, boolean gpuAreaWeighted) {
            this(sockets, cpuAccess, areaWeighted, gpuAreaWeighted, null);
        }
        public Sampling {
            sockets = List.copyOf(sockets);
            var namedFormats = new TreeMap<String, ShaderAccess>(SourceNames.ASCII_CASE_INSENSITIVE);
            for (var entry : shaderAccess.entrySet()) {
                if (namedFormats.putIfAbsent(entry.getKey(), entry.getValue()) != null)
                    throw new IllegalArgumentException("Duplicate source mesh shader format " + entry.getKey());
            }
            shaderAccess = java.util.Collections.unmodifiableMap(namedFormats);
        }
        public boolean defaultMeshBufferViews(String format) {
            var access = shaderAccess.get(format);
            return access == null ? !omitsDefaultMeshBufferViews : access.defaultViews();
        }
        public boolean fallbackAvailable(String format) {
            if (naniteFallbackStripped || cookedFallbackUnavailable) return false;
            var access = shaderAccess.get(format);
            return access == null || access.fallbackAvailable();
        }
    }
    public record ShaderAccess(boolean defaultViews, boolean fallbackAvailable) { }
    public record IntSetting(int value, Map<String, Integer> overrides) {
        public IntSetting { overrides = Map.copyOf(overrides); }
    }
    public record QualitySetting(int value, Map<Integer, Integer> overrides) {
        public QualitySetting { overrides = Map.copyOf(overrides); }
        public int at(int quality) { return quality < 0 ? value : overrides.getOrDefault(quality, value); }
    }
    public record FloatSetting(float value, Map<String, Float> overrides) {
        public FloatSetting {
            overrides = Map.copyOf(overrides);
            if (!Float.isFinite(value) || overrides.values().stream().anyMatch(number -> !Float.isFinite(number)))
                throw new IllegalArgumentException("Non-finite static mesh LOD setting");
        }
    }
    public record Bounds(Vec3 origin, Vec3 extent, float radius) {
        public Bounds {
            for (Vec3 vector : List.of(origin, extent))
                if (!Double.isFinite(vector.x) || !Double.isFinite(vector.y) || !Double.isFinite(vector.z))
                    throw new IllegalArgumentException("Non-finite static mesh bounds");
            if (extent.x < 0 || extent.y < 0 || extent.z < 0 || !Float.isFinite(radius) || radius < 0)
                throw new IllegalArgumentException("Invalid static mesh bounds");
        }
    }
    public record Lod(@Nullable StaticMeshGeometry geometry, Bounds bounds, FloatSetting screenSize, boolean hasVertexColors,
                      int vertexCount, List<Section> sections, @Nullable CpuBufferAccess cpuBufferAccess,
                      @Nullable Map<String, ShaderBuffers> gpuBufferPolicy) {
        public Lod(StaticMeshGeometry geometry, Bounds bounds, FloatSetting screenSize) {
            this(geometry, bounds, screenSize, geometry.vertexCount() != 0);
        }
        public Lod(StaticMeshGeometry geometry, Bounds bounds, FloatSetting screenSize, boolean hasVertexColors) {
            this(geometry, bounds, screenSize, hasVertexColors, geometry.vertexCount(), geometry.sections(), null, null);
        }
        public Lod(@Nullable StaticMeshGeometry geometry, Bounds bounds, FloatSetting screenSize, boolean hasVertexColors,
                   int vertexCount, List<Section> sections) {
            this(geometry, bounds, screenSize, hasVertexColors, vertexCount, sections, null, null);
        }
        public Lod {
            Objects.requireNonNull(bounds);
            Objects.requireNonNull(screenSize);
            sections = List.copyOf(sections);
            if (gpuBufferPolicy != null) {
                var formats = new TreeMap<String, ShaderBuffers>(SourceNames.ASCII_CASE_INSENSITIVE);
                for (var entry : gpuBufferPolicy.entrySet())
                    if (formats.putIfAbsent(entry.getKey(), entry.getValue()) != null)
                        throw new IllegalArgumentException("Duplicate static mesh shader-buffer format " + entry.getKey());
                gpuBufferPolicy = java.util.Collections.unmodifiableMap(formats);
            }
            if (vertexCount < 0 || geometry != null && (geometry.vertexCount() != vertexCount || !geometry.sections().equals(sections)))
                throw new IllegalArgumentException("Static mesh LOD metadata disagrees with geometry");
            if (hasVertexColors && vertexCount == 0)
                throw new IllegalArgumentException("Empty static mesh LOD cannot contain vertex colors");
        }

        public boolean hasRenderBuffers() {
            return vertexCount != 0;
        }
    }
    public record CpuBufferAccess(boolean indices, boolean positions, boolean tangents, boolean textureCoordinates, boolean colors) { }
    public record ShaderBuffers(boolean indices, boolean positions, boolean tangents, boolean textureCoordinates, boolean colors) {
        public boolean validMesh() { return indices && positions && tangents; }
    }
    public interface Streaming {
        ResidentLods resident();
        void request(int firstLod);
        default void request(int firstLod, boolean visible) { request(firstLod); }
    }
    public record ResidentLods(int firstLod, Map<Integer, StaticMeshGeometry> streamed) {
        private static final ResidentLods ALL = new ResidentLods(0, Map.of());
        public ResidentLods { streamed = Map.copyOf(streamed); }
    }
    public StaticMeshAsset {
        materials = List.copyOf(materials); lods = List.copyOf(lods);
        Objects.requireNonNull(minimumLod); Objects.requireNonNull(lodGroup);
        Objects.requireNonNull(renderBounds); Objects.requireNonNull(extendedBounds);
        Objects.requireNonNull(qualityMinimumLod);
        Objects.requireNonNull(sampling);
        if (lods.isEmpty()) throw new IllegalArgumentException("Static mesh has no LODs");
        if (sampling.firstInlineLod() < 0 || sampling.firstInlineLod() >= lods.size()
                || sampling.firstAvailableLod() < 0 || sampling.firstAvailableLod() >= lods.size())
            throw new IllegalArgumentException("Static mesh cook metadata references a missing LOD");
    }

    /** First cooked LOD containing render buffers. */
    public int firstValidLod(int minimum) {
        for (int index = Math.clamp(minimum, 0, lods.size() - 1); index < lods.size(); index++) {
            if (lods.get(index).hasRenderBuffers()) return index;
        }
        return -1;
    }

    public ResidentLods resident() { return streaming == null ? ResidentLods.ALL : streaming.resident(); }

    public @Nullable StaticMeshGeometry residentGeometry(int index, ResidentLods resident) {
        if (index < resident.firstLod() || index < 0 || index >= lods.size()) return null;
        var inline = lods.get(index).geometry();
        return inline == null ? resident.streamed().get(index) : inline;
    }

    public int firstResidentLod(int minimum, ResidentLods resident) {
        for (int index = Math.max(Math.max(0, minimum), resident.firstLod()); index < lods.size(); index++) {
            if (lods.get(index).hasRenderBuffers() && residentGeometry(index, resident) != null) return index;
        }
        return -1;
    }
}
