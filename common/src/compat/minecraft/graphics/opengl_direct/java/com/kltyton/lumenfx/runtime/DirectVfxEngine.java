package com.kltyton.lumenfx.runtime;

import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.runtime.audio.DirectSpatialAudioEngine;
import com.kltyton.lumenfx.runtime.bridge.McResourceBridge;
import com.kltyton.lumenfx.runtime.concurrent.DirectFrameExecutor;
import com.kltyton.lumenfx.runtime.gl.DirectGlEffectRenderer;
import com.kltyton.lumenfx.runtime.model.EffectModel;
import com.kltyton.lumenfx.runtime.model.PhotonFxModel;
import com.kltyton.lumenfx.runtime.model.ProductionEffectParser;
import com.kltyton.lumenfx.runtime.simulation.EffectRuntimeInstance;
import com.kltyton.lumenfx.runtime.simulation.NiagaraSystemRuntime;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Unified direct OpenGL 3.3+ / 4.6 VFX runtime for Minecraft 1.20.1, 1.21.1, and 26.1.2.
 * Features lock-free LPT work-stealing simulation, HDR Bloom, depth soft particles, custom GLSL materials,
 * zero-readback GPU Niagara rendering, full Photon {@code .fx} simulation, Nanite, 3D Gaussian Splatting,
 * RVT, Groom hair strands, volumetric fog/decals/lights, and 3D OpenAL spatial audio.
 */
public final class DirectVfxEngine {
    public static final DirectVfxEngine INSTANCE = new DirectVfxEngine();
    private static final Logger LOGGER = LoggerFactory.getLogger("LumenFX");

    private volatile @Nullable McResourceBridge bridge;
    private final Map<String, EffectModel> effects = new ConcurrentHashMap<>();
    private final Map<String, PhotonFxModel> photonModels = new ConcurrentHashMap<>();
    private final List<EffectRuntimeInstance> instances = new CopyOnWriteArrayList<>();
    private final Queue<NiagaraSystemRuntime.EmitterState> pendingGpuDisposals = new ConcurrentLinkedQueue<>();
    private final AtomicInteger resourceGeneration = new AtomicInteger(1);
    private final AtomicBoolean rendererResetPending = new AtomicBoolean(false);
    private final DirectGlEffectRenderer renderer = new DirectGlEffectRenderer();
    private final DirectSpatialAudioEngine spatialAudio = new DirectSpatialAudioEngine();

    private DirectVfxEngine() {
        com.kltyton.lumenfx.runtime.keyframe.DirectKeyframeAdapter.install();
    }

    public boolean hasEffect(String rawId) {
        String id = canonicalId(rawId);
        return effects.containsKey(id) || photonModels.containsKey(id);
    }

    public void attachBridge(McResourceBridge bridge) { this.bridge = bridge; }
    public @Nullable McResourceBridge bridge() { return bridge; }
    public int resourceGeneration() { return resourceGeneration.get(); }
    public int loadedEffectCount() { return effects.size(); }
    public int activeInstanceCount() { return instances.size(); }
    public DirectGlEffectRenderer renderer() { return renderer; }
    public DirectSpatialAudioEngine spatialAudio() { return spatialAudio; }

    public int liveGpuParticleCount() {
        int sum = 0;
        for (EffectRuntimeInstance inst : instances) sum += inst.liveGpuParticles();
        return sum;
    }

    public int totalActiveParticles() {
        int sum = 0;
        for (EffectRuntimeInstance inst : instances) sum += inst.totalParticles();
        return sum;
    }

    public @Nullable EffectRuntimeInstance runtimeFor(EffectHandle handle) {
        for (EffectRuntimeInstance inst : instances) {
            if (inst.handle() == handle) return inst;
        }
        return null;
    }

    public @Nullable EffectModel effectModel(String rawId) { return effects.get(canonicalId(rawId)); }
    public @Nullable PhotonFxModel photonModel(String rawId) { return photonModels.get(canonicalId(rawId)); }

    public void registerEffect(EffectModel model) {
        String id = canonicalId(model.id());
        effects.put(id, model);
        if (model.photon() != null) photonModels.put(id, model.photon());
    }

    public void reloadResources() {
        int gen = resourceGeneration.incrementAndGet();
        clearLevel();
        com.kltyton.lumenfx.client.keyframe.KeyframeEffectManager.onResourceReload();
        effects.clear();
        photonModels.clear();
        rendererResetPending.set(true);

        McResourceBridge b = this.bridge;
        if (b == null) return;

        int unityCount = 0;
        int niagaraCount = 0;
        int photonCount = 0;

        Map<String, byte[]> effectFiles = b.listResources("lumenfx/effects", ".json");
        for (Map.Entry<String, byte[]> entry : effectFiles.entrySet()) {
            String effectId = stripPrefixAndSuffix(entry.getKey(), "lumenfx/effects/", ".json");
            try {
                String json = new String(entry.getValue(), StandardCharsets.UTF_8);
                EffectModel parsed = ProductionEffectParser.parse(effectId, json, b);
                effects.put(effectId, parsed);
                if (parsed.niagara() != null) niagaraCount++;
                else unityCount++;
            } catch (RuntimeException ex) {
                LOGGER.warn("LumenFX failed to parse effect {}: {}", effectId, ex.getMessage());
            }
        }

        Map<String, byte[]> fxFiles = b.listResources("fx", ".fx");
        for (Map.Entry<String, byte[]> entry : fxFiles.entrySet()) {
            String fxId = stripPrefixAndSuffix(entry.getKey(), "fx/", ".fx");
            try {
                PhotonFxModel photon = PhotonFxModel.parseNbt(fxId, entry.getValue(), b);
                if (photon != null) {
                    photonModels.put(fxId, photon);
                    effects.putIfAbsent(fxId, EffectModel.fromPhoton(photon));
                    photonCount++;
                }
            } catch (RuntimeException ex) {
                LOGGER.warn("LumenFX failed to parse Photon FX {}: {}", fxId, ex.getMessage());
            }
        }

        LOGGER.info("LumenFX direct OpenGL runtime ({}/{}) loaded {} VFX assets (unity={}, niagara={}, photon={}, generation={})",
                b.mcVersion(), b.loaderName(), effects.size(), unityCount, niagaraCount, photonCount, gen);
    }

    public EffectHandle spawn(@Nullable Object level, String rawEffectId, Vec3 position, long seed) {
        String id = canonicalId(rawEffectId);
        EffectModel model = resolveOrLoad(id);
        EffectHandle handle = new EffectHandle(position);
        if (model == null) {
            handle.markDead();
            return handle;
        }
        EffectRuntimeInstance inst = new EffectRuntimeInstance(model, handle, level, seed, resourceGeneration.get());
        instances.add(inst);
        return handle;
    }

    public @Nullable EffectModel resolveOrLoad(String rawEffectId) {
        String id = canonicalId(rawEffectId);
        EffectModel cached = effects.get(id);
        if (cached != null) return cached;
        McResourceBridge b = this.bridge;
        if (b == null) return null;

        int colon = id.indexOf(':');
        String ns = id.substring(0, colon);
        String path = id.substring(colon + 1);

        byte[] jsonBytes = b.readResource(ns + ":lumenfx/effects/" + path + ".json");
        if (jsonBytes != null) {
            try {
                EffectModel parsed = ProductionEffectParser.parse(id, new String(jsonBytes, StandardCharsets.UTF_8), b);
                effects.put(id, parsed);
                return parsed;
            } catch (RuntimeException ex) {
                LOGGER.warn("LumenFX failed on-demand parse of {}: {}", id, ex.getMessage());
            }
        }
        byte[] fxBytes = b.readResource(ns + ":fx/" + path + ".fx");
        if (fxBytes != null) {
            try {
                PhotonFxModel photon = PhotonFxModel.parseNbt(id, fxBytes, b);
                if (photon != null) {
                    photonModels.put(id, photon);
                    EffectModel wrapped = EffectModel.fromPhoton(photon);
                    effects.put(id, wrapped);
                    return wrapped;
                }
            } catch (RuntimeException ex) {
                LOGGER.warn("LumenFX failed on-demand parse of Photon {}: {}", id, ex.getMessage());
            }
        }
        return null;
    }

    public void tickClient() {
        tickClient(0.05f);
    }

    public void tickClient(float dtSeconds) {
        if (instances.isEmpty()) return;
        int gen = resourceGeneration.get();
        McResourceBridge b = this.bridge;
        List<EffectRuntimeInstance> snapshot = new ArrayList<>(instances);

        List<EffectRuntimeInstance> ownerOnly = null;
        List<EffectRuntimeInstance> workerSafe = null;
        int workerEstimatedWork = 0;

        for (EffectRuntimeInstance inst : snapshot) {
            if (inst.requiresOwnerThread(b)) {
                if (ownerOnly == null) ownerOnly = new ArrayList<>();
                ownerOnly.add(inst);
            } else {
                if (workerSafe == null) workerSafe = new ArrayList<>();
                workerSafe.add(inst);
                workerEstimatedWork += inst.estimatedWorkload();
            }
        }

        List<EffectRuntimeInstance> dead = null;
        int workerCount = workerSafe != null ? workerSafe.size() : 0;
        int partitions = workerCount > 0
                ? DirectFrameExecutor.INSTANCE.partitions(workerCount, workerEstimatedWork) : 1;

        if (workerCount > 0 && (partitions > 1 || (ownerOnly != null && !ownerOnly.isEmpty() && DirectFrameExecutor.INSTANCE.maximumWorkers() > 1))) {
            // LPT order: heaviest worker-safe instances first so stragglers never tail-stall frame sync
            if (workerCount > 1) {
                workerSafe.sort((x, y) -> Integer.compare(y.estimatedWorkload(), x.estimatedWorkload()));
            }
            final List<EffectRuntimeInstance> ws = workerSafe;
            final boolean[] wsAlive = new boolean[workerCount];
            final List<EffectRuntimeInstance> oo = ownerOnly;
            final boolean[] ooAlive = oo != null ? new boolean[oo.size()] : null;
            int effPartitions = Math.max(partitions, oo != null && !oo.isEmpty() ? Math.min(DirectFrameExecutor.INSTANCE.maximumWorkers(), workerCount + 1) : 1);

            Runnable ownerWork = oo == null ? null : () -> {
                for (int i = 0; i < oo.size(); i++) {
                    ooAlive[i] = oo.get(i).tick(dtSeconds, gen, b);
                }
            };
            DirectFrameExecutor.INSTANCE.executeIndexed(workerCount, effPartitions, ownerWork,
                    (part, idx) -> wsAlive[idx] = ws.get(idx).tick(dtSeconds, gen, b));

            for (int i = 0; i < workerCount; i++) {
                if (!wsAlive[i]) {
                    if (dead == null) dead = new ArrayList<>();
                    dead.add(ws.get(i));
                }
            }
            if (oo != null) {
                for (int i = 0; i < oo.size(); i++) {
                    if (!ooAlive[i]) {
                        if (dead == null) dead = new ArrayList<>();
                        dead.add(oo.get(i));
                    }
                }
            }
        } else {
            for (EffectRuntimeInstance inst : snapshot) {
                if (!inst.tick(dtSeconds, gen, b)) {
                    if (dead == null) dead = new ArrayList<>();
                    dead.add(inst);
                }
            }
        }

        if (dead != null) {
            instances.removeAll(dead);
            for (EffectRuntimeInstance inst : dead) {
                inst.stopAudio(spatialAudio);
                enqueueGpuDisposal(inst);
            }
        }
        boolean anyAudio = false;
        for (EffectRuntimeInstance inst : instances) {
            if (!inst.model().audioCues().isEmpty()) {
                inst.updateAudio(spatialAudio, dtSeconds, b);
                anyAudio = true;
            }
        }
        if (anyAudio || spatialAudio.activeVoiceCount() > 0) {
            spatialAudio.reapFinishedVoices();
        }
    }

    public void renderLevel(Matrix4f projection, Matrix4f modelView,
                            double camX, double camY, double camZ, float partialTick) {
        if (rendererResetPending.compareAndSet(true, false)) {
            pendingGpuDisposals.clear();
            renderer.onResourcesReloaded();
        } else {
            NiagaraSystemRuntime.EmitterState stale;
            while ((stale = pendingGpuDisposals.poll()) != null) {
                renderer.gpuRunner().disposeEmitter(stale);
            }
        }
        if (instances.isEmpty()) return;
        renderer.render(instances, projection, modelView, camX, camY, camZ, partialTick, bridge);
    }

    public void clearLevel() {
        com.kltyton.lumenfx.client.keyframe.KeyframeEffectManager.onLevelUnload();
        for (EffectRuntimeInstance inst : instances) {
            inst.handle().markDead();
            inst.stopAudio(spatialAudio);
            enqueueGpuDisposal(inst);
        }
        instances.clear();
        spatialAudio.stopAll();
    }

    private void enqueueGpuDisposal(EffectRuntimeInstance inst) {
        NiagaraSystemRuntime nr = inst.niagaraRuntime();
        if (nr == null) return;
        for (NiagaraSystemRuntime.EmitterState e : nr.emitters()) {
            if (e.spec.isGpu()) pendingGpuDisposals.add(e);
        }
    }

    public void shutdown() {
        clearLevel();
        spatialAudio.dispose();
        DirectFrameExecutor.INSTANCE.close();
        renderer.dispose();
    }

    public static String canonicalId(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (!s.contains(":")) s = "lumenfx:" + s;
        int colon = s.indexOf(':');
        String ns = s.substring(0, colon);
        String path = s.substring(colon + 1);
        if (path.startsWith("lumenfx/effects/")) path = path.substring("lumenfx/effects/".length());
        if (path.startsWith("fx/")) path = path.substring("fx/".length());
        if (path.endsWith(".json")) path = path.substring(0, path.length() - 5);
        if (path.endsWith(".fx")) path = path.substring(0, path.length() - 3);
        return ns + ":" + path;
    }

    private static String stripPrefixAndSuffix(String resourceId, String prefix, String suffix) {
        int colon = resourceId.indexOf(':');
        String ns = colon >= 0 ? resourceId.substring(0, colon) : "lumenfx";
        String path = colon >= 0 ? resourceId.substring(colon + 1) : resourceId;
        if (path.startsWith(prefix)) path = path.substring(prefix.length());
        if (path.endsWith(suffix)) path = path.substring(0, path.length() - suffix.length());
        return ns + ":" + path;
    }
}
