package com.kltyton.lumenfx.api.client.parameter;

import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTexturePageRenderer.Request;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.multiplayer.ClientLevel;

/** Supplies the material raster for one RVT page through the active scene. */
public interface RuntimeVirtualTextureMaterialPass {
    boolean ready(ClientLevel level);

    /**
     * A native MRT raster carries the blended source channels in UE target order. Legacy rasters carry those
     * channels for BC pages and the final storage channels for uncompressed pages.
     * All targets remain live until LumenFX releases the raster.
     */
    Raster draw(ClientLevel level, Request request);

    record Raster(List<GpuTextureView> targets, Runnable release, boolean nativeMrt) implements AutoCloseable {
        public Raster(List<GpuTextureView> targets, Runnable release) { this(targets, release, false); }
        public Raster {
            targets = List.copyOf(targets);
            Objects.requireNonNull(release);
        }
        @Override public void close() { release.run(); }
    }
}
