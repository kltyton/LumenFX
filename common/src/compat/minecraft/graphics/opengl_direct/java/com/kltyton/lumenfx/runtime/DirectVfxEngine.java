package com.kltyton.lumenfx.runtime;

import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.runtime.bridge.McResourceBridge;
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
 * Supports 26.2-format Unity Shuriken effects, UE5 Niagara CPU/GPU SPIR-V effects,
 * real {@code .lfmesh} static meshes, and Photon 26.2 {@code .fx} authored-model assets.
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

    private DirectVfxEngine() { }

    public void attachBridge(McResourceBridge bridge) {
        this.bridge = bridge;
    }

    public @Nullable McResourceBridge bridge() {
        return bridge;
    }

    public int resourceGeneration() {
        return resourceGeneration.get();
    }

    public int loadedEffectCount() {
        return effects.size();
    }

    public int activeInstanceCount() {
        return instances.size();
    }

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

    public DirectGlEffectRenderer renderer() {
        return renderer;
    }

    public @Nullable EffectRuntimeInstance runtimeFor(EffectHandle handle) {
        for (EffectRuntimeInstance inst : instances) {
            if (inst.handle() == handle) return inst;
        }
        return null;
    }

    public @Nullable EffectModel effectModel(String rawId) {
        return effects.get(canonicalId(rawId));
    }

    public @Nullable PhotonFxModel photonModel(String rawId) {
        return photonModels.get(canonicalId(rawId));
    }

    public void registerEffect(EffectModel model) {
        String id = canonicalId(model.id());
        effects.put(id, model);
        if (model.photon() != null) photonModels.put(id, model.photon());
    }

    public void reloadResources() {
        int gen = resourceGeneration.incrementAndGet();
        clearLevel();
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
                photonModels.put(fxId, photon);
                effects.putIfAbsent(fxId, EffectModel.fromPhoton(photon));
                photonCount++;
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
                photonModels.put(id, photon);
                EffectModel wrapped = EffectModel.fromPhoton(photon);
                effects.put(id, wrapped);
                return wrapped;
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
        int gen = resourceGeneration.get();
        McResourceBridge b = this.bridge;
        List<EffectRuntimeInstance> dead = null;
        for (EffectRuntimeInstance inst : instances) {
            if (!inst.tick(dtSeconds, gen, b)) {
                if (dead == null) dead = new ArrayList<>();
                dead.add(inst);
            }
        }
        if (dead != null) {
            instances.removeAll(dead);
            for (EffectRuntimeInstance inst : dead) {
                enqueueGpuDisposal(inst);
            }
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
        for (EffectRuntimeInstance inst : instances) {
            inst.handle().markDead();
            enqueueGpuDisposal(inst);
        }
        instances.clear();
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
