package com.kltyton.lumenfx.client.render.light.shadow.geometry;

import com.kltyton.lumenfx.effect.render.light.scene.ScenePointLight;
import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Borrows compiled opaque and cutout section geometry from the loaded view area, including off-camera casters. */
public final class TerrainShadowScene {
    private static final ChunkSectionLayer[] LAYERS = {ChunkSectionLayer.SOLID, ChunkSectionLayer.CUTOUT};
    public record Entry(ChunkSectionLayer layer, GpuBuffer vertices, @Nullable GpuBuffer indices,
                        @Nullable IndexType indexType, int firstIndex, int indexCount, int baseVertex, Vec3 sourceOffset,
                        @Nullable SectionMesh geometryIdentity) {
        public Entry(ChunkSectionLayer layer, GpuBuffer vertices, @Nullable GpuBuffer indices,
                     @Nullable IndexType indexType, int firstIndex, int indexCount, int baseVertex, Vec3 sourceOffset) {
            this(layer, vertices, indices, indexType, firstIndex, indexCount, baseVertex, sourceOffset, null);
        }
    }
    public record Frame(List<Entry> entries, int maximumSequentialIndices) {
        public Frame { entries = List.copyOf(entries); }
    }
    @FunctionalInterface
    private interface SectionCull { boolean visible(int x, int y, int z); }
    private TerrainShadowScene() { }

    public static Frame collect(LevelRenderer renderer, ScenePointLight light) {
        Vec3 source = light.data().shader().worldPosition();
        double cx = source.x * .01, cy = source.z * .01, cz = source.y * .01;
        double radius = light.data().radius() * .01, radiusSquared = radius * radius;
        return collect(renderer, source, new AABB(cx - radius, cy - radius, cz - radius, cx + radius, cy + radius, cz + radius),
                (x, y, z) -> {
                    double dx = distanceToInterval(cx, x, x + 16), dy = distanceToInterval(cy, y, y + 16), dz = distanceToInterval(cz, z, z + 16);
                    return dx * dx + dy * dy + dz * dz <= radiusSquared;
                });
    }

    public static Frame collectDirectional(LevelRenderer renderer, Vec3 camera, Frustum frustum) {
        Vec3 source = new Vec3(camera.x * 100, camera.z * 100, camera.y * 100);
        return collect(renderer, source, null,
                (x, y, z) -> frustum.isVisible(new AABB(x, y, z, x + 16, y + 16, z + 16)));
    }

    private static Frame collect(LevelRenderer renderer, Vec3 source, @Nullable AABB bounds, SectionCull cull) {
        RenderSystem.assertOnRenderThread();
        var area = renderer.viewArea();
        var dispatcher = renderer.sectionRenderDispatcher();
        if (area == null || dispatcher == null || area.getViewDistance() <= 0) {
            return new Frame(List.of(), 0);
        }
        var center = area.getCameraSectionPos();
        if (center == null) {
            return new Frame(List.of(), 0);
        }
        int distance = area.getViewDistance();
        int minX = center.x() - distance, maxX = center.x() + distance;
        int minY = area.minSectionY(), maxY = area.maxSectionY();
        int minZ = center.z() - distance, maxZ = center.z() + distance;
        if (bounds != null) {
            minX = Math.max(minX, section(bounds.minX)); maxX = Math.min(maxX, section(bounds.maxX));
            minY = Math.max(minY, section(bounds.minY)); maxY = Math.min(maxY, section(bounds.maxY));
            minZ = Math.max(minZ, section(bounds.minZ)); maxZ = Math.min(maxZ, section(bounds.maxZ));
        }
        var entries = new ArrayList<Entry>(); int maximumIndices = 0;
        var position = new BlockPos.MutableBlockPos();
        dispatcher.lock();
        try {
            for (int y = minY; y <= maxY; y++) for (int z = minZ; z <= maxZ; z++) for (int x = minX; x <= maxX; x++) {
                int bx = SectionPos.sectionToBlockCoord(x), by = SectionPos.sectionToBlockCoord(y), bz = SectionPos.sectionToBlockCoord(z);
                if (!cull.visible(bx, by, bz)) continue;
                var section = area.getRenderSectionAt(position.set(bx, by, bz));
                if (section == null || section.getSectionNode() != SectionPos.asLong(x, y, z)) continue;
                var mesh = section.getSectionMesh();
                Vec3 offset = new Vec3(bx * 100.0 - source.x, bz * 100.0 - source.y, by * 100.0 - source.z);
                for (ChunkSectionLayer layer : LAYERS) {
                    var draw = mesh.getSectionDraw(layer);
                    if (draw == null) continue;
                    var slice = dispatcher.getRenderSectionSlice(mesh, layer);
                    if (slice == null || draw.hasCustomIndexBuffer() && slice.indexBuffer() == null) continue;
                    GpuBuffer indices = draw.hasCustomIndexBuffer() ? slice.indexBuffer() : null;
                    IndexType type = draw.hasCustomIndexBuffer() ? draw.indexType() : null;
                    int first = indices == null ? 0 : Math.toIntExact(slice.indexBufferOffset() / draw.indexType().bytes);
                    int base = Math.toIntExact(slice.vertexBufferOffset() / layer.vertexFormat().getVertexSize());
                    entries.add(new Entry(layer, slice.vertexBuffer(), indices, type, first, draw.indexCount(), base, offset, mesh));
                    if (indices == null) maximumIndices = Math.max(maximumIndices, draw.indexCount());
                }
            }
        } finally { dispatcher.unlock(); }
        return new Frame(entries, maximumIndices);
    }

    private static int section(double coordinate) { return (int)Math.floor(coordinate / 16); }
    private static double distanceToInterval(double value, double minimum, double maximum) {
        return value < minimum ? minimum - value : value > maximum ? value - maximum : 0;
    }
}
