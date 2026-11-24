package com.kltyton.lumenfx.client.playback;

import org.jspecify.annotations.Nullable;

/** Allows release of independent effect resources before reporting the first teardown failure. */
public final class EffectTeardown implements AutoCloseable {
    private @Nullable Throwable failure;

    public void run(Runnable release) {
        try {
            release.run();
        } catch (RuntimeException | Error error) {
            if (failure == null) failure = error;
            else if (failure != error) failure.addSuppressed(error);
        }
    }

    @Override public void close() {
        if (failure instanceof RuntimeException error) throw error;
        if (failure instanceof Error error) throw error;
    }
}
