package com.kltyton.lumenfx.client.compat.iris.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.textures.GpuTexture;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.gl.texture.DepthBufferFormat;
import net.irisshaders.iris.gl.texture.DepthCopyStrategy;
import net.irisshaders.iris.targets.RenderTargets;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** Uses Iris's allocated depth-stencil targets and normal copy strategy on the first copy after allocation. */
@Mixin(value = RenderTargets.class, remap = false)
abstract class IrisDepthCopyMixin {
    @Shadow @Final private GlFramebuffer depthSourceFb;
    @Shadow @Final private GlFramebuffer noHandDestFb;
    @Shadow @Final private GlFramebuffer noTranslucentsDestFb;
    @Shadow private DepthCopyStrategy copyStrategy;

    @WrapOperation(method = "copyPreHandDepth", at = @At(value = "INVOKE",
            target = "Lnet/irisshaders/iris/gl/IrisRenderSystem;copyTexImage2D(IIIIIIII)V"))
    private void lumenfx$copyPreHandDepth(int target, int level, int format, int x, int y,
            int width, int height, int border, Operation<Void> original) {
        if (lumenfx$combinedDepthStencil(format)) {
            RenderTargets targets = (RenderTargets) (Object) this;
            lumenfx$copyDepthStencil(targets.getDepthTexture(), targets.getDepthTextureNoHand(),
                    noHandDestFb, width, height);
        } else {
            original.call(target, level, format, x, y, width, height, border);
        }
    }

    @WrapOperation(method = "copyPreTranslucentDepth", at = @At(value = "INVOKE",
            target = "Lnet/irisshaders/iris/gl/IrisRenderSystem;copyTexImage2D(IIIIIIII)V"))
    private void lumenfx$copyPreTranslucentDepth(int target, int level, int format, int x, int y,
            int width, int height, int border, Operation<Void> original) {
        if (lumenfx$combinedDepthStencil(format)) {
            RenderTargets targets = (RenderTargets) (Object) this;
            lumenfx$copyDepthStencil(targets.getDepthTexture(), targets.getDepthTextureNoTranslucents(),
                    noTranslucentsDestFb, width, height);
        } else {
            original.call(target, level, format, x, y, width, height, border);
        }
    }

    @Unique
    private static boolean lumenfx$combinedDepthStencil(int format) {
        return DepthBufferFormat.fromGlEnumOrDefault(format).isCombinedStencil();
    }

    @Unique
    private void lumenfx$copyDepthStencil(GpuTexture source, GpuTexture destination,
            GlFramebuffer destinationFramebuffer, int width, int height) {
        // The first-copy branch uses glCopyTexImage2D, which fails for these combined formats.
        // Iris already created both textures and configured the appropriate copy or blit strategy.
        copyStrategy.copy(depthSourceFb, ((GlTexture) source).glId(),
                destinationFramebuffer, ((GlTexture) destination).glId(), width, height);
    }
}
