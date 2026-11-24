package com.kltyton.lumenfx.client.render.decal;

import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.client.compat.shaderpack.ColourEncoding;
import com.kltyton.lumenfx.client.compat.shaderpack.ShaderpackMaterialSurfaces;
import com.lowdragmc.photon.client.render.PhotonFullscreenPass;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Applies projected decal color using the real opaque shaderpack material's diffuse response. */
public final class ShaderpackDecalColorResolver {
    private record Key(GpuFormat format, ColourEncoding scene, ColourEncoding material, boolean ambientOnly) { }

    private final Map<Key, RenderPipeline> pipelines = new HashMap<>();

    public void resolve(RenderTarget target, GpuTextureView encodedScene, ShaderpackMaterialSurfaces surfaces,
                        DecalBuffers.Frame decals, ColourEncoding sceneEncoding, @Nullable GpuTextureView ambient) {
        var response = surfaces.diffuseResponse();
        if (response == null || !sceneEncoding.isKnown()) return;
        if (target.width != surfaces.width() || target.height != surfaces.height()
                || target.width != decals.width() || target.height != decals.height())
            throw new IllegalArgumentException("Shaderpack decal material and scene dimensions differ");
        var materialEncoding = surfaces.surfaceEncoding() == null ? sceneEncoding : surfaces.surfaceEncoding();
        if (!materialEncoding.isKnown()) return;
        var pipeline = pipelines.computeIfAbsent(new Key(target.getColorTexture().getFormat(), sceneEncoding,
                materialEncoding, ambient != null), ShaderpackDecalColorResolver::pipeline);
        var sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        PhotonFullscreenPass.draw("LumenFX shaderpack decal material", pipeline, target.getColorTextureView(), pass -> {
            pass.bindTexture("LfxEncodedScene", encodedScene, sampler);
            pass.bindTexture("LfxReceiverBaseDepth", surfaces.baseDepth(), sampler);
            pass.bindTexture("LfxReceiverDiffuse", response, sampler);
            pass.bindTexture("LfxReceiverDepth", target.getDepthTextureView(), sampler);
            pass.bindTexture("LfxDecalColor", decals.color(), sampler);
            if (ambient != null) pass.bindTexture("LfxDecalAmbient", ambient, sampler);
        });
    }

    private static RenderPipeline pipeline(Key key) {
        var bindings = BindGroupLayout.builder().withSampler("LfxEncodedScene").withSampler("LfxReceiverBaseDepth")
                .withSampler("LfxReceiverDiffuse").withSampler("LfxReceiverDepth").withSampler("LfxDecalColor");
        if (key.ambientOnly()) bindings.withSampler("LfxDecalAmbient");
        return PhotonFullscreenPass.builder(LumenFx.id("post/shaderpack_decal_color"))
                .withLocation(LumenFx.id("pipeline/shaderpack_decal_color/" + key.format().ordinal() + "/"
                        + key.scene().shaderCode() + "/" + key.material().shaderCode() + "/" + key.ambientOnly()))
                .withShaderDefine("LFX_DECAL_SCENE_ENCODING", key.scene().shaderCode())
                .withShaderDefine("LFX_DECAL_MATERIAL_ENCODING", key.material().shaderCode())
                .withShaderDefine("LFX_DECAL_AMBIENT_ONLY", key.ambientOnly() ? 1 : 0)
                .withBindGroupLayout(bindings.build())
                .withColorTargetState(new ColorTargetState(Optional.of(BlendFunction.ADDITIVE), key.format(), 7))
                .build();
    }

    public void clear() { pipelines.clear(); }
}
