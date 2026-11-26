package com.kltyton.lumenfx.runtime.simulation;

import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.runtime.audio.DirectSpatialAudioEngine;
import com.kltyton.lumenfx.runtime.audio.DirectSpatialAudioEngine.SoundCueSpec;
import com.kltyton.lumenfx.runtime.bridge.McResourceBridge;
import com.kltyton.lumenfx.runtime.model.EffectModel;
import com.kltyton.lumenfx.runtime.model.UnityEmitterModel;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.world.phys.Vec3;

/** Active runtime instance binding an {@link EffectHandle} to Unity, Niagara, Photon, visual attachments, and 3D spatial audio. */
public final class EffectRuntimeInstance {
    private final EffectModel model;
    private final EffectHandle handle;
    private final @Nullable Object level;
    private final int resourceGeneration;
    private final List<UnityEmitterRuntime> unityEmitters;
    private final @Nullable NiagaraSystemRuntime niagaraRuntime;
    private final @Nullable PhotonFxRuntime photonRuntime;
    private final boolean[] audioTriggered;
    private final int[] audioVoices;
    private float elapsedSeconds;
    private double lastAudioX, lastAudioY, lastAudioZ;
    private boolean hasLastAudioPos;

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
        int cueCount = model.audioCues().size();
        this.audioTriggered = new boolean[cueCount];
        this.audioVoices = new int[cueCount];
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

    public boolean requiresOwnerThread(@Nullable McResourceBridge bridge) {
        if (level == null || bridge == null) return false;
        if (!unityEmitters.isEmpty()) return true;
        return photonRuntime != null && photonRuntime.requiresOwnerThread();
    }

    public int estimatedWorkload() {
        int sum = 4;
        for (UnityEmitterRuntime u : unityEmitters) sum += Math.max(4, u.particles.size());
        if (niagaraRuntime != null) sum += niagaraRuntime.estimatedWorkload();
        if (photonRuntime != null) sum += Math.max(4, photonRuntime.totalParticles());
        return sum;
    }

    public boolean tick(float dtSeconds, int currentGeneration, @Nullable McResourceBridge bridge) {
        if (!handle.isAlive() || resourceGeneration != currentGeneration) {
            handle.markDead();
            return false;
        }
        elapsedSeconds += dtSeconds;
        EffectHandle.Snapshot snap = handle.snapshot();
        boolean stopping = handle.isStopping();
        boolean anyActive = !stopping && model.hasVisualAttachments();

        for (UnityEmitterRuntime u : unityEmitters) {
            u.step(snap, dtSeconds, stopping, level, bridge);
            if (!u.isDone(stopping)) anyActive = true;
        }
        if (niagaraRuntime != null) {
            niagaraRuntime.tick(handle, snap, dtSeconds, stopping);
            if (!niagaraRuntime.isDone(stopping)) anyActive = true;
        }
        if (photonRuntime != null) {
            if (photonRuntime.tick(snap, dtSeconds, stopping, level, bridge)) anyActive = true;
        }

        if (!anyActive) {
            handle.markDead();
            return false;
        }
        return true;
    }

    public void updateAudio(DirectSpatialAudioEngine audio, float dtSeconds, @Nullable McResourceBridge bridge) {
        if (audioVoices.length == 0) return;
        EffectHandle.Snapshot snap = handle.snapshot();
        Vec3 pos = snap.pose().position();
        double vx = 0.0, vy = 0.0, vz = 0.0;
        if (hasLastAudioPos && dtSeconds > 1e-4f) {
            vx = (pos.x - lastAudioX) / dtSeconds;
            vy = (pos.y - lastAudioY) / dtSeconds;
            vz = (pos.z - lastAudioZ) / dtSeconds;
        }
        lastAudioX = pos.x; lastAudioY = pos.y; lastAudioZ = pos.z;
        hasLastAudioPos = true;

        boolean stopping = handle.isStopping();
        List<SoundCueSpec> cues = model.audioCues();
        for (int i = 0; i < cues.size(); i++) {
            SoundCueSpec cue = cues.get(i);
            if (stopping) {
                if (audioVoices[i] != 0) {
                    audio.stopVoice(audioVoices[i]);
                    audioVoices[i] = 0;
                }
                continue;
            }
            if (!audioTriggered[i] && elapsedSeconds >= cue.delaySeconds()) {
                audioTriggered[i] = true;
                audioVoices[i] = audio.play(cue, pos.x, pos.y, pos.z, vx, vy, vz, bridge);
            } else if (audioVoices[i] != 0) {
                audio.updateVoice(audioVoices[i], pos.x, pos.y, pos.z, vx, vy, vz, cue.volume() * snap.alpha());
            }
        }
    }

    public void stopAudio(DirectSpatialAudioEngine audio) {
        for (int i = 0; i < audioVoices.length; i++) {
            if (audioVoices[i] != 0) {
                audio.stopVoice(audioVoices[i]);
                audioVoices[i] = 0;
            }
        }
    }
}
