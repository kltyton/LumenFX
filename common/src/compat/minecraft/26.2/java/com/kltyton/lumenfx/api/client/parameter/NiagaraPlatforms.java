package com.kltyton.lumenfx.api.client.parameter;

import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraPlatformEnvironment;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Publishes the imported source device profile and console values without changing Minecraft's settings. */
public final class NiagaraPlatforms {
    public record Publication(NiagaraPlatformEnvironment environment, long sequence) {
        public Publication { Objects.requireNonNull(environment); }
    }
    private static final AtomicReference<Publication> CURRENT = new AtomicReference<>(
            new Publication(NiagaraPlatformEnvironment.WINDOWS_DEFAULT, 0));
    private NiagaraPlatforms() { }
    public static NiagaraPlatformEnvironment snapshot() { return CURRENT.get().environment(); }
    public static Publication publication() { return CURRENT.get(); }
    /** Advances the source console-variable sink, including publications with unchanged values. */
    public static void publish(NiagaraPlatformEnvironment value) {
        Objects.requireNonNull(value);
        CURRENT.updateAndGet(previous -> new Publication(value, Math.incrementExact(previous.sequence())));
    }
}
