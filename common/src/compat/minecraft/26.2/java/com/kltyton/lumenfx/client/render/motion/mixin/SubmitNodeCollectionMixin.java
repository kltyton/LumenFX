package com.kltyton.lumenfx.client.render.motion.mixin;

import com.kltyton.lumenfx.client.render.motion.MotionHistory;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.List;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.feature.BlockModelFeatureRenderer;
import net.minecraft.client.renderer.feature.CustomFeatureRenderer;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.world.item.ItemDisplayContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Attaches owner + stable submission key at native submission creation. */
@Mixin(SubmitNodeCollection.class)
public abstract class SubmitNodeCollectionMixin {
    @WrapOperation(
            method = "submitCustomGeometry",
            at = @At(value = "NEW", target = "Lnet/minecraft/client/renderer/feature/CustomFeatureRenderer$Submit;"))
    private CustomFeatureRenderer.Submit lumenfx$attachCustom(
            PoseStack.Pose pose,
            RenderType renderType,
            SubmitNodeCollector.CustomGeometryRenderer renderer,
            Operation<CustomFeatureRenderer.Submit> original) {
        CustomFeatureRenderer.Submit submit = original.call(pose, renderType, renderer);
        MotionHistory.INSTANCE.attachCustomSubmit(submit, renderType);
        return submit;
    }

    @WrapOperation(
            method = "submitBlockModel",
            at = @At(value = "NEW", target = "Lnet/minecraft/client/renderer/feature/BlockModelFeatureRenderer$Submit;"))
    private BlockModelFeatureRenderer.Submit lumenfx$attachBlockModel(
            PoseStack.Pose pose,
            RenderType renderType,
            List<BlockStateModelPart> parts,
            int[] tintLayers,
            int lightCoords,
            int overlayCoords,
            int tintColor,
            PoseStack.Pose sheetedDecalPose,
            Operation<BlockModelFeatureRenderer.Submit> original) {
        return this.blockModel(original, pose, renderType, parts, tintLayers, lightCoords, overlayCoords, tintColor, sheetedDecalPose);
    }

    @WrapOperation(
            method = "submitBreakingBlockModel",
            at = @At(value = "NEW", target = "Lnet/minecraft/client/renderer/feature/BlockModelFeatureRenderer$Submit;"))
    private BlockModelFeatureRenderer.Submit lumenfx$attachBreakingBlockModel(
            PoseStack.Pose pose,
            RenderType renderType,
            List<BlockStateModelPart> parts,
            int[] tintLayers,
            int lightCoords,
            int overlayCoords,
            int tintColor,
            PoseStack.Pose sheetedDecalPose,
            Operation<BlockModelFeatureRenderer.Submit> original) {
        return this.blockModel(original, pose, renderType, parts, tintLayers, lightCoords, overlayCoords, tintColor, sheetedDecalPose);
    }

    private BlockModelFeatureRenderer.Submit blockModel(
            Operation<BlockModelFeatureRenderer.Submit> original,
            PoseStack.Pose pose,
            RenderType renderType,
            List<BlockStateModelPart> parts,
            int[] tintLayers,
            int lightCoords,
            int overlayCoords,
            int tintColor,
            PoseStack.Pose sheetedDecalPose) {
        BlockModelFeatureRenderer.Submit submit = original.call(
                pose, renderType, parts, tintLayers, lightCoords, overlayCoords, tintColor, sheetedDecalPose);
        MotionHistory.INSTANCE.attachBlockModelSubmit(submit, renderType, parts.size());
        return submit;
    }

    @WrapOperation(
            method = "submitItem",
            at = @At(value = "NEW", target = "Lnet/minecraft/client/renderer/feature/ItemFeatureRenderer$Submit;"))
    private ItemFeatureRenderer.Submit lumenfx$attachItem(
            PoseStack.Pose pose,
            ItemDisplayContext displayContext,
            int lightCoords,
            int overlayCoords,
            int outlineColor,
            int[] tintLayers,
            List<BakedQuad> quads,
            ItemStackRenderState.FoilType foilType,
            Operation<ItemFeatureRenderer.Submit> original) {
        ItemFeatureRenderer.Submit submit = original.call(
                pose, displayContext, lightCoords, overlayCoords, outlineColor, tintLayers, quads, foilType);
        MotionHistory.INSTANCE.attachItemSubmit(submit, displayContext, quads, foilType, outlineColor);
        return submit;
    }

}
