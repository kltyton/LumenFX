package com.kltyton.lumenfx.api.client.component;

import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Primitive visibility against the current view actor and the primitive's actor-owner chain. */
public record PrimitiveOwnerVisibility(boolean onlyOwnerSee, boolean ownerNoSee) {
    public static final PrimitiveOwnerVisibility UNRESTRICTED = new PrimitiveOwnerVisibility(false, false);

    public boolean visibleTo(Set<UUID> owners, @Nullable UUID viewActor) {
        if (!onlyOwnerSee && !ownerNoSee) return true;
        boolean owned = viewActor != null && owners.contains(viewActor);
        return (!onlyOwnerSee || owned) && (!ownerNoSee || !owned);
    }
}
