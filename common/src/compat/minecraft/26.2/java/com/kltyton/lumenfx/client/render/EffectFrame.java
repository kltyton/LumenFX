package com.kltyton.lumenfx.client.render;

import java.util.List;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuSpriteFrame;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuRibbonFrame;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuMeshFrame;
import com.kltyton.lumenfx.client.render.volume.NiagaraVolumeInstance;
import com.kltyton.lumenfx.client.render.temporal.geometry.ParticleMotionView.Snapshot;
import com.kltyton.lumenfx.effect.render.light.ParticleLight;
import com.kltyton.lumenfx.effect.render.light.scene.ScenePointLight;
import com.kltyton.lumenfx.effect.render.light.scene.SceneDirectionalLight;
import com.kltyton.lumenfx.client.render.scene.material.MaterialPrimitiveFrame;
import com.kltyton.lumenfx.effect.MaterialDefinition;
import com.kltyton.lumenfx.effect.render.MaterialDepth;
import com.kltyton.lumenfx.effect.render.fog.VolumetricFog;
import com.kltyton.lumenfx.effect.render.light.PrimitiveLighting;
import org.jspecify.annotations.Nullable;

public record EffectFrame(long generation, double time, double engineTime, double realTime, List<EffectMesh> meshes, List<NiagaraGpuSpriteFrame> gpuSprites,
                          List<NiagaraGpuRibbonFrame> gpuRibbons, float deltaTime, List<NiagaraGpuMeshFrame> gpuMeshes,
                          @Nullable Snapshot motionView, List<ParticleLight> lights,
                          List<com.kltyton.lumenfx.effect.render.decal.ParticleDecal> decals,
                          List<ScenePointLight> pointLights, List<NiagaraVolumeInstance> volumes,
                          List<SceneDirectionalLight> directionalLights) {
    public EffectFrame(long generation, double time, double engineTime, double realTime, List<EffectMesh> meshes,
                       List<NiagaraGpuSpriteFrame> sprites, List<NiagaraGpuRibbonFrame> ribbons, float deltaTime,
                       List<NiagaraGpuMeshFrame> gpuMeshes, @Nullable Snapshot motionView, List<ParticleLight> lights,
                       List<com.kltyton.lumenfx.effect.render.decal.ParticleDecal> decals, List<ScenePointLight> pointLights,
                       List<NiagaraVolumeInstance> volumes) {
        this(generation, time, engineTime, realTime, meshes, sprites, ribbons, deltaTime, gpuMeshes, motionView,
                lights, decals, pointLights, volumes, List.of());
    }
    public EffectFrame(long generation, double time, double engineTime, double realTime, List<EffectMesh> meshes,
                       List<NiagaraGpuSpriteFrame> sprites, List<NiagaraGpuRibbonFrame> ribbons, float deltaTime,
                       List<NiagaraGpuMeshFrame> gpuMeshes, @Nullable Snapshot motionView, List<ParticleLight> lights,
                       List<com.kltyton.lumenfx.effect.render.decal.ParticleDecal> decals, List<ScenePointLight> pointLights) {
        this(generation, time, engineTime, realTime, meshes, sprites, ribbons, deltaTime, gpuMeshes, motionView,
                lights, decals, pointLights, List.of());
    }
    public EffectFrame(long generation, double time, double engineTime, double realTime, List<EffectMesh> meshes,
                       List<NiagaraGpuSpriteFrame> sprites, List<NiagaraGpuRibbonFrame> ribbons, float deltaTime,
                       List<NiagaraGpuMeshFrame> gpuMeshes, @Nullable Snapshot motionView, List<ParticleLight> lights,
                       List<com.kltyton.lumenfx.effect.render.decal.ParticleDecal> decals) {
        this(generation, time, engineTime, realTime, meshes, sprites, ribbons, deltaTime, gpuMeshes, motionView,
                lights, decals, List.of());
    }
    public EffectFrame(long generation, double time, double engineTime, double realTime, List<EffectMesh> meshes,
                       List<NiagaraGpuSpriteFrame> sprites, List<NiagaraGpuRibbonFrame> ribbons, float deltaTime,
                       List<NiagaraGpuMeshFrame> gpuMeshes, @Nullable Snapshot motionView, List<ParticleLight> lights) {
        this(generation, time, engineTime, realTime, meshes, sprites, ribbons, deltaTime, gpuMeshes, motionView, lights, List.of());
    }
    public EffectFrame(long generation, double time, double engineTime, double realTime, List<EffectMesh> meshes,
                       List<NiagaraGpuSpriteFrame> sprites, List<NiagaraGpuRibbonFrame> ribbons, float deltaTime,
                       List<NiagaraGpuMeshFrame> gpuMeshes, @Nullable Snapshot motionView) {
        this(generation, time, engineTime, realTime, meshes, sprites, ribbons, deltaTime, gpuMeshes, motionView, List.of());
    }
    public EffectFrame(long generation, double time, double engineTime, double realTime, List<EffectMesh> meshes,
                       List<NiagaraGpuSpriteFrame> sprites, List<NiagaraGpuRibbonFrame> ribbons, float deltaTime, List<NiagaraGpuMeshFrame> gpuMeshes) {
        this(generation, time, engineTime, realTime, meshes, sprites, ribbons, deltaTime, gpuMeshes, null);
    }
    public EffectFrame {
        meshes = List.copyOf(meshes);
        gpuSprites = List.copyOf(gpuSprites);
        gpuRibbons = List.copyOf(gpuRibbons);
        gpuMeshes = List.copyOf(gpuMeshes);
        lights = List.copyOf(lights);
        decals = List.copyOf(decals);
        pointLights = List.copyOf(pointLights);
        volumes = List.copyOf(volumes);
        directionalLights = List.copyOf(directionalLights);
    }
    public EffectFrame(long generation, double time, double engineTime, double realTime, List<EffectMesh> meshes,
                       List<NiagaraGpuSpriteFrame> sprites, List<NiagaraGpuRibbonFrame> ribbons, float deltaTime) {
        this(generation, time, engineTime, realTime, meshes, sprites, ribbons, deltaTime, List.of());
    }
    public EffectFrame(long generation, double time, double engineTime, double realTime, List<EffectMesh> meshes,
                       List<NiagaraGpuSpriteFrame> sprites, List<NiagaraGpuRibbonFrame> ribbons) {
        this(generation, time, engineTime, realTime, meshes, sprites, ribbons, 0);
    }
    public EffectFrame(long generation, double time, List<EffectMesh> meshes, List<NiagaraGpuSpriteFrame> sprites,
                       List<NiagaraGpuRibbonFrame> ribbons) { this(generation, time, time, time, meshes, sprites, ribbons, 0); }
    public EffectFrame(long generation, double time, List<EffectMesh> meshes, List<NiagaraGpuSpriteFrame> sprites) {
        this(generation, time, time, time, meshes, sprites, List.of(), 0);
    }
    public EffectFrame(long generation, double time, List<EffectMesh> meshes) { this(generation, time, time, time, meshes, List.of(), List.of(), 0); }

    public boolean hasOpaqueDepthWriters() {
        return meshes.stream().anyMatch(mesh -> writesOpaqueDepth(mesh.key().material()))
                || gpuSprites.stream().anyMatch(sprite -> writesOpaqueDepth(sprite.material()))
                || gpuRibbons.stream().anyMatch(ribbon -> writesOpaqueDepth(ribbon.material()))
                || gpuMeshes.stream().anyMatch(mesh -> writesOpaqueDepth(mesh.material()));
    }

    public static boolean writesOpaqueDepth(MaterialDefinition material) {
        return material.blend() == MaterialDefinition.Blend.OPAQUE && material.depth().write()
                && material.depth().compare() != MaterialDepth.Compare.DISABLED
                && material.depth().compare() != MaterialDepth.Compare.NEVER;
    }

    public EffectFrame customDepthGeometry() {
        return new EffectFrame(generation, time, engineTime, realTime,
                meshes.stream().filter(mesh -> customDepth(mesh.key().material(), mesh.materialPrimitive())).toList(),
                gpuSprites.stream().filter(sprite -> customDepth(sprite.material(), sprite.materialPrimitive())).toList(),
                gpuRibbons.stream().filter(ribbon -> customDepth(ribbon.material(), ribbon.materialPrimitive())).toList(), deltaTime,
                gpuMeshes.stream().filter(mesh -> customDepth(mesh.material(), mesh.materialPrimitive())).toList());
    }

    public boolean hasGeometry() {
        return !meshes.isEmpty() || !gpuSprites.isEmpty() || !gpuRibbons.isEmpty() || !gpuMeshes.isEmpty();
    }

    public EffectFrame mainPassGeometry() {
        if (meshes.stream().allMatch(mesh -> MaterialPrimitiveFrame.drawsInMainPass(mesh.materialPrimitive()))
                && gpuSprites.stream().allMatch(sprite -> MaterialPrimitiveFrame.drawsInMainPass(sprite.materialPrimitive()))
                && gpuRibbons.stream().allMatch(ribbon -> MaterialPrimitiveFrame.drawsInMainPass(ribbon.materialPrimitive()))
                && gpuMeshes.stream().allMatch(mesh -> MaterialPrimitiveFrame.drawsInMainPass(mesh.materialPrimitive()))) return this;
        return new EffectFrame(generation, time, engineTime, realTime,
                meshes.stream().filter(mesh -> MaterialPrimitiveFrame.drawsInMainPass(mesh.materialPrimitive())).toList(),
                gpuSprites.stream().filter(sprite -> MaterialPrimitiveFrame.drawsInMainPass(sprite.materialPrimitive())).toList(),
                gpuRibbons.stream().filter(ribbon -> MaterialPrimitiveFrame.drawsInMainPass(ribbon.materialPrimitive())).toList(),
                deltaTime, gpuMeshes.stream().filter(mesh -> MaterialPrimitiveFrame.drawsInMainPass(mesh.materialPrimitive())).toList(),
                motionView, lights, decals, pointLights, volumes, directionalLights);
    }

    public boolean readsOpaqueSceneColor() {
        return meshes.stream().anyMatch(mesh -> readsOpaqueSceneColor(mesh.key().material()))
                || gpuSprites.stream().anyMatch(sprite -> readsOpaqueSceneColor(sprite.material()))
                || gpuRibbons.stream().anyMatch(ribbon -> readsOpaqueSceneColor(ribbon.material()))
                || gpuMeshes.stream().anyMatch(mesh -> readsOpaqueSceneColor(mesh.material()));
    }

    public static boolean readsOpaqueSceneColor(MaterialDefinition material) {
        return (material.deferredSceneTextures() & MaterialDefinition.RAW_SCENE_COLOR) != 0;
    }

    private static boolean customDepth(MaterialDefinition material, @Nullable MaterialPrimitiveFrame primitive) {
        return primitive != null && primitive.customDepthStencil().enabled() && material.writesCustomDepth();
    }

    public EffectFrame geometryPhase(boolean opaque) {
        return new EffectFrame(generation, time, engineTime, realTime,
                meshes.stream().filter(mesh -> (mesh.key().material().blend() == com.kltyton.lumenfx.effect.MaterialDefinition.Blend.OPAQUE) == opaque).toList(),
                gpuSprites.stream().filter(mesh -> (mesh.material().blend() == com.kltyton.lumenfx.effect.MaterialDefinition.Blend.OPAQUE) == opaque).toList(),
                gpuRibbons.stream().filter(mesh -> (mesh.material().blend() == com.kltyton.lumenfx.effect.MaterialDefinition.Blend.OPAQUE) == opaque).toList(),
                deltaTime,
                gpuMeshes.stream().filter(mesh -> (mesh.material().blend() == com.kltyton.lumenfx.effect.MaterialDefinition.Blend.OPAQUE) == opaque).toList(),
                motionView, opaque ? lights : List.of(), opaque ? decals : List.of(),
                opaque ? pointLights : List.of(), opaque ? volumes : List.of(), directionalLights);
    }

    public EffectFrame withPointLights(List<ScenePointLight> values) {
        return new EffectFrame(generation, time, engineTime, realTime, meshes, gpuSprites, gpuRibbons, deltaTime,
                gpuMeshes, motionView, lights, decals, values, volumes, directionalLights);
    }

    public boolean needsSceneColor() {
        return meshes.stream().anyMatch(mesh -> mesh.key().material().sceneColor())
                || gpuSprites.stream().anyMatch(mesh -> mesh.material().sceneColor())
                || gpuRibbons.stream().anyMatch(mesh -> mesh.material().sceneColor())
                || gpuMeshes.stream().anyMatch(mesh -> mesh.material().sceneColor())
                || volumes.stream().anyMatch(volume -> volume.material().sceneColor());
    }

    public boolean usesTranslucencyVolume() {
        return meshes.stream().anyMatch(mesh -> mesh.material().usesTranslucencyVolume())
                || gpuSprites.stream().anyMatch(sprite -> sprite.material().usesTranslucencyVolume())
                || gpuRibbons.stream().anyMatch(ribbon -> ribbon.material().usesTranslucencyVolume())
                || gpuMeshes.stream().anyMatch(mesh -> mesh.material().usesTranslucencyVolume());
    }

    public boolean usesForwardTranslucency() {
        return meshes.stream().anyMatch(mesh -> mesh.material().usesForwardTranslucency())
                || gpuSprites.stream().anyMatch(sprite -> sprite.material().usesForwardTranslucency())
                || gpuRibbons.stream().anyMatch(ribbon -> ribbon.material().usesForwardTranslucency())
                || gpuMeshes.stream().anyMatch(mesh -> mesh.material().usesForwardTranslucency());
    }

    public boolean usesVolumeSceneLighting() {
        return meshes.stream().anyMatch(mesh -> usesLitVolumeFog(mesh.key().material()))
                || gpuSprites.stream().anyMatch(sprite -> usesLitVolumeFog(sprite.material()))
                || gpuRibbons.stream().anyMatch(ribbon -> usesLitVolumeFog(ribbon.material()))
                || gpuMeshes.stream().anyMatch(mesh -> usesLitVolumeFog(mesh.material()))
                || volumes.stream().anyMatch(volume -> litVolumeFog(volume.material()));
    }

    public static boolean usesLitVolumeFog(MaterialDefinition material) {
        return material.appliesBasePassFog() && litVolumeFog(material);
    }

    private static boolean litVolumeFog(MaterialDefinition material) {
        return material.renderSettings().fog() instanceof VolumetricFog fog && fog.sceneLighting();
    }

    public record OpaqueSurfaceRequirements(boolean defaultLit, boolean contactCasters, int lightChannels,
                                            boolean anisotropic, boolean customData, int particleLightingFeatures,
                                            int adaptiveSubstrateWords) {
        OpaqueSurfaceRequirements include(MaterialDefinition material, @Nullable MaterialPrimitiveFrame primitive) {
            if (!writesOpaqueDepth(material)) return this;
            int flags = surfaceFlags(material, primitive);
            return new OpaqueSurfaceRequirements(defaultLit || (flags & DEFAULT_LIT_SURFACE) != 0,
                    contactCasters || (flags & CONTACT_CASTER) != 0, lightChannels | (flags & 7),
                    anisotropic || (flags & ANISOTROPIC_SURFACE) != 0, customData || (flags & CUSTOM_SURFACE_DATA) != 0,
                    particleLightingFeatures | (flags >>> PARTICLE_LIGHTING_FEATURES_SHIFT),
                    Math.max(adaptiveSubstrateWords, material.adaptiveSubstrateWords()));
        }
    }
    private static final int DEFAULT_LIT_SURFACE = 8, CONTACT_CASTER = 16, ANISOTROPIC_SURFACE = 32, CUSTOM_SURFACE_DATA = 64;
    private static final int PARTICLE_LIGHTING_FEATURES_SHIFT = 7;

    public OpaqueSurfaceRequirements opaqueSurfaceRequirements() {
        int flags = 0;
        int adaptiveWords = 0;
        for (var mesh : meshes) {
            if (!writesOpaqueDepth(mesh.material())) continue;
            flags |= surfaceFlags(mesh.material(), mesh.materialPrimitive());
            adaptiveWords = Math.max(adaptiveWords, mesh.material().adaptiveSubstrateWords());
        }
        for (var sprite : gpuSprites) {
            if (!writesOpaqueDepth(sprite.material())) continue;
            flags |= surfaceFlags(sprite.material(), sprite.materialPrimitive());
            adaptiveWords = Math.max(adaptiveWords, sprite.material().adaptiveSubstrateWords());
        }
        for (var ribbon : gpuRibbons) {
            if (!writesOpaqueDepth(ribbon.material())) continue;
            flags |= surfaceFlags(ribbon.material(), ribbon.materialPrimitive());
            adaptiveWords = Math.max(adaptiveWords, ribbon.material().adaptiveSubstrateWords());
        }
        for (var mesh : gpuMeshes) {
            if (!writesOpaqueDepth(mesh.material())) continue;
            flags |= surfaceFlags(mesh.material(), mesh.materialPrimitive());
            adaptiveWords = Math.max(adaptiveWords, mesh.material().adaptiveSubstrateWords());
        }
        return new OpaqueSurfaceRequirements((flags & DEFAULT_LIT_SURFACE) != 0, (flags & CONTACT_CASTER) != 0, flags & 7,
                (flags & ANISOTROPIC_SURFACE) != 0, (flags & CUSTOM_SURFACE_DATA) != 0,
                flags >>> PARTICLE_LIGHTING_FEATURES_SHIFT, adaptiveWords);
    }

    private static int surfaceFlags(MaterialDefinition material, @Nullable MaterialPrimitiveFrame primitive) {
        if (!writesOpaqueDepth(material)) return 0;
        var lighting = primitive == null ? PrimitiveLighting.DEFAULT : primitive.lighting();
        return (material.defaultLitSurface() ? DEFAULT_LIT_SURFACE | lighting.channels() : 0)
                | (lighting.castsContactShadow() ? CONTACT_CASTER : 0)
                | (material.anisotropicSurface() ? ANISOTROPIC_SURFACE : 0)
                | (material.customSurfaceData() ? CUSTOM_SURFACE_DATA : 0)
                | (material.particleLightingFeatures() << PARTICLE_LIGHTING_FEATURES_SHIFT);
    }
}
