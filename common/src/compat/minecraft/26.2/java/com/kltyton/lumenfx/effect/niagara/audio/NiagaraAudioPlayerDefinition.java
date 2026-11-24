package com.kltyton.lumenfx.effect.niagara.audio;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.api.client.parameter.NiagaraAudioBackend.Settings;
import com.kltyton.lumenfx.api.client.parameter.NiagaraAudioBackend.Overrides;
import com.kltyton.lumenfx.effect.curve.NiagaraCurve;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import net.minecraft.resources.Identifier;

/** Source Audio Player configuration and author parameter names. */
public record NiagaraAudioPlayerDefinition(Settings settings, String configurationParameter, List<String> parameters,
                                           int maximumOneShots, boolean stopOnDestroy, boolean loopingOneShots,
                                           boolean gameplayOnly) {
    public NiagaraAudioPlayerDefinition {
        configurationParameter = SourceNames.canonical(configurationParameter);
        parameters = List.copyOf(parameters);
    }

    public static Overrides readOverrides(JsonObject data, Function<JsonObject, NiagaraCurve> curves) {
        return new Overrides(data.get("override_attenuation").getAsBoolean(),
                data.has("attenuation") ? NiagaraSoundAcoustics.readAttenuation(data.getAsJsonObject("attenuation"), curves) : null,
                data.get("override_concurrency").getAsBoolean(),
                data.has("concurrency") ? NiagaraSoundAcoustics.readConcurrency(data.getAsJsonObject("concurrency")) : null);
    }

    public static NiagaraAudioPlayerDefinition read(JsonObject data, Function<JsonObject, NiagaraCurve> curves) {
        if (data.has("sound_source")) throw new IllegalArgumentException("Niagara sound source must be packaged before loading");
        var names = new ArrayList<String>();
        for (var entry : data.getAsJsonArray("parameter_names")) names.add(entry.getAsString());
        return new NiagaraAudioPlayerDefinition(readSound(data, curves), text(data, "configuration"), names,
                data.get("maximum_one_shots").getAsInt(), data.get("stop_on_destroy").getAsBoolean(),
                data.get("looping_one_shots").getAsBoolean(), data.get("gameplay_only").getAsBoolean());
    }

    public static Settings readSound(JsonObject data, Function<JsonObject, NiagaraCurve> curves) {
        if (data.has("sound_source")) throw new IllegalArgumentException("Niagara sound source must be packaged before loading");
        var sound = data.has("sound") ? Identifier.parse(data.get("sound").getAsString()) : null;
        var cue = data.has("cue") ? NiagaraSoundCue.read(data.getAsJsonObject("cue"), curves) : null;
        boolean looping = cue == null ? data.has("looping") && data.get("looping").getAsBoolean() : cue.looping();
        var attenuation = data.has("attenuation") ? NiagaraSoundAcoustics.readAttenuation(data.getAsJsonObject("attenuation"), curves) : null;
        var concurrency = data.has("concurrency") ? NiagaraSoundAcoustics.readConcurrency(data.getAsJsonObject("concurrency")) : null;
        return new Settings(sound, looping, attenuation, concurrency,
                data.has("source_volume") ? data.get("source_volume").getAsFloat() : 1f,
                data.has("source_pitch") ? data.get("source_pitch").getAsFloat() : 1f, cue,
                data.has("source_priority") ? data.get("source_priority").getAsFloat() : 1f,
                NiagaraSoundAcoustics.readConcurrencySet(data.getAsJsonArray("asset_concurrency")),
                NiagaraSoundRouting.read(data.getAsJsonObject("sound_routing")));
    }

    private static String text(JsonObject source, String key) { return source.has(key) ? source.get(key).getAsString() : ""; }
}
