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
import net.minecraft.client.renderer.texture.UvMapping;
import net.minecraft.client.resources.palette.PalettedTextureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Reads the 26.3 native UV mapping without replacing the submitted model or remap. */
@Mixin(SubmitNodeCollection.class)
abstract class ModelSubmissionMixin {
    @WrapOperation(method = "submitModel",
            at = @At(value = "NEW", target = "Lnet/minecraft/client/renderer/feature/ModelFeatureRenderer$Submit;"))
    @SuppressWarnings({"rawtypes", "unchecked"})
    private ModelFeatureRenderer.Submit lumenfx$attachModel(
            RenderType renderType, PoseStack.Pose pose, Model model, Object state, int lightCoords, int overlayCoords,
            int tintedColor, UvMapping uvMapping, PoseStack.Pose sheetedDecalPose,
            Operation<ModelFeatureRenderer.Submit> original) {
        ModelFeatureRenderer.Submit submit = original.call(
                renderType, pose, model, state, lightCoords, overlayCoords, tintedColor, uvMapping, sheetedDecalPose);
        String textureKey = switch (uvMapping) {
            case null -> null;
            case TextureAtlasSprite sprite -> sprite.contents().name().toString();
            case PalettedTextureManager.Handle handle -> handle.textureLocation() + "|" + handle.getU(0)
                    + "|" + handle.getV(0) + "|" + handle.getU(1) + "|" + handle.getV(1);
            default -> uvMapping.getClass().getName() + "|" + uvMapping.getU(0) + "|" + uvMapping.getV(0)
                    + "|" + uvMapping.getU(1) + "|" + uvMapping.getV(1);
        };
        MotionHistory.INSTANCE.attachModelSubmit(submit, renderType, textureKey, model.getClass().getName());
        return submit;
    }
}
