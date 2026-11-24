package com.kltyton.lumenfx.effect.render.mesh;

import com.kltyton.lumenfx.effect.niagara.parameter.store.NiagaraParameterLayout.Key;
import com.kltyton.lumenfx.effect.render.mesh.nanite.NaniteRendererSettings;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public record MeshRendererSettings(List<Entry> entries, Facing facing, boolean lockedAxisEnabled,
                                   Vec3 lockedAxis, AxisSpace lockedAxisSpace, Vec3 boundsScale,
                                   boolean frustumCulling, boolean castShadows, @Nullable String meshesBinding,
                                   List<MaterialBinding> overrideMaterials, Map<String, String> materialRemap,
                                   SceneTransforms sceneTransforms, Map<String, String> dynamicMaterials, String materialVariant,
                                   @Nullable ArrayBinding meshArray, @Nullable NaniteRendererSettings nanite) {
    public MeshRendererSettings(List<Entry> entries, Facing facing, boolean lockedAxisEnabled, Vec3 lockedAxis, AxisSpace lockedAxisSpace,
                                Vec3 boundsScale, boolean frustumCulling, boolean castShadows, @Nullable String meshesBinding,
                                List<MaterialBinding> overrideMaterials, Map<String, String> materialRemap,
                                SceneTransforms sceneTransforms, Map<String, String> dynamicMaterials, String materialVariant,
                                @Nullable ArrayBinding meshArray) {
        this(entries, facing, lockedAxisEnabled, lockedAxis, lockedAxisSpace, boundsScale, frustumCulling, castShadows,
                meshesBinding, overrideMaterials, materialRemap, sceneTransforms, dynamicMaterials, materialVariant, meshArray, null);
    }
    public MeshRendererSettings(List<Entry> entries, Facing facing, boolean lockedAxisEnabled, Vec3 lockedAxis, AxisSpace lockedAxisSpace,
                                Vec3 boundsScale, boolean frustumCulling, boolean castShadows, @Nullable String meshesBinding,
                                List<MaterialBinding> overrideMaterials, Map<String, String> materialRemap,
                                SceneTransforms sceneTransforms, Map<String, String> dynamicMaterials, String materialVariant) {
        this(entries, facing, lockedAxisEnabled, lockedAxis, lockedAxisSpace, boundsScale, frustumCulling, castShadows,
                meshesBinding, overrideMaterials, materialRemap, sceneTransforms, dynamicMaterials, materialVariant, null);
    }
    public MeshRendererSettings(List<Entry> entries, Facing facing, boolean lockedAxisEnabled, Vec3 lockedAxis, AxisSpace lockedAxisSpace,
                                Vec3 boundsScale, boolean frustumCulling, boolean castShadows, @Nullable String meshesBinding,
                                List<MaterialBinding> overrideMaterials, Map<String, String> materialRemap, SceneTransforms sceneTransforms) {
        this(entries, facing, lockedAxisEnabled, lockedAxis, lockedAxisSpace, boundsScale, frustumCulling, castShadows,
                meshesBinding, overrideMaterials, materialRemap, sceneTransforms, Map.of(), "mesh");
    }
    public MeshRendererSettings(List<Entry> entries, Facing facing, boolean lockedAxisEnabled,
                                Vec3 lockedAxis, AxisSpace lockedAxisSpace, Vec3 boundsScale,
                                boolean frustumCulling, boolean castShadows, @Nullable String meshesBinding,
                                List<MaterialBinding> overrideMaterials, Map<String, String> materialRemap) {
        this(entries, facing, lockedAxisEnabled, lockedAxis, lockedAxisSpace, boundsScale, frustumCulling,
                castShadows, meshesBinding, overrideMaterials, materialRemap, SceneTransforms.LEGACY);
    }
    public MeshRendererSettings(List<Entry> entries, Facing facing, boolean lockedAxisEnabled,
                                Vec3 lockedAxis, AxisSpace lockedAxisSpace, Vec3 boundsScale,
                                boolean frustumCulling, boolean castShadows, @Nullable String meshesBinding) {
        this(entries, facing, lockedAxisEnabled, lockedAxis, lockedAxisSpace, boundsScale, frustumCulling, castShadows,
                meshesBinding, List.of(), Map.of());
    }
    public enum Facing { DEFAULT, VELOCITY, CAMERA_POSITION, CAMERA_PLANE }
    public enum AxisSpace { SIMULATION, WORLD, LOCAL }
    public enum PivotSpace { MESH, SIMULATION, WORLD, LOCAL }
    public enum LodMode { LOD_LEVEL, LOD_BIAS, BY_COMPONENT_BOUNDS, COMPONENT_ORIGIN, PER_PARTICLE }
    public enum SceneTransforms { LEGACY, GPU_SCENE, GPU_SCENE_COMPRESSED }
    public record ArrayBinding(Key slot, Map<String, List<Entry>> values) {
        public ArrayBinding {
            Objects.requireNonNull(slot);
            var copied = new LinkedHashMap<String, List<Entry>>();
            values.forEach((id, entries) -> copied.put(id, List.copyOf(entries)));
            values = Map.copyOf(copied);
        }
    }
    public record Rotation(float x, float y, float z, float w) {
        public Rotation {
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z) || !Float.isFinite(w))
                throw new IllegalArgumentException("Non-finite mesh rotation");
        }
    }
    public record IntegerBinding(@Nullable String name, int value) { }
    public record MaterialBinding(@Nullable String material, @Nullable String userParameter, @Nullable String defaultMaterial,
                                  String materialVariant) {
        public MaterialBinding(@Nullable String material, @Nullable String userParameter, @Nullable String defaultMaterial) {
            this(material, userParameter, defaultMaterial, "mesh");
        }
        public MaterialBinding(@Nullable String material, @Nullable String userParameter) { this(material, userParameter, null); }
        public MaterialBinding { Objects.requireNonNull(materialVariant); }
    }
    public record Entry(@Nullable StaticMeshAsset asset, @Nullable String assetBinding, List<MaterialBinding> materials,
                        Vec3 scale, Rotation rotation, Vec3 pivot, PivotSpace pivotSpace, LodMode lodMode,
                        IntegerBinding lodLevel, IntegerBinding lodBias, float lodDistanceFactor,
                        boolean useLodRange, int minimumLod, int maximumLod) {
        public Entry {
            materials = List.copyOf(materials);
            finite(scale); finite(pivot);
            Objects.requireNonNull(rotation); Objects.requireNonNull(pivotSpace); Objects.requireNonNull(lodMode);
            Objects.requireNonNull(lodLevel); Objects.requireNonNull(lodBias);
            if (!Float.isFinite(lodDistanceFactor) || lodDistanceFactor < 0 || minimumLod < 0 || maximumLod < minimumLod)
                throw new IllegalArgumentException("Invalid mesh LOD settings");
        }
    }
    public MeshRendererSettings {
        entries = List.copyOf(entries); Objects.requireNonNull(facing); Objects.requireNonNull(lockedAxisSpace);
        overrideMaterials = List.copyOf(overrideMaterials); materialRemap = Map.copyOf(materialRemap);
        dynamicMaterials = Map.copyOf(dynamicMaterials);
        Objects.requireNonNull(materialVariant);
        Objects.requireNonNull(sceneTransforms);
        if (nanite != null)
            for (var index : nanite.meshParameters().keySet())
                if (index < 0 || index >= entries.size() || entries.get(index).assetBinding() == null)
                    throw new IllegalArgumentException("Nanite mesh parameter has no corresponding slot");
        finite(lockedAxis); finite(boundsScale);
        float x = (float)lockedAxis.x, y = (float)lockedAxis.y, z = (float)lockedAxis.z;
        float square = x * x + y * y + z * z;
        if (square > 1.0e-8f) {
            float inverse = (float)(1 / Math.sqrt(square));
            lockedAxis = new Vec3(x * inverse, y * inverse, z * inverse);
        } else lockedAxis = new Vec3(0, 0, 1);
    }
    private static void finite(Vec3 vector) {
        if (!Double.isFinite(vector.x) || !Double.isFinite(vector.y) || !Double.isFinite(vector.z))
            throw new IllegalArgumentException("Non-finite mesh transform vector");
    }
}
