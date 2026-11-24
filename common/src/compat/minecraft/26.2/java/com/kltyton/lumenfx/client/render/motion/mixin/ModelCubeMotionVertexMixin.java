package com.kltyton.lumenfx.client.render.motion.mixin;

import com.kltyton.lumenfx.client.render.motion.MotionHistory;
import com.kltyton.lumenfx.client.render.motion.SourceVertexIdentity;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Publishes baked source polygon/vertex identity around each native cube vertex emission. */
@Mixin(ModelPart.Cube.class)
public abstract class ModelCubeMotionVertexMixin {
    @WrapOperation(
            method = "compile",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/vertex/VertexConsumer;addVertex(FFFIFFIIFFF)V"))
    private void lumenfx$captureSourceVertex(
            VertexConsumer instance,
            float x,
            float y,
            float z,
            int color,
            float u,
            float v,
            int overlayCoords,
            int lightCoords,
            float nx,
            float ny,
            float nz,
            Operation<Void> original,
            @Local ModelPart.Polygon polygon,
            @Local ModelPart.Vertex vertex) {
        if (MotionHistory.INSTANCE.isCapturingVertices()) {
            SourceVertexIdentity.push(polygon, vertex);
            try {
                original.call(
                        instance, x, y, z, color, u, v, overlayCoords, lightCoords, nx, ny, nz);
            } finally {
                SourceVertexIdentity.pop();
            }
        } else {
            original.call(instance, x, y, z, color, u, v, overlayCoords, lightCoords, nx, ny, nz);
        }
    }
}
