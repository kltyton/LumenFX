package com.kltyton.lumenfx.client.audio.scene;

import com.kltyton.lumenfx.api.client.parameter.NiagaraAudioBackend.Playback;
import com.kltyton.lumenfx.api.client.parameter.NiagaraAudioBackend.Voice;
import com.kltyton.lumenfx.effect.niagara.audio.NiagaraSoundAcoustics.Concurrency;
import com.kltyton.lumenfx.effect.niagara.audio.NiagaraSoundAcoustics.DuckMode;
import com.kltyton.lumenfx.effect.niagara.audio.NiagaraSoundAcoustics.Rule;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.util.Util;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Source concurrency groups share admission, voice stealing and ducking across active effects. */
public final class NiagaraAudioConcurrency {
    private static final float MIN_VOLUME_SCALE = 1.e-3f;

    private record Key(String path, @Nullable Object owner) {
        @Override public int hashCode() { return 31 * path.hashCode() + System.identityHashCode(owner); }
        @Override public boolean equals(Object other) { return other instanceof Key key && path.equals(key.path) && owner == key.owner; }
    }
    private static final class Group {
        final Concurrency settings;
        final ArrayList<ManagedVoice> sounds = new ArrayList<>();
        double lastPlay = Double.NEGATIVE_INFINITY;
        Group(Concurrency settings) { this.settings = settings; }
    }

    private static final class ManagedVoice implements Voice {
        final Voice voice;
        final float priority;
        final long order;
        final @Nullable NiagaraAudioAttenuation attenuation;
        final boolean looping;
        final Map<Group, Duck> ducks = new IdentityHashMap<>();
        Vec3 position, rotation;
        float volume, age;
        boolean paused;
        ManagedVoice(Voice voice, Playback request, long order, List<Group> groups) {
            this.voice = voice;
            this.order = order;
            priority = request.priority();
            looping = request.looping();
            position = request.position();
            rotation = request.rotation();
            volume = request.volume();
            var profile = request.attenuation();
            attenuation = profile == null ? null : new NiagaraAudioAttenuation(profile);
            for (var group : groups) ducks.put(group, new Duck());
        }
        @Override public boolean isPlaying() { return voice.isPlaying(); }
        @Override public void position(Vec3 value) { position = value; voice.position(value); }
        @Override public void rotation(Vec3 value) { rotation = value; voice.rotation(value); }
        @Override public void volume(float value) { volume = value; applyVolume(); }
        @Override public void pitch(float value) { voice.pitch(value); }
        @Override public void paused(boolean value) { paused = value; voice.paused(value); }
        @Override public boolean parameter(String name, Object value) { return voice.parameter(name, value); }
        @Override public void stop(float fade) { voice.stop(fade); }
        float audibility(Vec3 listener) { return volume * (attenuation == null ? 1 : attenuation.gain(position, rotation, listener)); }

        void applyVolume() {
            float combined = volume;
            for (var duck : ducks.values()) combined *= duck.value;
            voice.volume(combined);
        }
        void evict() {
            float fade = Float.POSITIVE_INFINITY;
            boolean suppressFade = false;
            for (var group : ducks.keySet()) {
                fade = Math.min(fade, group.settings.release());
                // Post-start culling skips voice-steal fades for loops that have barely begun playing.
                suppressFade |= looping && age <= 0.1f && !immediate(group.settings.rule());
                group.sounds.remove(this);
            }
            ducks.clear();
            voice.stop(suppressFade ? 0 : Float.isFinite(fade) ? fade : 0);
        }
    }

    private static final class Duck {
        float value = 1, start = 1, target = 1, time;
        float currentDecibels, startDecibels, targetDecibels;
        void update(Concurrency settings, float gain, float elapsed) {
            if (settings.duckMode() == DuckMode.Default && !settings.recover()) gain = Math.min(target, gain);
            gain = audibleScale(gain);
            if (Math.abs(gain - target) > .001f) {
                start = value;
                target = gain;
                startDecibels = currentDecibels;
                targetDecibels = decibels(gain);
                time = 0;
            }
            time += elapsed;
            float seconds = target > start && settings.duckMode() == DuckMode.Default ? settings.duckRelease() : settings.duckAttack();
            float fraction = seconds <= 0 ? 1 : Math.min(1f, time / seconds);
            currentDecibels = startDecibels + (targetDecibels - startDecibels) * fraction;
            value = fromDecibels(currentDecibels);
        }

        private static float audibleScale(float value) { return value <= MIN_VOLUME_SCALE ? 0 : value; }
        private static float decibels(float value) { return 20 * (float)Math.log10(Math.max(value, MIN_VOLUME_SCALE)); }
        private static float fromDecibels(float value) {
            float scale = (float)Math.pow(10, value / 20);
            return scale <= MIN_VOLUME_SCALE ? 0 : scale;
        }
    }

    private final Map<Key, Group> groups = new HashMap<>();
    private long playOrder;

    public @Nullable Voice play(Playback request, Vec3 listener, Function<Playback, @Nullable Voice> factory) {
        if (request.concurrency().isEmpty()) return factory.apply(request);
        var accepted = new ArrayList<Group>();
        var evictions = new HashSet<ManagedVoice>();
        double time = now();
        for (var settings : request.concurrency()) {
            var key = new Key(settings.path(), settings.perOwner() ? request.owner() : null);
            var group = groups.computeIfAbsent(key, ignored -> new Group(settings));
            if (!group.settings.equals(settings)) throw new IllegalStateException("Conflicting Niagara audio concurrency asset " + settings.path());
            if (accepted.contains(group)) continue;
            group.sounds.removeIf(sound -> !sound.isPlaying());
            if (time - group.lastPlay < settings.retrigger()) return null;
            group.lastPlay = time;
            if (group.sounds.size() >= settings.maximum() && immediate(settings.rule())) {
                var eviction = evictable(group, request, listener);
                if (eviction == null) return null;
                evictions.add(eviction);
            }
            accepted.add(group);
        }
        var voice = factory.apply(request);
        if (voice == null) return null;
        evictions.forEach(ManagedVoice::evict);
        var result = new ManagedVoice(voice, request, ++playOrder, accepted);
        for (var group : accepted) group.sounds.add(result);
        return result;
    }

    private static boolean immediate(Rule rule) {
        return switch (rule) {
            case PreventNew, StopOldest, StopFarthestThenPreventNew, StopFarthestThenOldest -> true;
            default -> false;
        };
    }

    private static @Nullable ManagedVoice evictable(Group group, Playback request, Vec3 listener) {
        Rule rule = group.settings.rule();
        if (rule == Rule.PreventNew) return null;
        if (rule == Rule.StopOldest)
            return group.sounds.stream().max(Comparator.comparingDouble(voice -> voice.age)).orElse(null);
        double farthest = request.position().distanceToSqr(listener);
        ManagedVoice choice = null;
        for (var sound : group.sounds) {
            double distance = sound.position.distanceToSqr(listener);
            if (distance > farthest || distance == farthest && rule == Rule.StopFarthestThenOldest && (choice == null || sound.age > choice.age)) {
                farthest = distance;
                choice = sound;
            }
        }
        return choice;
    }

    public void tick(Vec3 listener, float elapsed) {
        var iterator = groups.values().iterator();
        double time = now();
        var sounds = new HashSet<ManagedVoice>();
        while (iterator.hasNext()) {
            var group = iterator.next();
            group.sounds.removeIf(sound -> !sound.isPlaying());
            if (group.sounds.isEmpty()) {
                if (time - group.lastPlay >= group.settings.retrigger()) iterator.remove();
                continue;
            }
            cull(group, listener);
            duck(group, listener, elapsed);
            sounds.addAll(group.sounds);
        }
        for (var sound : sounds) if (!sound.ducks.isEmpty()) {
            if (!sound.paused) sound.age += elapsed;
            sound.applyVolume();
        }
    }

    private static void cull(Group group, Vec3 listener) {
        int excess = group.sounds.size() - group.settings.maximum();
        if (excess <= 0) return;
        Rule rule = group.settings.rule();
        if (immediate(rule)) return;
        var candidates = new ArrayList<>(group.sounds);
        candidates.sort((left, right) -> {
            double a = rule == Rule.StopQuietest ? left.audibility(listener) : left.priority;
            double b = rule == Rule.StopQuietest ? right.audibility(listener) : right.priority;
            if (Math.abs(a - b) > 1.e-4) return Double.compare(a, b);
            return rule == Rule.StopLowestPriority ? Long.compare(left.order, right.order) : Long.compare(right.order, left.order);
        });
        for (int index = 0; index < excess; index++) {
            var victim = candidates.get(index);
            victim.evict();
        }
    }

    private static void duck(Group group, Vec3 listener, float elapsed) {
        if (group.settings.duckScale() == 1) return;
        var sorted = new ArrayList<>(group.sounds);
        switch (group.settings.duckMode()) {
            case Default -> sorted.sort(Comparator.comparingLong(sound -> sound.order));
            case Distance -> sorted.sort(Comparator.<ManagedVoice>comparingDouble(sound -> sound.position.distanceToSqr(listener)).reversed());
            case Priority -> sorted.sort(Comparator.comparingDouble(sound -> sound.priority));
        }
        for (int index = 0; index < sorted.size(); index++) {
            float gain = (float)Math.pow(group.settings.duckScale(), sorted.size() - 1 - index);
            sorted.get(index).ducks.get(group).update(group.settings, gain, elapsed);
        }
    }

    public void clear() {
        var sounds = new HashSet<ManagedVoice>();
        for (var group : groups.values()) sounds.addAll(group.sounds);
        for (var sound : sounds) sound.stop(0);
        groups.clear();
    }

    private static double now() { return Util.getNanos() * 1.e-9; }
}
