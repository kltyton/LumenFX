package com.kltyton.lumenfx.client.render.decal;

import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.client.render.texture.NativeColorFormats;
import com.kltyton.lumenfx.client.render.texture.SpecularProfileBindings;
import com.kltyton.lumenfx.client.render.texture.virtual.MaterialVirtualTextureBindings;
import com.kltyton.lumenfx.client.resource.texture.TextureStorageFormat;
import com.kltyton.lumenfx.client.resource.texture.storage.NativeTextureStorage;
import com.kltyton.lumenfx.effect.MaterialDefinition;
import com.kltyton.lumenfx.effect.render.decal.DecalMaterialSettings;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.kltyton.lumenfx.client.render.scene.atmosphere.AtmosphereMaterialTextures;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.BlendFactor;
import com.mojang.blaze3d.shaders.UniformType;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.renderer.BindGroupLayouts;

/** Source DBuffer, emissive and AO stages retain their distinct channel masks and blending. */
public final class DecalPipelines {
    public enum Stage { DBUFFER, EMISSIVE, AMBIENT_OCCLUSION }
    private record Key(MaterialDefinition material, Stage stage, GpuFormat format, boolean capturedSurface,
                       boolean customDepth, boolean customStencil, boolean normalReprojection) { }
    private static final AtomicLong NEXT = new AtomicLong();
    private final Map<Key, RenderPipeline> pipelines = new HashMap<>();

    public RenderPipeline get(MaterialDefinition material, Stage stage, GpuFormat format) {
        return get(material, stage, format, false);
    }

    public RenderPipeline get(MaterialDefinition material, Stage stage, GpuFormat format, boolean capturedSurface) {
        return get(material, stage, format, capturedSurface, false, false);
    }

    public RenderPipeline get(MaterialDefinition material, Stage stage, GpuFormat format, boolean capturedSurface,
                              boolean customDepth, boolean customStencil) {
        return get(material, stage, format, capturedSurface, customDepth, customStencil, false);
    }

    public RenderPipeline get(MaterialDefinition material, Stage stage, GpuFormat format, boolean capturedSurface,
                              boolean customDepth, boolean customStencil, boolean normalReprojection) {
        return pipelines.computeIfAbsent(new Key(material.programDefinition(), stage, format, capturedSurface,
                        customDepth, customStencil, normalReprojection),
                DecalPipelines::create);
    }

    private static RenderPipeline create(Key key) {
        MaterialDefinition material = key.material();
        DecalMaterialSettings settings = Objects.requireNonNull(material.decalSettings(), "Decal material has no domain settings");
        var bindings = BindGroupLayout.builder().withUniform("DecalPrimitive", UniformType.UNIFORM_BUFFER)
                .withUniform("EffectFrame", UniformType.UNIFORM_BUFFER).withUniform("EffectView", UniformType.UNIFORM_BUFFER)
                .withSampler("SceneDepthSampler");
        if (key.capturedSurface()) {
            bindings.withSampler("DecalSurfaceMask");
            if (key.stage() != Stage.DBUFFER) bindings.withSampler("DecalSceneNormal").withSampler("DecalSceneGeometry");
        }
        if (key.customDepth()) bindings.withSampler("DecalCustomDepth");
        if (key.customStencil()) bindings.withSampler("DecalCustomStencil");
        if (key.normalReprojection()) bindings.withUniform("DecalReprojectionView", UniformType.UNIFORM_BUFFER)
                .withSampler("DecalPreviousNormal").withSampler("DecalEarlyVelocity");
        if (material.materialView() != 0) bindings.withUniform("MaterialView", UniformType.UNIFORM_BUFFER);
        AtmosphereMaterialTextures.layout(bindings, material.materialView());
        if (material.instanceParameters().byteSize() != 0) bindings.withUniform("MaterialInstance", UniformType.UNIFORM_BUFFER);
        for (var collection : material.materialCollections()) bindings.withUniform(collection.uniform(), UniformType.UNIFORM_BUFFER);
        for (String sampler : new java.util.TreeSet<>(material.textures().keySet())) bindings.withSampler(sampler);
        MaterialVirtualTextureBindings.layout(bindings, material.virtualTextureReads(), material.virtualTextureFeedback());
        SpecularProfileBindings.layout(bindings, !material.specularProfiles().isEmpty());
        var builder = RenderPipeline.builder().withLocation(LumenFx.id("pipeline/decal/" + NEXT.getAndIncrement()))
                .withVertexShader(LumenFx.id("core/particle_decal")).withFragmentShader(material.fragmentShader())
                .withShaderDefine("LFX_DECAL_STAGE", key.stage().ordinal()).withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withCull(false).withDepthStencilState(Optional.empty())
                .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION).withBindGroupLayout(bindings.build());
        if (key.capturedSurface()) builder.withShaderDefine("LFX_DECAL_CAPTURED_SURFACE");
        if (key.customDepth()) builder.withShaderDefine("LFX_DECAL_CUSTOM_DEPTH");
        if (key.customStencil()) builder.withShaderDefine("LFX_DECAL_CUSTOM_STENCIL");
        if (key.normalReprojection()) builder.withShaderDefine("LFX_DECAL_NORMAL_REPROJECTION");
        if (key.stage() == Stage.DBUFFER) {
            boolean premultiplied = settings.blend() == DecalMaterialSettings.Blend.ALPHA_COMPOSITE;
            var blend = new BlendFunction(premultiplied ? BlendFactor.ONE : BlendFactor.SRC_ALPHA,
                    BlendFactor.ONE_MINUS_SRC_ALPHA, BlendFactor.ZERO, BlendFactor.ONE_MINUS_SRC_ALPHA);
            int[] channels = { DecalMaterialSettings.BASE_COLOR, DecalMaterialSettings.NORMAL, DecalMaterialSettings.MATERIAL };
            for (int i = 0; i < channels.length; i++) builder.withColorTargetState(i,
                    new ColorTargetState(Optional.of(blend), GpuFormat.RGBA8_UNORM, settings.writes(channels[i]) ? 15 : 0));
        } else {
            BlendFunction blend = key.stage() == Stage.EMISSIVE
                    ? new BlendFunction(BlendFactor.SRC_ALPHA, BlendFactor.ONE, BlendFactor.ZERO, BlendFactor.ONE)
                    : new BlendFunction(BlendFactor.DST_COLOR, BlendFactor.ZERO, BlendFactor.ZERO, BlendFactor.ONE);
            builder.withColorTargetState(new ColorTargetState(Optional.of(blend), key.format(), key.stage() == Stage.EMISSIVE ? 7 : 1));
        }
        SpecularProfileBindings.define(builder, material.specularProfiles());
        RenderPipeline pipeline = builder.build();
        if (key.stage() == Stage.DBUFFER) NativeColorFormats.register(pipeline,
                new NativeTextureStorage(TextureStorageFormat.BGRA8, true),
                new NativeTextureStorage(TextureStorageFormat.BGRA8, false),
                new NativeTextureStorage(TextureStorageFormat.BGRA8, false));
        return pipeline;
    }

    public void clear() { pipelines.clear(); }
}
