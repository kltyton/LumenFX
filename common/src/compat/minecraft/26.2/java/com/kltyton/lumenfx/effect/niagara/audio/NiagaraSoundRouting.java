package com.kltyton.lumenfx.effect.niagara.audio;

import com.google.gson.JsonObject;
import java.util.HashSet;
import org.jspecify.annotations.Nullable;

/** Parse-time routing. The cue's explicit send precedes the leaf wave's explicit send. */
public record NiagaraSoundRouting(@Nullable NiagaraSoundClass soundClass, @Nullable String sourceSubmix,
                                  @Nullable String eqSubmix, @Nullable String baseSubmix, @Nullable String reverbSubmix,
                                  @Nullable String waveSubmix, @Nullable NiagaraSoundMix baseMix,
                                  float minimumPitch, float maximumPitch, @Nullable NiagaraSubmix sourceGraph,
                                  @Nullable NiagaraSubmix waveGraph, ProjectSubmixes projectSubmixes) {
    public static final NiagaraSoundRouting DEFAULT = new NiagaraSoundRouting(null, null, null, null, null, null, null, .4f, 2f,
            null, null, new ProjectSubmixes(null, null, null, null));

    public record ProjectSubmixes(@Nullable NiagaraSubmix master, @Nullable NiagaraSubmix baseDefault,
                                 @Nullable NiagaraSubmix equalizer, @Nullable NiagaraSubmix reverb) {
        @Nullable NiagaraSubmix defaultRoute() { return baseDefault == null ? master : baseDefault; }
    }

    public NiagaraSoundRouting withSoundClass(NiagaraSoundClass value) {
        return new NiagaraSoundRouting(value, sourceSubmix, eqSubmix, baseSubmix, reverbSubmix, waveSubmix, baseMix,
                minimumPitch, maximumPitch, sourceGraph, waveGraph, projectSubmixes);
    }

    public NiagaraSoundRouting withWave(NiagaraSoundCue.Wave wave) {
        return new NiagaraSoundRouting(soundClass, sourceSubmix, eqSubmix, baseSubmix, reverbSubmix, wave.submix(), baseMix,
                minimumPitch, maximumPitch, sourceGraph, wave.submixGraph(), projectSubmixes);
    }

    public @Nullable String outputSubmix() {
        if (sourceSubmix != null) return sourceSubmix;
        if (waveSubmix != null) return waveSubmix;
        var properties = soundClass == null ? null : soundClass.authored();
        if (properties != null) {
            String submix = properties.masterEq() ? eqSubmix : properties.submix();
            if (submix != null) return submix;
        }
        return baseSubmix;
    }

    public float attenuationDistanceScale() { return soundClass == null ? 1 : Math.max(0, soundClass.authored().attenuationDistanceScale()); }
    public float clampPitch(float pitch) { return pitch < minimumPitch ? minimumPitch : Math.min(pitch, maximumPitch); }

    public @Nullable NiagaraSubmix outputSubmixGraph() {
        if (sourceSubmix != null) return sourceGraph;
        if (waveSubmix != null) return waveGraph;
        var properties = soundClass == null ? null : soundClass.authored();
        if (properties != null) {
            if (properties.masterEq() && eqSubmix != null) return projectSubmixes.equalizer();
            if (!properties.masterEq() && properties.submix() != null) return properties.submixGraph();
        }
        return projectSubmixes.defaultRoute();
    }

    /** Constant linear submix levels can be applied to a source before the device sums it. */
    public float linearSubmixGain() {
        var submix = outputSubmixGraph();
        if (submix == null) return 1;
        var visited = new HashSet<String>();
        float gain = 1;
        while (submix != null && visited.add(submix.path())) {
            if (!submix.hasConstantGain() || submix.dynamic()) return 1;
            gain *= submix.constantGain();
            if (same(submix, projectSubmixes.master())) return gain;
            if (same(submix, projectSubmixes.baseDefault()) || same(submix, projectSubmixes.equalizer())
                    || same(submix, projectSubmixes.reverb())) submix = projectSubmixes.master();
            else submix = submix.parent() == null ? projectSubmixes.defaultRoute() : submix.parent();
        }
        return submix == null ? gain : 1;
    }

    private static boolean same(NiagaraSubmix submix, @Nullable NiagaraSubmix other) {
        return other != null && submix.path().equalsIgnoreCase(other.path());
    }

    public static NiagaraSoundRouting read(@Nullable JsonObject source) {
        return source == null ? DEFAULT : new NiagaraSoundRouting(NiagaraSoundClass.read(source.getAsJsonObject("class")),
                path(source, "submix"), path(source, "eq_submix"), path(source, "base_submix"), path(source, "reverb_submix"), null,
                NiagaraSoundMix.read(source.getAsJsonObject("base_mix")),
                source.has("minimum_pitch") ? source.get("minimum_pitch").getAsFloat() : .4f,
                source.has("maximum_pitch") ? source.get("maximum_pitch").getAsFloat() : 2f,
                NiagaraSubmix.read(source.getAsJsonObject("submix_graph")), null, new ProjectSubmixes(
                        NiagaraSubmix.read(source.getAsJsonObject("master_submix_graph")),
                        NiagaraSubmix.read(source.getAsJsonObject("base_submix_graph")),
                        NiagaraSubmix.read(source.getAsJsonObject("eq_submix_graph")),
                        NiagaraSubmix.read(source.getAsJsonObject("reverb_submix_graph"))));
    }

    static @Nullable String path(JsonObject source, String field) {
        return source.has(field) ? source.get(field).getAsString() : null;
    }
}
