package com.kltyton.lumenfx.client.resource.mesh;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.minecraft.util.Util;

/** A render-asset LOD read remains pending until its I/O worker has unwound. */
public final class MeshLodRead<T> {
    public final int first;
    public final CompletableFuture<Map<Integer, T>> future = new CompletableFuture<>();
    public volatile boolean finished;

    public MeshLodRead(int first) { this.first = first; }

    public void cancelSuperseded(int wanted, int resident, boolean wasMissingTooManyLods, boolean missingTooManyLods) {
        if (first == resident || first == wanted) return;
        // The native mip counts increase toward more detail. First LOD indices use the inverse order.
        if (first < Math.min(resident, wanted - (wasMissingTooManyLods ? 0 : 1))
                || first > (missingTooManyLods ? wanted : Math.max(resident, wanted))) future.cancel(false);
    }

    public void submit(String label, AtomicInteger inFlight, Supplier<Map<Integer, T>> load) {
        inFlight.incrementAndGet();
        try {
            Util.ioPool().forName(label).execute(() -> {
                try { future.complete(load.get()); }
                catch (RuntimeException | Error failure) { future.completeExceptionally(failure); }
                finally { finish(inFlight); }
            });
        } catch (RuntimeException | Error failure) {
            future.completeExceptionally(failure);
            finish(inFlight);
        }
    }

    private void finish(AtomicInteger inFlight) {
        inFlight.decrementAndGet();
        finished = true;
    }
}
