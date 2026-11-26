package com.kltyton.lumenfx.client.render.motion.mixin;

import com.kltyton.lumenfx.client.render.motion.MotionHistory;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Records the stable texture and model identity for native geometry motion history. */
@Mixin(SubmitNodeCollection.class)
abstract class ModelSubmissionMixin {
    @WrapOperation(method = "submitModel",
            at = @At(value = "NEW", target = "Lnet/minecraft/client/renderer/feature/ModelFeatureRenderer$Submit;"))
    @SuppressWarnings({"rawtypes", "unchecked"})
    private ModelFeatureRenderer.Submit lumenfx$attachModel(
            RenderType renderType, PoseStack.Pose pose, Model model, Object state, int lightCoords, int overlayCoords,
            int tintedColor, TextureAtlasSprite sprite, PoseStack.Pose sheetedDecalPose,
            Operation<ModelFeatureRenderer.Submit> original) {
        ModelFeatureRenderer.Submit submit = original.call(
                renderType, pose, model, state, lightCoords, overlayCoords, tintedColor, sprite, sheetedDecalPose);
        String textureKey = sprite == null ? null : sprite.contents().name().toString();
        MotionHistory.INSTANCE.attachModelSubmit(submit, renderType, textureKey, model.getClass().getName());
        return submit;
    }
}
