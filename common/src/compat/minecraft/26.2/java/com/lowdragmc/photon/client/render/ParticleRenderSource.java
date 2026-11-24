package com.lowdragmc.photon.client.render;

import net.minecraft.client.Camera;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

/** A live effect object that contributes particle draw jobs to the world render pass. */
public interface ParticleRenderSource {
    @Nullable
    AABB getRenderBoundingBox(float partialTick);

    void extractBatches(PhotonFXRenderState renderState, Camera camera, float partialTick);
}
