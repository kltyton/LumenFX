package com.kltyton.lumenfx.client.render.light.function;

import com.kltyton.lumenfx.api.client.parameter.NiagaraPlatforms;
import com.kltyton.lumenfx.client.render.EffectFrame;
import com.kltyton.lumenfx.client.render.decal.DecalBuffers;
import com.kltyton.lumenfx.client.render.light.ParticleLightRenderer.PointDraw;
import com.kltyton.lumenfx.client.render.light.scene.PointLightProjection;
import com.kltyton.lumenfx.client.render.light.shadow.PointShadowSceneRenderer;
import com.kltyton.lumenfx.client.render.light.shadow.PointShadowSceneRenderer.ParticleCasters;
import com.kltyton.lumenfx.client.render.light.shadow.PointShadowSceneRenderer.VolumeShadow;
import com.kltyton.lumenfx.client.render.light.function.LightFunctionRenderer.VolumeMaterial;
import com.kltyton.lumenfx.effect.render.light.scene.SceneDirectionalLight;
import com.kltyton.lumenfx.effect.render.light.scene.ScenePointLight;
import com.kltyton.lumenfx.client.render.scene.surface.SceneSurfaceCapture.Surfaces;
import com.kltyton.lumenfx.client.compat.shaderpack.ShaderpackMaterialSurfaces;
import com.kltyton.lumenfx.client.render.niagara.ParticleSceneDepth;
import com.kltyton.lumenfx.client.render.scene.material.MaterialViewUniforms;
import com.kltyton.lumenfx.client.render.scene.atmosphere.AtmosphereMaterialLighting;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraSceneInputs.Atmosphere;
import com.kltyton.lumenfx.client.render.texture.OwnedColorTarget;
import com.kltyton.lumenfx.effect.MaterialDefinition;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.kltyton.lumenfx.client.render.scene.surface.OpaqueParticleSurfaces;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/** Reuses one attenuation target, projecting each light function immediately before its light draw. */
public final class PointLightFunctionRenderer implements AutoCloseable {
    private final LightFunctionRenderer renderer = new LightFunctionRenderer();
    private final LightFunctionAtlas atlas = new LightFunctionAtlas();
    private final PointShadowSceneRenderer shadows = new PointShadowSceneRenderer();
    private Map<ScenePointLight, VolumeShadow> volumeShadows = Map.of();
    private LightFunctionAtlas.@Nullable Frame atlasFrame;
    private LightFunctionAtlas.@Nullable Frame translucencyAtlasFrame;
    private LightFunctionAtlas.@Nullable Frame forwardAtlasFrame;
    private Map<ScenePointLight, VolumeMaterial> translucencyFunctions = Map.of();
    private Map<SceneDirectionalLight, VolumeMaterial> directionalVolumeFunctions = Map.of();
    private @Nullable VolumeMaterial fogDirectionalMaterial;
    private @Nullable OwnedColorTarget attenuation;
    private final LightFunctionFade fade;

    public PointLightFunctionRenderer() { this(LightFunctionFade.DEFAULT); }
    public PointLightFunctionRenderer(LightFunctionFade fade) { this.fade = Objects.requireNonNull(fade); }

    public static boolean hasDirectionalFunctions(EffectFrame frame, boolean sceneFog, boolean volume, boolean forward) {
        if (sceneFog || forward) {
            var selected = SceneDirectionalLight.selectForward(frame.directionalLights());
            if (selected != null && selected.lightFunction() != null
                    && (sceneFog && selected.volumetricScattering() > 0 || forward && selected.affectsTranslucency())) return true;
        }
        return volume && frame.directionalLights().stream()
                .anyMatch(light -> light.affectsTranslucency() && light.lightFunction() != null);
    }

    public List<PointDraw> prepare(EffectFrame frame, PointLightProjection lightView, Matrix4fc projection,
                                  Matrix4fc modelView, Quaternionf camera, Vec3 cameraWorld, RenderTarget scene,
                                  float mipBias, float resolutionFraction) {
        return prepare(frame, lightView, projection, modelView, camera, cameraWorld, scene, mipBias, resolutionFraction,
                null, false, false, false, false);
    }

    public List<PointDraw> prepare(EffectFrame frame, PointLightProjection lightView, Matrix4fc projection,
                                  Matrix4fc modelView, Quaternionf camera, Vec3 cameraWorld, RenderTarget scene,
                                  float mipBias, float resolutionFraction, @Nullable Surfaces nativeSurface,
                                  @Nullable ShaderpackMaterialSurfaces hostSurface) {
        return prepare(frame, lightView, projection, modelView, camera, cameraWorld, scene, mipBias, resolutionFraction,
                nativeSurface, hostSurface, null);
    }

    public List<PointDraw> prepare(EffectFrame frame, PointLightProjection lightView, Matrix4fc projection,
                                  Matrix4fc modelView, Quaternionf camera, Vec3 cameraWorld, RenderTarget scene,
                                  float mipBias, float resolutionFraction, @Nullable Surfaces nativeSurface,
                                  @Nullable ShaderpackMaterialSurfaces hostSurface, DecalBuffers.@Nullable Frame decals) {
        return prepare(frame, lightView, projection, modelView, camera, cameraWorld, scene, mipBias, resolutionFraction,
                nativeSurface, hostSurface, decals, null);
    }

    public List<PointDraw> prepare(EffectFrame frame, PointLightProjection lightView, Matrix4fc projection,
                                  Matrix4fc modelView, Quaternionf camera, Vec3 cameraWorld, RenderTarget scene,
                                  float mipBias, float resolutionFraction, @Nullable Surfaces nativeSurface,
                                  @Nullable ShaderpackMaterialSurfaces hostSurface, DecalBuffers.@Nullable Frame decals,
                                  @Nullable ParticleCasters particleCasters) {
        return prepare(frame, lightView, projection, modelView, camera, cameraWorld, scene, mipBias, resolutionFraction,
                nativeSurface, hostSurface, decals, particleCasters, null);
    }

    public List<PointDraw> prepare(EffectFrame frame, PointLightProjection lightView, Matrix4fc projection,
                                  Matrix4fc modelView, Quaternionf camera, Vec3 cameraWorld, RenderTarget scene,
                                  float mipBias, float resolutionFraction, @Nullable Surfaces nativeSurface,
                                  @Nullable ShaderpackMaterialSurfaces hostSurface, DecalBuffers.@Nullable Frame decals,
                                  @Nullable ParticleCasters particleCasters, OpaqueParticleSurfaces.@Nullable Frame materials) {
        return prepare(frame, lightView, projection, modelView, camera, cameraWorld, scene, mipBias, resolutionFraction,
                nativeSurface, hostSurface, decals, particleCasters, materials, false, false, false);
    }

    public List<PointDraw> prepare(EffectFrame frame, PointLightProjection lightView, Matrix4fc projection,
                                  Matrix4fc modelView, Quaternionf camera, Vec3 cameraWorld, RenderTarget scene,
                                  float mipBias, float resolutionFraction, @Nullable Surfaces nativeSurface,
                                  @Nullable ShaderpackMaterialSurfaces hostSurface, DecalBuffers.@Nullable Frame decals,
                                  @Nullable ParticleCasters particleCasters, OpaqueParticleSurfaces.@Nullable Frame materials,
                                  boolean sceneFog, boolean translucentLightingVolume, boolean forwardLightFunctions) {
        return prepare(frame, lightView, projection, modelView, camera, cameraWorld, scene, mipBias, resolutionFraction,
                nativeSurface, hostSurface, decals, particleCasters, materials, sceneFog, translucentLightingVolume,
                forwardLightFunctions, false);
    }

    public List<PointDraw> prepare(EffectFrame frame, PointLightProjection lightView, Matrix4fc projection, Matrix4fc modelView,
                                  Quaternionf camera, Vec3 cameraWorld, RenderTarget scene,
                                  float mipBias, float resolutionFraction, @Nullable Surfaces nativeSurface,
                                  @Nullable ShaderpackMaterialSurfaces hostSurface, DecalBuffers.@Nullable Frame decals,
                                  @Nullable ParticleCasters particleCasters, OpaqueParticleSurfaces.@Nullable Frame materials,
                                  boolean sceneFog, boolean translucentLightingVolume, boolean forwardLightFunctions,
                                  boolean forwardLocalShadows) {
        return prepare(frame, lightView, projection, modelView, camera, cameraWorld, scene, mipBias, resolutionFraction,
                nativeSurface, hostSurface, decals, particleCasters, materials, sceneFog, translucentLightingVolume,
                forwardLightFunctions, forwardLocalShadows, null);
    }

    public List<PointDraw> prepare(EffectFrame frame, PointLightProjection lightView, Matrix4fc projection,
                                  Matrix4fc modelView, Quaternionf camera, Vec3 cameraWorld, RenderTarget scene,
                                  float mipBias, float resolutionFraction, @Nullable Surfaces nativeSurface,
                                  @Nullable ShaderpackMaterialSurfaces hostSurface, DecalBuffers.@Nullable Frame decals,
                                  @Nullable ParticleCasters particleCasters, OpaqueParticleSurfaces.@Nullable Frame materials,
                                  boolean sceneFog, boolean translucentLightingVolume, boolean forwardLightFunctions,
                                  boolean forwardLocalShadows, @Nullable Atmosphere atmosphere) {
        var prepared = shadows.prepare(frame, lightView, scene, projection, cameraWorld, nativeSurface, hostSurface,
                modelView, decals, particleCasters, materials, sceneFog, translucentLightingVolume, forwardLocalShadows);
        var volume = new IdentityHashMap<ScenePointLight, VolumeShadow>();
        prepared.forEach((light, shadow) -> {
            if (shadow.volume() != null) volume.put(light, shadow.volume());
        });
        volumeShadows = Collections.unmodifiableMap(volume);
        return prepare(frame, lightView, projection, modelView, camera, cameraWorld, scene, mipBias, resolutionFraction,
                prepared, sceneFog, nativeSurface != null, translucentLightingVolume, forwardLightFunctions, atmosphere);
    }

    private List<PointDraw> prepare(EffectFrame frame, PointLightProjection lightView, Matrix4fc projection,
                                   Matrix4fc modelView, Quaternionf camera, Vec3 cameraWorld, RenderTarget scene,
                                   float mipBias, float resolutionFraction,
                                   @Nullable Map<ScenePointLight, PointShadowSceneRenderer.Prepared> shadowFrames,
                                   boolean sceneFog, boolean nativeDeferred, boolean translucentLightingVolume,
                                   boolean forwardLightFunctions) {
        return prepare(frame, lightView, projection, modelView, camera, cameraWorld, scene, mipBias, resolutionFraction,
                shadowFrames, sceneFog, nativeDeferred, translucentLightingVolume, forwardLightFunctions, null);
    }

    private List<PointDraw> prepare(EffectFrame frame, PointLightProjection lightView, Matrix4fc projection,
                                   Matrix4fc modelView, Quaternionf camera, Vec3 cameraWorld, RenderTarget scene,
                                   float mipBias, float resolutionFraction,
                                   @Nullable Map<ScenePointLight, PointShadowSceneRenderer.Prepared> shadowFrames,
                                   boolean sceneFog, boolean nativeDeferred, boolean translucentLightingVolume,
                                   boolean forwardLightFunctions, @Nullable Atmosphere atmosphere) {
        RenderSystem.assertOnRenderThread();
        atlasFrame = null;
        translucencyAtlasFrame = null;
        forwardAtlasFrame = null;
        translucencyFunctions = Map.of();
        directionalVolumeFunctions = Map.of();
        fogDirectionalMaterial = null;
        var platform = NiagaraPlatforms.snapshot();
        boolean fogAtlas = sceneFog && Objects.requireNonNull(platform.console("r.VolumetricFog.UsesLightFunctionAtlas")).bits() > 0;
        var directional = forwardLightFunctions || sceneFog ? SceneDirectionalLight.selectForward(frame.directionalLights()) : null;
        boolean fogDirectional = sceneFog && directional != null && directional.volumetricScattering() > 0;
        var directionalFunction = directional != null && (forwardLightFunctions && directional.affectsTranslucency()
                || fogDirectional) ? directional.lightFunction() : null;
        Map<MaterialDefinition, GpuBufferSlice> frames = new IdentityHashMap<>();
        Map<ScenePointLight, MaterialDefinition> materials = new IdentityHashMap<>();
        Map<SceneDirectionalLight, MaterialDefinition> directionalMaterials = new IdentityHashMap<>();
        boolean deferredAtlas = (nativeDeferred || translucentLightingVolume)
                && Objects.requireNonNull(platform.console("r.Deferred.UsesLightFunctionAtlas")).bits() > 0;
        int materialInputs = 0;
        for (var light : frame.pointLights()) {
            if (light.lightFunctionMaterial() == null) continue;
            MaterialDefinition material = Objects.requireNonNull(light.effect().materials().get(light.lightFunctionMaterial()),
                    "Point light function material was not prepared: " + light.lightFunctionMaterial());
            if (!material.lightFunction()) throw new IllegalArgumentException("Point light references a non-light-function material");
            materials.put(light, material);
            materialInputs |= material.materialView();
        }
        boolean unityCookies = materials.keySet().stream().anyMatch(light -> light.data().lightFunction().unityCookie());
        MaterialDefinition directionalMaterial = null;
        if (directionalFunction != null) {
            directionalMaterial = directionalMaterial(directionalFunction);
            materialInputs |= directionalMaterial.materialView();
        }
        if (translucentLightingVolume) for (var light : frame.directionalLights()) {
            if (!light.affectsTranslucency() || light.lightFunction() == null) continue;
            var material = directionalMaterial(light.lightFunction());
            directionalMaterials.put(light, material);
            materialInputs |= material.materialView();
        }
        if (frame.pointLights().isEmpty() && directionalMaterial == null && directionalMaterials.isEmpty()) return List.of();
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        int alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
        GpuBufferSlice effectView = null, materialView = null;
        LightFunctionAtlas.Frame sharedAtlas = null;
        if (!materials.isEmpty() || directionalMaterial != null || !directionalMaterials.isEmpty()) {
            effectView = view(encoder, projection, camera, scene, alignment);
            if (materialInputs != 0) materialView = MaterialViewUniforms.write(encoder, materialInputs, cameraWorld,
                    modelView, camera, false, (materialInputs & AtmosphereMaterialLighting.VIEW_INPUT) == 0 ? null
                            : AtmosphereMaterialLighting.resolve(atmosphere, frame.directionalLights(), platform));
            for (MaterialDefinition material : materials.values())
                frames.computeIfAbsent(material, value -> frame(encoder, frame, value, mipBias, resolutionFraction, alignment));
            if (directionalMaterial != null)
                frames.computeIfAbsent(directionalMaterial, value -> frame(encoder, frame, value, mipBias, resolutionFraction, alignment));
            for (MaterialDefinition material : directionalMaterials.values())
                frames.computeIfAbsent(material, value -> frame(encoder, frame, value, mipBias, resolutionFraction, alignment));
            boolean atlasDirectional = directional != null
                    && (forwardLightFunctions && directional.affectsTranslucency() || fogAtlas && fogDirectional);
            boolean unrealAtlas = fogAtlas || deferredAtlas || forwardLightFunctions;
            if (unrealAtlas || unityCookies)
                sharedAtlas = atlas.prepare(encoder, renderer, materials, frames, atlasDirectional ? directionalFunction : null,
                        atlasDirectional ? directionalMaterial : null,
                        effectView, materialView, cameraWorld, atmosphere, unrealAtlas);
            var cookieOnlyAtlas = LightFunctionAtlas.unityCookies(sharedAtlas);
            if (sceneFog) atlasFrame = fogAtlas ? sharedAtlas : cookieOnlyAtlas;
            if (translucentLightingVolume) translucencyAtlasFrame = deferredAtlas ? sharedAtlas : cookieOnlyAtlas;
            forwardAtlasFrame = forwardLightFunctions ? sharedAtlas : cookieOnlyAtlas;
            if (fogDirectional && directionalMaterial != null && (atlasFrame == null || atlasFrame.directional() == null)) {
                var depth = Objects.requireNonNull(scene.getDepthTextureView(), "Fog light functions require scene depth");
                fogDirectionalMaterial = new VolumeMaterial(directionalMaterial, Objects.requireNonNull(frames.get(directionalMaterial)),
                        effectView, materialView, depth, atmosphere);
            }
            if (translucentLightingVolume) {
                var volumeFunctions = new IdentityHashMap<ScenePointLight, VolumeMaterial>();
                var depth = Objects.requireNonNull(scene.getDepthTextureView(), "Volume light functions require captured scene depth");
                for (var entry : materials.entrySet()) if (entry.getKey().data().shader().affectsTranslucency()) {
                    var material = entry.getValue();
                    volumeFunctions.put(entry.getKey(), new VolumeMaterial(material, Objects.requireNonNull(frames.get(material)),
                            effectView, materialView, depth, atmosphere));
                }
                translucencyFunctions = Collections.unmodifiableMap(volumeFunctions);
                var directionalFunctions = new IdentityHashMap<SceneDirectionalLight, VolumeMaterial>();
                for (var entry : directionalMaterials.entrySet()) {
                    var material = entry.getValue();
                    directionalFunctions.put(entry.getKey(), new VolumeMaterial(material,
                            Objects.requireNonNull(frames.get(material)), effectView, materialView, depth, atmosphere));
                }
                directionalVolumeFunctions = Collections.unmodifiableMap(directionalFunctions);
            }
        }
        var result = new ArrayList<PointDraw>(frame.pointLights().size());
        for (var light : frame.pointLights()) {
            var sample = (light.data().lightFunction().unityCookie() || nativeDeferred && deferredAtlas) && sharedAtlas != null
                    ? sharedAtlas.sample(light) : null;
            MaterialDefinition material = sample == null ? materials.get(light) : null;
            var shadow = shadowFrames == null ? null : Objects.requireNonNull(shadowFrames.get(light), "Missing point shadow frame");
            boolean projectedShadow = shadow != null && shadow.plan().projected();
            var shadowPlan = shadow == null ? null : shadow.plan();
            if (material == null && !projectedShadow) {
                result.add(new PointDraw(light, null, false, sample != null, null, shadowPlan, lightView.colorFade(light), sample));
                continue;
            }
            resize(scene.width, scene.height);
            OwnedColorTarget output = Objects.requireNonNull(attenuation);
            GpuBufferSlice viewUniform = effectView, materialUniform = materialView;
            var functionView = material == null ? null : lightView.function(light, fade);
            var depth = Objects.requireNonNull(scene.getDepthTextureView(), "Light functions require captured scene depth");
            Runnable projectionDraw = () -> {
                if (projectedShadow) Objects.requireNonNull(Objects.requireNonNull(shadow).project()).accept(output);
                if (material != null) renderer.draw(material, output, depth, Objects.requireNonNull(functionView),
                        Objects.requireNonNull(frames.get(material)), Objects.requireNonNull(viewUniform), materialUniform, atmosphere,
                        0, 0, scene.width, scene.height, projectedShadow);
            };
            result.add(new PointDraw(light, output.getColorTextureView(), projectedShadow, material != null || sample != null,
                    projectionDraw, shadowPlan, lightView.colorFade(light), sample));
        }
        return result;
    }

    private void resize(int width, int height) {
        if (attenuation != null && attenuation.width == width && attenuation.height == height) return;
        var next = new OwnedColorTarget("LumenFX point light-function attenuation", width, height, GpuFormat.RGBA8_UNORM);
        if (attenuation != null) attenuation.close();
        attenuation = next;
    }

    private static MaterialDefinition directionalMaterial(SceneDirectionalLight.Function function) {
        var material = Objects.requireNonNull(function.effect().materials().get(function.material()),
                "Directional light function material was not prepared: " + function.material());
        if (!material.lightFunction()) throw new IllegalArgumentException("Directional light references a non-light-function material");
        return material;
    }

    private static GpuBufferSlice frame(CommandEncoder encoder, EffectFrame frame, MaterialDefinition material,
                                       float mipBias, float resolutionFraction, int alignment) {
        try (var mapped = encoder.transientMemory().allocateGpuMapped(48, alignment, GpuBuffer.USAGE_UNIFORM)) {
            var data = mapped.data().order(ByteOrder.nativeOrder());
            data.putFloat((float)frame.time()).putFloat(mipBias).putFloat((float)frame.realTime()).putFloat((float)frame.engineTime());
            material.writeParameters(data);
            data.putFloat(frame.deltaTime()).putFloat(resolutionFraction).putFloat(1f / resolutionFraction).putFloat(0);
            return mapped.slice();
        }
    }

    private static GpuBufferSlice view(CommandEncoder encoder, Matrix4fc projection, Quaternionf camera,
                                      RenderTarget target, int alignment) {
        Vector3f right = camera.transform(new Vector3f(1, 0, 0)), up = camera.transform(new Vector3f(0, 1, 0));
        boolean gl = RenderSystem.getDevice().backend instanceof com.mojang.blaze3d.opengl.GlDevice;
        float[] depth = ParticleSceneDepth.depthTransform(projection, RenderSystem.getDevice().getDeviceInfo().isZZeroToOne());
        try (var mapped = encoder.transientMemory().allocateGpuMapped(64, alignment, GpuBuffer.USAGE_UNIFORM)) {
            var data = mapped.data().order(ByteOrder.nativeOrder());
            data.putFloat(right.x).putFloat(right.y).putFloat(right.z).putFloat(gl ? 1 : 0);
            data.putFloat(up.x).putFloat(up.y).putFloat(up.z).putFloat(1f / target.height);
            data.putFloat(1).putFloat(0).putFloat(0).putFloat(1f / target.width);
            for (float value : depth) data.putFloat(value);
            return mapped.slice();
        }
    }

    public void clearVolumeShadows(boolean retainTargets) {
        volumeShadows = Map.of();
        atlasFrame = null;
        translucencyAtlasFrame = null;
        forwardAtlasFrame = null;
        translucencyFunctions = Map.of();
        directionalVolumeFunctions = Map.of();
        fogDirectionalMaterial = null;
        if (!retainTargets) shadows.clearVolumeTargets();
    }
    public Map<ScenePointLight, VolumeShadow> volumeShadows() { return volumeShadows; }
    public LightFunctionAtlas.@Nullable Frame atlasFrame() { return atlasFrame; }
    public LightFunctionAtlas.@Nullable Frame translucencyAtlasFrame() { return translucencyAtlasFrame; }
    public LightFunctionAtlas.@Nullable Frame forwardAtlasFrame() { return forwardAtlasFrame; }
    public Map<ScenePointLight, VolumeMaterial> translucencyFunctions() { return translucencyFunctions; }
    public Map<SceneDirectionalLight, VolumeMaterial> directionalVolumeFunctions() { return directionalVolumeFunctions; }
    public @Nullable VolumeMaterial fogDirectionalMaterial() { return fogDirectionalMaterial; }
    public void renderVolumeCasters() { volumeShadows.values().forEach(shadow -> shadow.renderCasters().run()); }

    public void endFrame() { shadows.endFrame(); }
    public void expireShadows(double time) { shadows.expire(time); }
    public void removeShadow(long identity) { shadows.remove(identity); }
    public void resetShadowCache() { shadows.resetCache(); }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        renderer.close();
        atlas.close();
        shadows.close();
        if (attenuation != null) { attenuation.close(); attenuation = null; }
    }
}
