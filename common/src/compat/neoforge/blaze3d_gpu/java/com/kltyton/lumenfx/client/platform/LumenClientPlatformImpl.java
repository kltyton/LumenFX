package com.kltyton.lumenfx.client.platform;

import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.client.VfxEngine;
import com.kltyton.lumenfx.client.render.motion.OwnerKey;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import java.io.Reader;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.util.context.ContextKey;
import net.neoforged.neoforge.client.model.UnbakedModelParser;
import net.neoforged.neoforge.client.model.quad.BakedNormals;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

final class LumenClientPlatformImpl {
    private static final ContextKey<VfxEngine.FrameInput> FRAME = new ContextKey<>(LumenFx.id("effect_frame"));
    private static final ContextKey<OwnerKey> MOTION_OWNER = new ContextKey<>(LumenFx.id("motion_owner"));

    private LumenClientPlatformImpl() { }

    static UnbakedModel parseUnbakedModel(Reader reader) {
        return UnbakedModelParser.parse(reader);
    }

    static void readCornerNormal(BakedQuad quad, int cornerIndex, Vector3fc faceNormal, float[] corner) {
        int packedNormal = quad.bakedNormals().normal(cornerIndex);
        if (BakedNormals.isUnspecified(packedNormal)) {
            corner[5] = faceNormal.x();
            corner[6] = faceNormal.y();
            corner[7] = faceNormal.z();
        } else {
            corner[5] = ((byte) packedNormal) / 127.0f;
            corner[6] = ((byte) (packedNormal >> 8)) / 127.0f;
            corner[7] = ((byte) (packedNormal >> 16)) / 127.0f;
        }
    }

    static VfxEngine.@Nullable FrameInput getFrameInput(LevelRenderState state) {
        return state.getRenderData(FRAME);
    }

    static void setFrameInput(LevelRenderState state, VfxEngine.FrameInput input) {
        state.setRenderData(FRAME, input);
    }

    static @Nullable OwnerKey getMotionOwner(EntityRenderState state) {
        return state.getRenderData(MOTION_OWNER);
    }

    static void setMotionOwner(EntityRenderState state, OwnerKey owner) {
        state.setRenderData(MOTION_OWNER, owner);
    }

    static RenderPipeline.Builder toBuilder(RenderPipeline pipeline) {
        return pipeline.toBuilder();
    }
}
