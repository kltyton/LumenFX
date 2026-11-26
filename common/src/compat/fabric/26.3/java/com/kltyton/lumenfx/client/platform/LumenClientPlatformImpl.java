package com.kltyton.lumenfx.client.platform;

import com.kltyton.lumenfx.client.VfxEngine;
import com.kltyton.lumenfx.client.graphics.RenderPearlCompat;
import com.kltyton.lumenfx.client.render.motion.OwnerKey;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import java.io.Reader;
import net.fabricmc.fabric.api.client.model.loading.v1.UnbakedModelDeserializer;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

final class LumenClientPlatformImpl {
    private static final RenderStateDataKey<VfxEngine.FrameInput> FRAME =
            RenderStateDataKey.create(() -> "lumenfx:effect_frame");
    private static final RenderStateDataKey<OwnerKey> MOTION_OWNER =
            RenderStateDataKey.create(() -> "lumenfx:motion_owner");

    private LumenClientPlatformImpl() { }

    static UnbakedModel parseUnbakedModel(Reader reader) {
        return UnbakedModelDeserializer.deserialize(reader);
    }

    static void readCornerNormal(BakedQuad quad, int cornerIndex, Vector3fc faceNormal, float[] corner) {
        corner[5] = faceNormal.x();
        corner[6] = faceNormal.y();
        corner[7] = faceNormal.z();
    }

    static VfxEngine.@Nullable FrameInput getFrameInput(LevelRenderState state) {
        return state.getData(FRAME);
    }

    static void setFrameInput(LevelRenderState state, VfxEngine.FrameInput input) {
        state.setData(FRAME, input);
    }

    static @Nullable OwnerKey getMotionOwner(EntityRenderState state) {
        return state.getData(MOTION_OWNER);
    }

    static void setMotionOwner(EntityRenderState state, OwnerKey owner) {
        state.setData(MOTION_OWNER, owner);
    }

    static RenderPipeline.Builder toBuilder(RenderPipeline pipeline) {
        return RenderPearlCompat.toBuilder(pipeline);
    }
}
