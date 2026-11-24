package com.kltyton.lumenfx.api.client.groom;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Saved Groom physics and native guide data in source group order. */
public record NiagaraGroomAsset(List<Group> groups) {
    public record Group(boolean simulationEnabled, NiagaraGroomPhysics physics, @Nullable NiagaraGroomGuides guides) {
        public Group { Objects.requireNonNull(physics); }
    }

    public NiagaraGroomAsset {
        groups = List.copyOf(groups);
        if (groups.isEmpty()) throw new IllegalArgumentException("Groom asset contains no groups");
    }
}
