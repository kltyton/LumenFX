package com.kltyton.lumenfx.effect.niagara.audio;

import com.google.gson.JsonObject;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Authored class properties and the four values propagated through the device class tree. */
public record NiagaraSoundClass(String path, @Nullable NiagaraSoundClass parent, boolean inheritRuntime,
                                Properties authored, float volume, float pitch, boolean uiSound, boolean music,
                                Loading effectiveLoading) {
    public record Properties(float volume, float pitch, float lowPass, float attenuationDistanceScale,
                             float lfeBleed, float centerVolume, float radioVolume, float radioThreshold,
                             float reverbSend, OutputTarget outputTarget, Loading loading,
                             boolean masterEq, boolean alwaysPlay, boolean uiSound, boolean music,
                             boolean centerOnly, boolean ambientVolumes, boolean reverb, @Nullable String submix,
                             @Nullable NiagaraSubmix submixGraph, List<NiagaraSoundMix.Passive> passiveMixes) {
        public Properties { passiveMixes = List.copyOf(passiveMixes); }
    }

    public enum OutputTarget { SPEAKER, CONTROLLER, CONTROLLER_FALLBACK_TO_SPEAKER }
    public enum Loading {
        INHERITED, RETAIN_ON_LOAD, PRIME_ON_LOAD, LOAD_ON_DEMAND, FORCE_INLINE, UNINITIALIZED;

        static Loading fromNative(int value) {
            return value == 255 ? UNINITIALIZED : values()[value];
        }
    }

    public NiagaraSoundClass(String path, @Nullable NiagaraSoundClass parent, boolean inheritRuntime, Properties authored) {
        this(path, parent, inheritRuntime, authored,
                authored.volume() * (inheritRuntime && parent != null ? parent.volume() : 1),
                authored.pitch() * (inheritRuntime && parent != null ? parent.pitch() : 1),
                authored.uiSound() || inheritRuntime && parent != null && parent.uiSound(),
                authored.music() || inheritRuntime && parent != null && parent.music(),
                authored.loading() == Loading.INHERITED && parent != null ? parent.effectiveLoading() : authored.loading());
    }

    public static @Nullable NiagaraSoundClass read(@Nullable JsonObject source) {
        if (source == null) return null;
        var parent = read(source.getAsJsonObject("parent"));
        var authored = new Properties(
                scalar(source, "volume"), scalar(source, "pitch"), scalar(source, "low_pass"),
                scalar(source, "attenuation_distance_scale"), scalar(source, "lfe_bleed"),
                scalar(source, "center_volume"), scalar(source, "radio_volume"), scalar(source, "radio_threshold"),
                scalar(source, "reverb_send"), OutputTarget.values()[source.get("output_target").getAsInt()],
                Loading.fromNative(source.get("loading").getAsInt()),
                flag(source, "master_eq"), flag(source, "always_play"), flag(source, "ui_sound"), flag(source, "music"),
                flag(source, "center_only"), flag(source, "ambient_volumes"), flag(source, "reverb"),
                NiagaraSoundRouting.path(source, "submix"), NiagaraSubmix.read(source.getAsJsonObject("submix_graph")),
                NiagaraSoundMix.passiveMixes(source));
        return new NiagaraSoundClass(source.get("path").getAsString(), parent,
                source.has("inherit_runtime") && flag(source, "inherit_runtime"), authored);
    }

    private static float scalar(JsonObject source, String name) { return source.get(name).getAsFloat(); }
    private static boolean flag(JsonObject source, String name) { return source.get(name).getAsBoolean(); }
}
