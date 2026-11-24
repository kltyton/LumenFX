package com.kltyton.lumenfx.runtime.simulation;

import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.runtime.bridge.McResourceBridge;
import com.kltyton.lumenfx.runtime.model.EffectModel;
import com.kltyton.lumenfx.runtime.model.UnityEmitterModel;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/** Active runtime instance binding an {@link EffectHandle} to Unity, Niagara, or Photon emitters. */
public final class EffectRuntimeInstance {
    private final EffectModel model;
    private final EffectHandle handle;
    private final @Nullable Object level;
    private final int resourceGeneration;
    private final List<UnityEmitterRuntime> unityEmitters;
    private final @Nullable NiagaraSystemRuntime niagaraRuntime;
    private final @Nullable PhotonFxRuntime photonRuntime;

    public EffectRuntimeInstance(EffectModel model, EffectHandle handle, @Nullable Object level,
                                 long seed, int resourceGeneration) {
        this.model = model;
        this.handle = handle;
        this.level = level;
        this.resourceGeneration = resourceGeneration;
        List<UnityEmitterRuntime> unity = new ArrayList<>();
        if (model.niagara() == null && model.photon() == null) {
            for (int i = 0; i < model.emitters().size(); i++) {
                UnityEmitterModel em = model.emitters().get(i);
                if (em.integrator() == UnityEmitterModel.IntegratorKind.NIAGARA) continue;
                UnityEmitterRuntime rt = new UnityEmitterRuntime(em, seed + i * 0x9E3779B9L);
                rt.prewarm(handle.snapshot());
                unity.add(rt);
            }
        }
        this.unityEmitters = List.copyOf(unity);
        this.niagaraRuntime = model.niagara() != null
                ? new NiagaraSystemRuntime(model.niagara(), model.materials(), seed) : null;
        this.photonRuntime = model.photon() != null
                ? new PhotonFxRuntime(model.photon(), seed) : null;
    }

    public EffectModel model() { return model; }
    public EffectHandle handle() { return handle; }
    public int resourceGeneration() { return resourceGeneration; }
    public List<UnityEmitterRuntime> unityEmitters() { return unityEmitters; }
    public @Nullable NiagaraSystemRuntime niagaraRuntime() { return niagaraRuntime; }
    public @Nullable PhotonFxRuntime photonRuntime() { return photonRuntime; }

    public int totalParticles() {
        int sum = 0;
        for (UnityEmitterRuntime u : unityEmitters) sum += u.particles.size();
        if (niagaraRuntime != null) sum += niagaraRuntime.totalParticles();
        if (photonRuntime != null) sum += photonRuntime.totalParticles();
        return sum;
    }

    public int liveGpuParticles() {
        return niagaraRuntime != null ? niagaraRuntime.totalLiveGpuParticles() : 0;
    }

    public boolean tick(float dtSeconds, int currentGeneration, @Nullable McResourceBridge bridge) {
        if (!handle.isAlive() || resourceGeneration != currentGeneration) {
            handle.markDead();
            return false;
        }
        EffectHandle.Snapshot snap = handle.snapshot();
        boolean stopping = handle.isStopping();
        boolean anyActive = false;

        for (UnityEmitterRuntime u : unityEmitters) {
            u.step(snap, dtSeconds, stopping, level, bridge);
            if (!u.isDone(stopping)) anyActive = true;
        }
        if (niagaraRuntime != null) {
            niagaraRuntime.tick(handle, snap, dtSeconds, stopping);
            if (!niagaraRuntime.isDone(stopping)) anyActive = true;
        }
        if (photonRuntime != null) {
            if (photonRuntime.tick(snap, dtSeconds, stopping)) anyActive = true;
        }

        if (!anyActive) {
            handle.markDead();
            return false;
        }
        return true;
    }
}
