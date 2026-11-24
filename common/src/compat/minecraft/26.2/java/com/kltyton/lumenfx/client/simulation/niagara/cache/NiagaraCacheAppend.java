package com.kltyton.lumenfx.client.simulation.niagara.cache;

import com.kltyton.lumenfx.effect.niagara.cache.NiagaraCacheInterfaceData;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraCacheInterfaceData.Counters;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraCacheInterfaceData.Frames;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraCacheInterfaceData.Key;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache.EmitterFrame;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache.Frame;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

/** Appends to an immutable cache, keeping the source layout and replacing the overlapping frame suffix. */
final class NiagaraCacheAppend {
    private NiagaraCacheAppend() { }

    static NiagaraSimulationCache merge(NiagaraSimulationCache previous, NiagaraSimulationCache captured) {
        Frame first = captured.frames().getFirst();
        int retained = retainedFrames(previous, captured.start(), first.tick());
        var currentInterfaces = new HashMap<Key, Frames>();
        captured.interfaces().forEach(data -> currentInterfaces.put(data.key(), data.frames()));
        var storage = new ArrayList<NiagaraCacheInterfaceData>(previous.interfaces().size());
        for (var original : previous.interfaces()) {
            var next = currentInterfaces.get(original.key());
            if (next == null) throw new IllegalArgumentException("Niagara cache append lost active interface " + original.key().name());
            storage.add(new NiagaraCacheInterfaceData(original.key(), join(original.frames(), next, retained)));
        }

        var frames = new ArrayList<Frame>(retained + captured.frames().size());
        frames.addAll(previous.frames().subList(0, retained));
        if (retained > 0 && previous.interpolation()) {
            var boundary = frames.getLast();
            var particles = new ArrayList<EmitterFrame>(boundary.emitters().size());
            for (int index = 0; index < boundary.emitters().size(); index++) {
                var left = boundary.emitters().get(index);
                var right = first.emitters().get(index);
                particles.add(new EmitterFrame(left.bounds(), left.totalSpawned(), NiagaraCacheRecorder.match(
                        previous.emitterLayouts().get(index), left.particles(), right.particles())));
            }
            frames.set(retained - 1, new Frame(boundary.age(), boundary.tick(), boundary.transform(), boundary.bounds(),
                    NiagaraCacheRecorder.match(previous.systemLayout(), boundary.system(), first.system()), particles));
        }
        frames.addAll(captured.frames());
        float start = retained == 0 ? captured.start() : previous.start();
        return new NiagaraSimulationCache(previous.sourceSystem(), start, captured.start() + captured.duration() - start,
                previous.interpolation(), previous.extrapolation(), previous.systemLayout(), previous.emitterLayouts(), frames,
                storage, null, previous.captureMode());
    }

    private static int retainedFrames(NiagaraSimulationCache previous, float age, int tick) {
        if (age <= previous.start() || tick == 0) return 0;
        int index = previous.frames().size();
        if (age > previous.start() + previous.duration()) return index;
        while (index > 0) {
            var frame = previous.frames().get(index - 1);
            if (frame.age() < age && frame.tick() < tick) break;
            index--;
        }
        return index;
    }

    private static Frames join(Frames before, Frames after, int retained) {
        if (before.getClass() != after.getClass()) throw new IllegalArgumentException("Niagara cache interface storage type changed");
        return switch (before) {
            case NiagaraCacheInterfaceData.Arrays arrays -> {
                var next = (NiagaraCacheInterfaceData.Arrays)after;
                yield new NiagaraCacheInterfaceData.Arrays(join(arrays.cpu(), next.cpu(), retained), join(arrays.gpu(), next.gpu(), retained));
            }
            case Counters counters -> {
                var next = (Counters)after;
                yield new Counters(join(counters.cpu(), next.cpu(), retained), join(counters.gpu(), next.gpu(), retained));
            }
            case NiagaraCacheInterfaceData.Targets2D targets -> new NiagaraCacheInterfaceData.Targets2D(
                    join(targets.values(), ((NiagaraCacheInterfaceData.Targets2D)after).values(), retained));
            case NiagaraCacheInterfaceData.Channels channels -> new NiagaraCacheInterfaceData.Channels(
                    join(channels.values(), ((NiagaraCacheInterfaceData.Channels)after).values(), retained));
            case NiagaraCacheInterfaceData.DynamicMeshes meshes -> new NiagaraCacheInterfaceData.DynamicMeshes(
                    join(meshes.values(), ((NiagaraCacheInterfaceData.DynamicMeshes)after).values(), retained));
            case NiagaraCacheInterfaceData.VolumeTargets targets -> new NiagaraCacheInterfaceData.VolumeTargets(
                    join(targets.values(), ((NiagaraCacheInterfaceData.VolumeTargets)after).values(), retained));
        };
    }

    private static int[] join(int[] previous, int[] captured, int retained) {
        int[] result = Arrays.copyOf(previous, Math.addExact(retained, captured.length));
        System.arraycopy(captured, 0, result, retained, captured.length);
        return result;
    }

    private static <T> List<T> join(List<T> previous, List<T> captured, int retained) {
        var result = new ArrayList<T>(retained + captured.size());
        result.addAll(previous.subList(0, retained));
        result.addAll(captured);
        return result;
    }
}
