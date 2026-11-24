package com.kltyton.lumenfx.client.audio.cue;

import com.kltyton.lumenfx.api.client.parameter.NiagaraAudioBackend.Voice;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.Identifier;

/** Tracks the active sounds occupying each physical SoundNodeGroupControl branch. */
public final class NiagaraCueGroupSlots {
    private record Key(Identifier sound, int node) { }

    private static final class Group {
        final Map<Integer, Map<Object, Set<Voice>>> branches = new HashMap<>();

        int count(int index) {
            var voices = branches.get(index);
            return voices == null ? 0 : voices.size();
        }

        void publish(int index, Object owner, List<Voice> voices) {
            branches.computeIfAbsent(index, ignored -> new IdentityHashMap<>())
                    .computeIfAbsent(owner, ignored -> Collections.newSetFromMap(new IdentityHashMap<>())).addAll(voices);
        }

        boolean prune() {
            branches.values().removeIf(owners -> {
                owners.values().removeIf(waves -> {
                    waves.removeIf(wave -> !wave.isPlaying());
                    return waves.isEmpty();
                });
                return owners.isEmpty();
            });
            return branches.isEmpty();
        }
    }

    private final Map<Key, Group> groups = new HashMap<>();

    int choose(Identifier sound, int node, List<Integer> limits) {
        var key = new Key(sound, node);
        var group = groups.get(key);
        if (group == null) return 0;
        if (group.prune()) {
            groups.remove(key);
            return 0;
        }
        for (int index = 0; index < limits.size(); index++)
            if (group.count(index) < limits.get(index)) return index;
        return limits.size();
    }

    void publish(Identifier sound, int node, int branch, Object owner, List<Voice> voices) {
        groups.computeIfAbsent(new Key(sound, node), ignored -> new Group()).publish(branch, owner, voices);
    }

    public void prune() { groups.values().removeIf(Group::prune); }
    public void clear() { groups.clear(); }
}
