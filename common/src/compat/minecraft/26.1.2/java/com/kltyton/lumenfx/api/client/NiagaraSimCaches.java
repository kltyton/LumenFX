package com.kltyton.lumenfx.api.client;

import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache;
import com.kltyton.lumenfx.runtime.DirectVfxEngine;
import com.kltyton.lumenfx.runtime.bridge.McResourceBridge;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.CompletableFuture;
import net.minecraft.resources.Identifier;

/** Loads packaged {@code .lfsc} Niagara simulation caches for Minecraft 26.1.2. */
public final class NiagaraSimCaches {
    private NiagaraSimCaches() { }

    public static CompletableFuture<NiagaraSimulationCache> load(Identifier id) {
        String resourceId = id.getNamespace() + ":lumenfx/sim_caches/" + id.getPath() + ".lfsc";
        McResourceBridge bridge = DirectVfxEngine.INSTANCE.bridge();
        return CompletableFuture.supplyAsync(() -> {
            if (bridge == null) throw new UncheckedIOException(new IOException("Resource bridge unavailable for " + id));
            byte[] raw = bridge.readResource(resourceId);
            if (raw == null) throw new UncheckedIOException(new IOException("Missing Niagara simulation cache " + resourceId));
            try {
                return NiagaraSimulationCache.decode(id.toString(), raw);
            } catch (IOException ex) {
                throw new UncheckedIOException("Could not load Niagara simulation cache " + id, ex);
            }
        });
    }
}
