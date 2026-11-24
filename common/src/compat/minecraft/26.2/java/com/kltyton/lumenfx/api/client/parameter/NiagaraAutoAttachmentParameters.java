package com.kltyton.lumenfx.api.client.parameter;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Runtime equivalent of NiagaraComponent::SetAutoAttachmentParameters. */
public record NiagaraAutoAttachmentParameters(@Nullable String parentIdentity, String socketName,
                                               Rule locationRule, Rule rotationRule, Rule scaleRule) {
    public enum Rule { KeepRelative, KeepWorld, SnapToTarget }

    public NiagaraAutoAttachmentParameters {
        Objects.requireNonNull(socketName);
        Objects.requireNonNull(locationRule);
        Objects.requireNonNull(rotationRule);
        Objects.requireNonNull(scaleRule);
        if (parentIdentity != null && parentIdentity.isBlank())
            throw new IllegalArgumentException("Auto-attachment parent identity cannot be blank");
    }
}
