package com.kltyton.lumenfx.api.client.parameter;

import com.kltyton.lumenfx.api.client.component.ComponentTickWindow;
import com.kltyton.lumenfx.api.client.parameter.ActorComponentInputs.Velocity;
import com.kltyton.lumenfx.api.client.parameter.ObjectPropertyInputs.Transform;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.kltyton.lumenfx.effect.render.mesh.StaticMeshAsset;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/** Source meshes and component state for one effect instance, sampled before the Niagara tick. */
public record NiagaraStaticMeshInputs(Map<String, Source> components, Map<String, Source> actors,
                                     Map<String, Source> parameters, @Nullable Source attachment) {
    public static final NiagaraStaticMeshInputs EMPTY = new NiagaraStaticMeshInputs(Map.of(), Map.of(), Map.of(), null);

    public NiagaraStaticMeshInputs {
        components = names(components);
        actors = names(actors);
        parameters = names(parameters);
    }

    /** Component and instance transforms are in absolute source axes and centimeters. */
    public record Source(@Nullable StaticMeshAsset mesh, @Nullable Transform component,
                         List<Transform> instances, @Nullable Velocity physicsVelocity,
                         int firstResidentLod, int firstInlineLod, boolean sceneDistanceField, @Nullable Object componentIdentity,
                         Map<Integer, VertexColors> vertexColors, Map<Integer, LodBuffers> lodBuffers, ComponentTickWindow tickWindow) {
        public Source(@Nullable StaticMeshAsset mesh, @Nullable Transform component, List<Transform> instances,
                      @Nullable Velocity physicsVelocity, int firstResidentLod, int firstInlineLod, boolean sceneDistanceField,
                      @Nullable Object componentIdentity, Map<Integer, VertexColors> vertexColors, Map<Integer, LodBuffers> lodBuffers) {
            this(mesh, component, instances, physicsVelocity, firstResidentLod, firstInlineLod, sceneDistanceField,
                    componentIdentity, vertexColors, lodBuffers, ComponentTickWindow.DEFAULT);
        }

        public Source(@Nullable StaticMeshAsset mesh, @Nullable Transform component, List<Transform> instances,
                      @Nullable Velocity physicsVelocity, int firstResidentLod, int firstInlineLod, boolean sceneDistanceField,
                      @Nullable Object componentIdentity, Map<Integer, VertexColors> vertexColors) {
            this(mesh, component, instances, physicsVelocity, firstResidentLod, firstInlineLod, sceneDistanceField,
                    componentIdentity, vertexColors, Map.of());
        }

        public Source(@Nullable StaticMeshAsset mesh, @Nullable Transform component, List<Transform> instances,
                      @Nullable Velocity physicsVelocity, int firstResidentLod, int firstInlineLod, boolean sceneDistanceField,
                      @Nullable Object componentIdentity) {
            this(mesh, component, instances, physicsVelocity, firstResidentLod, firstInlineLod, sceneDistanceField, componentIdentity, Map.of());
        }
        public Source(@Nullable StaticMeshAsset mesh, @Nullable Transform component, List<Transform> instances,
                      @Nullable Velocity physicsVelocity, int firstResidentLod, int firstInlineLod, boolean sceneDistanceField) {
            this(mesh, component, instances, physicsVelocity, firstResidentLod, firstInlineLod, sceneDistanceField, null);
        }
        public Source(@Nullable StaticMeshAsset mesh, @Nullable Transform component, List<Transform> instances,
                      @Nullable Velocity physicsVelocity, int firstResidentLod, int firstInlineLod) {
            this(mesh, component, instances, physicsVelocity, firstResidentLod, firstInlineLod, component != null);
        }
        public Source {
            Objects.requireNonNull(tickWindow);
            instances = List.copyOf(instances);
            vertexColors = Map.copyOf(vertexColors);
            lodBuffers = Map.copyOf(lodBuffers);
            if (firstResidentLod < 0 || firstInlineLod < 0 || component == null && (!instances.isEmpty() || sceneDistanceField || !vertexColors.isEmpty()))
                throw new IllegalArgumentException("Invalid Niagara static mesh component state");
            for (int lod : lodBuffers.keySet()) {
                if (mesh == null || lod < 0 || lod >= mesh.lods().size())
                    throw new IllegalArgumentException("Static mesh buffer state must reference a source LOD");
            }
            for (var entry : vertexColors.entrySet()) {
                int lod = entry.getKey();
                if (mesh == null || lod < 0 || lod >= mesh.lods().size()
                        || entry.getValue().size() != mesh.lods().get(lod).vertexCount())
                    throw new IllegalArgumentException("Static mesh vertex colors must match the source LOD vertex count");
            }
        }
        public static Source asset(StaticMeshAsset mesh) {
            return new Source(mesh, null, List.of(), null, mesh.sampling().firstAvailableLod(), mesh.sampling().firstInlineLod());
        }
    }

    /** CPU arrays visible in a resident LOD snapshot. Component override colors have their own access flag. */
    public record CpuBuffers(boolean indices, boolean positions, boolean tangents, boolean textureCoordinates, boolean colors) {
        public static final CpuBuffers ALL = new CpuBuffers(true, true, true, true, true);
        public static final CpuBuffers NONE = new CpuBuffers(false, false, false, false, false);
    }

    /** Source RHI shader views for this LOD. Section, socket and transform data are stored independently. */
    public record GpuBuffers(boolean indices, boolean positions, boolean tangents, boolean textureCoordinates,
                             boolean colors, boolean areaSampling) {
        public static final GpuBuffers ALL = new GpuBuffers(true, true, true, true, true, true);
        public static final GpuBuffers NONE = new GpuBuffers(false, false, false, false, false, false);
        public boolean validMesh() { return indices && positions && tangents; }
    }

    public record LodBuffers(CpuBuffers cpu, GpuBuffers gpu) {
        public LodBuffers {
            Objects.requireNonNull(cpu);
            Objects.requireNonNull(gpu);
        }
    }

    /** Component vertex colors in the LOD vertex order, packed as 0xRRGGBBAA sRGB RGB and linear alpha. */
    public record VertexColors(int[] rgba, boolean cpuAccess) {
        public VertexColors(int[] rgba) { this(rgba, true); }
        public VertexColors { rgba = rgba.clone(); }
        @Override public int[] rgba() { return rgba.clone(); }
        public int size() { return rgba.length; }
        public int channel(int vertex, int channel) { return (rgba[vertex] >>> ((3 - channel) * 8)) & 255; }
        @Override public boolean equals(Object value) {
            return value instanceof VertexColors colors
                    && (this == colors || cpuAccess == colors.cpuAccess && Arrays.equals(rgba, colors.rgba));
        }
        @Override public int hashCode() { return 31 * Arrays.hashCode(rgba) + Boolean.hashCode(cpuAccess); }
    }

    private static Map<String, Source> names(Map<String, Source> source) {
        var result = new TreeMap<String, Source>(SourceNames.ASCII_CASE_INSENSITIVE);
        result.putAll(Map.copyOf(source));
        if (result.size() != source.size()) throw new IllegalArgumentException("Duplicate Niagara static mesh source name");
        return Collections.unmodifiableMap(result);
    }
}
