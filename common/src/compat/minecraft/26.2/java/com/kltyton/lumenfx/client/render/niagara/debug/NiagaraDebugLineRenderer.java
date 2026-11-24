package com.kltyton.lumenfx.client.render.niagara.debug;

import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.client.render.niagara.debug.NiagaraGpuDebugLines.Frame;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.debug.NiagaraCpuDebugLines.Snapshot;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.NiagaraPositionParameters;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;

/** Draws the original packed Niagara line format and fades lines behind the current scene depth. */
public final class NiagaraDebugLineRenderer {
    public static final String OCCLUDED_SCALE = "fx.Niagara.GpuComputeDebug.OccludedLineColorScale";
    private record Key(GpuFormat format, boolean depth) { }
    private final Map<Key, RenderPipeline> pipelines = new HashMap<>();

    public void draw(List<Frame> gpu, List<Snapshot> cpu, Matrix4fc modelView, RenderTarget target, Vec3 camera, float occludedScale) {
        if (cpu.isEmpty()) {
            draw(gpu, modelView, target, camera, occludedScale);
            return;
        }
        var frames = new ArrayList<Frame>();
        try (var upload = new NiagaraCpuDebugUpload()) {
            for (Snapshot source : cpu) upload.append(source, frames);
            frames.addAll(gpu);
            draw(frames, modelView, target, camera, occludedScale);
        }
    }

    private void draw(List<Frame> frames, Matrix4fc modelView, RenderTarget target, Vec3 camera, float occludedScale) {
        if (frames.isEmpty()) return;
        var sceneDepth = target.getDepthTextureView();
        var key = new Key(target.getColorTexture().getFormat(), sceneDepth != null);
        var pipeline = pipelines.computeIfAbsent(key, NiagaraDebugLineRenderer::pipeline);
        var indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.DEBUG_LINES);
        var indexBuffer = indices.getBuffer(2);
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        var model = new Matrix4f(modelView);
        var color = new Vector4f(occludedScale, 1, 1, 1);
        var transforms = new ArrayList<GpuBufferSlice>(frames.size());
        double tileInBlocks = NiagaraPositionParameters.TILE_SIZE / 100.0;
        for (Frame frame : frames) {
            var tile = frame.sourceTile();
            var offset = new Vector3f((float)(tile.x * tileInBlocks - camera.x),
                    (float)(tile.z * tileInBlocks - camera.y), (float)(tile.y * tileInBlocks - camera.z));
            transforms.add(RenderSystem.getDynamicUniforms().writeTransform(model, color, offset, new Matrix4f()));
        }
        try (var pass = encoder.createRenderPass(() -> "Niagara Debug Draw", target.getColorTextureView(), Optional.empty())) {
            pass.setPipeline(pipeline);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setIndexBuffer(indexBuffer, indices.type());
            if (sceneDepth != null)
                pass.bindTexture("DebugDepth", sceneDepth, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            for (int index = 0; index < frames.size(); index++) {
                Frame frame = frames.get(index);
                pass.setUniform("DynamicTransforms", transforms.get(index));
                pass.setUniform("DebugLines", frame.vertices());
                pass.drawIndexedIndirect(frame.arguments(), 1);
            }
        }
    }

    private static RenderPipeline pipeline(Key key) {
        var layout = BindGroupLayout.builder().withUniform("DebugLines", UniformType.TEXEL_BUFFER, GpuFormat.R32_UINT);
        if (key.depth()) layout.withSampler("DebugDepth");
        return RenderPipeline.builder().withLocation(LumenFx.id("pipeline/niagara/debug_lines/" + key.format().name().toLowerCase(java.util.Locale.ROOT)
                        + (key.depth() ? "_scene" : "_nodepth")))
                .withVertexShader(LumenFx.id("core/niagara/debug_lines")).withFragmentShader(LumenFx.id("core/niagara/debug_lines"))
                .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION).withBindGroupLayout(layout.build())
                .withShaderDefine("LFX_DEBUG_DEPTH", key.depth() ? 1 : 0).withCull(false)
                .withPrimitiveTopology(PrimitiveTopology.DEBUG_LINES).withDepthStencilState(Optional.empty())
                .withColorTargetState(new ColorTargetState(Optional.of(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA), key.format(), 15))
                .build();
    }

    public void clear() { pipelines.clear(); }
}
