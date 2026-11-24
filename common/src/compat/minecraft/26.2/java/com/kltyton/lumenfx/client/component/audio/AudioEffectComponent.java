package com.kltyton.lumenfx.client.component.audio;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.api.client.component.ComponentFactory;
import com.kltyton.lumenfx.api.client.component.ComponentValue;
import com.kltyton.lumenfx.api.client.component.ComponentValue.Kind;
import com.kltyton.lumenfx.api.client.parameter.NiagaraAudioBackend.Playback;
import com.kltyton.lumenfx.api.client.parameter.NiagaraAudioBackend.ScalarParameters;
import com.kltyton.lumenfx.api.client.parameter.NiagaraAudioBackend.Settings;
import com.kltyton.lumenfx.api.client.parameter.NiagaraAudioBackend.Voice;
import com.kltyton.lumenfx.client.audio.MinecraftNiagaraAudio;
import com.kltyton.lumenfx.client.audio.scene.NiagaraAudioPose;
import com.kltyton.lumenfx.client.component.SceneEffectComponent;
import com.kltyton.lumenfx.client.resource.niagara.NiagaraJson;
import com.kltyton.lumenfx.effect.niagara.audio.NiagaraAudioPlayerDefinition;
import com.kltyton.lumenfx.effect.niagara.audio.NiagaraSoundCue;
import com.kltyton.lumenfx.effect.niagara.audio.NiagaraSoundAcoustics;
import com.kltyton.lumenfx.effect.niagara.audio.NiagaraSoundAcoustics.Attenuation;
import com.kltyton.lumenfx.effect.niagara.audio.NiagaraSoundAcoustics.Concurrency;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.jspecify.annotations.Nullable;

/** Applies Niagara's pooled scene transforms and property updates to a real audio voice. */
final class AudioEffectComponent extends SceneEffectComponent {
    private record Initial(Template scene, Settings sound, @Nullable Attenuation attenuation, List<Concurrency> concurrency,
                           float volume, float pitch, float volumeMin, float volumeMax, float pitchMin, float pitchMax,
                           float priority, boolean overridePriority, boolean paused, Map<String, Object> parameters) { }
    private final Initial template;
    private final RandomSource random = RandomSource.create();
    private final Quaterniond rotation = new Quaterniond();
    private final Map<String, Object> parameters;
    private @Nullable Voice voice;
    private float volumeModulation, pitchModulation;

    public static ComponentFactory.Prepared prepare(JsonObject template) {
        var data = template.getAsJsonObject("audio_component");
        var sound = NiagaraAudioPlayerDefinition.readSound(data, NiagaraJson::niagaraCurve);
        var concurrency = NiagaraSoundAcoustics.readConcurrencySet(data.getAsJsonArray("component_concurrency"));
        var attenuation = sound.attenuation();
        if (!flag(template, "bAllowSpatialization", true)) {
            attenuation = attenuation == null
                    ? new Attenuation(false, false, NiagaraSoundAcoustics.Shape.Sphere, NiagaraSoundAcoustics.Distance.Linear,
                            NiagaraSoundAcoustics.Falloff.Continues, -60, Vec3.ZERO, 0, 0, 0, 0, null)
                    : new Attenuation(attenuation.attenuate(), false, attenuation.shape(), attenuation.distance(), attenuation.mode(),
                            attenuation.decibels(), attenuation.extents(), attenuation.coneOffset(), attenuation.falloff(),
                            attenuation.coneSphereRadius(), attenuation.coneSphereFalloff(), attenuation.customCurve());
        }
        var initial = new Initial(prepareTemplate(template), sound, attenuation, concurrency.isEmpty() ? sound.assetConcurrency() : concurrency,
                scalar(template, "VolumeMultiplier", 1), scalar(template, "PitchMultiplier", 1),
                scalar(template, "VolumeModulationMin", 1), scalar(template, "VolumeModulationMax", 1),
                scalar(template, "PitchModulationMin", 1), scalar(template, "PitchModulationMax", 1),
                scalar(template, "Priority", 1), flag(template, "bOverridePriority", false), flag(template, "bIsPaused", false),
                defaultParameters(template, data.getAsJsonObject("default_wave_parameters")));
        return context -> new AudioEffectComponent(context, initial);
    }

    private AudioEffectComponent(ComponentFactory.Context context, Initial template) {
        super(context, template.scene());
        this.template = template;
        parameters = new HashMap<>(template.parameters());
        define("VolumeMultiplier", floating(template.volume()), this::updatePlayback);
        define("PitchMultiplier", floating(template.pitch()), this::updatePlayback);
        define("bIsPaused", ComponentValue.bool(template.paused()), this::updatePlayback);
        setter("SetVolumeMultiplier", Kind.FLOAT, (value, defaults) -> apply("VolumeMultiplier", value));
        setter("SetPitchMultiplier", Kind.FLOAT, (value, defaults) -> apply("PitchMultiplier", value));
        setter("SetPaused", Kind.BOOL, (value, defaults) -> apply("bIsPaused", value));
        setter("SetFloatParameter", Kind.FLOAT, (value, defaults) -> parameter(defaults, (float)value.number(0)));
        setter("SetIntParameter", Kind.INT, (value, defaults) -> parameter(defaults, (int)value.number(0)));
        setter("SetBoolParameter", Kind.BOOL, (value, defaults) -> parameter(defaults, value.number(0) != 0));
        onChange(change -> { if (change == Change.TRANSFORM) updateTransform(); });
    }

    @Override public boolean active() { return super.active() && voice != null && voice.isPlaying(); }

    @Override public void activate(boolean reset) {
        requireAlive();
        if (!reset && active()) return;
        deactivate();
        var settings = template.sound();
        var sound = settings.sound();
        if (sound == null) return;
        volumeModulation = template.volumeMax() + (template.volumeMin() - template.volumeMax()) * random.nextFloat();
        pitchModulation = template.pitchMax() + (template.pitchMin() - template.pitchMax()) * random.nextFloat();
        voice = MinecraftNiagaraAudio.INSTANCE.play(new Playback(sound, settings.looping(), location(),
                NiagaraAudioPose.rotator(sourceRotation(rotation)), volume(), pitch(), 0, 0, parameters,
                template.attenuation(), template.concurrency(), settings.cue(), context.owner(),
                template.overridePriority() ? template.priority() : settings.priority(), null, settings.routing()));
        if (voice != null) {
            voice.paused(value("bIsPaused").number(0) != 0);
            super.activate(reset);
        }
    }

    @Override public void deactivate() {
        var playing = voice;
        voice = null;
        if (playing != null) playing.stop(0);
        super.deactivate();
    }

    @Override public @Nullable Property property(String path) {
        var property = super.property(path);
        if (property == null) return null;
        return switch (path.toLowerCase(Locale.ROOT)) {
            case "volumemultiplier", "pitchmultiplier", "bispaused" -> new Property() {
                @Override public Kind type() { return property.type(); }
                @Override public void set(ComponentValue value) { apply(path, value); }
            };
            default -> property;
        };
    }

    private void updatePlayback() {
        var playing = voice;
        if (playing == null) return;
        playing.volume(volume());
        playing.pitch(pitch());
        playing.paused(value("bIsPaused").number(0) != 0);
    }

    private void updateTransform() {
        var playing = voice;
        if (playing == null) return;
        playing.position(location());
        playing.rotation(NiagaraAudioPose.rotator(sourceRotation(rotation)));
    }

    private void parameter(Map<String, String> defaults, Object value) {
        String name = defaults.get("InName");
        if (name == null || name.isBlank() || name.equals("None")) return;
        parameters.put(name, value);
        if (voice != null) voice.parameter(name, value);
    }

    private Vec3 location() {
        var transform = sourceTransform();
        return new Vec3(transform.m30(), transform.m31(), transform.m32());
    }
    private float volume() { return (float)value("VolumeMultiplier").number(0) * volumeModulation * template.sound().volume(); }
    private float pitch() { return (float)value("PitchMultiplier").number(0) * pitchModulation * template.sound().pitch(); }
    private static ComponentValue floating(float value) { return ComponentValue.floating(Kind.FLOAT, value, 0, 0, 0); }
    private static boolean flag(JsonObject data, String name, boolean fallback) { return data.has(name) ? data.get(name).getAsBoolean() : fallback; }
    private static float scalar(JsonObject data, String name, float fallback) { return (float)number(data, name, fallback); }

    private static Map<String, Object> defaultParameters(JsonObject template, @Nullable JsonObject waves) {
        if (!template.has("DefaultParameters")) return Map.of();
        var values = new HashMap<String, Object>();
        for (var item : template.getAsJsonArray("DefaultParameters")) {
            var data = item.getAsJsonObject();
            String name = data.has("ParamName") ? data.get("ParamName").getAsString() : "";
            if (name.isEmpty()) continue;
            String type = data.has("ParamType") ? data.get("ParamType").getAsString() : "None";
            if (type.contains("::")) type = type.substring(type.lastIndexOf("::") + 2);
            boolean bool = flag(data, "BoolParam", false);
            int integer = (int)number(data, "IntParam", 0);
            float floating = scalar(data, "FloatParam", 0);
            var wave = waves != null && waves.has(name) ? NiagaraSoundCue.readWave(waves.getAsJsonObject(name)) : null;
            Object value = switch (type) {
                case "None", "0" -> new ScalarParameters(bool, integer, floating, wave);
                case "Boolean", "1" -> bool;
                case "Integer", "2" -> integer;
                case "Float", "3" -> floating;
                case "Object", "5" -> wave;
                default -> new ScalarParameters(false, 0, 0);
            };
            if (value != null) values.put(name, value);
        }
        return Map.copyOf(values);
    }
}
