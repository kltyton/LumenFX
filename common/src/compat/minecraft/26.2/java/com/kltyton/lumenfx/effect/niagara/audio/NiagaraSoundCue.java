package com.kltyton.lumenfx.effect.niagara.audio;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.api.client.parameter.NiagaraAudioBackend.ScalarParameters;
import com.kltyton.lumenfx.effect.curve.NiagaraCurve;
import com.kltyton.lumenfx.effect.niagara.audio.NiagaraSoundAcoustics.Attenuation;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/** Cooked source sound cue graph and its typed playback parameters. */
public record NiagaraSoundCue(Node root) {
    public sealed interface Node permits Empty, Wave, Mix, Random, Modulator, Continuous, Delay, Loop, Selector,
            Attenuated, CrossFade, Doppler, Envelope, WaveParameter, Dialogue, Mature, Oscillator, GroupControl, ClassOverride {
        default List<Node> children() { return List.of(); }
    }
    public record Empty() implements Node { }
    public record Wave(Identifier sound, double duration, float volume, float pitch, boolean looping, boolean mature,
                       @Nullable String submix, @Nullable NiagaraSubmix submixGraph) implements Node {
        public Wave(Identifier sound, double duration, float volume, float pitch, boolean looping) {
            this(sound, duration, volume, pitch, looping, false);
        }
        public Wave(Identifier sound, double duration, float volume, float pitch, boolean looping, boolean mature) {
            this(sound, duration, volume, pitch, looping, mature, null, null);
        }
        public Wave {
            if (!Double.isFinite(duration) || duration <= 0) throw new IllegalArgumentException("Invalid Niagara cue wave duration");
        }
    }
    public record Mix(List<Node> children, List<Float> gains, boolean sequential) implements Node { }
    public record Random(int sourceIndex, List<Node> children, List<Float> weights, boolean withoutReplacement,
                         int preselect, boolean excludeBranchCulling) implements Node { }
    public record Modulator(int sourceIndex, List<Node> children, Range volume, Range pitch) implements Node {
        public Modulator(List<Node> children, Range volume, Range pitch) { this(-1, children, volume, pitch); }
    }
    public record Continuous(List<Node> children, Modulation volume, Modulation pitch) implements Node { }
    public record Oscillator(int sourceIndex, List<Node> children, Range amplitude, Range frequency, Range offset,
                             Range center, boolean volume, boolean pitch) implements Node { }
    public record Delay(int sourceIndex, List<Node> children, Range time) implements Node {
        public Delay(List<Node> children, Range time) { this(-1, children, time); }
    }
    public record Loop(List<Node> children, int repetitions) implements Node { }
    public record GroupControl(int sourceIndex, List<Node> children, List<Integer> limits) implements Node { }
    public record ClassOverride(List<Node> children, @Nullable NiagaraSoundClass soundClass) implements Node { }
    public enum Selection { BOOLEAN, INTEGER, QUALITY }
    public record Selector(List<Node> children, Selection selection, String parameter, int cookedQuality) implements Node { }
    public record Attenuated(List<Node> children, @Nullable Attenuation profile) implements Node { }
    public record CrossFade(List<Node> children, @Nullable String parameter, List<FadeEdge> edges) implements Node { }
    public record Doppler(List<Node> children, float intensity, boolean smooth, float speed) implements Node { }
    public record WaveParameter(List<Node> children, String name) implements Node { }
    public record DialogueLine(@Nullable Wave wave, String subtitle) {
        public boolean looping() { return wave != null && wave.looping(); }
    }

    public record Dialogue(@Nullable Wave wave, String subtitle, Map<String, DialogueLine> localizedLines, DialogueLine fallback) implements Node {
        public Dialogue(Wave wave, String subtitle) {
            this(wave, subtitle, Map.of(), new DialogueLine(wave, subtitle));
        }

        public Dialogue {
            var normalized = new HashMap<String, DialogueLine>();
            localizedLines.forEach((language, line) -> normalized.put(languageKey(language), line));
            localizedLines = Map.copyOf(normalized);
        }

        public DialogueLine line(String language) {
            return language.isEmpty() ? new DialogueLine(wave, subtitle) : localizedLines.getOrDefault(languageKey(language), fallback);
        }

        public boolean looping() {
            return wave != null && wave.looping() || fallback.looping() || localizedLines.values().stream().anyMatch(DialogueLine::looping);
        }

        private static String languageKey(String language) {
            return language.replace('_', '-').toLowerCase(Locale.ROOT);
        }
    }
    public record Mature(List<Node> children) implements Node { }
    public record Envelope(int sourceIndex, List<Node> children, Range volume, Range pitch, NiagaraCurve volumeCurve, NiagaraCurve pitchCurve,
                           boolean loop, boolean indefinitely, float loopStart, float loopEnd, int loopCount,
                           float durationAfterLoop) implements Node {
        public float loopDuration() { return loopEnd - loopStart; }
        public float duration() { return indefinitely ? 10000f : loopStart + loopCount * loopDuration() + durationAfterLoop; }
    }
    public record FadeEdge(float inStart, float inEnd, float outStart, float outEnd, float volume) {
        public float gain(float distance) {
            if (distance >= inStart && distance <= inEnd)
                return inEnd > 0 ? volume * ((distance - inStart) / (inEnd - inStart)) : volume;
            if (distance >= outStart && distance <= outEnd)
                return outEnd > 0 ? volume * (1 - (distance - outStart) / (outEnd - outStart)) : 0;
            return distance >= inEnd && distance <= outStart ? volume : 0;
        }
    }
    public record Range(float minimum, float maximum) {
        public float sample(float random) { return maximum + (minimum - maximum) * random; }
    }
    public record Modulation(String parameter, float fallback, float inputMin, float inputMax, float outputMin, float outputMax, int mode) {
        public float apply(@Nullable Object value) {
            float number = value == null ? fallback : ScalarParameters.asFloat(value);
            if (mode == 2) return number;
            if (mode == 1) number = Math.abs(number);
            float slope = inputMax <= inputMin ? 0 : (outputMax - outputMin) / (inputMax - inputMin);
            float clamped = number < inputMin ? inputMin : Math.min(number, inputMax);
            return outputMin + (clamped - inputMin) * slope;
        }
    }

    public static NiagaraSoundCue read(JsonObject source, Function<JsonObject, NiagaraCurve> curves) {
        return new NiagaraSoundCue(readNode(source.getAsJsonObject("node"), curves));
    }

    public boolean looping() { return looping(root); }
    private static boolean looping(Node node) {
        return switch (node) {
            case Wave wave -> wave.looping();
            case Dialogue dialogue -> dialogue.looping();
            case WaveParameter ignored -> true;
            case Loop loop -> loop.repetitions() == -1 || loop.children().stream().anyMatch(NiagaraSoundCue::looping);
            case Envelope envelope -> envelope.loop() ? envelope.indefinitely() : envelope.children().stream().anyMatch(NiagaraSoundCue::looping);
            default -> node.children().stream().anyMatch(NiagaraSoundCue::looping);
        };
    }

    public boolean tracksDopplerMotion() { return containsDoppler(root); }
    private static boolean containsDoppler(Node node) {
        return node instanceof Doppler || node.children().stream().anyMatch(NiagaraSoundCue::containsDoppler);
    }

    private static Node readNode(JsonObject source, Function<JsonObject, NiagaraCurve> curves) {
        var children = new ArrayList<Node>();
        if (source.has("children")) for (var child : source.getAsJsonArray("children")) children.add(readNode(child.getAsJsonObject(), curves));
        List<Node> nodes = List.copyOf(children);
        return switch (source.get("kind").getAsString()) {
            case "empty" -> new Empty();
            case "SoundNodeWavePlayer" -> readWave(source);
            case "SoundNodeDialoguePlayer" -> readDialogue(source);
            case "SoundNodeWaveParam" -> new WaveParameter(nodes, parameter(source));
            case "SoundNodeMature" -> new Mature(nodes);
            case "SoundNodeMixer", "SoundNodeConcatenator" -> new Mix(nodes, values(source, "gains"),
                    source.get("kind").getAsString().equals("SoundNodeConcatenator"));
            case "SoundNodeRandom" -> new Random(source.get("source_index").getAsInt(), nodes, values(source, "weights"),
                    source.get("without_replacement").getAsBoolean(), source.get("preselect").getAsInt(),
                    source.get("exclude_branch_culling").getAsBoolean());
            case "SoundNodeModulator" -> new Modulator(sourceIndex(source), nodes, range(source, "volume"), range(source, "pitch"));
            case "SoundNodeModulatorContinuous" -> new Continuous(nodes, modulation(source.getAsJsonObject("volume_parameter")),
                    modulation(source.getAsJsonObject("pitch_parameter")));
            case "SoundNodeOscillator" -> new Oscillator(sourceIndex(source), nodes, range(source, "amplitude"),
                    range(source, "frequency"), range(source, "offset"), range(source, "center"),
                    source.get("modulate_volume").getAsBoolean(), source.get("modulate_pitch").getAsBoolean());
            case "SoundNodeDoppler" -> new Doppler(nodes, source.get("intensity").getAsFloat(), source.get("smooth").getAsBoolean(),
                    source.get("speed").getAsFloat());
            case "SoundNodeEnveloper" -> new Envelope(source.get("source_index").getAsInt(), nodes, range(source, "volume"), range(source, "pitch"),
                    curves.apply(source.getAsJsonObject("volume_curve")), curves.apply(source.getAsJsonObject("pitch_curve")),
                    source.get("loop").getAsBoolean(), source.get("loop_indefinitely").getAsBoolean(),
                    source.get("loop_start").getAsFloat(), source.get("loop_end").getAsFloat(), source.get("loop_count").getAsInt(),
                    source.get("after_loop").getAsFloat());
            case "SoundNodeDelay" -> new Delay(sourceIndex(source), nodes, range(source, "delay"));
            case "SoundNodeLooping" -> new Loop(nodes, source.get("loops").getAsInt());
            case "SoundNodeGroupControl" -> {
                var limits = new ArrayList<Integer>();
                for (var value : source.getAsJsonArray("limits")) limits.add(value.getAsInt());
                yield new GroupControl(sourceIndex(source), nodes, List.copyOf(limits));
            }
            case "SoundNodeSoundClass" -> new ClassOverride(nodes, NiagaraSoundClass.read(source.getAsJsonObject("sound_class")));
            case "SoundNodeBranch" -> new Selector(nodes, Selection.BOOLEAN, parameter(source), -1);
            case "SoundNodeSwitch" -> new Selector(nodes, Selection.INTEGER, parameter(source), -1);
            case "SoundNodeQualityLevel" -> new Selector(nodes, Selection.QUALITY, "", source.get("cooked_level").getAsInt());
            case "SoundNodeAttenuation" -> new Attenuated(nodes, source.has("attenuation")
                    ? NiagaraSoundAcoustics.readAttenuation(source.getAsJsonObject("attenuation"), curves) : null);
            case "SoundNodeDistanceCrossFade", "SoundNodeParamCrossFade" -> {
                var edges = new ArrayList<FadeEdge>();
                for (var value : source.getAsJsonArray("edges")) {
                    var edge = value.getAsJsonObject();
                    edges.add(new FadeEdge(edge.get("in_start").getAsFloat(), edge.get("in_end").getAsFloat(),
                            edge.get("out_start").getAsFloat(), edge.get("out_end").getAsFloat(), edge.get("volume").getAsFloat()));
                }
                yield new CrossFade(nodes, source.has("parameter") && !source.get("parameter").isJsonNull() ? parameter(source) : null,
                        List.copyOf(edges));
            }
            default -> throw new IllegalArgumentException("Unknown Niagara sound cue node " + source.get("kind"));
        };
    }

    private static Dialogue readDialogue(JsonObject source) {
        var base = readDialogueLine(source);
        var localized = new HashMap<String, DialogueLine>();
        if (source.has("localized_dialogue")) source.getAsJsonObject("localized_dialogue").entrySet().forEach(
                entry -> localized.put(entry.getKey(), readDialogueLine(entry.getValue().getAsJsonObject())));
        var fallback = source.has("dialogue_fallback") ? readDialogueLine(source.getAsJsonObject("dialogue_fallback")) : base;
        return new Dialogue(base.wave(), base.subtitle(), localized, fallback);
    }

    private static DialogueLine readDialogueLine(JsonObject source) {
        return new DialogueLine(source.has("sound") ? readWave(source) : null, source.get("subtitle").getAsString());
    }

    public static Wave readWave(JsonObject source) {
        return new Wave(Identifier.parse(source.get("sound").getAsString()), source.get("duration").getAsDouble(),
                source.get("volume").getAsFloat(), source.get("pitch").getAsFloat(), source.get("looping").getAsBoolean(),
                source.has("mature") && source.get("mature").getAsBoolean(), NiagaraSoundRouting.path(source, "submix"),
                NiagaraSubmix.read(source.getAsJsonObject("submix_graph")));
    }

    private static int sourceIndex(JsonObject source) {
        return source.has("source_index") ? source.get("source_index").getAsInt() : -1;
    }

    private static String parameter(JsonObject source) { return SourceNames.canonical(source.get("parameter").getAsString()); }
    private static List<Float> values(JsonObject source, String key) {
        var values = new ArrayList<Float>();
        for (var value : source.getAsJsonArray(key)) values.add(value.getAsFloat());
        return List.copyOf(values);
    }
    private static Range range(JsonObject source, String key) {
        var data = source.getAsJsonArray(key);
        return new Range(data.get(0).getAsFloat(), data.get(1).getAsFloat());
    }
    private static Modulation modulation(JsonObject source) {
        return new Modulation(parameter(source), source.get("default").getAsFloat(), source.get("input_min").getAsFloat(),
                source.get("input_max").getAsFloat(), source.get("output_min").getAsFloat(), source.get("output_max").getAsFloat(),
                source.get("mode").getAsInt());
    }
}
