/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.render;

import com.lowdragmc.kilagraph.rendertype.compiler.CompiledShaderGraph;
import com.lowdragmc.kilagraph.rendertype.compiler.MaterialUniformLayout;
import com.lowdragmc.kilagraph.rendertype.runtime.DynamicShaderSourceRegistry;
import com.lowdragmc.kilagraph.rendertype.runtime.RenderTypeGraphMaterial;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.LitParticles;
import com.lowdragmc.photon.client.shadergraph.PhotonShaderCompiler;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** KilaGraph-backed Photon shader-graph pipelines and RenderType factory (compiled only on targets with KilaGraph). */
public final class PhotonGraphPipelines {
    private record GraphKey(String contentHash, @Nullable PhotonPipelines.InstancedGeometryKey geometry,
                            PhotonPipelines.ParticlePipelineKey key, @Nullable PhotonPipelines.CpuData cpu) {
    }

    private static final Map<GraphKey, RenderPipeline> GRAPH_VARIANTS = new ConcurrentHashMap<>();

    private PhotonGraphPipelines() {
    }

    private static void applyGraphResources(PhotonPipelines.LayoutBuilder layout, CompiledShaderGraph compiled) {
        for (var ubo : compiled.builtinUniforms()) {
            layout.uniform(ubo);
        }
        if (!compiled.layout().isEmpty()) {
            layout.uniform(MaterialUniformLayout.UBO_NAME);
        }
        for (var block : compiled.uniformBlocks()) {
            layout.uniform(block.uboName());
        }
        for (var sampler : compiled.layout().samplers()) {
            layout.sampler(sampler);
        }
    }

    public static RenderPipeline graphShader(CompiledShaderGraph compiled,
                                             @Nullable PhotonPipelines.InstancedGeometryKey geometry,
                                             PhotonPipelines.ParticlePipelineKey key) {
        return graphShader(compiled, geometry, key, null);
    }

    public static RenderPipeline graphShader(CompiledShaderGraph compiled,
                                             @Nullable PhotonPipelines.InstancedGeometryKey geometry,
                                             PhotonPipelines.ParticlePipelineKey key,
                                             @Nullable PhotonPipelines.CpuData cpu) {
        var cpuData = geometry == null ? cpu : null;
        return GRAPH_VARIANTS.computeIfAbsent(new GraphKey(compiled.contentHash(), geometry, key, cpuData), gk -> {
            var k = gk.key();
            var shaderId = DynamicShaderSourceRegistry.shaderId(compiled.contentHash());
            var builder = RenderPipeline.builder()
                    .withLocation(Photon.id("pipeline/graph_" + PhotonPipelines.nextVariantId()))
                    .withVertexShader(shaderId)
                    .withFragmentShader(shaderId)
                    .withColorTargetState(PhotonPipelines.hdrTarget(k.blend()))
                    .withDepthStencilState(PhotonPipelines.depthState(k))
                    .withCull(k.cull());
            var layout = new PhotonPipelines.LayoutBuilder(Set.of());
            applyGraphResources(layout, compiled);
            var lit = LitParticles.includedBy(compiled.fragmentSource());
            if (lit) {
                PhotonPipelines.declareLights(builder, layout);
            }
            PhotonPipelines.applyGeometry(builder, layout, gk.geometry(), k.mode(), gk.cpu());
            layout.applyTo(builder);
            var pipeline = builder.build();
            return lit ? PhotonPipelines.markLit(pipeline) : pipeline;
        });
    }

    public static Optional<RenderType> createGraphShader(
            CompiledShaderGraph compiled,
            RenderTypeGraphMaterial material,
            PhotonPipelines.ParticlePipelineKey pipelineKey,
            long usedChannelMask, boolean usesCustomData) {
        var pipeline = graphShader(compiled, null, pipelineKey);
        if (!RenderSystem.getDevice().precompilePipeline(pipeline).isValid()) {
            Photon.LOGGER.warn("shader graph pipeline failed to compile: {}", compiled.contentHash());
            return Optional.empty();
        }
        var sceneSamplers = new ArrayList<String>();
        if (compiled.usesSceneColor()) {
            sceneSamplers.add(PhotonShaderCompiler.SCENE_COLOR);
        }
        if (compiled.usesSceneDepth()) {
            sceneSamplers.add(PhotonShaderCompiler.SCENE_DEPTH);
        }
        var setup = RenderSetup.builder(pipeline);
        if (compiled.usesLightmap()) {
            setup.useLightmap();
        }
        if (pipelineKey.blend() != null && pipelineKey.mode() == PrimitiveTopology.QUADS) {
            setup.sortOnUpload();
        }
        var renderType = RenderType.create("photon_shader_graph", setup.createRenderSetup());
        PhotonRenderTypes.registerDrawInfo(renderType, new PhotonRenderTypes.PhotonDrawInfo(
                new PhotonRenderTypes.PhotonDrawInfo.Programs(pipeline),
                new PhotonRenderTypes.PhotonDrawInfo.Bindings(Map.of(), sceneSamplers, null,
                        new KilaGraphSource(compiled, material, usedChannelMask, usesCustomData)),
                new PhotonRenderTypes.PhotonDrawInfo.InstancedRecipe(pipelineKey, null, null)));
        return Optional.of(renderType);
    }

    private record KilaGraphSource(CompiledShaderGraph compiled,
                                   RenderTypeGraphMaterial material,
                                   long usedChannelMask, boolean usesCustomData) implements PhotonRenderTypes.GraphSource {
        @Override
        public RenderPipeline pipeline(@Nullable PhotonPipelines.InstancedGeometryKey geometry,
                                       PhotonPipelines.ParticlePipelineKey key,
                                       @Nullable PhotonPipelines.CpuData cpu) {
            return graphShader(compiled, geometry, key, cpu);
        }

        @Override
        public void prepareUniforms() {
            material.prepareUniforms();
        }

        @Override
        public void bindCustomUniforms(RenderPass pass) {
            material.bindCustomUniforms(pass);
        }
    }
}
