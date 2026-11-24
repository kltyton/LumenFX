/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.render;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.ParticleGroupRenderState;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/**
 * The visible emitter bake tasks of {@link PhotonParticleGroup}. Both native world passes and external
 * shaderpack composition consume the extracted frame through {@code ClientVfxEvents} so Photon draws
 * share the active world color and depth attachments.
 */
public final class PhotonFXRenderState implements ParticleGroupRenderState {

    private final List<PhotonBakeTask> tasks = new ArrayList<>();
    private float partialTick;

    void partialTick(float value) {
        partialTick = value;
    }

    public void defer(PhotonBakeTask task) {
        tasks.add(task);
    }

    @Override
    public void submit(SubmitNodeCollector collector, CameraRenderState camera) {
        // World level rendering consumes frame(camera) directly from ClientVfxEvents in both native
        // and Iris shaderpack pipelines so Photon never draws into an unattached mainRenderTarget.
    }

    /** Snapshot the extracted work before the engine clears or reuses the particle group state. */
    @Nullable
    public PhotonWorldRenderState.Frame frame(CameraRenderState camera) {
        return tasks.isEmpty() ? null : new PhotonWorldRenderState.Frame(List.copyOf(tasks), camera, partialTick);
    }

    @Override
    public void clear() {
        tasks.clear();
    }
}
