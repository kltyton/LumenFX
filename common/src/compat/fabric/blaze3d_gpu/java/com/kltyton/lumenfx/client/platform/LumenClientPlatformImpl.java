package com.kltyton.lumenfx.client.platform;

import com.kltyton.lumenfx.client.VfxEngine;
import com.kltyton.lumenfx.client.render.motion.OwnerKey;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.io.Reader;
import java.util.Optional;
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
        var builder = RenderPipeline.builder()
                .withLocation(pipeline.getLocation())
                .withVertexShader(pipeline.getVertexShader())
                .withFragmentShader(pipeline.getFragmentShader())
                .withPolygonMode(pipeline.getPolygonMode())
                .withCull(pipeline.isCull())
                .withPrimitiveTopology(pipeline.getPrimitiveTopology());
        var depth = pipeline.getDepthStencilState();
        builder.withDepthStencilState(Optional.ofNullable(depth));
        ColorTargetState[] targets = pipeline.getColorTargetStates();
        for (int i = 0; i < targets.length; i++) {
            if (targets[i] != null) {
                builder.withColorTargetState(i, targets[i]);
            } else {
                builder.withUnusedColorTargetState(i);
            }
        }
        VertexFormat[] bindings = pipeline.getVertexFormatBindings();
        for (int i = 0; i < bindings.length; i++) {
            if (bindings[i] != null) {
                builder.withVertexBinding(i, bindings[i]);
            }
        }
        for (BindGroupLayout layout : pipeline.getBindGroupLayouts()) {
            builder.withBindGroupLayout(layout);
        }
        var defines = pipeline.getShaderDefines();
        for (String flag : defines.flags()) {
            builder.withShaderDefine(flag);
        }
        for (var entry : defines.values().entrySet()) {
            String key = entry.getKey();
            String raw = entry.getValue();
            try {
                builder.withShaderDefine(key, Integer.parseInt(raw));
            } catch (NumberFormatException notInt) {
                try {
                    builder.withShaderDefine(key, Float.parseFloat(raw));
                } catch (NumberFormatException notFloat) {
                    builder.withShaderDefine(key);
                }
            }
        }
        return builder;
    }
}
