package com.kltyton.lumenfx.client.render.scene.material;

import com.lowdragmc.photon.client.render.PhotonTextureCopy;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.Objects;

/** Saves the actual opaque scene values for later material passes that may write to the scene target. */
public final class OpaqueSceneColor implements AutoCloseable {
    private final PhotonTextureCopy snapshot = new PhotonTextureCopy("LumenFX opaque scene color");

    public GpuTextureView capture(CommandEncoder encoder, RenderTarget source) {
        var color = Objects.requireNonNull(source.getColorTextureView(), "Particle materials require a scene color attachment");
        return Objects.requireNonNull(snapshot.copyOf(encoder, color), "Particle materials cannot sample the opaque scene color");
    }

    @Override public void close() {
        snapshot.close();
    }
}
