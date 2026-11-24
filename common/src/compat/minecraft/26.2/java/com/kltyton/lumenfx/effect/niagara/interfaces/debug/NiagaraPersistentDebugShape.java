package com.kltyton.lumenfx.effect.niagara.interfaces.debug;

import com.kltyton.lumenfx.effect.niagara.parameter.store.NiagaraParameterLayout.Key;
import java.util.List;

/** One script's compiled tags for a named Debug Draw primitive. */
public record NiagaraPersistentDebugShape(String interfaceId, String function, int lineLimit, boolean local,
                                          List<Tag> tags) {
    public NiagaraPersistentDebugShape {
        tags = List.copyOf(tags);
    }

    public record Tag(String name, Key binding, int bytes, List<Integer> words) {
        public Tag {
            words = List.copyOf(words);
            if (bytes <= 0 || bytes % Integer.BYTES != 0 || (!words.isEmpty() && words.size() * Integer.BYTES != bytes))
                throw new IllegalArgumentException("Invalid Niagara compiler tag layout: " + name);
        }
    }
}
