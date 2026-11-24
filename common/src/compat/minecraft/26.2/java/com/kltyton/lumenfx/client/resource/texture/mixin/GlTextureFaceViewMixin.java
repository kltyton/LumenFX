package com.kltyton.lumenfx.client.resource.texture.mixin;

import com.kltyton.lumenfx.client.render.texture.layer.TextureLayerViews;
import com.mojang.blaze3d.opengl.GlConst;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.opengl.GlTextureView;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL43C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A GL_TEXTURE_2D subresource view shares immutable layer storage and host framebuffer ownership. */
@Mixin(GlTextureView.class)
abstract class GlTextureFaceViewMixin {
    @Unique private int lumenfx$faceView;
    @Inject(method = "<init>", at = @At("TAIL"))
    private void lumenfx$createFace(CallbackInfo callback) {
        var view = (GlTextureView)(Object)this;
        GlTexture texture = view.texture();
        var selection = TextureLayerViews.creating(texture);
        if (selection == null || selection.array()) return;
        GlStateManager.clearGlErrors();
        int id = GlStateManager._genTexture();
        try {
            // FrameBufferCache supplies the view's base mip as its GL attachment level.
            GL43C.glTextureView(id, GL11C.GL_TEXTURE_2D, texture.glId(), GlConst.toGlInternalId(texture.getFormat()),
                    0, texture.getMipLevels(), selection.first(), 1);
            int error = GlStateManager._getError();
            if (error != GL11C.GL_NO_ERROR) throw new IllegalStateException("OpenGL image layer view creation failed: " + error);
        }
        catch (RuntimeException | Error failure) {
            GlStateManager._deleteTexture(id); texture.removeViews(); throw failure;
        }
        lumenfx$faceView = id;
    }
    @Inject(method = "glId", at = @At("HEAD"), cancellable = true)
    private void lumenfx$faceId(CallbackInfoReturnable<Integer> callback) {
        if (lumenfx$faceView != 0) callback.setReturnValue(lumenfx$faceView);
    }
    @Inject(method = "close", at = @At("TAIL"))
    private void lumenfx$releaseFace(CallbackInfo callback) {
        if (lumenfx$faceView != 0) { GlStateManager._deleteTexture(lumenfx$faceView); lumenfx$faceView = 0; }
    }
}
