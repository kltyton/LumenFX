/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.render;

import com.lowdragmc.photon.client.gameobject.FXObject;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleGroup;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.ParticleGroupRenderState;
import net.minecraft.world.phys.AABB;

import java.util.Collection;
import java.util.Collections;

/**
 * The particle-engine host of every Photon FX object (registered for
 * {@link PhotonParticleRenderTypes#FX} via {@code RegisterParticleGroupsEvent}, which fires for
 * world {@code ParticleEngine} construction).
 * <p>
 * Ticking is inherited; extraction drives the per-frame chain that used to hang off the 1.21
 * {@code Particle.render} call ({@link FXObject#extractFrame}: deltaTime bookkeeping, timeline
 * frame animation, {@code FXRuntime}'s onUpdateFrame) and registers each visible emitter's deferred
 * bake on the {@link PhotonFXRenderState}.
 * <p>
 */
public final class PhotonParticleGroup extends ParticleGroup<FXObject> {

    /** Reused across frames; vanilla resets it via {@link ParticleGroupRenderState#clear()}. */
    private final PhotonFXRenderState renderState = new PhotonFXRenderState();

    public PhotonParticleGroup(ParticleEngine engine) {
        super(engine);
    }

    @Override
    public ParticleGroupRenderState extractRenderState(Frustum frustum, Camera camera, float partialTick) {
        renderState.clear(); // reused across frames; vanilla's reset() may not have run for this view
        renderState.partialTick(partialTick);
        for (var fxObject : particles) {
            // every FX object gets its frame drive, visible or not — timelines must not freeze
            // when the emitter happens to be off-screen
            fxObject.extractFrame(partialTick);
        }
        for (var fxObject : particles) {
            if (!(fxObject instanceof ParticleRenderSource emitter) || !fxObject.isVisible()) {
                continue;
            }
            var cullBox = emitter.getRenderBoundingBox(partialTick);
            if (cullBox != null && cullBox != com.lowdragmc.photon.client.gameobject.FXObject.INFINITE_CULL && !frustum.isVisible(cullBox)) {
                continue;
            }
            emitter.extractBatches(renderState, camera, partialTick);
        }
        return renderState;
    }

    /** The FX objects this engine ticks, live. */
    public Collection<FXObject> objects() {
        return Collections.unmodifiableCollection(particles);
    }
}
