package com.kltyton.lumenfx.client.event;

import com.lowdragmc.photon.client.collision.BlockFilter;
import com.lowdragmc.photon.client.collision.CollisionWorld;
import com.lowdragmc.photon.client.fx.FXPreloader;
import com.lowdragmc.photon.client.light.DynamicLightManager;
import com.lowdragmc.photon.client.render.OpaqueDepthCapture;
import com.lowdragmc.photon.client.render.PhotonWorldRenderState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;

public final class WorldFxClientEvents {
    static {
        CollisionWorld.clientOwnerCheck(() -> Minecraft.getInstance().isSameThread());
        com.kltyton.lumenfx.client.keyframe.Blaze3dKeyframeAdapter.install();
    }

    private WorldFxClientEvents() {
    }

    public static void ensureInitialized() {
        // Triggers static CollisionWorld.clientOwnerCheck and keyframe playback adapter registration.
        com.kltyton.lumenfx.client.keyframe.Blaze3dKeyframeAdapter.install();
    }

    public static void onFrameStart() {
        FXPreloader.onFrameStart();
    }

    public static void onFrameEnd() {
        PhotonWorldRenderState.endFrame();
    }

    public static void onChunkChanged(ClientLevel level, ChunkPos position) {
        CollisionWorld.chunkChanged(level, position.x(), position.z());
        DynamicLightManager.chunkChanged(level, position.x(), position.z());
    }

    public static void onClientTagsUpdated() {
        BlockFilter.tagsChanged();
    }

    public static void onLevelUnload(ClientLevel level) {
        com.kltyton.lumenfx.client.keyframe.KeyframeEffectManager.onLevelUnload();
        CollisionWorld.levelUnloaded(level);
        DynamicLightManager.release(level);
        OpaqueDepthCapture.release();
    }
}
