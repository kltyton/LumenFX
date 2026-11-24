package com.kltyton.lumenfx.client.simulation.niagara.cache;

import com.kltyton.lumenfx.effect.niagara.cache.NiagaraCacheInterfaceData;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraCacheInterfaceData.ArrayFrame;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraCacheInterfaceData.ChannelFrame;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraCacheInterfaceData.DynamicMeshPair;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraCacheVolumeFrame;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraCacheInterfaceData.Key;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraCacheInterfaceData.Target2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Transposes independent per-frame interface results into the cache's storage objects. */
public final class NiagaraCacheInterfaceRecorder {
    public sealed interface Value permits ArrayValue, CounterValue, TargetValue, ChannelValue, DynamicValue, VolumeValue { }
    public record ArrayValue(ArrayFrame cpu, ArrayFrame gpu) implements Value { }
    public record CounterValue(int cpu, int gpu) implements Value { }
    public record TargetValue(Target2D value) implements Value { }
    public record ChannelValue(ChannelFrame value) implements Value { }
    public record DynamicValue(DynamicMeshPair value) implements Value { }
    public record VolumeValue(NiagaraCacheVolumeFrame value) implements Value { }

    private NiagaraCacheInterfaceRecorder() { }

    public static List<NiagaraCacheInterfaceData> collect(List<Map<Key, Value>> frames) {
        var initial = frames.getFirst();
        for (var frame : frames)
            if (!frame.keySet().equals(initial.keySet()))
                throw new IllegalStateException("Niagara capture data-interface bindings changed while recording");
        var values = new ArrayList<NiagaraCacheInterfaceData>(initial.size());
        initial.forEach((key, value) -> {
            var storage = switch (value) {
                case ArrayValue ignored -> arrays(frames, key);
                case CounterValue ignored -> counters(frames, key);
                case TargetValue ignored -> targets(frames, key);
                case ChannelValue ignored -> new NiagaraCacheInterfaceData.Channels(
                        frames.stream().map(frame -> ((ChannelValue)frame.get(key)).value()).toList());
                case DynamicValue ignored -> new NiagaraCacheInterfaceData.DynamicMeshes(
                        frames.stream().map(frame -> ((DynamicValue)frame.get(key)).value()).toList());
                case VolumeValue ignored -> new NiagaraCacheInterfaceData.VolumeTargets(
                        frames.stream().map(frame -> ((VolumeValue)frame.get(key)).value()).toList());
            };
            values.add(new NiagaraCacheInterfaceData(key, storage));
        });
        return List.copyOf(values);
    }

    private static NiagaraCacheInterfaceData.Arrays arrays(List<Map<Key, Value>> frames, Key key) {
        var cpu = new ArrayList<ArrayFrame>(frames.size());
        var gpu = new ArrayList<ArrayFrame>(frames.size());
        for (var frame : frames) {
            var value = (ArrayValue)frame.get(key);
            cpu.add(value.cpu());
            gpu.add(value.gpu());
        }
        return new NiagaraCacheInterfaceData.Arrays(cpu, gpu);
    }

    private static NiagaraCacheInterfaceData.Counters counters(List<Map<Key, Value>> frames, Key key) {
        var cpu = new int[frames.size()];
        var gpu = new int[frames.size()];
        for (int i = 0; i < frames.size(); i++) {
            var value = (CounterValue)frames.get(i).get(key);
            cpu[i] = value.cpu();
            gpu[i] = value.gpu();
        }
        return new NiagaraCacheInterfaceData.Counters(cpu, gpu);
    }

    private static NiagaraCacheInterfaceData.Targets2D targets(List<Map<Key, Value>> frames, Key key) {
        return new NiagaraCacheInterfaceData.Targets2D(frames.stream().map(frame -> ((TargetValue)frame.get(key)).value()).toList());
    }
}
