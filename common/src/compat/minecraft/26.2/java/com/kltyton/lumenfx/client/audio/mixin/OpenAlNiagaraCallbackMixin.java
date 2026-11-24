package com.kltyton.lumenfx.client.audio.mixin;

import com.kltyton.lumenfx.client.audio.wave.NiagaraWavePlayback;
import com.mojang.blaze3d.audio.Channel;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import net.minecraft.client.sounds.AudioStream;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Lets the device pull samples from the Niagara source mixer without queuing the vanilla stream buffers. */
@Mixin(Channel.class)
public abstract class OpenAlNiagaraCallbackMixin {
    @Unique private @Nullable NiagaraWavePlayback lumenfx$callback;

    @Inject(method = "attachBufferStream", at = @At("HEAD"), cancellable = true)
    private void lumenfx$attachCallback(AudioStream stream, CallbackInfo info) {
        if (!(stream instanceof NiagaraWavePlayback playback)) return;
        try {
            if (!playback.attachCallback(((Channel)(Object)this).source)) return;
            lumenfx$callback = playback;
        } catch (IOException | RuntimeException | LinkageError failure) {
            try { playback.close(); } catch (IOException close) { failure.addSuppressed(close); }
            playback.failed(failure);
        }
        info.cancel();
    }

    @Inject(method = "destroy", at = @At("RETURN"))
    private void lumenfx$releaseCallback(CallbackInfo info) {
        var playback = lumenfx$callback;
        if (playback == null) return;
        try {
            playback.close();
            lumenfx$callback = null;
        } catch (IOException failure) {
            LogUtils.getLogger().error("Failed to release the Niagara audio callback", failure);
        }
    }
}
