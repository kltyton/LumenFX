package com.kltyton.lumenfx.client.audio.cue;

import com.kltyton.lumenfx.api.client.parameter.NiagaraAudioBackend.Playback;
import com.kltyton.lumenfx.api.client.parameter.NiagaraAudioBackend.ScalarParameters;
import com.kltyton.lumenfx.api.client.parameter.NiagaraAudioBackend.Voice;
import com.kltyton.lumenfx.effect.niagara.audio.NiagaraSoundCue;
import com.kltyton.lumenfx.effect.niagara.audio.NiagaraSoundCue.Attenuated;
import com.kltyton.lumenfx.effect.niagara.audio.NiagaraSoundCue.ClassOverride;
import com.kltyton.lumenfx.effect.niagara.audio.NiagaraSoundRouting;
import com.kltyton.lumenfx.api.client.parameter.NiagaraAudioBackend.DistanceAttenuation;
import com.kltyton.lumenfx.effect.niagara.audio.NiagaraSoundCue.CrossFade;
import com.kltyton.lumenfx.effect.niagara.audio.NiagaraSoundCue.FadeEdge;
import com.kltyton.lumenfx.effect.niagara.audio.NiagaraSoundAcoustics;
import com.kltyton.lumenfx.effect.niagara.audio.NiagaraSoundAcoustics.Attenuation;
import com.kltyton.lumenfx.effect.niagara.audio.NiagaraSoundCue.Continuous;
import com.kltyton.lumenfx.effect.niagara.audio.NiagaraSoundCue.Delay;
import com.kltyton.lumenfx.effect.niagara.audio.NiagaraSoundCue.Dialogue;
import com.kltyton.lumenfx.effect.niagara.audio.NiagaraSoundCue.Mature;
import com.kltyton.lumenfx.effect.niagara.audio.NiagaraSoundCue.Doppler;
import com.kltyton.lumenfx.effect.niagara.audio.NiagaraSoundCue.Empty;
import com.kltyton.lumenfx.effect.niagara.audio.NiagaraSoundCue.Envelope;
import com.kltyton.lumenfx.effect.niagara.audio.NiagaraSoundCue.GroupControl;
import com.kltyton.lumenfx.effect.niagara.audio.NiagaraSoundCue.Loop;
import com.kltyton.lumenfx.effect.niagara.audio.NiagaraSoundCue.Mix;
import com.kltyton.lumenfx.effect.niagara.audio.NiagaraSoundCue.Modulator;
import com.kltyton.lumenfx.effect.niagara.audio.NiagaraSoundCue.Oscillator;
import com.kltyton.lumenfx.effect.niagara.audio.NiagaraSoundCue.Node;
import com.kltyton.lumenfx.effect.niagara.audio.NiagaraSoundCue.Random;
import com.kltyton.lumenfx.effect.niagara.audio.NiagaraSoundCue.Selector;
import com.kltyton.lumenfx.effect.niagara.audio.NiagaraSoundCue.Wave;
import com.kltyton.lumenfx.effect.niagara.audio.NiagaraSoundCue.WaveParameter;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.kltyton.lumenfx.math.SourceRandomStream;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** A cue runs its selected source graph as a group of voices on the client audio surface. */
public final class NiagaraCueVoice implements Voice {
    private static final Attenuation TWO_DIMENSIONAL = new Attenuation(false, false, NiagaraSoundAcoustics.Shape.Sphere,
            NiagaraSoundAcoustics.Distance.Linear, NiagaraSoundAcoustics.Falloff.Continues, -60, Vec3.ZERO, 0, 0, 0, 0, null);
    private static final FadeEdge DEFAULT_EDGE = new FadeEdge(0, 0, 0, 0, 1);
    public static final class Choices {
        private final Map<Integer, BitSet> usedBranches = new HashMap<>();
        private final Map<Integer, SourceRandomStream> streams = new HashMap<>();
        private final RandomSource random = RandomSource.create();
        private final NiagaraCueGroupSlots groupSlots = new NiagaraCueGroupSlots();
        private final int maximumPreloadedBranches;

        public Choices() { this(0); }

        public Choices(int maximumPreloadedBranches) {
            this.maximumPreloadedBranches = maximumPreloadedBranches;
        }

        private float fraction(int sourceIndex) {
            if (sourceIndex < 0) return random.nextFloat();
            return streams.computeIfAbsent(sourceIndex, ignored -> new SourceRandomStream(random.nextInt())).fraction();
        }

        private int choose(Random node) {
            int count = loadedCount(node);
            BitSet used = usedBranches.computeIfAbsent(node.sourceIndex(), ignored -> new BitSet(count));
            float total = 0;
            for (int i = 0; i < count; i++)
                if (!node.withoutReplacement() || !used.get(i)) total += value(node.weights(), i, 0);
            float threshold = random.nextFloat() * total;
            float cumulative = 0;
            int selected = 0;
            for (int i = 0; i < count; i++) {
                if (node.withoutReplacement() && used.get(i)) continue;
                cumulative += value(node.weights(), i, 0);
                if (threshold < cumulative) {
                    selected = i;
                    if (node.withoutReplacement()) used.set(i);
                    break;
                }
            }
            if (node.withoutReplacement() && count > 0 && used.cardinality() == count) {
                used.clear();
                used.set(selected);
            }
            return count == 0 ? -1 : selected;
        }

        private int loadedCount(Random node) {
            int count = node.children().size();
            if (node.excludeBranchCulling()) return count;
            if (node.preselect() > 0) count = Math.min(count, node.preselect());
            if (maximumPreloadedBranches > 0) count = Math.min(count, maximumPreloadedBranches);
            return count;
        }
    }

    private final Playback request;
    private final Function<Playback, @Nullable Voice> wavePlayer;
    private final Choices choices;
    private final NiagaraCueGroupSlots groupSlots;
    private final NodeState root;
    private final Map<String, Object> parameters = new HashMap<>();
    private final Map<List<Integer>, RetainedWave> loopWaves = new HashMap<>();
    private final int audioQuality;
    private final boolean allowMatureLanguage;
    private final Supplier<Vec3> listener;
    private final Supplier<String> language;
    private final @Nullable DopplerMotion dopplerMotion;
    private Vec3 position, rotation;
    private float volume, pitch;
    private boolean alive = true, paused;
    private float age, sourcePlayback, sourcePitch = 1;

    public NiagaraCueVoice(NiagaraSoundCue cue, Playback request, Choices choices, int audioQuality,
                           Supplier<Vec3> listener, Function<Playback, @Nullable Voice> wavePlayer) {
        this(cue, request, choices, audioQuality, false, listener, wavePlayer);
    }

    public NiagaraCueVoice(NiagaraSoundCue cue, Playback request, Choices choices, int audioQuality, boolean allowMatureLanguage,
                           Supplier<Vec3> listener, Function<Playback, @Nullable Voice> wavePlayer) {
        this(cue, request, choices, audioQuality, allowMatureLanguage, listener, () -> "", wavePlayer);
    }

    public NiagaraCueVoice(NiagaraSoundCue cue, Playback request, Choices choices, int audioQuality, boolean allowMatureLanguage,
                           Supplier<Vec3> listener, Supplier<String> language, Function<Playback, @Nullable Voice> wavePlayer) {
        this(cue, request, choices, choices.groupSlots, audioQuality, allowMatureLanguage, listener, language, wavePlayer);
    }

    public NiagaraCueVoice(NiagaraSoundCue cue, Playback request, Choices choices, NiagaraCueGroupSlots groupSlots,
                           int audioQuality, boolean allowMatureLanguage, Supplier<Vec3> listener,
                           Supplier<String> language, Function<Playback, @Nullable Voice> wavePlayer) {
        this.request = request;
        this.choices = choices;
        this.groupSlots = groupSlots;
        this.audioQuality = audioQuality;
        this.allowMatureLanguage = allowMatureLanguage;
        this.listener = listener;
        this.language = language;
        this.wavePlayer = wavePlayer;
        position = request.position();
        rotation = request.rotation();
        volume = request.volume();
        pitch = request.pitch();
        dopplerMotion = cue.tracksDopplerMotion() ? new DopplerMotion(position, listener.get()) : null;
        request.parameters().forEach((name, value) -> parameters.put(SourceNames.canonical(name), value));
        root = new NodeState(cue.root(), request.attenuation(), null, -1);
    }

    public boolean advance(float seconds) {
        if (alive && dopplerMotion != null && seconds > 0) dopplerMotion.advance(position, listener.get(), seconds);
        if (!alive || paused) return alive;
        age += seconds;
        sourcePlayback += seconds * sourcePitch;
        sourcePitch = Math.min(1, pitch);
        float fade = request.fadeInSeconds() <= 0 ? 1 : Math.min(1, age / request.fadeInSeconds());
        try {
            root.collectWaveCompletions();
            alive = root.step(seconds, request.startSeconds(), volume * fade, pitch);
        } finally {
            // Sources still present at the same cue path keep their channel through a loop reparse.
            for (RetainedWave retained : loopWaves.values()) retained.voice().stop(0);
            loopWaves.clear();
        }
        return alive;
    }
    @Override public boolean isPlaying() { return alive; }

    @Override public void position(Vec3 value) { position = value; root.forVoices(voice -> voice.position(value)); }
    @Override public void rotation(Vec3 value) { rotation = value; root.forVoices(voice -> voice.rotation(value)); }
    @Override public void volume(float value) { volume = value; }
    @Override public void pitch(float value) { pitch = value; }
    @Override public void paused(boolean value) { paused = value; root.forVoices(voice -> voice.paused(value)); }
    @Override public boolean parameter(String name, Object value) {
        parameters.put(SourceNames.canonical(name), value);
        return true;
    }
    @Override public void stop(float fade) {
        if (!alive) return;
        alive = false;
        root.stop(fade);
    }

    private boolean loadedMatureChild(Mature parent, Node child) {
        // The native game PostLoad only removes mismatched direct Wave Player children.
        return parent.children().size() < 2 || !(child instanceof Wave wave) || wave.mature() == allowMatureLanguage;
    }

    private @Nullable Wave firstMaturityWave(Node node) {
        if (node instanceof Wave wave) return wave;
        List<Node> children = node.children();
        int count = node instanceof Random random ? choices.loadedCount(random) : children.size();
        for (int index = 0; index < count; index++) {
            Node child = children.get(index);
            if (node instanceof Mature mature && !loadedMatureChild(mature, child)) continue;
            Wave wave = firstMaturityWave(child);
            if (wave != null) return wave;
        }
        return null;
    }

    private int matureChild(Mature source) {
        int safe = -1;
        int mature = -1;
        for (int index = 0; index < source.children().size(); index++) {
            Node child = source.children().get(index);
            if (!loadedMatureChild(source, child)) continue;
            Wave wave = firstMaturityWave(child);
            if (wave == null) continue;
            if (wave.mature() && mature < 0) mature = index;
            if (!wave.mature() && safe < 0) safe = index;
        }
        return allowMatureLanguage && mature >= 0 ? mature : safe;
    }

    private static final class DopplerMotion {
        private static final float SPEED_OF_SOUND = 33000f;
        private Vec3 source, listener;
        private Vec3 sourceVelocity = Vec3.ZERO, listenerVelocity = Vec3.ZERO;

        DopplerMotion(Vec3 source, Vec3 listener) {
            this.source = source;
            this.listener = listener;
        }

        void advance(Vec3 nextSource, Vec3 nextListener, float elapsed) {
            sourceVelocity = nextSource.subtract(source).scale(1d / elapsed);
            listenerVelocity = nextListener.subtract(listener).scale(1d / elapsed);
            source = nextSource;
            listener = nextListener;
        }

        float scale(float intensity) {
            Vec3 direction = listener.subtract(source);
            double squared = direction.lengthSqr();
            if (squared < 1e-8) return 1f;
            direction = direction.scale(1d / Math.sqrt(squared));
            float sourceSpeed = (float)sourceVelocity.dot(direction);
            float listenerSpeed = (float)listenerVelocity.dot(direction);
            float pitchScale = 1f / (1f - (sourceSpeed - listenerSpeed) / SPEED_OF_SOUND);
            return (pitchScale - 1f) * intensity + 1f;
        }
    }

    private record RetainedWave(Wave source, Voice voice) { }

    private final class NodeState {
        private final Node node;
        private final @Nullable Attenuation attenuation;
        private final List<DistanceAttenuation> distanceAttenuations;
        private final NiagaraSoundRouting routing;
        private final @Nullable NodeState parent;
        private final int childIndex;
        private List<NodeState> children = List.of();
        private @Nullable Voice wave;
        private @Nullable Wave playingWave;
        private @Nullable Wave parameterWave;
        private boolean initialized, finished;
        private boolean restartLoop;
        private boolean groupCommitted;
        private int cursor, sequenceAdvanceTo = -1, repetitions, selected = -1;
        private float scale = 1, tune = 1, delayEnd;
        private int envelopeLoop = -1;
        private float envelopeOrigin;
        private float oscillatorAmplitude, oscillatorFrequency, oscillatorOffset, oscillatorCenter;

        NodeState(Node node, @Nullable Attenuation attenuation, @Nullable NodeState parent, int childIndex) {
            this.node = node;
            var inheritedRouting = parent == null ? request.routing() : parent.routing;
            routing = node instanceof ClassOverride override && override.soundClass() != null
                    ? inheritedRouting.withSoundClass(override.soundClass()) : inheritedRouting;
            var inheritedAttenuations = parent == null ? request.distanceAttenuations() : parent.distanceAttenuations;
            if (node instanceof Attenuated tuned) {
                var profile = tuned.profile();
                this.attenuation = profile == null ? TWO_DIMENSIONAL : profile;
                if (profile == null || !profile.attenuate()) distanceAttenuations = inheritedAttenuations;
                else {
                    var stages = new ArrayList<>(inheritedAttenuations);
                    stages.add(new DistanceAttenuation(profile, inheritedRouting));
                    distanceAttenuations = List.copyOf(stages);
                }
            } else {
                this.attenuation = attenuation;
                distanceAttenuations = inheritedAttenuations;
            }
            this.parent = parent;
            this.childIndex = childIndex;
        }

        boolean step(float elapsed, float start, float volume, float pitch) {
            if (finished) return false;
            boolean running = switch (node) {
                case Empty ignored -> false;
                case Wave source -> wave(source, start, volume, pitch, null);
                case Dialogue source -> dialogue(source, start, volume, pitch);
                case WaveParameter source -> waveParameter(source, elapsed, start, volume, pitch);
                case Mix mix -> mix.sequential() ? sequence(mix, elapsed, start, volume, pitch)
                        : mix(mix.children(), mix.gains(), elapsed, start, volume, pitch);
                case Modulator modulator -> {
                    if (!initialized) {
                        scale = modulator.volume().sample(choices.fraction(modulator.sourceIndex()));
                        tune = modulator.pitch().sample(choices.fraction(modulator.sourceIndex()));
                    }
                    yield mix(modulator.children(), List.of(), elapsed, start, volume * scale, pitch * tune);
                }
                case Continuous continuous -> mix(continuous.children(), List.of(), elapsed, start,
                        volume * continuous.volume().apply(parameters.get(continuous.volume().parameter())),
                        pitch * continuous.pitch().apply(parameters.get(continuous.pitch().parameter())));
                case Oscillator oscillator -> oscillator(oscillator, elapsed, start, volume, pitch);
                case Doppler doppler -> doppler(doppler, elapsed, start, volume, pitch);
                case Envelope envelope -> envelope(envelope, elapsed, start, volume, pitch);
                case Delay delayed -> delay(delayed, elapsed, start, volume, pitch);
                case Loop loop -> loop(loop, elapsed, start, volume, pitch);
                case GroupControl control -> groupControl(control, elapsed, start, volume, pitch);
                case Random random -> {
                    if (!initialized) choose(random.children(), choices.choose(random));
                    yield selected >= 0 && children.getFirst().step(elapsed, start, volume, pitch);
                }
                case Mature mature -> {
                    if (!initialized) choose(mature.children(), matureChild(mature));
                    yield selected >= 0 && children.getFirst().step(elapsed, start, volume, pitch);
                }
                case Selector selector -> selector(selector, elapsed, start, volume, pitch);
                case Attenuated tuned -> mix(tuned.children(), List.of(), elapsed, start, volume, pitch);
                case ClassOverride override -> mix(override.children(), List.of(), elapsed, start, volume, pitch);
                case CrossFade crossFade -> crossFade(crossFade, elapsed, start, volume, pitch);
            };
            initialized = true;
            finished = !running && !(node instanceof WaveParameter) && !(node instanceof GroupControl && !groupCommitted);
            return running;
        }

        private boolean dialogue(Dialogue source, float start, float volume, float pitch) {
            if (initialized) return playingWave != null && wave(playingWave, start, volume, pitch, null);
            var line = source.line(language.get());
            var recording = line.wave();
            return recording != null && wave(recording, start, volume, pitch, line.subtitle());
        }

        private boolean wave(Wave source, float start, float volume, float pitch, @Nullable String subtitle) {
            if (!initialized) {
                RetainedWave retained = loopWaves.isEmpty() ? null : loopWaves.remove(path());
                if (retained != null && retained.source().equals(source) && retained.voice().isPlaying()) {
                    wave = retained.voice();
                    wave.volume(volume * source.volume());
                    wave.pitch(pitch * source.pitch());
                } else {
                    if (retained != null) retained.voice().stop(0);
                    wave = wavePlayer.apply(new Playback(source.sound(), source.looping(), position, rotation, volume * source.volume(),
                            pitch * source.pitch(), start, 0, Map.of(), attenuation, List.of(), null, request.owner(),
                            request.priority(), subtitle, routing.withWave(source), distanceAttenuations, age == 0));
                }
                playingWave = source;
                return wave != null;
            }
            var voice = wave;
            if (voice == null) return false;
            voice.volume(volume * source.volume());
            voice.pitch(pitch * source.pitch());
            return true;
        }

        private boolean waveParameter(WaveParameter source, float elapsed, float start, float volume, float pitch) {
            Object value = parameters.get(source.name());
            Wave selected = value instanceof Wave direct ? direct : value instanceof ScalarParameters fields ? fields.wave() : null;
            if (!initialized || !Objects.equals(selected, parameterWave)) {
                stopChildren(0);
                children = selected == null ? instantiate(source.children()) : List.of(new NodeState(selected, attenuation, this, -1));
                parameterWave = selected;
            }
            boolean running = false;
            for (NodeState child : children) running |= child.step(elapsed, start, volume, pitch);
            return running;
        }

        private boolean mix(List<Node> nodes, List<Float> gains, float elapsed, float start, float volume, float pitch) {
            if (!initialized && children.isEmpty()) children = instantiate(nodes);
            boolean active = false;
            for (int i = 0; i < children.size(); i++)
                active |= children.get(i).step(elapsed, start, volume * value(gains, i, 1), pitch);
            return active;
        }

        private boolean sequence(Mix mix, float elapsed, float start, float volume, float pitch) {
            if (!initialized) children = instantiate(mix.children());
            if (sequenceAdvanceTo >= 0) {
                children.get(cursor).stop(0);
                cursor = sequenceAdvanceTo;
                sequenceAdvanceTo = -1;
            }
            boolean waiting = false;
            for (int index = cursor; index < children.size(); index++) {
                NodeState child = children.get(index);
                if (!child.step(elapsed, start, volume * value(mix.gains(), index, 1), pitch)) continue;
                if (!child.hasWave()) {
                    waiting = true;
                    continue;
                }
                // The native cursor commits only after this child submits a wave instance.
                for (int skipped = cursor; skipped < index; skipped++) children.get(skipped).stop(0);
                cursor = index;
                return true;
            }
            return waiting;
        }

        private boolean hasWave() {
            if (wave != null) return true;
            for (NodeState child : children) if (child.hasWave()) return true;
            return false;
        }

        private boolean delay(Delay source, float elapsed, float start, float volume, float pitch) {
            if (!initialized) {
                float seconds = source.time().sample(choices.fraction(source.sourceIndex()));
                boolean seekPastDelay = seconds > 0 && start >= seconds;
                scale = seekPastDelay ? start - seconds : start;
                delayEnd = seekPastDelay ? -1 : sourcePlayback + seconds - start;
                children = instantiate(source.children());
            }
            return delayEnd > sourcePlayback || mix(source.children(), List.of(), elapsed, scale, volume, pitch);
        }

        private boolean oscillator(Oscillator source, float elapsed, float start, float volume, float pitch) {
            if (!initialized) {
                oscillatorAmplitude = source.amplitude().sample(choices.fraction(source.sourceIndex()));
                oscillatorFrequency = source.frequency().sample(choices.fraction(source.sourceIndex()));
                oscillatorOffset = source.offset().sample(choices.fraction(source.sourceIndex()));
                oscillatorCenter = source.center().sample(choices.fraction(source.sourceIndex()));
            }
            float modulation = NativeCueMath.oscillator(oscillatorCenter, oscillatorAmplitude, oscillatorFrequency,
                    oscillatorOffset, sourcePlayback);
            return mix(source.children(), List.of(), elapsed, start,
                    source.volume() ? volume * modulation : volume, source.pitch() ? pitch * modulation : pitch);
        }

        private boolean doppler(Doppler source, float elapsed, float start, float volume, float pitch) {
            float target = Objects.requireNonNull(dopplerMotion).scale(source.intensity());
            float difference = target - tune;
            tune = !initialized || !source.smooth() || source.speed() <= 0 || difference * difference < 1e-8f
                    ? target : tune + difference * Math.clamp(elapsed * source.speed(), 0f, 1f);
            return mix(source.children(), List.of(), elapsed, start, volume, pitch * tune);
        }

        private boolean envelope(Envelope source, float elapsed, float start, float volume, float pitch) {
            if (!initialized) {
                envelopeOrigin = sourcePlayback - start;
                envelopeRandomize(source);
            }
            float playback = sourcePlayback - envelopeOrigin;
            if (source.loop() && playback > source.loopEnd()) {
                if (playback > source.duration()) {
                    stopChildren(0);
                    return false;
                }
                float loopDuration = source.loopDuration();
                int loop = (int)((int)(playback - source.loopStart()) / Math.max(1e-8f, loopDuration));
                playback -= loop * loopDuration;
                if (loop == source.loopCount() && !source.indefinitely() && source.loopCount() != 0) playback += loopDuration;
                else if (loop != envelopeLoop) {
                    envelopeRandomize(source);
                    envelopeLoop = loop;
                }
            }
            return mix(source.children(), List.of(), elapsed, start,
                    volume * (source.volumeCurve().sample(playback) * scale),
                    pitch * (source.pitchCurve().sample(playback) * tune));
        }

        private void envelopeRandomize(Envelope source) {
            scale = source.volume().sample(choices.fraction(source.sourceIndex()));
            tune = source.pitch().sample(choices.fraction(source.sourceIndex()));
        }

        private boolean loop(Loop source, float elapsed, float start, float volume, float pitch) {
            if (restartLoop) {
                for (NodeState child : children) child.retainLiveWaves();
                children = instantiate(source.children());
                restartLoop = false;
                elapsed = 0;
            }
            boolean active = mix(source.children(), List.of(), elapsed, start, volume, pitch);
            // Empty parses can let a Random child choose again, without consuming a Wave completion.
            if (!active && (source.repetitions() == -1 || repetitions < source.repetitions())) restartLoop = true;
            return active || restartLoop;
        }

        private void collectWaveCompletions() {
            if (restartLoop) return;
            if (wave != null && !wave.isPlaying()) {
                wave = null;
                finished = true;
                if (parent != null) parent.waveCompleted(this);
            }
            for (NodeState child : children) {
                if (restartLoop) break;
                child.collectWaveCompletions();
            }
        }

        private boolean groupControl(GroupControl source, float elapsed, float start, float volume, float pitch) {
            if (!groupCommitted) {
                int branch = groupSlots.choose(request.sound(), source.sourceIndex(), source.limits());
                if (selected != branch) {
                    stopChildren(0);
                    choose(source.children(), branch);
                }
            }
            if (children.isEmpty()) return false;
            var child = children.getFirst();
            boolean active = child.step(elapsed, start, volume, pitch);
            if (child.hasWave()) {
                var waves = new ArrayList<Voice>();
                child.forVoices(waves::add);
                groupSlots.publish(request.sound(), source.sourceIndex(), selected, NiagaraCueVoice.this, waves);
                groupCommitted = true;
            } else if (!active && !groupCommitted) {
                stopChildren(0);
                selected = -1;
            }
            return active;
        }

        private void waveCompleted(NodeState branch) {
            if (node instanceof Loop loop) {
                if (restartLoop) return;
                if (loop.repetitions() == -1 || ++repetitions < loop.repetitions()) {
                    restartLoop = true;
                    return;
                }
            } else if (node instanceof Mix mix && mix.sequential()) {
                if (sequenceAdvanceTo >= 0 || children.get(cursor) != branch) return;
                // A finite Loop or an inner Concatenator forwards one terminal hook for the whole node.
                if (!(branch.node instanceof Loop) && !(branch.node instanceof Mix nested && nested.sequential()) && branch.hasWave()) return;
                sequenceAdvanceTo = cursor + 1;
                while (sequenceAdvanceTo < mix.children().size() && mix.children().get(sequenceAdvanceTo) instanceof Empty)
                    sequenceAdvanceTo++;
                if (sequenceAdvanceTo < mix.children().size()) return;
            }
            if (parent != null) parent.waveCompleted(this);
        }

        private void retainLiveWaves() {
            if (wave != null && wave.isPlaying())
                loopWaves.put(path(), new RetainedWave(Objects.requireNonNull(playingWave), wave));
            for (NodeState child : children) child.retainLiveWaves();
        }

        private List<Integer> path() {
            var indices = new ArrayList<Integer>();
            for (NodeState current = this; current != null; current = current.parent) indices.add(current.childIndex);
            return indices;
        }

        private boolean selector(Selector source, float elapsed, float start, float volume, float pitch) {
            Object value = parameters.get(source.parameter());
            int choice = switch (source.selection()) {
                case BOOLEAN -> value == null ? 2 : ScalarParameters.asBoolean(value) ? 0 : 1;
                case INTEGER -> value == null ? 0 : ScalarParameters.asInteger(value) + 1;
                case QUALITY -> source.cookedQuality() >= 0 && source.children().size() == 1 ? 0 : audioQuality;
            };
            if (source.selection() == NiagaraSoundCue.Selection.INTEGER && (choice < 0 || choice >= source.children().size())) choice = 0;
            if (!initialized || selected != choice) {
                stopChildren(0);
                choose(source.children(), choice);
                elapsed = 0;
                if (initialized) start = 0;
            }
            return selected >= 0 && !children.isEmpty() && children.getFirst().step(elapsed, start, volume, pitch);
        }

        private boolean crossFade(CrossFade source, float elapsed, float start, float volume, float pitch) {
            if (!initialized) children = instantiate(source.children());
            String parameter = source.parameter();
            Object value = parameter == null ? null : parameters.get(parameter);
            float distance = parameter == null ? (float)position.distanceTo(listener.get()) : ScalarParameters.asFloat(value);
            boolean active = false;
            for (int i = 0; i < children.size(); i++) {
                var edge = i < source.edges().size() ? source.edges().get(i) : DEFAULT_EDGE;
                float gain = edge.gain(distance);
                boolean playing = children.get(i).step(elapsed, start, gain * volume, pitch);
                active |= gain > 0 && playing;
            }
            if (!active) stopChildren(0);
            return active;
        }

        private void choose(List<Node> nodes, int choice) {
            selected = choice;
            children = choice < 0 || choice >= nodes.size() ? List.of() : List.of(new NodeState(nodes.get(choice), attenuation, this, choice));
        }

        private List<NodeState> instantiate(List<Node> nodes) {
            var values = new ArrayList<NodeState>(nodes.size());
            for (int index = 0; index < nodes.size(); index++)
                values.add(new NodeState(nodes.get(index), attenuation, this, index));
            return values;
        }

        void forVoices(Consumer<Voice> action) {
            if (wave != null) action.accept(wave);
            for (var child : children) child.forVoices(action);
        }
        void stop(float fade) {
            if (wave != null) wave.stop(fade);
            wave = null;
            stopChildren(fade);
            finished = true;
        }
        private void stopChildren(float fade) {
            for (var child : children) child.stop(fade);
            children = List.of();
        }
    }

    private static float value(List<Float> values, int index, float fallback) {
        return index < values.size() ? values.get(index) : fallback;
    }
}
