package com.kltyton.lumenfx.api.client.parameter;

import com.kltyton.lumenfx.effect.niagara.audio.NiagaraSoundCue;
import com.kltyton.lumenfx.effect.niagara.audio.NiagaraSoundCue.Wave;
import com.kltyton.lumenfx.effect.niagara.audio.NiagaraSoundRouting;
import com.kltyton.lumenfx.effect.niagara.audio.NiagaraSoundAcoustics.Attenuation;
import com.kltyton.lumenfx.effect.niagara.audio.NiagaraSoundAcoustics.Concurrency;

import java.util.Map;
import java.util.List;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Playback surface used by the Niagara Audio Player interface. */
public interface NiagaraAudioBackend {
    /** Source audio quality, mature-language policy and platform limit for loaded Random branches. */
    record DeviceSettings(int qualityLevel, boolean allowMatureLanguage, int maximumPreloadedBranches) {
        public static final DeviceSettings DEFAULT = new DeviceSettings(0, false, 0);

        public DeviceSettings {
            if (qualityLevel < 0 || maximumPreloadedBranches < 0)
                throw new IllegalArgumentException("Audio device settings cannot use negative quality levels or branch limits");
        }
    }

    /** Each attenuation stage retains the class identity in effect at that point in the cue parse. */
    record DistanceAttenuation(Attenuation profile, float distanceScale, @Nullable String soundClass) {
        public DistanceAttenuation(Attenuation profile, float distanceScale) { this(profile, distanceScale, null); }

        public DistanceAttenuation(Attenuation profile, NiagaraSoundRouting routing) {
            this(profile, routing.attenuationDistanceScale(), routing.soundClass() == null ? null : routing.soundClass().path());
        }
    }

    /** Positions are absolute source centimeters. Rotations are source pitch, yaw and roll in degrees. */
    record Playback(Identifier sound, boolean looping, Vec3 position, Vec3 rotation, float volume, float pitch,
                    float startSeconds, float fadeInSeconds, Map<String, Object> parameters,
                    @Nullable Attenuation attenuation, List<Concurrency> concurrency, @Nullable NiagaraSoundCue cue,
                    @Nullable Object owner, float priority, @Nullable String subtitle, NiagaraSoundRouting routing,
                    List<DistanceAttenuation> distanceAttenuations, boolean firstSourceParse) {
        public Playback(Identifier sound, boolean looping, Vec3 position, Vec3 rotation, float volume, float pitch,
                        float startSeconds, float fadeInSeconds, Map<String, Object> parameters,
                        @Nullable Attenuation attenuation, List<Concurrency> concurrency, @Nullable NiagaraSoundCue cue,
                        @Nullable Object owner, float priority, @Nullable String subtitle, NiagaraSoundRouting routing) {
            this(sound, looping, position, rotation, volume, pitch, startSeconds, fadeInSeconds, parameters,
                    attenuation, concurrency, cue, owner, priority, subtitle, routing,
                    attenuation == null ? List.of() : List.of(new DistanceAttenuation(attenuation, routing)), true);
        }

        public Playback(Identifier sound, boolean looping, Vec3 position, Vec3 rotation, float volume, float pitch,
                        float startSeconds, float fadeInSeconds, Map<String, Object> parameters,
                        @Nullable Attenuation attenuation, List<Concurrency> concurrency, @Nullable NiagaraSoundCue cue,
                        @Nullable Object owner, float priority, @Nullable String subtitle) {
            this(sound, looping, position, rotation, volume, pitch, startSeconds, fadeInSeconds, parameters,
                    attenuation, concurrency, cue, owner, priority, subtitle, NiagaraSoundRouting.DEFAULT);
        }

        public Playback(Identifier sound, boolean looping, Vec3 position, Vec3 rotation, float volume, float pitch,
                        float startSeconds, float fadeInSeconds, Map<String, Object> parameters,
                        @Nullable Attenuation attenuation, List<Concurrency> concurrency, @Nullable NiagaraSoundCue cue,
                        @Nullable Object owner, float priority) {
            this(sound, looping, position, rotation, volume, pitch, startSeconds, fadeInSeconds, parameters,
                    attenuation, concurrency, cue, owner, priority, null);
        }
        public Playback {
            parameters = Map.copyOf(parameters);
            concurrency = List.copyOf(concurrency);
            distanceAttenuations = List.copyOf(distanceAttenuations);
        }
    }

    record Settings(@Nullable Identifier sound, boolean looping, @Nullable Attenuation attenuation, @Nullable Concurrency concurrency,
                    float volume, float pitch, @Nullable NiagaraSoundCue cue, float priority, List<Concurrency> assetConcurrency,
                    NiagaraSoundRouting routing) {
        public Settings(@Nullable Identifier sound, boolean looping, @Nullable Attenuation attenuation, @Nullable Concurrency concurrency,
                        float volume, float pitch, @Nullable NiagaraSoundCue cue, float priority, List<Concurrency> assetConcurrency) {
            this(sound, looping, attenuation, concurrency, volume, pitch, cue, priority, assetConcurrency, NiagaraSoundRouting.DEFAULT);
        }
        public Settings { assetConcurrency = List.copyOf(assetConcurrency); }
    }
    record Overrides(boolean overrideAttenuation, @Nullable Attenuation attenuation,
                     boolean overrideConcurrency, @Nullable Concurrency concurrency) { }

    /** The native default parameter retains its scalar and sound-wave fields under one name. */
    record ScalarParameters(boolean booleanValue, int integerValue, float floatValue, @Nullable Wave wave) {
        public ScalarParameters(boolean booleanValue, int integerValue, float floatValue) {
            this(booleanValue, integerValue, floatValue, null);
        }
        public static boolean asBoolean(@Nullable Object value) {
            return value instanceof ScalarParameters fields ? fields.booleanValue() : value instanceof Boolean flag && flag;
        }
        public static int asInteger(@Nullable Object value) {
            return value instanceof ScalarParameters fields ? fields.integerValue() : value instanceof Integer integer ? integer : 0;
        }
        public static float asFloat(@Nullable Object value) {
            return value instanceof ScalarParameters fields ? fields.floatValue() : value instanceof Float number ? number : 0;
        }
    }

    interface Voice {
        boolean isPlaying();
        void position(Vec3 position);
        void rotation(Vec3 rotation);
        void volume(float volume);
        void pitch(float pitch);
        void paused(boolean paused);
        boolean parameter(String name, Object value);
        void stop(float fadeOutSeconds);
    }

    /** A null result means the host could not start the selected sound. */
    @Nullable Voice play(Playback playback);
}
