package com.kltyton.lumenfx.client.simulation.niagara.cache;

import com.kltyton.lumenfx.api.client.EffectHandle.Pose;
import com.kltyton.lumenfx.client.simulation.niagara.VectorDataSet;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition.DisabledSimulation;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition.GpuSimulation;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition.StatelessSimulation;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache.Buffer;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache.EmitterFrame;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache.Sample;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import java.util.Arrays;
import java.util.HashMap;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Resolves recorded layouts once against the current system and advances the instance's playback cursor. */
public final class NiagaraCachePlayer {
    private final NiagaraSimulationCache cache;
    private final NiagaraCachePlayback system;
    private final NiagaraCachePlayback[] emitters;
    private final int[] layoutIndices;
    private final boolean poseDependent;
    private float age;
    private boolean initialized;
    private @Nullable Pose renderedPose;

    public NiagaraCachePlayer(NiagaraSimulationCache cache, NiagaraDefinition definition) {
        this.cache = cache;
        if (definition.sourceSystem() == null || !SourceNames.equal(cache.sourceSystem(), definition.sourceSystem()))
            throw new IllegalArgumentException("Niagara cache belongs to " + cache.sourceSystem() + ", not " + definition.sourceSystem());
        system = NiagaraCachePlayback.system(cache.systemLayout(), definition.systemLayout());
        emitters = new NiagaraCachePlayback[definition.emitters().size()];
        layoutIndices = new int[emitters.length];
        var byName = new HashMap<String, Integer>();
        for (int i = 0; i < cache.emitterLayouts().size(); i++) {
            String name = cache.emitterLayouts().get(i).name();
            if (!name.isEmpty() && !name.equals("none") && byName.putIfAbsent(name, i) != null)
                throw new IllegalArgumentException("Duplicate emitter layout in Niagara cache: " + name);
        }
        for (int i = 0; i < emitters.length; i++) {
            var emitter = definition.emitters().get(i);
            var simulation = emitter.simulation();
            if (simulation instanceof DisabledSimulation) continue;
            Integer layoutIndex = byName.remove(SourceNames.canonical(emitter.name()));
            if (layoutIndex == null && simulation instanceof StatelessSimulation) continue;
            if (layoutIndex == null) throw new IllegalArgumentException("Niagara cache is missing emitter " + emitter.name());
            var layout = cache.emitterLayouts().get(layoutIndex);
            boolean gpu = simulation instanceof GpuSimulation
                    || simulation instanceof StatelessSimulation stateless && !stateless.target().equals("CPUSim");
            if (layout.gpu() != gpu || layout.localSpace() != emitter.localSpace())
                throw new IllegalArgumentException("Niagara cache simulation layout changed for " + emitter.name());
            layoutIndices[i] = layoutIndex;
            emitters[i] = NiagaraCachePlayback.emitter(layout, emitter.attributes());
        }
        if (!byName.isEmpty()) throw new IllegalArgumentException("Niagara cache contains unknown emitters: " + byName.keySet());
        poseDependent = system.dependsOnPose(cache.interpolation()) || Arrays.stream(emitters)
                .anyMatch(emitter -> emitter != null && emitter.dependsOnPose(cache.interpolation()))
                || cache.interfaces().stream().anyMatch(value -> value.frames() instanceof
                    com.kltyton.lumenfx.effect.niagara.cache.NiagaraCacheInterfaceData.Channels);
        age = cache.start();
    }

    public NiagaraSimulationCache cache() { return cache; }
    public float age() { return age; }
    public boolean hasEmitter(int index) { return emitters[index] != null; }
    public int[] particleIndex(int index, Buffer saved, VectorDataSet output) {
        return emitters[index].particleIndex(saved, output);
    }

    public boolean shouldRead(float componentAge, Pose owner) {
        return renderedPose == null || Math.abs(age - componentAge) > 1.e-8f || poseDependent && !renderedPose.equals(owner);
    }

    public void markRead(Pose owner) { renderedPose = owner; }

    public boolean hasReadFrame() { return renderedPose != null; }

    public boolean canResumeLive() {
        switch (cache.captureMode()) {
            case ALL -> { return true; }
            case RENDERING_ONLY, EXPLICIT -> { return false; }
            case UNKNOWN -> { }
        }
        if (!system.completeLayout()) return false;
        for (var emitter : emitters) if (emitter != null && !emitter.completeLayout()) return false;
        return true;
    }

    public boolean hasEmitterFloat3(int index, String name, String sourceType) {
        if (!hasEmitter(index)) return false;
        for (var field : cache.emitterLayouts().get(layoutIndices[index]).fields())
            if (SourceNames.equal(field.name(), name) && SourceNames.equal(field.sourceType(), sourceType))
                return field.floats().count() == 3;
        return false;
    }

    public @Nullable Sample advance(float delta, @Nullable Float overrideAge) {
        if (overrideAge != null) age = overrideAge;
        else if (initialized) age += Math.max(delta, 0);
        initialized = true;
        return cache.sample(age);
    }

    public void readSystem(Sample sample, float delta, Pose owner, Vec3 tile, VectorDataSet output) {
        var first = sample.first();
        var second = sample.second();
        system.copy(first.system(), first.system(), sample.fraction(), second.age() - first.age(), delta,
                cache.extrapolation(), cache.interpolation(), first.transform(), second.transform(), owner, tile, output,
                Integer.MAX_VALUE);
    }

    public EmitterFrame readEmitter(int index, Sample sample, float delta, Pose owner, Vec3 tile, VectorDataSet output) {
        return readEmitter(index, sample, delta, owner, tile, output, Integer.MAX_VALUE);
    }

    EmitterFrame readEmitter(int index, Sample sample, float delta, Pose owner, Vec3 tile, VectorDataSet output,
                             int maximumRows) {
        var first = sample.first();
        var second = sample.second();
        int layout = layoutIndices[index];
        var saved = first.emitters().get(layout);
        emitters[index].copy(saved.particles(), second.emitters().get(layout).particles(), sample.fraction(),
                second.age() - first.age(), delta, cache.extrapolation(), cache.interpolation(),
                first.transform(), second.transform(), owner, tile, output, maximumRows);
        return saved;
    }
}
