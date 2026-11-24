package com.kltyton.lumenfx.client.audio.mixin;

import com.kltyton.lumenfx.client.audio.wave.NiagaraPcmWave;
import com.kltyton.lumenfx.client.audio.wave.NiagaraWavePlayback;
import com.mojang.blaze3d.audio.OpenAlUtil;
import javax.sound.sampled.AudioFormat;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.EXTMCFormats;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Enables surround PCM and float source-mixer output for Niagara waves. */
@Mixin(OpenAlUtil.class)
public abstract class OpenAlNiagaraWaveMixin {
    @Inject(method = "audioFormatToOpenAl", at = @At("HEAD"), cancellable = true)
    private static void lumenfx$surroundWave(AudioFormat format, CallbackInfoReturnable<Integer> result) {
        if (format instanceof NiagaraWavePlayback.Format mixed) {
            result.setReturnValue(NiagaraWavePlayback.openAlFormat(mixed));
            return;
        }
        if (!(format instanceof NiagaraPcmWave.Format) || format.getChannels() <= 2) return;
        if (!AL.getCapabilities().AL_EXT_MCFORMATS)
            throw new IllegalStateException("The audio device lacks OpenAL PCM surround support for the Niagara wave");
        result.setReturnValue(switch (format.getChannels()) {
            case 4 -> EXTMCFormats.AL_FORMAT_QUAD16;
            case 6 -> EXTMCFormats.AL_FORMAT_51CHN16;
            case 8 -> EXTMCFormats.AL_FORMAT_71CHN16;
            default -> throw new IllegalArgumentException("Unsupported Niagara PCM surround channel count");
        });
    }
}
