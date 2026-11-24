/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.fx;


/**
 * {@link ParticleTickHost} view of the vanilla {@code ParticleEngine} singleton. Fed by
 * {@code ParticleEngineMixin} (tick + setLevel); consumed by
 * {@link FXRuntime#isValid()}.
 */
public final class VanillaParticleHost implements ParticleTickHost {
    public static final VanillaParticleHost INSTANCE = new VanillaParticleHost();

    private long tickCount = 0;
    private volatile int generation = 0;

    private VanillaParticleHost() {
    }

    /** Called from {@code ParticleEngineMixin} at the head of every {@code ParticleEngine.tick()}. */
    public static void onEngineTick() {
        INSTANCE.tickCount++;
    }

    /** Called whenever the engine mass-discards particles during {@code setLevel}. */
    public static void onWipe() {
        INSTANCE.generation++;
    }

    @Override
    public long tickCount() {
        return tickCount;
    }

    @Override
    public int generation() {
        return generation;
    }
}
