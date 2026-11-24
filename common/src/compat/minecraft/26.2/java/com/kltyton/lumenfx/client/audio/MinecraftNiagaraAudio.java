package com.kltyton.lumenfx.client.audio;

import com.kltyton.lumenfx.api.client.parameter.NiagaraAudioBackend;
import com.kltyton.lumenfx.client.audio.wave.NiagaraPcmWave;
import com.kltyton.lumenfx.client.audio.wave.NiagaraWavePlayback;
import com.kltyton.lumenfx.client.audio.cue.NiagaraCueVoice;
import com.kltyton.lumenfx.client.audio.cue.NiagaraCueGroupSlots;
import com.kltyton.lumenfx.client.audio.scene.NiagaraAudioAttenuation;
import com.kltyton.lumenfx.client.audio.scene.NiagaraAudioConcurrency;
import com.kltyton.lumenfx.client.audio.scene.NiagaraSoundMixes;
import com.kltyton.lumenfx.client.audio.scene.MinecraftAudioClock;
import com.kltyton.lumenfx.effect.niagara.audio.NiagaraSoundClass;
import com.kltyton.lumenfx.effect.niagara.audio.NiagaraSoundMix;
import com.kltyton.lumenfx.effect.niagara.audio.NiagaraSoundRouting;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import javax.sound.sampled.AudioFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.ChannelAccess.ChannelHandle;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;
import net.minecraft.util.valueproviders.ConstantFloat;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC10;
import org.lwjgl.system.Platform;
import org.slf4j.Logger;

/** Plays Niagara wave voices through Minecraft's registered sound events and audio executor. */
public enum MinecraftNiagaraAudio implements NiagaraAudioBackend {
    INSTANCE;

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String WAVE_PREFIX = "lumenfx/audio/waves/";
    private final ConcurrentHashMap<Identifier, CompletableFuture<NiagaraPcmWave>> waves = new ConcurrentHashMap<>();
    private final Map<Identifier, NiagaraCueVoice.Choices> cueChoices = new HashMap<>();
    private final NiagaraCueGroupSlots cueGroupSlots = new NiagaraCueGroupSlots();
    private final List<NiagaraCueVoice> cues = new ArrayList<>();
    private final List<SoundVoice> liveSounds = new ArrayList<>();
    private final NiagaraAudioConcurrency concurrency = new NiagaraAudioConcurrency();
    private final Map<String, NiagaraSoundMixes> soundMixes = new HashMap<>();
    private final MinecraftAudioClock mixClock = new MinecraftAudioClock();
    private DeviceSettings deviceSettings = DeviceSettings.DEFAULT;
    private @Nullable ChannelObservation channelObservation;

    public void setDeviceSettings(DeviceSettings settings) {
        requireClientThread();
        Objects.requireNonNull(settings);
        if (deviceSettings.maximumPreloadedBranches() != settings.maximumPreloadedBranches()) cueChoices.clear();
        deviceSettings = settings;
    }

    public void setBaseSoundMix(String namespace, @Nullable NiagaraSoundMix mix) {
        requireClientThread();
        soundMixes(namespace).setBase(mix, audioClock());
    }

    public void pushSoundMix(String namespace, NiagaraSoundMix mix) {
        requireClientThread();
        soundMixes(namespace).push(mix, audioClock());
    }

    public void popSoundMix(String namespace, String sourcePath) {
        requireClientThread();
        var mixes = soundMixes.get(namespace);
        if (mixes != null) mixes.pop(sourcePath, audioClock());
    }

    public void setSoundMixClassOverride(String namespace, String mix, String soundClass, float volume, float pitch,
                                         float fade, boolean children) {
        requireClientThread();
        soundMixes(namespace).setClassOverride(mix, soundClass, volume, pitch, fade, children);
    }

    public void clearSoundMixClassOverride(String namespace, NiagaraSoundMix mix, String soundClass, float fade) {
        requireClientThread();
        var mixes = soundMixes.get(namespace);
        if (mixes != null) mixes.clearClassOverride(mix, soundClass, fade);
    }

    public void setSoundClassDistanceScale(String namespace, NiagaraSoundClass soundClass, float scale, float seconds) {
        requireClientThread();
        soundMixes(namespace).setDistanceScale(soundClass, scale, seconds);
    }

    private static void requireClientThread() {
        if (!Minecraft.getInstance().isSameThread()) throw new IllegalStateException("Niagara audio device changes require the client thread");
    }

    private double audioClock() { return mixClock.current(currentDevice()); }

    private static long currentDevice() {
        return Minecraft.getInstance().getSoundManager().soundEngine.library.currentDevice;
    }

    private NiagaraSoundMixes soundMixes(String namespace) {
        return soundMixes.computeIfAbsent(namespace, ignored -> new NiagaraSoundMixes());
    }

    public void clearWaves() {
        concurrency.clear();
        for (var cue : cues) cue.stop(0);
        cues.clear();
        for (SoundVoice sound : liveSounds) if (sound.isPlaying()) sound.stop(0);
        liveSounds.clear();
        channelObservation = null;
        cueChoices.clear();
        cueGroupSlots.clear();
        soundMixes.clear();
        mixClock.clear();
        waves.clear();
    }

    public void tickCues() {
        liveSounds.removeIf(sound -> !sound.isPlaying());
        cueGroupSlots.prune();
        observeChannels();
        double clock = soundMixes.isEmpty() ? 0 : mixClock.advance(currentDevice());
        for (var mixes : soundMixes.values()) mixes.advance(clock);
        if (!Minecraft.getInstance().isPaused()) {
            cues.removeIf(cue -> !cue.advance(1f / 20f));
            concurrency.tick(listener(), 1f / 20f);
        }
        for (SoundVoice voice : liveSounds) voice.recordPassiveMixes(clock);
        for (var mixes : soundMixes.values()) mixes.finishPassive(clock);
    }

    private void observeChannels() {
        if (liveSounds.isEmpty()) return;
        // SoundEngine.stopAll can drop scheduled work before restarting its executor.
        if (channelObservation != null && !channelObservation.finished
                && channelObservation.voices.stream().anyMatch(SoundVoice::ownsCurrentChannel)) return;
        var observation = new ChannelObservation(List.copyOf(liveSounds));
        channelObservation = observation;
        var channels = Minecraft.getInstance().getSoundManager().soundEngine.channelAccess;
        channels.executeOnChannels(ignored -> {
            try {
                for (SoundVoice voice : observation.voices) voice.observeChannel();
            } finally {
                observation.finished = true;
            }
        });
    }

    private static final class ChannelObservation {
        private final List<SoundVoice> voices;
        private volatile boolean finished;

        private ChannelObservation(List<SoundVoice> voices) { this.voices = voices; }
    }

    private CompletableFuture<NiagaraPcmWave> wave(Identifier sound) {
        return waves.computeIfAbsent(sound, id -> {
            var resources = Minecraft.getInstance().getResourceManager();
            return CompletableFuture.supplyAsync(() -> {
                var path = Identifier.fromNamespaceAndPath(id.getNamespace(), id.getPath() + ".lfaw");
                try (var input = resources.open(path)) {
                    return NiagaraPcmWave.read(input.readAllBytes());
                } catch (IOException failure) {
                    throw new CompletionException(failure);
                }
            }, Util.nonCriticalIoPool());
        });
    }

    @Override public @Nullable Voice play(Playback request) {
        var base = request.routing().baseMix();
        if (base != null) soundMixes(request.sound().getNamespace()).registerDefault(base, audioClock());
        return concurrency.play(request, listener(), this::playDirect);
    }

    private @Nullable Voice playDirect(Playback request) {
        var cue = request.cue();
        if (cue != null) {
            DeviceSettings settings = deviceSettings;
            var scope = cueChoices.computeIfAbsent(request.sound(),
                    ignored -> new NiagaraCueVoice.Choices(settings.maximumPreloadedBranches()));
            var voice = new NiagaraCueVoice(cue, request, scope, cueGroupSlots, settings.qualityLevel(), settings.allowMatureLanguage(),
                    MinecraftNiagaraAudio::listener, MinecraftNiagaraAudio::language, this::playWave);
            if (!voice.advance(0)) return null;
            cues.add(voice);
            return voice;
        }
        return playWave(request);
    }

    private static Vec3 listener() {
        var camera = Minecraft.getInstance().gameRenderer.mainCamera().position();
        return new Vec3(camera.x * 100, camera.z * 100, camera.y * 100);
    }

    private static String language() {
        var manager = Minecraft.getInstance().getLanguageManager();
        String selected = manager.getSelected();
        return manager.getLanguage(selected) == null ? "en_us" : selected;
    }

    private @Nullable Voice playWave(Playback request) {
        var manager = Minecraft.getInstance().getSoundManager();
        var voice = new SoundVoice(manager, request, soundMixes(request.sound().getNamespace()));
        if (manager.play(voice) == SoundEngine.PlayResult.NOT_STARTED) return null;
        voice.channelHandle = manager.soundEngine.instanceToChannel.get(voice);
        if (voice.channelHandle == null) return null;
        liveSounds.add(voice);
        return voice;
    }

    private static final class DialogueSoundEvent extends WeighedSoundEvents {
        private final Component subtitle;

        DialogueSoundEvent(Identifier sound, String subtitle) {
            super(sound, null);
            this.subtitle = Component.literal(subtitle);
        }

        @Override public Component getSubtitle() { return subtitle; }
    }

    private static final class SoundVoice extends AbstractTickableSoundInstance implements Voice {
        private record DistanceGain(NiagaraAudioAttenuation evaluation, float scale, @Nullable String soundClass) { }

        private final SoundManager manager;
        private final float start, fadeIn, submixGain;
        private final NiagaraSoundMixes mixes;
        private final @Nullable NiagaraSoundClass soundClass;
        private final NiagaraSoundRouting routing;
        private final boolean packagedWave;
        private final int outputRate;
        private final @Nullable String subtitle;
        private final List<DistanceGain> distanceGains;
        private @Nullable ChannelHandle channelHandle;
        private volatile boolean channelReleased;
        private Vec3 sourcePosition;
        private Vec3 rotation;
        private boolean paused;
        private boolean updateDirection = true;
        private boolean firstSourceParse;
        private float requestedVolume, requestedPitch, age, fadeRemaining, fadeDuration, fadeStartGain;
        private float wavePitch, waveCutoff;
        private @Nullable NiagaraWavePlayback mixedWave;

        SoundVoice(SoundManager manager, Playback request, NiagaraSoundMixes mixes) {
            super(SoundEvent.createVariableRangeEvent(request.sound()), SoundSource.NEUTRAL, RandomSource.create());
            this.manager = manager;
            this.mixes = mixes;
            routing = request.routing();
            soundClass = routing.soundClass();
            submixGain = routing.linearSubmixGain();
            firstSourceParse = request.firstSourceParse();
            start = Math.max(0, request.startSeconds());
            fadeIn = Math.max(0, request.fadeInSeconds());
            requestedVolume = request.volume();
            requestedPitch = request.pitch();
            var levels = mixes.levels(soundClass);
            looping = request.looping();
            subtitle = request.subtitle();
            packagedWave = request.sound().getPath().startsWith(WAVE_PREFIX);
            long device = packagedWave && soundClass != null && Platform.get() == Platform.WINDOWS
                    && Platform.getArchitecture() == Platform.Architecture.X64 ? manager.soundEngine.library.currentDevice : 0;
            outputRate = device == 0 ? 0 : ALC10.alcGetInteger(device, ALC10.ALC_FREQUENCY);
            updatePitchAndFilter(levels);
            var profile = request.attenuation();
            distanceGains = request.distanceAttenuations().stream()
                    .map(stage -> new DistanceGain(new NiagaraAudioAttenuation(stage.profile()), stage.distanceScale(), stage.soundClass()))
                    .toList();
            if (profile != null || packagedWave) attenuation = Attenuation.NONE;
            relative = profile != null && !profile.spatialize();
            position(request.position());
            rotation = request.rotation();
            volume = fadeIn == 0 ? requestedVolume * levels.volume() * distanceGain() : 0;
        }

        void recordPassiveMixes(double clock) {
            if (!isPlaying()) return;
            mixes.recordPassive(soundClass, volume, firstSourceParse, clock);
            firstSourceParse = false;
        }

        @Override public @Nullable WeighedSoundEvents resolve(SoundManager sounds) {
            if (packagedWave) {
                var wave = new Sound(getIdentifier(), ConstantFloat.of(1), ConstantFloat.of(1), 1, Sound.Type.FILE, true, false, 16);
                var event = subtitle == null || subtitle.isEmpty() ? new WeighedSoundEvents(getIdentifier(), null)
                        : new DialogueSoundEvent(getIdentifier(), subtitle);
                event.addSound(wave);
                this.sound = wave;
                return event;
            }
            var event = super.resolve(sounds);
            var selected = this.sound;
            if (start > 0 && event != null && selected != null && !selected.shouldStream()
                    && selected != SoundManager.EMPTY_SOUND && selected != SoundManager.INTENTIONALLY_EMPTY_SOUND) {
                this.sound = new Sound(selected.getLocation(), selected.getVolume(), selected.getPitch(), selected.getWeight(),
                        selected.getType(), true, false, selected.getAttenuationDistance());
            }
            return event;
        }

        /** NeoForge SoundInstance hook. */
        public CompletableFuture<AudioStream> getStream(SoundBufferLibrary library, Sound sound, boolean loop) {
            return openNiagaraStream(library, sound.getPath(), loop);
        }

        /** Fabric FabricSoundInstance hook. */
        public CompletableFuture<AudioStream> getAudioStream(SoundBufferLibrary library, Identifier id, boolean loop) {
            return openNiagaraStream(library, id, loop);
        }

        private CompletableFuture<AudioStream> openNiagaraStream(SoundBufferLibrary library, Identifier path, boolean loop) {
            if (packagedWave)
                return withPlaybackFailure(INSTANCE.wave(getIdentifier()).thenApply(wave ->
                        outputRate <= 0 ? wave.stream(start, loop) : openMixedWave(wave, loop)));
            var original = library.getStream(path, loop);
            if (start == 0) return withPlaybackFailure(original);
            return withPlaybackFailure(original.<AudioStream>thenApplyAsync(stream -> {
                try { return new OffsetStream(stream, start); }
                catch (IOException failure) {
                    try { stream.close(); } catch (IOException close) { failure.addSuppressed(close); }
                    throw new CompletionException(failure);
                }
            }, Util.nonCriticalIoPool()));
        }

        private synchronized NiagaraWavePlayback openMixedWave(NiagaraPcmWave wave, boolean loop) {
            var stream = wave.mixedStream(start, loop, outputRate, wavePitch, waveCutoff, this::playbackFailed);
            mixedWave = stream;
            return stream;
        }

        private synchronized void updatePitchAndFilter(NiagaraSoundMixes.Levels levels) {
            float value = requestedPitch * levels.pitch();
            if (outputRate <= 0) {
                pitch = value;
                return;
            }
            pitch = 1;
            wavePitch = routing.clampPitch(value);
            waveCutoff = levels.lowPass();
            var stream = mixedWave;
            if (stream != null) stream.parameters(wavePitch, waveCutoff);
        }

        private CompletableFuture<AudioStream> withPlaybackFailure(CompletableFuture<AudioStream> stream) {
            return stream.whenComplete((ready, failure) -> {
                if (failure != null) playbackFailed(failure);
            });
        }

        private void playbackFailed(Throwable failure) {
            LOGGER.error("Unable to play Niagara sound {}", getIdentifier(), failure);
            Minecraft.getInstance().execute(() -> stop(0));
        }

        @Override public float getVolume() { return super.getVolume() * submixGain; }
        @Override public boolean canStartSilent() { return true; }
        @Override public boolean isPlaying() { return !isStopped() && !channelReleased && manager.isActive(this); }

        boolean ownsCurrentChannel() {
            return channelHandle != null && manager.soundEngine.instanceToChannel.get(this) == channelHandle;
        }

        void observeChannel() {
            ChannelHandle channel = channelHandle;
            if (channel != null && channel.isStopped()) channelReleased = true;
        }
        @Override public void position(Vec3 position) {
            sourcePosition = position;
            if (!relative) { x = position.x / 100; y = position.z / 100; z = position.y / 100; }
        }
        @Override public void rotation(Vec3 rotation) { this.rotation = rotation; updateDirection = true; }
        @Override public void volume(float volume) { requestedVolume = volume; }
        @Override public void pitch(float pitch) { requestedPitch = pitch; }
        @Override public boolean parameter(String name, Object value) { return false; }

        @Override public void paused(boolean value) {
            if (paused == value) return;
            paused = value;
            var channel = manager.soundEngine.instanceToChannel.get(this);
            if (channel != null) channel.execute(sound -> { if (value) sound.pause(); else sound.unpause(); });
        }

        @Override public void stop(float seconds) {
            if (seconds > 0) {
                fadeStartGain = volume;
                fadeDuration = fadeRemaining = seconds;
                paused(false);
            } else {
                stop();
                manager.stop(this);
            }
        }

        @Override public void tick() {
            var channel = manager.soundEngine.instanceToChannel.get(this);
            if (channel != null && (updateDirection || paused)) {
                boolean orient = updateDirection, suspend = paused;
                double pitchRadians = Math.toRadians(rotation.x), yawRadians = Math.toRadians(rotation.y);
                float forwardX = (float)(Math.cos(pitchRadians) * Math.cos(yawRadians));
                float forwardY = (float)Math.sin(pitchRadians);
                float forwardZ = (float)(Math.cos(pitchRadians) * Math.sin(yawRadians));
                channel.execute(sound -> {
                    if (orient) AL10.alSource3f(sound.source, AL10.AL_DIRECTION, forwardX, forwardY, forwardZ);
                    if (suspend) sound.pause();
                });
                updateDirection = false;
            }
            if (paused) return;
            var levels = mixes.levels(soundClass);
            updatePitchAndFilter(levels);
            float secondsPerTick = 1f / 20f;
            age += secondsPerTick;
            if (fadeRemaining > 0) {
                fadeRemaining = Math.max(0, fadeRemaining - secondsPerTick);
                volume = fadeStartGain * (fadeRemaining / fadeDuration);
                if (fadeRemaining == 0) stop();
            } else volume = requestedVolume * (fadeIn == 0 ? 1 : Math.min(1f, age / fadeIn)) * levels.volume() * distanceGain();
        }

        private float distanceGain() {
            if (distanceGains.isEmpty()) return 1;
            Vec3 listenerPosition = listener();
            float gain = 1;
            for (DistanceGain stage : distanceGains)
                gain *= stage.evaluation().gain(sourcePosition, rotation, listenerPosition, mixes.distanceScale(stage.soundClass(), stage.scale()));
            return gain;
        }
    }

    private static final class OffsetStream implements AudioStream {
        private final AudioStream original;
        private @Nullable ByteBuffer prefix;

        OffsetStream(AudioStream original, float seconds) throws IOException {
            this.original = original;
            var format = original.getFormat();
            long frames = (long)(seconds * (double)format.getFrameRate());
            long remaining = Math.multiplyExact(frames, format.getFrameSize());
            while (remaining > 0) {
                var chunk = original.read((int)Math.min(remaining, 64 * 1024L));
                if (chunk == null || !chunk.hasRemaining()) return;
                int available = chunk.remaining();
                if (available > remaining) {
                    chunk.position(chunk.position() + Math.toIntExact(remaining));
                    prefix = chunk.slice();
                    return;
                }
                remaining -= available;
            }
        }

        @Override public AudioFormat getFormat() { return original.getFormat(); }
        @Override public ByteBuffer read(int expectedSize) throws IOException {
            var buffered = prefix;
            if (buffered == null) return original.read(expectedSize);
            prefix = null;
            return buffered;
        }
        @Override public void close() throws IOException { prefix = null; original.close(); }
    }
}
