/* Adapted from Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.kltyton.lumenfx.client.playback.mixin;

import com.lowdragmc.photon.client.render.PhotonShaderSources;
import com.mojang.blaze3d.shaders.ShaderType;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Resolves Photon particle shader variants generated from the active pack's shader sources. */
@Mixin(ShaderManager.class)
abstract class ParticleShaderSourcesMixin {
    @Inject(method = "getShader", at = @At("HEAD"), cancellable = true)
    private void lumenfx$particleShader(Identifier id, ShaderType type, CallbackInfoReturnable<String> callback) {
        if (!PhotonShaderSources.isAdapted(id)) return;
        var source = PhotonShaderSources.get(id, type);
        if (source != null) callback.setReturnValue(source);
    }
}
