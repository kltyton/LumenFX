/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.light;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/** Level-scoped light providers owned by live particle emitters. */
public final class DynamicLightManager {
    private static final WeakHashMap<Level, Scene> SCENES = new WeakHashMap<>();
    private static final List<DynamicLight> POOL = new ArrayList<>();
    private static int poolUsed;
    private static final LightSink SINK = () -> {
        if (poolUsed == POOL.size()) POOL.add(new DynamicLight());
        return POOL.get(poolUsed++).reset();
    };

    private DynamicLightManager() {}

    public static void addProvider(Level level, LightProvider provider) {
        SCENES.computeIfAbsent(level, key -> new Scene()).providers.add(provider);
    }

    public static void removeProvider(Level level, LightProvider provider) {
        var scene = SCENES.get(level);
        if (scene != null) scene.providers.remove(provider);
    }

    public static void blockChanged(Level level, int x, int y, int z) {
        var scene = SCENES.get(level);
        if (scene != null) scene.voxels.markDirty(x, y, z);
    }

    public static void chunkChanged(Level level, int x, int z) {
        var scene = SCENES.get(level);
        if (scene != null) scene.voxels.markColumnDirty(x, z);
    }

    public static void release(Level level) {
        var scene = SCENES.remove(level);
        if (scene != null) scene.releaseShadows();
    }

    @Nullable
    static Scene scene(Level level) {
        return SCENES.get(level);
    }

    static void collect(Scene scene, float partialTick, List<DynamicLight> out) {
        poolUsed = 0;
        out.clear();
        for (var provider : List.copyOf(scene.providers)) provider.submitLights(SINK, partialTick);
        out.addAll(POOL.subList(0, poolUsed));
    }

    static final class Scene {
        private final Set<LightProvider> providers = Collections.newSetFromMap(new WeakHashMap<>());
        final VoxelWorld voxels = new VoxelWorld();
        private @Nullable GpuTexture visibilityTexture;
        private @Nullable GpuTextureView visibilityView;
        long lastShadowFrame;

        GpuTextureView visibilityAtlas(int width, int height) {
            if (visibilityView == null) {
                var device = RenderSystem.getDevice();
                var texture = device.createTexture(() -> "Photon particle light visibility",
                        GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT,
                        DynamicLightRenderer.VISIBILITY_FORMAT, width, height, 1, 1);
                try {
                    visibilityView = device.createTextureView(texture);
                    visibilityTexture = texture;
                } catch (RuntimeException | Error error) {
                    texture.close();
                    throw error;
                }
            }
            return visibilityView;
        }

        void releaseShadows() {
            if (visibilityView != null) {
                var view = visibilityView;
                var texture = visibilityTexture;
                visibilityView = null;
                visibilityTexture = null;
                RenderSystem.queueFencedTask(() -> {
                    view.close();
                    texture.close();
                });
            }
            voxels.release();
        }
    }
}
