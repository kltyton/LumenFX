package com.kltyton.lumenfx.client.resource.texture.mixin;

import com.kltyton.lumenfx.client.render.texture.cube.CubeFaceView;
import com.kltyton.lumenfx.client.render.texture.layer.TextureLayerView;
import com.kltyton.lumenfx.client.render.texture.layer.TextureLayerViews;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Records the explicit layer or array selection made when creating a native image view. */
@Mixin(GpuTextureView.class)
abstract class GpuTextureFaceViewMixin implements CubeFaceView, TextureLayerView {
    @Unique private int lumenfx$cubeFace = -1;
    @Unique private int lumenfx$imageLayer = -1;
    @Unique private boolean lumenfx$imageArray;
    @Inject(method = "<init>", at = @At("TAIL"))
    private void lumenfx$faceSubresource(GpuTexture texture, int baseMip, int mipCount, CallbackInfo callback) {
        var selection = TextureLayerViews.creating(texture);
        if (selection == null) return;
        lumenfx$imageArray = selection.array();
        if (selection.array()) return;
        lumenfx$imageLayer = selection.first();
        if ((texture.usage() & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0) lumenfx$cubeFace = selection.first();
    }
    @Override public int cubeFaceLayer() { return lumenfx$cubeFace; }
    @Override public int selectedImageLayer() { return lumenfx$imageLayer; }
    @Override public boolean samplesImageArray() { return lumenfx$imageArray; }
}
