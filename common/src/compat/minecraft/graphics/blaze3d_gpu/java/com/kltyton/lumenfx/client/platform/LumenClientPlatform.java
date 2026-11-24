package com.kltyton.lumenfx.client.platform;

import com.kltyton.lumenfx.client.VfxEngine;
import com.kltyton.lumenfx.client.render.motion.OwnerKey;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import java.io.Reader;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

/** Client-side loader hooks for MC 26.x (NeoForge vs Fabric) without leaking loader types into common code. */
public final class LumenClientPlatform {
    private LumenClientPlatform() { }

    public static UnbakedModel parseUnbakedModel(Reader reader) {
        return LumenClientPlatformImpl.parseUnbakedModel(reader);
    }

    public static void readCornerNormal(BakedQuad quad, int cornerIndex, Vector3fc faceNormal, float[] corner) {
        LumenClientPlatformImpl.readCornerNormal(quad, cornerIndex, faceNormal, corner);
    }

    public static VfxEngine.@Nullable FrameInput getFrameInput(LevelRenderState state) {
        return LumenClientPlatformImpl.getFrameInput(state);
    }

    public static void setFrameInput(LevelRenderState state, VfxEngine.FrameInput input) {
        LumenClientPlatformImpl.setFrameInput(state, input);
    }

    public static @Nullable OwnerKey getMotionOwner(EntityRenderState state) {
        return LumenClientPlatformImpl.getMotionOwner(state);
    }

    public static void setMotionOwner(EntityRenderState state, OwnerKey owner) {
        LumenClientPlatformImpl.setMotionOwner(state, owner);
    }

    public static RenderPipeline.Builder toBuilder(RenderPipeline pipeline) {
        return LumenClientPlatformImpl.toBuilder(pipeline);
    }
}
