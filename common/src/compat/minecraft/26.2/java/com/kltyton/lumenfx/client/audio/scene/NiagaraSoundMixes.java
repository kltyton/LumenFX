package com.kltyton.lumenfx.client.audio.scene;

import com.kltyton.lumenfx.effect.niagara.audio.NiagaraSoundClass;
import com.kltyton.lumenfx.effect.niagara.audio.NiagaraSoundMix;
import com.kltyton.lumenfx.effect.niagara.audio.NiagaraSoundMix.Adjustment;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Device Sound Mix lifetimes and the class properties used by the next source parse. */
public final class NiagaraSoundMixes {
    private static final float MAX_CUTOFF = 20_000, MIN_CUTOFF = 20;
    private static final double CUTOFF_RANGE = Math.log(MAX_CUTOFF / MIN_CUTOFF);
    private static final Levels NEUTRAL = new Levels(1, 1, MAX_CUTOFF, 0);

    public record Levels(float volume, float pitch, float lowPass, float centerVolume) { }

    private enum Phase { DELAY, FADE_IN, ACTIVE, FADE_OUT, AWAITING_REMOVAL }

    private static final class ClassOverride {
        final String soundClass;
        final AudioDynamicParameter volume = new AudioDynamicParameter(1);
        final AudioDynamicParameter pitch = new AudioDynamicParameter(1);
        boolean applied, changed, children, clearing, cleared;
        float targetVolume, targetPitch, fade;

        ClassOverride(String soundClass) { this.soundClass = soundClass; }

        void change(float volume, float pitch, float fade, boolean children, boolean clearing) {
            targetVolume = volume;
            targetPitch = pitch;
            this.fade = fade;
            this.children = children;
            this.clearing = clearing;
            changed = true;
            cleared = false;
        }

        Adjustment apply(Adjustment authored, float seconds) {
            if (!applied) {
                volume.set(authored.volume(), 0);
                pitch.set(authored.pitch(), 0);
            }
            if (!applied || changed) {
                volume.set(targetVolume, fade);
                pitch.set(targetPitch, fade);
            } else {
                volume.advance(seconds);
                pitch.advance(seconds);
            }
            applied = true;
            changed = false;
            cleared = clearing && volume.done();
            return new Adjustment(soundClass, volume.value(), pitch.value(), authored.lowPass(), authored.centerVolume(), children);
        }
    }

    private static final class ActiveMix {
        final NiagaraSoundMix mix;
        Phase phase = Phase.DELAY;
        boolean base;
        int activeReferences;
        boolean passive;
        double start, fadeInStart, fadeInEnd, fadeOutStart = -1, end = -1;
        float weight;

        ActiveMix(NiagaraSoundMix mix, double clock) {
            this.mix = mix;
            start = clock;
            fadeInStart = start + mix.delay();
            fadeInEnd = fadeInStart + mix.fadeIn();
            if (mix.duration() >= 0) scheduleFadeOut(fadeInEnd + mix.duration());
        }

        void scheduleFadeOut(double time) {
            fadeOutStart = time;
            end = time + mix.fadeOut();
        }

        void refresh(double clock) {
            if (clock >= start && clock < fadeInStart) {
                weight = 0;
                phase = Phase.DELAY;
            } else if (clock >= fadeInStart && clock < fadeInEnd) {
                weight = (float)((clock - fadeInStart) / (fadeInEnd - fadeInStart));
                phase = Phase.FADE_IN;
            } else if (clock >= fadeInEnd && (base || ((passive || activeReferences > 0) && fadeOutStart < 0) || clock < fadeOutStart)) {
                weight = 1;
                phase = Phase.ACTIVE;
            } else if (clock >= fadeOutStart && clock < end) {
                weight = 1 - (float)((clock - fadeOutStart) / (end - fadeOutStart));
                phase = Phase.FADE_OUT;
            } else {
                weight = 0;
                phase = Phase.AWAITING_REMOVAL;
            }
        }

        void retrigger(double clock) {
            if (fadeOutStart < 0) return;
            start = clock;
            if (phase == Phase.DELAY) return;
            fadeOutStart = end = -1;
            if (mix.duration() >= 0) {
                if (phase == Phase.FADE_OUT || phase == Phase.AWAITING_REMOVAL) {
                    phase = Phase.FADE_IN;
                    weight = 0;
                    fadeInStart = start;
                }
                scheduleFadeOut(clock + mix.fadeIn() + mix.duration());
            } else if (phase == Phase.FADE_OUT || phase == Phase.AWAITING_REMOVAL) {
                fadeInStart = start - (1 - weight) * mix.fadeIn();
                fadeInEnd = fadeInStart + mix.fadeIn();
                phase = Phase.FADE_IN;
            }
        }

        boolean release(double clock) {
            if (mix.duration() >= 0 || base || passive || activeReferences > 0) return false;
            if (mix.fadeOut() <= 0 || phase == Phase.DELAY || phase == Phase.AWAITING_REMOVAL) return true;
            if (phase == Phase.FADE_IN) {
                scheduleFadeOut(clock - (1 - weight) * mix.fadeOut());
                start = fadeInStart = fadeInEnd = fadeOutStart - 1;
            } else if (phase == Phase.ACTIVE) scheduleFadeOut(clock);
            return false;
        }
    }

    private final Map<String, ActiveMix> active = new LinkedHashMap<>();
    private final Map<String, NiagaraSoundMix> nextPassive = new LinkedHashMap<>();
    private final Map<String, Levels> levels = new HashMap<>();
    private final Map<String, Map<String, ClassOverride>> overrides = new HashMap<>();
    private final Map<String, AudioDynamicParameter> distanceScales = new HashMap<>();
    private final ArrayList<Adjustment> adjustments = new ArrayList<>();
    private @Nullable ActiveMix base;
    private @Nullable NiagaraSoundMix defaultBase;
    private double previousClock = Double.NaN;

    public void registerDefault(@Nullable NiagaraSoundMix mix, double clock) {
        if (mix == null || defaultBase != null) return;
        defaultBase = mix;
        if (base == null) setBase(mix, clock);
    }

    public void setBase(@Nullable NiagaraSoundMix mix, double clock) {
        NiagaraSoundMix selected = mix == null ? defaultBase : mix;
        if (selected == null) return;
        var previous = base;
        base = activate(selected, clock, false);
        base.base = true;
        if (previous != null && previous != base) {
            previous.base = false;
            if (previous.release(clock)) remove(previous);
        }
    }

    public void push(NiagaraSoundMix mix, double clock) { activate(mix, clock, true).activeReferences++; }

    public void pop(String path, double clock) {
        String key = name(path);
        var state = active.get(key);
        if (state == null) return;
        if (state.activeReferences > 0) state.activeReferences--;
        if (state.release(clock)) remove(state);
    }

    public void setClassOverride(String mix, String soundClass, float volume, float pitch, float fade, boolean children) {
        var byClass = overrides.computeIfAbsent(name(mix), ignored -> new LinkedHashMap<>());
        byClass.computeIfAbsent(name(soundClass), ignored -> new ClassOverride(soundClass))
                .change(volume, pitch, fade, children, false);
    }

    public void clearClassOverride(NiagaraSoundMix mix, String soundClass, float fade) {
        String key = name(mix.path());
        var byClass = overrides.get(key);
        if (byClass == null) return;
        String classKey = name(soundClass);
        var override = byClass.get(classKey);
        if (override == null) return;
        if (!override.applied) {
            byClass.remove(classKey);
            if (byClass.isEmpty()) overrides.remove(key);
            return;
        }
        var authored = mix.adjustments().stream().filter(adjustment -> name(adjustment.soundClass()).equals(classKey))
                .findFirst().orElse(null);
        override.change(authored == null ? 1 : authored.volume(), authored == null ? 1 : authored.pitch(),
                fade, override.children, true);
    }

    public void setDistanceScale(NiagaraSoundClass soundClass, float scale, float seconds) {
        distanceScales.computeIfAbsent(name(soundClass.path()), ignored ->
                new AudioDynamicParameter(soundClass.authored().attenuationDistanceScale())).set(scale, seconds);
    }

    public float distanceScale(@Nullable String soundClass, float authored) {
        if (soundClass == null || distanceScales.isEmpty()) return authored;
        var override = distanceScales.get(name(soundClass));
        return override == null ? authored : Math.max(0, override.value());
    }

    public void recordPassive(@Nullable NiagaraSoundClass soundClass, float volume, boolean firstParse, double clock) {
        if (soundClass == null) return;
        for (var modifier : soundClass.authored().passiveMixes()) {
            if (!modifier.applies(volume)) continue;
            String key = name(modifier.mix().path());
            if (firstParse) activate(modifier.mix(), clock, true);
            nextPassive.putIfAbsent(key, modifier.mix());
        }
    }

    public void finishPassive(double clock) {
        for (var entry : nextPassive.entrySet()) {
            var state = active.get(entry.getKey());
            if (state == null || !state.passive) activate(entry.getValue(), clock, true).passive = true;
        }
        active.entrySet().removeIf(entry -> {
            var state = entry.getValue();
            if (!state.passive || nextPassive.containsKey(entry.getKey())) return false;
            state.passive = false;
            if (!state.release(clock)) return false;
            resetOverrides(entry.getKey());
            return true;
        });
        nextPassive.clear();
    }

    public void advance(double now) {
        float elapsed = Double.isNaN(previousClock) ? 0 : (float)Math.max(0, now - previousClock);
        previousClock = now;
        distanceScales.values().forEach(parameter -> parameter.advance(elapsed));
        adjustments.clear();
        active.entrySet().removeIf(entry -> {
            var state = entry.getValue();
            state.refresh(now);
            collectAdjustments(entry.getKey(), state, elapsed);
            boolean remove = state.phase == Phase.AWAITING_REMOVAL && !state.passive;
            if (remove) resetOverrides(entry.getKey());
            return remove;
        });
        levels.clear();
    }

    private void remove(ActiveMix state) {
        String key = name(state.mix.path());
        active.remove(key);
        resetOverrides(key);
    }

    private void resetOverrides(String key) {
        var byClass = overrides.get(key);
        if (byClass != null) byClass.values().forEach(override -> override.applied = false);
    }

    private void collectAdjustments(String key, ActiveMix state, float elapsed) {
        var byClass = overrides.get(key);
        int start = adjustments.size();
        for (var authored : state.mix.adjustments()) adjustments.add(interpolated(authored, state.weight));
        if (byClass == null) return;
        byClass.values().removeIf(override -> override.cleared);
        for (var override : byClass.values()) {
            int index = start;
            int end = start + state.mix.adjustments().size();
            while (index < end && !name(adjustments.get(index).soundClass()).equals(name(override.soundClass))) index++;
            if (index == end) {
                var neutral = new Adjustment(override.soundClass, 1, 1, MAX_CUTOFF, 1, false);
                adjustments.add(override.apply(neutral, elapsed));
            } else adjustments.set(index, override.apply(adjustments.get(index), elapsed));
        }
        if (byClass.isEmpty()) overrides.remove(key);
    }

    private static Adjustment interpolated(Adjustment adjustment, float weight) {
        double linear = Math.log(Math.clamp(adjustment.lowPass(), MIN_CUTOFF, MAX_CUTOFF) / MIN_CUTOFF) / CUTOFF_RANGE;
        float cutoff = (float)(MIN_CUTOFF * Math.exp(CUTOFF_RANGE * interpolate((float)linear, weight)));
        return new Adjustment(adjustment.soundClass(), interpolate(adjustment.volume(), weight), interpolate(adjustment.pitch(), weight),
                cutoff, interpolate(adjustment.centerVolume(), weight), adjustment.children());
    }

    private ActiveMix activate(NiagaraSoundMix mix, double now, boolean retrigger) {
        String key = name(mix.path());
        var state = active.get(key);
        if (state == null) {
            state = new ActiveMix(mix, now);
            active.put(key, state);
        } else if (retrigger) state.retrigger(now);
        return state;
    }

    public Levels levels(@Nullable NiagaraSoundClass soundClass) {
        if (soundClass == null) return NEUTRAL;
        return levels.computeIfAbsent(name(soundClass.path()), ignored -> evaluate(soundClass));
    }

    private Levels evaluate(NiagaraSoundClass soundClass) {
        float volume = soundClass.volume(), pitch = soundClass.pitch();
        float lowPass = soundClass.authored().lowPass(), center = soundClass.authored().centerVolume();
        for (var adjustment : adjustments) {
            if (!applies(soundClass, adjustment)) continue;
            volume *= adjustment.volume();
            pitch *= adjustment.pitch();
            center *= adjustment.centerVolume();
            lowPass = Math.min(lowPass, adjustment.lowPass());
        }
        return new Levels(volume, pitch, lowPass, center);
    }

    private static boolean applies(NiagaraSoundClass soundClass, Adjustment adjustment) {
        NiagaraSoundClass candidate = soundClass;
        String target = name(adjustment.soundClass());
        while (candidate != null) {
            if (name(candidate.path()).equals(target)) return true;
            if (!adjustment.children() || !candidate.inheritRuntime()) break;
            candidate = candidate.parent();
        }
        return false;
    }

    private static float interpolate(float target, float weight) { return 1 + (target - 1) * weight; }
    private static String name(String path) { return SourceNames.canonical(path); }
}
