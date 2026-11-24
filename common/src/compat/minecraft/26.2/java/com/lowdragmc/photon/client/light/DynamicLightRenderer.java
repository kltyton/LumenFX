/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.light;

import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.PhotonConfig;
import com.lowdragmc.photon.client.render.PhotonFullscreenPass;
import com.lowdragmc.photon.client.render.PhotonPipelines;
import com.lowdragmc.photon.client.render.PhotonSceneCapture;
import com.lowdragmc.photon.client.render.PhotonUploads;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.ScissorState;
import com.mojang.blaze3d.textures.GpuTextureView;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.function.Consumer;

/** Uploads the emitter lights, screen/depth clusters and voxel visibility consumed by lit particle materials. */
public final class DynamicLightRenderer {
    static final int MAX_LIGHTS = 1024;
    static final GpuFormat VISIBILITY_FORMAT = GpuFormat.R16_FLOAT;
    private static final int MAP_SIZE = 128;
    private static final int MAPS_PER_ROW = 8;
    private static final int MAP_ROWS = (PhotonConfig.MAX_SHADOWED_LIGHTS + MAPS_PER_ROW - 1) / MAPS_PER_ROW;
    private static final int LIGHTS_BLOCK_SIZE = 112;
    private static final String VISIBILITY_BLOCK = "PhotonLightView";
    private static final String VOXEL_PAGES = "VoxelPages";
    private static final String VOXEL_BRICKS = "VoxelBricks";
    private static final String VISIBILITY_LIGHTS = "VisibilityLights";
    private static final RenderPipeline VISIBILITY_PIPELINE = visibilityPipeline();

    private static final LightBuffer LIGHT_DATA = LightBuffer.texels("Photon light list");
    private static final LightBuffer VISIBILITY_DATA = LightBuffer.texels("Photon visibility light list");
    private static final java.nio.FloatBuffer DATA = MemoryUtil.memAllocFloat(MAX_LIGHTS * 16);
    private static final java.nio.FloatBuffer VISIBILITY = MemoryUtil.memAllocFloat(PhotonConfig.MAX_SHADOWED_LIGHTS * 4);
    private static final float[] VIEW = new float[MAX_LIGHTS * 4];
    private static final ArrayList<DynamicLight> LIGHTS = new ArrayList<>();
    private static final ArrayList<DynamicLight> SHADOWED = new ArrayList<>();
    private static final IntArrayList MAP_RANK = new IntArrayList();
    private static final Vector3f POSITION = new Vector3f();
    private static final Vector3f DIRECTION = new Vector3f();
    private static final Matrix4f INVERSE_VIEW = new Matrix4f();

    private static @Nullable GpuBufferSlice emptyBlock;
    private static @Nullable GpuBufferSlice lightsBlock;
    private static @Nullable GpuTextureView visibilityAtlas;
    private static long frame;

    private DynamicLightRenderer() {}

    public static void register(Consumer<RenderPipeline> registrar) {
        registrar.accept(VISIBILITY_PIPELINE);
    }

    private static RenderPipeline visibilityPipeline() {
        var builder = PhotonFullscreenPass.builder(Photon.id("core/dynamic_light_visibility"))
                .withLocation(Photon.id("pipeline/particle_light_visibility"))
                .withColorTargetState(PhotonFullscreenPass.opaqueTarget(VISIBILITY_FORMAT));
        PhotonPipelines.LayoutBuilder.standalone().uniform(VISIBILITY_BLOCK).texel(VOXEL_PAGES)
                .sampler(VOXEL_BRICKS).texel(VISIBILITY_LIGHTS).applyTo(builder);
        return builder.build();
    }

    public static void prepare(ClientLevel level, Vec3 camera, Matrix4fc view, Matrix4fc projection, Frustum frustum,
                               float partialTick, int width, int height) {
        lightsBlock = null;
        visibilityAtlas = null;
        var scene = DynamicLightManager.scene(level);
        if (scene != null) {
            collect(scene, camera, frustum, partialTick);
            var config = PhotonConfig.INSTANCE;
            if (!LIGHTS.isEmpty()) upload(level, scene, camera, view, projection, width, height,
                    config.lightShadowedLights.get(), config.lightVoxelBudgetMs.get());
            if (frame - scene.lastShadowFrame == 200) scene.releaseShadows();
        }
        LIGHT_DATA.current();
        LightClusters.clusterGrid();
        LightClusters.clusterIndices();
        VoxelWorld.emptyTexture();
        if (emptyBlock == null) emptyBlock = LightBuffer.constantBlock("PhotonLights (empty)", LIGHTS_BLOCK_SIZE,
                block -> block.putIVec4(LightClusters.TILE, 1, 1, 1)
                        .putVec4(LightClusters.NEAR, LightClusters.SLICE_SCALE, 0f, 0f)
                        .putIVec4(MAP_SIZE, MAPS_PER_ROW, 0, 0).putMat4f(new Matrix4f()));
    }

    private static void collect(DynamicLightManager.Scene scene, Vec3 camera, Frustum frustum, float partialTick) {
        DynamicLightManager.collect(scene, partialTick, LIGHTS);
        LIGHTS.removeIf(light -> {
            var pos = light.position;
            var color = light.color;
            double range = light.range;
            return !light.enabled || !(range > 0) || !(light.intensity > 0)
                    || !Double.isFinite(pos.x + pos.y + pos.z)
                    || !Float.isFinite(color.x + color.y + color.z + light.intensity + light.range)
                    || !frustum.isVisible(new AABB(pos.x - range, pos.y - range, pos.z - range,
                    pos.x + range, pos.y + range, pos.z + range));
        });
        LIGHTS.sort(Comparator.comparingDouble((DynamicLight light) -> importance(light, camera)).reversed());
    }

    private static double importance(DynamicLight light, Vec3 camera) {
        var color = light.color;
        var position = light.position;
        double luminance = 0.2126 * color.x + 0.7152 * color.y + 0.0722 * color.z;
        return luminance * light.intensity * light.range * light.range
                / Math.max(1.0, camera.distanceToSqr(position.x, position.y, position.z));
    }

    private static void upload(ClientLevel level, DynamicLightManager.Scene scene, Vec3 camera, Matrix4fc view,
                               Matrix4fc projection, int width, int height, int shadowBudget, double voxelBudgetMillis) {
        INVERSE_VIEW.set(view).invert();
        SHADOWED.clear();
        MAP_RANK.clear();
        DATA.clear();
        int count = Math.min(LIGHTS.size(), MAX_LIGHTS);
        for (int index = 0; index < count; index++) {
            var light = LIGHTS.get(index);
            int visibility = 0;
            if (light.castShadows && SHADOWED.size() < shadowBudget) {
                SHADOWED.add(light);
                MAP_RANK.add(index);
                visibility = SHADOWED.size();
            }
            pack(level, light, index, visibility, camera, view);
        }
        LightClusters.build(VIEW, count, projection, width, height);
        LIGHT_DATA.upload(DATA.flip());
        if (!SHADOWED.isEmpty()) {
            scene.lastShadowFrame = frame;
            scene.voxels.update(level, camera, SHADOWED, (long) (voxelBudgetMillis * 1_000_000));
            visibilityAtlas = drawVisibility(scene, camera);
        }
        lightsBlock = PhotonUploads.block(LIGHTS_BLOCK_SIZE, block -> block
                .putIVec4(LightClusters.TILE, LightClusters.tilesX(), LightClusters.tilesY(), LightClusters.SLICES)
                .putVec4(LightClusters.NEAR, LightClusters.SLICE_SCALE, 1f, 0f)
                .putIVec4(MAP_SIZE, MAPS_PER_ROW, SHADOWED.size(), 0)
                .putMat4f(INVERSE_VIEW));
    }

    private static void pack(ClientLevel level, DynamicLight light, int index, int map, Vec3 camera, Matrix4fc view) {
        var pos = light.position;
        view.transformPosition((float) (pos.x - camera.x), (float) (pos.y - camera.y),
                (float) (pos.z - camera.z), POSITION);
        int offset = index * 4;
        VIEW[offset] = POSITION.x;
        VIEW[offset + 1] = POSITION.y;
        VIEW[offset + 2] = POSITION.z;
        VIEW[offset + 3] = light.range;
        DATA.put(POSITION.x).put(POSITION.y).put(POSITION.z).put(light.range);
        view.transformDirection(light.direction, DIRECTION);
        float length = DIRECTION.length();
        boolean spot = light.type == DynamicLight.Type.SPOT && length > 1e-6f && Float.isFinite(length);
        if (spot) DIRECTION.div(length);
        else DIRECTION.zero();
        DATA.put(light.color.x * light.intensity).put(light.color.y * light.intensity)
                .put(light.color.z * light.intensity).put(spot ? 1f : 0f);
        float outer = light.outerAngle >= 0.1f ? Math.min(light.outerAngle, 179f) : 0.1f;
        float inner = light.innerAngle > 0f ? Math.min(light.innerAngle, outer - 0.05f) : 0f;
        var packedLight = LightCoordsUtil.getLightCoords(level, BlockPos.containing(pos.x, pos.y, pos.z));
        DATA.put(DIRECTION.x).put(DIRECTION.y).put(DIRECTION.z).put((float) Math.cos(Math.toRadians(outer)));
        DATA.put((float) Math.cos(Math.toRadians(inner))).put(map)
                .put(LightCoordsUtil.block(packedLight) + 16f * LightCoordsUtil.sky(packedLight))
                .put(Math.max(0f, light.sourceRadius));
    }

    private static GpuTextureView drawVisibility(DynamicLightManager.Scene scene, Vec3 camera) {
        int maps = MAP_RANK.size();
        var atlas = scene.visibilityAtlas(MAP_SIZE * MAPS_PER_ROW, MAP_SIZE * MAP_ROWS);
        VISIBILITY.clear();
        for (int rank = 0; rank < maps; rank++) {
            int index = MAP_RANK.getInt(rank) * 4;
            VISIBILITY.put(VIEW, index, 4);
        }
        var lights = VISIBILITY_DATA.upload(VISIBILITY.flip());
        var voxels = scene.voxels;
        var block = BlockPos.containing(camera);
        var parameters = PhotonUploads.block(112, b -> b.putMat4f(INVERSE_VIEW)
                .putVec4((float) (camera.x - block.getX()), (float) (camera.y - block.getY()),
                        (float) (camera.z - block.getZ()), 0f)
                .putIVec4(voxels.originBlockX() - block.getX(), voxels.originBlockY() - block.getY(),
                        voxels.originBlockZ() - block.getZ(), 0)
                .putVec4(MAP_SIZE, MAPS_PER_ROW, maps, 0f));
        var pages = voxels.pages();
        var bricks = voxels.bricks();
        var scissor = new ScissorState();
        scissor.enable(0, 0, atlas.getWidth(0), MAP_SIZE * ((maps + MAPS_PER_ROW - 1) / MAPS_PER_ROW));
        PhotonFullscreenPass.draw("Photon particle light visibility", VISIBILITY_PIPELINE, atlas, scissor, pass -> {
            pass.setUniform(VISIBILITY_BLOCK, parameters);
            pass.setUniform(VOXEL_PAGES, pages);
            pass.bindTexture(VOXEL_BRICKS, bricks, PhotonSceneCapture.sampler());
            pass.setUniform(VISIBILITY_LIGHTS, lights);
        });
        return atlas;
    }

    public static void bindLights(RenderPass pass) {
        pass.setUniform(PhotonPipelines.LIGHT_DATA, LIGHT_DATA.current());
        pass.setUniform(PhotonPipelines.CLUSTER_GRID, LightClusters.clusterGrid());
        pass.setUniform(PhotonPipelines.CLUSTER_INDICES, LightClusters.clusterIndices());
        pass.bindTexture(PhotonPipelines.VISIBILITY_ATLAS,
                visibilityAtlas == null ? VoxelWorld.emptyTexture() : visibilityAtlas, PhotonSceneCapture.sampler());
        pass.setUniform(PhotonPipelines.LIGHTS_BLOCK, lightsBlock == null ? emptyBlock : lightsBlock);
    }

    public static void endFrame() {
        frame++;
        lightsBlock = null;
        visibilityAtlas = null;
        LIGHTS.clear();
        SHADOWED.clear();
    }
}
