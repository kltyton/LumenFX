package com.kltyton.lumenfx.client.render.motion;

import java.util.Objects;

/**
 * Stable per-submission history key.
 *
 * <p>The key never contains a per-frame object identity.  {@code renderSignature} is derived from
 * public render-type state only: pipeline location, primitive topology, vertex format, stable flags
 * and (when available) the sprite texture name.  {@code occurrence} disambiguates submissions that
 * share the same signature inside one owner, so the key remains stable as long as the owner's submit
 * sequence and those signatures do not change.</p>
 */
public record SubmitKey(OwnerKey owner, SubmitKind kind, String renderSignature, int occurrence) {
    public SubmitKey {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(renderSignature, "renderSignature");
        if (occurrence < 0) {
            throw new IllegalArgumentException("occurrence must be >= 0");
        }
    }
}
