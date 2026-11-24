package com.kltyton.lumenfx.api.client;

import com.kltyton.lumenfx.client.resource.volume.SparseVolumeTextureLoader;
import com.kltyton.lumenfx.effect.render.volume.SparseVolumeTextureAsset;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;

/** Loads source sparse volume data from the active resource snapshot. */
public final class NiagaraSparseVolumes {
    private NiagaraSparseVolumes() { }
    public static CompletableFuture<SparseVolumeTextureAsset> load(Identifier id) {
        var resources = Minecraft.getInstance().getResourceManager();
        return CompletableFuture.supplyAsync(() -> SparseVolumeTextureLoader.load(resources, id), Util.backgroundExecutor());
    }
}
