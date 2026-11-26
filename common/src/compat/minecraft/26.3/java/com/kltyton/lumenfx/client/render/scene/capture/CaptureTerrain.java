package com.kltyton.lumenfx.client.render.scene.capture;

import com.kltyton.lumenfx.client.graphics.RenderPearlCompat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.textures.FilterMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.DynamicGpuData;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Util;

/** Renders compiled world sections against the capture frustum and native terrain pipelines on MC 26.3. */
final class CaptureTerrain {
    private final List<SectionRenderDispatcher.RenderSection> visible;
    private final CameraRenderState camera;

    CaptureTerrain(Minecraft minecraft, CameraRenderState camera) {
        this.camera = camera;
        visible = new ArrayList<>();
        var area = minecraft.levelRenderer.viewArea();
        if (area == null) return;
        var center = area.getCameraSectionPos();
        int range = area.getViewDistance();
        var position = new BlockPos.MutableBlockPos();
        for (int z = center.z() - range; z <= center.z() + range; z++)
            for (int x = center.x() - range; x <= center.x() + range; x++)
                for (int y = area.minSectionY(); y <= area.maxSectionY(); y++) {
                    position.set(SectionPos.sectionToBlockCoord(x), SectionPos.sectionToBlockCoord(y), SectionPos.sectionToBlockCoord(z));
                    var section = area.getRenderSectionAt(position);
                    if (section != null && section.getSectionNode() == SectionPos.asLong(x, y, z)
                            && camera.cullFrustum.isVisible(section.getBoundingBox())) visible.add(section);
                }
        visible.sort(Comparator.comparingDouble(section -> camera.pos.distanceToSqr(section.getBoundingBox().getCenter())));
    }

    void draw(Minecraft minecraft, RenderTarget target, ChunkSectionLayerGroup group) {
        var dispatcher = minecraft.levelRenderer.sectionRenderDispatcher();
        if (dispatcher == null || visible.isEmpty()) return;
        var atlas = minecraft.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
        var lightmap = minecraft.gameRenderer.lightmap();
        var sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        var sequential = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        int size = visible.size();
        long time = Util.getMillis();
        var dynamicUniforms = RenderSystem.getDynamicUniforms();
        var terrainUniform = dynamicUniforms.writeTerrainTransform(camera.viewRotationMatrix, atlas.getWidth(0), atlas.getHeight(0));
        var data = new DynamicGpuData.ChunkSectionInfo[size];
        for (int index = 0; index < size; index++) {
            var section = visible.get(index);
            var origin = section.getRenderOrigin();
            data[index] = new DynamicGpuData.ChunkSectionInfo(origin.getX(), origin.getY(), origin.getZ(),
                    section.getVisibility(time, 0L));
        }
        var uniforms = dynamicUniforms.writeChunkSections(data);
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        dispatcher.lock();
        try {
            int maximum = 0;
            for (var section : visible) for (var layer : group.layers()) {
                var draw = section.getSectionMesh().getSectionDraw(layer);
                if (draw != null && !draw.hasCustomIndexBuffer()) maximum = Math.max(maximum, draw.indexCount());
            }
            var defaultIndices = maximum == 0 ? null : sequential.getBuffer(maximum);
            try (var pass = encoder.createRenderPass(() -> "Niagara capture terrain " + group.label(),
                    target.getColorTextureView(), Optional.empty(), target.getDepthTextureView(), OptionalDouble.empty())) {
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("TerrainUniform", terrainUniform);
                pass.setUniform("Sampler0", atlas, sampler);
                pass.setUniform("Sampler2", lightmap, sampler);
                for (var layer : group.layers()) {
                    RenderPearlCompat.setPipeline(pass, layer.pipeline(false));
                    for (int index = 0; index < size; index++) {
                        int item = layer == ChunkSectionLayer.TRANSLUCENT ? size - index - 1 : index;
                        var section = visible.get(item);
                        var mesh = section.getSectionMesh();
                        var draw = mesh.getSectionDraw(layer);
                        if (draw == null) continue;
                        var slice = dispatcher.getRenderSectionSlice(mesh, layer);
                        if (slice == null || draw.hasCustomIndexBuffer() && slice.indexBuffer() == null) continue;
                        pass.setUniform("ChunkSection", uniforms[item]);
                        pass.setVertexBuffer(0, slice.vertexBuffer().slice());
                        int firstIndex = 0;
                        if (draw.hasCustomIndexBuffer()) {
                            pass.setIndexBuffer(slice.indexBuffer(), draw.indexType());
                            firstIndex = Math.toIntExact(slice.indexBufferOffset() / draw.indexType().bytes);
                        } else {
                            pass.setIndexBuffer(defaultIndices, sequential.type());
                        }
                        int baseVertex = Math.toIntExact(slice.vertexBufferOffset() / layer.vertexFormat().getVertexSize());
                        pass.drawIndexed(draw.indexCount(), 1, firstIndex, baseVertex, 0);
                    }
                }
            }
        } finally {
            dispatcher.unlock();
        }
    }
}
