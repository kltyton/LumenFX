package com.kltyton.lumenfx.client.simulation.niagara;

import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;

/** Parallel overlap groups derived from explicit emitter and CPU event dependencies. */
final class NiagaraEmitterSchedule {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final int[] groups;

    NiagaraEmitterSchedule(List<NiagaraDefinition.Emitter> emitters) {
        int count = emitters.size();
        List<List<Integer>> dependencies = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            var emitter = emitters.get(i);
            List<Integer> edges = new ArrayList<>();
            for (var binding : emitter.dependencies()) {
                if (binding.mode().equals("Self")) continue;
                if (!binding.mode().equals("Other")) throw new IllegalArgumentException("Unknown Niagara emitter binding " + binding.mode());
                for (int j = 0; j < count; j++) {
                    if (j != i && SourceNames.equal(emitters.get(j).sourceName(), binding.emitterName()) && !edges.contains(j)) edges.add(j);
                }
            }
            if (emitter.simulation() instanceof NiagaraDefinition.CpuSimulation) {
                for (var handler : emitter.eventHandlers()) {
                    UUID source = guid(handler.sourceEmitterId());
                    if (source == null || source.equals(new UUID(0, 0))) continue;
                    for (int j = 0; j < count; j++) {
                        if (j != i && source.equals(guid(emitters.get(j).handleId())) && !edges.contains(j)) edges.add(j);
                    }
                }
            }
            dependencies.add(edges);
        }
        groups = new int[count];
        Arrays.fill(groups, -1);
        boolean[] visiting = new boolean[count];
        for (int i = 0; i < count; i++) priority(i, dependencies, visiting);
        int[] sourceOrder = groups.clone();
        for (int i = 0; i < count; i++) {
            var emitter = emitters.get(i);
            if (!(emitter.simulation() instanceof NiagaraDefinition.GpuSimulation)) continue;
            for (var handler : emitter.eventHandlers()) {
                UUID source = guid(handler.sourceEmitterId());
                if (source == null || source.equals(new UUID(0, 0))) continue;
                for (int j = 0; j < count; j++) {
                    if (j == i || !source.equals(guid(emitters.get(j).handleId()))) continue;
                    boolean producerFirst = sourceOrder[j] < sourceOrder[i] || sourceOrder[j] == sourceOrder[i] && j < i;
                    int consumer = producerFirst ? i : j, dependency = producerFirst ? j : i;
                    if (!dependencies.get(consumer).contains(dependency)) dependencies.get(consumer).add(dependency);
                }
            }
        }
        Arrays.fill(groups, -1);
        Arrays.fill(visiting, false);
        for (int i = 0; i < count; i++) priority(i, dependencies, visiting);
    }

    int group(int index) { return groups[index]; }

    private int priority(int index, List<List<Integer>> dependencies, boolean[] visiting) {
        if (groups[index] >= 0) return groups[index];
        if (visiting[index]) {
            LOGGER.warn("Circular Niagara emitter dependency at index {}; execution order is undefined", index);
            return 0;
        }
        visiting[index] = true;
        int result = 0;
        for (int dependency : dependencies.get(index)) result = Math.max(result, Math.addExact(priority(dependency, dependencies, visiting), 1));
        visiting[index] = false;
        return groups[index] = result;
    }

    static UUID guid(String value) {
        if (value == null || value.isBlank() || value.equals("None")) return null;
        String text = value.startsWith("{") && value.endsWith("}") ? value.substring(1, value.length() - 1) : value;
        return UUID.fromString(text);
    }
}
