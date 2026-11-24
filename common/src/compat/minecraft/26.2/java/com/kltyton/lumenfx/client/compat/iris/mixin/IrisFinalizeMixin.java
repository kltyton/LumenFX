package com.kltyton.lumenfx.client.compat.iris.mixin;

import com.kltyton.lumenfx.client.compat.iris.IrisCompat;
import com.kltyton.lumenfx.client.compat.iris.material.IrisMaterialCapture;
import com.kltyton.lumenfx.client.compat.iris.particle.KbrIrisParticlePipelines;
import com.kltyton.lumenfx.client.event.ClientVfxEvents;
import com.mojang.blaze3d.opengl.GlStateManager;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL30C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Publishes opaque and final scene boundaries while preserving the shaderpack's deferred and composite passes. */
@Mixin(IrisRenderingPipeline.class)
abstract class IrisFinalizeMixin {
    @Inject(method = "beginLevelRendering", at = @At("RETURN"), remap = false)
    private void lumenfx$beginMaterialFrame(CallbackInfo callback) {
        IrisRenderingPipeline self = (IrisRenderingPipeline)(Object)this;
        KbrIrisParticlePipelines.register(self);
        IrisMaterialCapture.begin(self);
        var targets = ((IrisRenderingPipelineAccessor)(Object)self).lumenfx$renderTargets();
        if (targets != null) {
            com.kltyton.lumenfx.client.render.hdr.WorldHdrCapture.INSTANCE.syncExternalMotionDepth(targets.getDepthTexture());
        }
    }

    @Inject(
            method = "beginTranslucents",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/irisshaders/iris/targets/RenderTargets;copyPreTranslucentDepth()V",
                    shift = At.Shift.BEFORE
            ),
            remap = false
    )
    private void lumenfx$opaqueScene(CallbackInfo callback) {
        if (!ClientVfxEvents.hasShaderpackOpaqueWork(CapturedRenderingState.INSTANCE.getGbufferModelView())) return;
        IrisMaterialCapture.finish();
        IrisCompat.afterOpaque((IrisRenderingPipeline)(Object)this);
        GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, 0);
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0);
    }

    @Inject(
            method = "finalizeLevelRendering",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/irisshaders/iris/pipeline/CompositeRenderer;renderAll()V",
                    shift = At.Shift.BEFORE
            )
    )
    private void lumenfx$beforeComposite(final CallbackInfo callback) {
        IrisMaterialCapture.finish();
        IrisCompat.beforeComposite((IrisRenderingPipeline) (Object) this);
    }

    @Inject(method = "destroy", at = @At("HEAD"), remap = false)
    private void lumenfx$releaseMaterialTargets(CallbackInfo callback) {
        IrisMaterialCapture.release((IrisRenderingPipeline)(Object)this);
    }
}
