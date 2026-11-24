package com.kltyton.lumenfx.client.render.decal;

import com.kltyton.lumenfx.client.render.EffectFrame;
import com.kltyton.lumenfx.client.render.niagara.ParticleSceneDepth;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraSceneInputs;
import com.kltyton.lumenfx.api.client.parameter.NiagaraPlatforms;
import com.kltyton.lumenfx.client.render.scene.atmosphere.AtmosphereMaterialLighting;
import com.kltyton.lumenfx.client.render.scene.atmosphere.AtmosphereMaterialTextures;
import com.kltyton.lumenfx.client.render.scene.surface.SceneSurfaceCapture.Surfaces;
import com.kltyton.lumenfx.client.render.texture.MaterialTextureBindings;
import com.kltyton.lumenfx.client.render.texture.SpecularProfileBindings;
import com.kltyton.lumenfx.effect.MaterialDefinition;
import com.kltyton.lumenfx.client.render.scene.material.MaterialInstanceUniforms;
import com.kltyton.lumenfx.client.render.scene.material.MaterialCollectionUniforms;
import com.kltyton.lumenfx.effect.render.decal.DecalMaterialSettings;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;

/** Sorted scene decals upload once and submit independent material, AO and emissive stages. */
public final class ParticleDecalRenderer implements AutoCloseable {
    private record Draw(DecalScene.Visible decal, GpuBufferSlice primitive, GpuBufferSlice frame,
                        @Nullable GpuBufferSlice instance, Map<String, GpuBufferSlice> collections, float mipBias) { }
    private final DecalScene scene = new DecalScene();
    private final DecalBuffers buffers = new DecalBuffers();
    private final DecalPipelines pipelines = new DecalPipelines();
    private final List<Draw> drawing = new ArrayList<>();
    private final Map<MaterialDefinition, GpuBufferSlice> materialFrames = new IdentityHashMap<>();
    private final Map<MaterialDefinition, GpuBufferSlice> materialInstances = new IdentityHashMap<>();
    private DecalBuffers.@Nullable Frame frame;
    private @Nullable GpuBufferSlice viewUniforms;
    private @Nullable GpuBufferSlice materialViewUniforms;
    private @Nullable GpuTextureView sceneDepth;
    private @Nullable GpuTextureView surfaceMask;
    private @Nullable Surfaces shadingSurface;
    private @Nullable GpuTextureView customDepth, customStencil;
    private NiagaraSceneInputs.@Nullable Atmosphere atmosphere;
    private DecalNormalHistory.@Nullable Input normalHistory;
    private boolean customDepthBeforeBasePass;

    public DecalBuffers.@Nullable Frame prepare(EffectFrame effect, Matrix4fc projection, Matrix4fc modelView,
            Quaternionf camera, RenderTarget target, Vec3 cameraWorld, float nearClipCentimeters,
            float preExposure, float mipBias, float resolutionFraction) {
        return prepare(effect, projection, modelView, camera, target, cameraWorld, nearClipCentimeters,
                preExposure, mipBias, resolutionFraction, null);
    }

    public DecalBuffers.@Nullable Frame prepare(EffectFrame effect, Matrix4fc projection, Matrix4fc modelView,
            Quaternionf camera, RenderTarget target, Vec3 cameraWorld, float nearClipCentimeters,
            float preExposure, float mipBias, float resolutionFraction, @Nullable Surfaces surface) {
        return prepare(effect, projection, modelView, camera, target, cameraWorld, nearClipCentimeters,
                preExposure, mipBias, resolutionFraction, surface, surface, NiagaraSceneInputs.EMPTY);
    }

    public DecalBuffers.@Nullable Frame prepare(EffectFrame effect, Matrix4fc projection, Matrix4fc modelView,
            Quaternionf camera, RenderTarget target, Vec3 cameraWorld, float nearClipCentimeters,
            float preExposure, float mipBias, float resolutionFraction, @Nullable Surfaces projectionSurface,
            @Nullable Surfaces shadingSurface, NiagaraSceneInputs sceneInputs) {
        return prepare(effect, projection, modelView, camera, target, cameraWorld, nearClipCentimeters, preExposure,
                mipBias, resolutionFraction, projectionSurface, shadingSurface, sceneInputs, null);
    }

    public DecalBuffers.@Nullable Frame prepare(EffectFrame effect, Matrix4fc projection, Matrix4fc modelView,
            Quaternionf camera, RenderTarget target, Vec3 cameraWorld, float nearClipCentimeters,
            float preExposure, float mipBias, float resolutionFraction, @Nullable Surfaces projectionSurface,
            @Nullable Surfaces shadingSurface, NiagaraSceneInputs sceneInputs, DecalNormalHistory.@Nullable Input normalHistory) {
        RenderSystem.assertOnRenderThread();
        resetFrame();
        if (effect.decals().isEmpty()) { scene.clear(); return null; }
        sceneDepth = Objects.requireNonNull(target.getDepthTextureView(), "Scene decals require the current scene depth");
        bindSceneInputs(sceneInputs, target);
        this.normalHistory = normalHistory;
        if (projectionSurface != null) {
            if (projectionSurface.width() != target.width || projectionSurface.height() != target.height)
                throw new IllegalArgumentException("Decal opaque surfaces and scene dimensions differ");
            sceneDepth = projectionSurface.positionDepth();
            surfaceMask = Objects.requireNonNull(projectionSurface.materialResponse(), "Native decals require captured material responses").base();
            this.shadingSurface = Objects.requireNonNull(shadingSurface, "Native decals require captured scene materials");
        }
        var view = new DecalProjectionView(projection, modelView, cameraWorld, target);
        var visible = scene.prepare(effect.decals(), view, effect.engineTime(), nearClipCentimeters, 1, preExposure);
        if (visible.isEmpty()) return null;
        var device = RenderSystem.getDevice();
        var encoder = device.createCommandEncoder();
        var collectionFrame = MaterialCollectionUniforms.INSTANCE.frame(encoder, Minecraft.getInstance().level);
        int alignment = device.getDeviceInfo().limits().minUniformOffsetAlignment();
        viewUniforms = viewUniforms(encoder, projection, camera, target, alignment);
        int materialViewInputs = visible.stream().mapToInt(decal -> decal.decal().material().materialView()).reduce(0, (a, b) -> a | b);
        materialViewUniforms = materialViewInputs != 0
                ? com.kltyton.lumenfx.client.render.scene.material.MaterialViewUniforms.write(encoder, materialViewInputs, cameraWorld,
                        modelView, camera, false, (materialViewInputs & AtmosphereMaterialLighting.VIEW_INPUT) == 0 ? null
                                : AtmosphereMaterialLighting.resolve(sceneInputs.atmosphere(), effect.directionalLights(), NiagaraPlatforms.snapshot())) : null;
        for (var decal : visible) {
            MaterialDefinition material = decal.decal().material();
            var timing = materialFrames.computeIfAbsent(material, ignored -> frameUniforms(encoder, effect, material, mipBias, resolutionFraction, alignment));
            if (!materialInstances.containsKey(material))
                materialInstances.put(material, MaterialInstanceUniforms.write(encoder, material));
            var instance = materialInstances.get(material);
            var collections = collectionFrame.prepare(material);
            try (var mapped = encoder.transientMemory().allocateGpuMapped(DecalProjectionView.UNIFORM_BYTES, alignment, GpuBuffer.USAGE_UNIFORM)) {
                view.write(decal.decal(), decal.shading(), mapped.data().order(ByteOrder.nativeOrder()));
                drawing.add(new Draw(decal, mapped.slice(), timing, instance, collections, mipBias));
            }
        }
        buffers.prepare(target.width, target.height, encoder);
        draw(encoder, buffers.descriptor(), DecalPipelines.Stage.DBUFFER, GpuFormat.RGBA8_UNORM);
        int outputs = 0;
        for (Draw decal : drawing) outputs |= settings(decal).outputs();
        frame = buffers.frame(outputs);
        return frame;
    }

    public @Nullable GpuTextureView drawAfterBasePass(RenderTarget target, NiagaraSceneInputs inputs) {
        if (drawing.isEmpty()) return null;
        bindSceneInputs(inputs, target);
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        var area = new RenderPass.RenderArea(0, 0, target.width, target.height);
        if (drawing.stream().anyMatch(draw -> settings(draw).writes(DecalMaterialSettings.EMISSIVE)))
            draw(encoder, RenderPassDescriptor.create(() -> "LumenFX decal emissive")
                    .withColorAttachment(target.getColorTextureView()).withRenderArea(area),
                    DecalPipelines.Stage.EMISSIVE, target.getColorTexture().getFormat());
        if (drawing.stream().noneMatch(draw -> settings(draw).writes(DecalMaterialSettings.AMBIENT_OCCLUSION))) return null;
        var ambient = buffers.prepareAmbientOcclusion(encoder);
        draw(encoder, RenderPassDescriptor.create(() -> "LumenFX decal AO").withColorAttachment(ambient.getColorTextureView())
                .withRenderArea(area), DecalPipelines.Stage.AMBIENT_OCCLUSION, GpuFormat.R8_UNORM);
        return ambient.getColorTextureView();
    }

    private void bindSceneInputs(NiagaraSceneInputs inputs, RenderTarget target) {
        atmosphere = inputs.atmosphere();
        customDepth = sceneTexture(inputs, NiagaraSceneInputs.Texture.CUSTOM_DEPTH, target);
        customStencil = sceneTexture(inputs, NiagaraSceneInputs.Texture.CUSTOM_STENCIL, target);
        customDepthBeforeBasePass = inputs.customDepthBeforeBasePass();
    }

    private void draw(CommandEncoder encoder, RenderPassDescriptor descriptor, DecalPipelines.Stage stage, GpuFormat format) {
        boolean gl = RenderSystem.getDevice().backend instanceof com.mojang.blaze3d.opengl.GlDevice;
        boolean restore = stage == DecalPipelines.Stage.DBUFFER && gl && !GL11C.glIsEnabled(GL30C.GL_FRAMEBUFFER_SRGB);
        if (restore) GL11C.glEnable(GL30C.GL_FRAMEBUFFER_SRGB);
        try (var pass = encoder.createRenderPass(descriptor)) {
            var depthSampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
            var hostSampler = RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR, true);
            boolean customAvailable = stage != DecalPipelines.Stage.DBUFFER || customDepthBeforeBasePass;
            var stageCustomDepth = customAvailable ? customDepth : null;
            var stageCustomStencil = customAvailable ? customStencil : null;
            var history = stage == DecalPipelines.Stage.DBUFFER ? normalHistory : null;
            for (Draw draw : drawing) {
                var settings = settings(draw);
                boolean enabled = switch (stage) {
                    case DBUFFER -> settings.writesDBuffer();
                    case EMISSIVE -> settings.writes(DecalMaterialSettings.EMISSIVE);
                    case AMBIENT_OCCLUSION -> settings.writes(DecalMaterialSettings.AMBIENT_OCCLUSION);
                };
                if (!enabled) continue;
                MaterialDefinition material = draw.decal().decal().material();
                pass.setPipeline(pipelines.get(material, stage, format, surfaceMask != null, stageCustomDepth != null,
                        stageCustomStencil != null, history != null));
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("DecalPrimitive", draw.primitive());
                pass.setUniform("EffectFrame", draw.frame());
                if (draw.instance() != null) pass.setUniform("MaterialInstance", draw.instance());
                MaterialCollectionUniforms.bind(pass, draw.collections());
                pass.setUniform("EffectView", Objects.requireNonNull(viewUniforms));
                if (material.materialView() != 0) pass.setUniform("MaterialView", Objects.requireNonNull(materialViewUniforms));
                AtmosphereMaterialTextures.bind(pass, material.materialView(), atmosphere);
                pass.bindTexture("SceneDepthSampler", Objects.requireNonNull(sceneDepth), depthSampler);
                if (stageCustomDepth != null) pass.bindTexture("DecalCustomDepth", stageCustomDepth, depthSampler);
                if (stageCustomStencil != null) pass.bindTexture("DecalCustomStencil", stageCustomStencil, depthSampler);
                if (history != null) {
                    pass.setUniform("DecalReprojectionView", history.view());
                    pass.bindTexture("DecalPreviousNormal", history.normals(),
                            RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
                    pass.bindTexture("DecalEarlyVelocity", history.velocity(), depthSampler);
                }
                if (surfaceMask != null) pass.bindTexture("DecalSurfaceMask", surfaceMask, depthSampler);
                if (shadingSurface != null && stage != DecalPipelines.Stage.DBUFFER) {
                    pass.bindTexture("DecalSceneNormal", shadingSurface.normalAmbient(), depthSampler);
                    pass.bindTexture("DecalSceneGeometry", shadingSurface.positionDepth(), depthSampler);
                }
                MaterialTextureBindings.bind(pass, material, hostSampler, draw.mipBias());
                SpecularProfileBindings.bind(pass, !material.specularProfiles().isEmpty());
                pass.draw(36, 1, 0, 0);
            }
        } finally { if (restore) GL11C.glDisable(GL30C.GL_FRAMEBUFFER_SRGB); }
    }

    private static @Nullable GpuTextureView sceneTexture(NiagaraSceneInputs inputs, NiagaraSceneInputs.Texture slot, RenderTarget target) {
        var texture = inputs.textures().get(slot);
        if (texture != null && (texture.getWidth(0) != target.width || texture.getHeight(0) != target.height))
            throw new IllegalArgumentException("Decal " + slot + " dimensions differ from the scene");
        return texture;
    }

    private static GpuBufferSlice frameUniforms(CommandEncoder encoder, EffectFrame frame, MaterialDefinition material,
            float mipBias, float resolutionFraction, int alignment) {
        try (var mapped = encoder.transientMemory().allocateGpuMapped(48, alignment, GpuBuffer.USAGE_UNIFORM)) {
            var data = mapped.data().order(ByteOrder.nativeOrder());
            data.putFloat((float)frame.time()).putFloat(mipBias).putFloat((float)frame.realTime()).putFloat((float)frame.engineTime());
            material.writeParameters(data);
            data.putFloat(frame.deltaTime()).putFloat(resolutionFraction).putFloat(1f / resolutionFraction).putFloat(0);
            return mapped.slice();
        }
    }

    private static GpuBufferSlice viewUniforms(CommandEncoder encoder, Matrix4fc projection, Quaternionf camera,
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

    private static DecalMaterialSettings settings(Draw draw) { return Objects.requireNonNull(draw.decal().decal().material().decalSettings()); }
    public DecalBuffers.@Nullable Frame frame() { return frame; }
    public void resetFrame() { frame = null; sceneDepth = null; surfaceMask = null; shadingSurface = null; customDepth = null; customStencil = null; atmosphere = null; normalHistory = null; viewUniforms = null; materialViewUniforms = null; drawing.clear(); materialFrames.clear(); materialInstances.clear(); }
    public void resetScene() { resetFrame(); scene.clear(); }
    @Override public void close() { resetFrame(); scene.clear(); pipelines.clear(); buffers.close(); }
}
