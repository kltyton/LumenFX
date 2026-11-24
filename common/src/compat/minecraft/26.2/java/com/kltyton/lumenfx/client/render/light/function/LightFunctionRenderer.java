package com.kltyton.lumenfx.client.render.light.function;

import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraSceneInputs.Atmosphere;
import com.kltyton.lumenfx.client.render.scene.atmosphere.AtmosphereMaterialTextures;

import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.client.render.hdr.WorldHdrCapture;
import com.kltyton.lumenfx.client.render.texture.MaterialTextureBindings;
import com.kltyton.lumenfx.client.render.texture.virtual.MaterialVirtualTextureBindings;
import com.kltyton.lumenfx.client.render.scene.material.MaterialInstanceUniforms;
import com.kltyton.lumenfx.client.render.scene.material.MaterialCollectionUniforms;
import com.kltyton.lumenfx.effect.MaterialDefinition;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.platform.BlendFactor;
import com.mojang.blaze3d.platform.BlendOp;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/** Projects a prepared light-function material into its encoded attenuation surface. */
public final class LightFunctionRenderer implements AutoCloseable {
    private enum Projection { SCREEN, ATLAS, VOLUME, VOLUME_DIRECTIONAL, FOG_DIRECTIONAL }
    private record Key(MaterialDefinition material, GpuFormat format, boolean preserveAttenuation,
                       Projection projection, boolean coloredAtlas) {
        private Key(MaterialDefinition material, GpuFormat format, boolean preserveAttenuation, Projection projection) {
            this(material, format, preserveAttenuation, projection, false);
        }
    }
    public record VolumeMaterial(MaterialDefinition material, GpuBufferSlice frame, GpuBufferSlice view,
                                 @Nullable GpuBufferSlice materialView, GpuTextureView sceneDepth, @Nullable Atmosphere atmosphere) { }
    private static final AtomicLong NEXT = new AtomicLong();
    private final Map<Key, RenderPipeline> pipelines = new HashMap<>();

    public void draw(MaterialDefinition material, RenderTarget target, GpuTextureView sceneDepth,
                     LightFunctionView view, GpuBufferSlice effectFrame, GpuBufferSlice effectView,
                     @Nullable GpuBufferSlice materialView, int x, int y, int width, int height) {
        draw(material, target, sceneDepth, view, effectFrame, effectView, materialView, x, y, width, height, false);
    }

    public void draw(MaterialDefinition material, RenderTarget target, GpuTextureView sceneDepth,
                     LightFunctionView view, GpuBufferSlice effectFrame, GpuBufferSlice effectView,
                     @Nullable GpuBufferSlice materialView, int x, int y, int width, int height, boolean preserveAttenuation) {
        draw(material, target, sceneDepth, view, effectFrame, effectView, materialView, null, x, y, width, height, preserveAttenuation);
    }

    void draw(MaterialDefinition material, RenderTarget target, GpuTextureView sceneDepth,
              LightFunctionView view, GpuBufferSlice effectFrame, GpuBufferSlice effectView,
              @Nullable GpuBufferSlice materialView, @Nullable Atmosphere atmosphere, int x, int y, int width, int height,
              boolean preserveAttenuation) {
        RenderSystem.assertOnRenderThread();
        if (!material.lightFunction()) throw new IllegalArgumentException("Material is not a light function");
        if (width <= 0 || height <= 0) return;
        var device = RenderSystem.getDevice();
        var encoder = device.createCommandEncoder();
        GpuBufferSlice lightUniforms;
        try (var mapped = encoder.transientMemory().allocateGpuMapped(LightFunctionView.BYTES,
                device.getDeviceInfo().limits().minUniformOffsetAlignment(), GpuBuffer.USAGE_UNIFORM)) {
            view.write(mapped.data().order(ByteOrder.nativeOrder()));
            lightUniforms = mapped.slice();
        }
        draw(encoder, material, target, sceneDepth, lightUniforms, effectFrame, effectView, materialView,
                atmosphere, x, y, width, height, preserveAttenuation, false);
    }

    void drawAtlas(CommandEncoder encoder, MaterialDefinition material, RenderTarget target, GpuBufferSlice tile,
                   GpuBufferSlice effectFrame, GpuBufferSlice effectView, @Nullable GpuBufferSlice materialView,
                   @Nullable Atmosphere atmosphere, boolean colored) {
        if (!material.lightFunctionAtlasCompatible()) throw new IllegalArgumentException("Material cannot render into the light-function atlas");
        draw(encoder, material, target, null, tile, effectFrame, effectView, materialView, atmosphere, 0, 0,
                target.width, target.height, false, colored);
    }

    private void draw(CommandEncoder encoder, MaterialDefinition material, RenderTarget target,
                      @Nullable GpuTextureView sceneDepth, GpuBufferSlice lightUniforms, GpuBufferSlice effectFrame,
                      GpuBufferSlice effectView, @Nullable GpuBufferSlice materialView, @Nullable Atmosphere atmosphere,
                      int x, int y, int width, int height,
                      boolean preserveAttenuation, boolean coloredAtlas) {
        boolean atlas = sceneDepth == null;
        var instanceUniforms = MaterialInstanceUniforms.write(encoder, material);
        var collectionUniforms = MaterialCollectionUniforms.INSTANCE.frame(encoder, Minecraft.getInstance().level).prepare(material);
        var key = new Key(material.programDefinition(), target.getColorTexture().getFormat(), preserveAttenuation,
                atlas ? Projection.ATLAS : Projection.SCREEN, coloredAtlas);
        RenderPipeline pipeline = pipelines.computeIfAbsent(key, LightFunctionRenderer::pipeline);
        try (var pass = encoder.createRenderPass(() -> "LumenFX light function", target.getColorTextureView(), Optional.empty())) {
            pass.setPipeline(pipeline);
            RenderSystem.bindDefaultUniforms(pass);
            if (!atlas) pass.enableScissor(x, y, width, height);
            pass.setUniform(atlas ? "LightFunctionAtlasTile" : "LightFunctionView", lightUniforms);
            bindMaterial(pass, material, effectFrame, effectView, materialView, atmosphere, sceneDepth, instanceUniforms, collectionUniforms);
            pass.draw(atlas ? 6 : 3, 1, 0, 0);
        }
    }

    public void drawVolume(CommandEncoder encoder, VolumeMaterial material, RenderTarget target, GpuBufferSlice grid,
                           GpuBufferSlice localLight, GpuBufferSlice projection, int slices) {
        drawVolume(encoder, material, target, grid, localLight, projection, slices, false);
    }

    public void drawDirectionalFog(CommandEncoder encoder, VolumeMaterial material, RenderTarget target, GpuBufferSlice projection) {
        var definition = material.material();
        var key = new Key(definition.programDefinition(), target.getColorTexture().getFormat(), false, Projection.FOG_DIRECTIONAL);
        var instanceUniforms = MaterialInstanceUniforms.write(encoder, definition);
        var collectionUniforms = MaterialCollectionUniforms.INSTANCE.frame(encoder, Minecraft.getInstance().level).prepare(definition);
        try (var pass = encoder.createRenderPass(() -> "LumenFX fog directional light function",
                target.getColorTextureView(), Optional.empty())) {
            pass.setPipeline(pipelines.computeIfAbsent(key, LightFunctionRenderer::pipeline));
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("LightFunctionFogView", projection);
            bindMaterial(pass, definition, material.frame(), material.view(), material.materialView(), material.atmosphere(), material.sceneDepth(),
                    instanceUniforms, collectionUniforms);
            pass.draw(3, 1, 0, 0);
        }
    }

    public void drawVolume(CommandEncoder encoder, VolumeMaterial material, RenderTarget target, GpuBufferSlice grid,
                           GpuBufferSlice light, GpuBufferSlice projection, int slices, boolean directional) {
        var definition = material.material();
        var key = new Key(definition.programDefinition(), target.getColorTexture().getFormat(), false,
                directional ? Projection.VOLUME_DIRECTIONAL : Projection.VOLUME);
        var instanceUniforms = MaterialInstanceUniforms.write(encoder, definition);
        var collectionUniforms = MaterialCollectionUniforms.INSTANCE.frame(encoder, Minecraft.getInstance().level).prepare(definition);
        try (var pass = encoder.createRenderPass(() -> "LumenFX translucent volume light function",
                target.getColorTextureView(), Optional.empty())) {
            pass.setPipeline(pipelines.computeIfAbsent(key, LightFunctionRenderer::pipeline));
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("TranslucencyLightingVolume", grid);
            pass.setUniform(directional ? "TlvDirectionalLight" : "TlvLocalLight", light);
            pass.setUniform("LightFunctionVolumeView", projection);
            bindMaterial(pass, definition, material.frame(), material.view(), material.materialView(), material.atmosphere(), material.sceneDepth(),
                    instanceUniforms, collectionUniforms);
            pass.draw(6, slices, 0, 0);
        }
    }

    private static void bindMaterial(RenderPass pass, MaterialDefinition material, GpuBufferSlice effectFrame,
                                     GpuBufferSlice effectView, @Nullable GpuBufferSlice materialView,
                                     @Nullable Atmosphere atmosphere,
                                     @Nullable GpuTextureView sceneDepth, @Nullable GpuBufferSlice instanceUniforms,
                                     Map<String, GpuBufferSlice> collectionUniforms) {
        pass.setUniform("EffectFrame", effectFrame);
        if (instanceUniforms != null) pass.setUniform("MaterialInstance", instanceUniforms);
        MaterialCollectionUniforms.bind(pass, collectionUniforms);
        pass.setUniform("EffectView", effectView);
        if (material.materialView() != 0)
            pass.setUniform("MaterialView", Objects.requireNonNull(materialView, "Light function needs material view data"));
        AtmosphereMaterialTextures.bind(pass, material.materialView(), atmosphere);
        if (sceneDepth != null)
            pass.bindTexture("SceneDepthSampler", sceneDepth, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
        MaterialTextureBindings.bind(pass, material, RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR, true),
                WorldHdrCapture.INSTANCE.materialViewMipBias());
    }

    private static RenderPipeline pipeline(Key key) {
        MaterialDefinition material = key.material();
        var bindings = BindGroupLayout.builder()
                .withUniform("EffectFrame", UniformType.UNIFORM_BUFFER).withUniform("EffectView", UniformType.UNIFORM_BUFFER);
        switch (key.projection()) {
            case ATLAS -> bindings.withUniform("LightFunctionAtlasTile", UniformType.UNIFORM_BUFFER);
            case SCREEN -> bindings.withUniform("LightFunctionView", UniformType.UNIFORM_BUFFER).withSampler("SceneDepthSampler");
            case FOG_DIRECTIONAL -> bindings.withUniform("LightFunctionFogView", UniformType.UNIFORM_BUFFER).withSampler("SceneDepthSampler");
            case VOLUME, VOLUME_DIRECTIONAL -> bindings.withUniform("LightFunctionVolumeView", UniformType.UNIFORM_BUFFER)
                    .withUniform("TranslucencyLightingVolume", UniformType.UNIFORM_BUFFER)
                    .withUniform(key.projection() == Projection.VOLUME_DIRECTIONAL ? "TlvDirectionalLight" : "TlvLocalLight", UniformType.UNIFORM_BUFFER)
                    .withSampler("SceneDepthSampler");
        }
        if (material.materialView() != 0) bindings.withUniform("MaterialView", UniformType.UNIFORM_BUFFER);
        AtmosphereMaterialTextures.layout(bindings, material.materialView());
        if (material.instanceParameters().byteSize() != 0) bindings.withUniform("MaterialInstance", UniformType.UNIFORM_BUFFER);
        for (var collection : material.materialCollections()) bindings.withUniform(collection.uniform(), UniformType.UNIFORM_BUFFER);
        for (String sampler : new TreeSet<>(material.textures().keySet())) bindings.withSampler(sampler);
        MaterialVirtualTextureBindings.layout(bindings, material.virtualTextureReads(), material.virtualTextureFeedback());
        String vertex = switch (key.projection()) {
            case ATLAS -> "core/light_function_atlas";
            case SCREEN, FOG_DIRECTIONAL -> "post/fullscreen";
            case VOLUME, VOLUME_DIRECTIONAL -> "translucency/inject_lights";
        };
        var pipeline = RenderPipeline.builder().withLocation(LumenFx.id("pipeline/light_function/" + NEXT.getAndIncrement()))
                .withVertexShader(LumenFx.id(vertex))
                .withFragmentShader(material.fragmentShader())
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).withDepthStencilState(Optional.empty())
                .withColorTargetState(new ColorTargetState(key.preserveAttenuation()
                        ? Optional.of(new BlendFunction(BlendFactor.ONE, BlendFactor.ONE, BlendOp.MIN)) : Optional.empty(),
                        key.format(), key.projection() == Projection.VOLUME || key.projection() == Projection.VOLUME_DIRECTIONAL
                                || key.projection() == Projection.FOG_DIRECTIONAL
                                || key.projection() == Projection.ATLAS && key.format() == GpuFormat.R8_UNORM
                                ? 1 : key.preserveAttenuation() ? 12 : 15));
        if (key.projection() == Projection.ATLAS) {
            pipeline.withShaderDefine("LFX_LIGHT_FUNCTION_ATLAS");
            if (key.coloredAtlas()) pipeline.withShaderDefine("LFX_LIGHT_FUNCTION_ATLAS_COLOR");
        }
        if (key.projection() == Projection.VOLUME)
            pipeline.withShaderDefine("LFX_LIGHT_FUNCTION_VOLUME").withShaderDefine("LFX_TLV_LOCAL_LIGHT");
        if (key.projection() == Projection.VOLUME_DIRECTIONAL)
            pipeline.withShaderDefine("LFX_LIGHT_FUNCTION_VOLUME").withShaderDefine("LFX_TLV_DIRECTIONAL_LIGHT");
        if (key.projection() == Projection.FOG_DIRECTIONAL)
            pipeline.withShaderDefine("LFX_LIGHT_FUNCTION_FOG_DIRECTIONAL");
        return pipeline.withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION).withBindGroupLayout(bindings.build()).build();
    }

    @Override public void close() { RenderSystem.assertOnRenderThread(); pipelines.clear(); }
}
