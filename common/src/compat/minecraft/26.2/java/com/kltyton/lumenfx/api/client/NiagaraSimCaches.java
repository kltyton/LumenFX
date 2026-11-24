package com.kltyton.lumenfx.api.client;

import com.kltyton.lumenfx.client.resource.niagara.cache.NiagaraSimCacheLoader;
import com.kltyton.lumenfx.client.resource.niagara.cache.NiagaraSimCacheWriter;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;

/** Loads a packaged Niagara simulation cache using the current resource snapshot. */
public final class NiagaraSimCaches {
    private NiagaraSimCaches() { }

    public static CompletableFuture<NiagaraSimulationCache> load(Identifier id) {
        var resources = Minecraft.getInstance().getResourceManager();
        return CompletableFuture.supplyAsync(() -> {
            try {
                return NiagaraSimCacheLoader.load(resources, id);
            } catch (IOException failure) {
                throw new UncheckedIOException("Could not load Niagara simulation cache " + id, failure);
            }
        }, Util.backgroundExecutor());
    }

    /** Writes a cache to a caller-owned stream without closing the stream. */
    public static void write(NiagaraSimulationCache cache, OutputStream output) throws IOException {
        NiagaraSimCacheWriter.write(cache, output);
    }
}
