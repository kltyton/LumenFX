package com.kltyton.lumenfx.client.render.niagara;

import com.kltyton.lumenfx.client.simulation.niagara.NiagaraParticles;
import net.minecraft.world.phys.Vec3;

/** Previous source attributes in the same current-camera-relative coordinates as the instance stream. */
public final class SpriteMotionData {
    private SpriteMotionData() { }

    public static void write(float[] target, int offset, NiagaraParticles particles, int row, boolean local,
                              double cameraX, double cameraY, double cameraZ) {
        var component = new NiagaraComponentGeometry();
        component.prepare(particles, new Vec3(cameraX, cameraY, cameraZ));
        write(target, offset, particles, row, local, component);
    }

    public static void write(float[] target, int offset, NiagaraParticles particles, int row, boolean local,
                              NiagaraComponentGeometry component) {
        component.position(target, offset, particles.previousSimulationPosition(row, 0), particles.previousSimulationPosition(row, 1),
                particles.previousSimulationPosition(row, 2), local, true);
        target[offset + 3] = particles.motion().precise() ? 1 : 2;
        target[offset + 4] = particles.previousSpriteSize(row, 0); target[offset + 5] = particles.previousSpriteSize(row, 1);
        target[offset + 6] = -particles.previousSpriteRotation(row); target[offset + 7] = particles.previousCameraOffset(row) * .01f;
        component.direction(target, offset + 8, particles.previousVelocity(row, 0), particles.previousVelocity(row, 1), particles.previousVelocity(row, 2), local, true);
        component.direction(target, offset + 12, particles.previousSpriteFacing(row, 0), particles.previousSpriteFacing(row, 1), particles.previousSpriteFacing(row, 2), local, true);
        component.direction(target, offset + 16, particles.previousSpriteAlignment(row, 0), particles.previousSpriteAlignment(row, 1), particles.previousSpriteAlignment(row, 2), local, true);
        target[offset + 11] = target[offset + 15] = target[offset + 19] = 0;
    }

}
