package com.kltyton.lumenfx.effect.niagara.audio;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;

/** The source submix and sampling settings of a waveform or spectrum interface. */
public record NiagaraAudioAnalysisDefinition(boolean spectrum, String source, int resolution, float windowMilliseconds,
                                             float minimumFrequency, float maximumFrequency, float noiseFloor) {
    public NiagaraAudioAnalysisDefinition {
        source = SourceNames.canonical(source);
        if (resolution <= 0 || !Float.isFinite(windowMilliseconds) || !Float.isFinite(minimumFrequency)
                || !Float.isFinite(maximumFrequency) || !Float.isFinite(noiseFloor))
            throw new IllegalArgumentException("Invalid Niagara audio analyzer settings");
    }
    public static NiagaraAudioAnalysisDefinition read(JsonObject source) {
        boolean spectrum = source.get("spectrum").getAsBoolean();
        int resolution = source.get("resolution").getAsInt();
        return new NiagaraAudioAnalysisDefinition(spectrum, source.get("submix").getAsString(),
                spectrum ? Math.clamp(resolution, 1, 16384) : resolution, source.get("scope_milliseconds").getAsFloat(),
                source.get("minimum_frequency").getAsFloat(), source.get("maximum_frequency").getAsFloat(),
                source.get("noise_floor").getAsFloat());
    }
}
