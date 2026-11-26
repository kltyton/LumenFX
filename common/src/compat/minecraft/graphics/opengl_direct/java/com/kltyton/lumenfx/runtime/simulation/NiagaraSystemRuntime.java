package com.kltyton.lumenfx.runtime.simulation;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache.Buffer;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache.EmitterFrame;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache.Frame;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache.Layout;
import com.kltyton.lumenfx.math.SourceRandomStream;
import com.kltyton.lumenfx.runtime.gl.DirectGlNiagaraGpuRunner;
import com.kltyton.lumenfx.runtime.model.MaterialModel;
import com.kltyton.lumenfx.runtime.model.NiagaraSystemModel;
import com.kltyton.lumenfx.runtime.model.NiagaraSystemModel.AttributeSpec;
import com.kltyton.lumenfx.runtime.model.NiagaraSystemModel.BindingSpec;
import com.kltyton.lumenfx.runtime.model.NiagaraSystemModel.EmitterSpec;
import com.kltyton.lumenfx.runtime.model.NiagaraSystemModel.RendererSpec;
import com.kltyton.lumenfx.runtime.model.NiagaraSystemModel.VariableSpec;
import com.kltyton.lumenfx.runtime.niagara.DirectNiagaraFrame;
import com.kltyton.lumenfx.runtime.niagara.DirectVectorDataSet;
import com.kltyton.lumenfx.runtime.niagara.DirectVectorProgram;
import com.kltyton.lumenfx.runtime.niagara.DirectVectorVm;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;
import net.minecraft.world.phys.Vec3;

/**
 * Full 26.2-parity Niagara system runtime for direct OpenGL targets:
 * <ul>
 *   <li>Pre-compiled parameter binding plans & O(1) dataset swaps eliminating per-frame string lookups and array copies.</li>
 *   <li>Real compiled VectorVM execution, OpenGL 4.6 SPIR-V GPU compute dispatches, analytical Stateless emitters,
 *       and full {@code .lfsc} {@link NiagaraSimulationCache} playback & live GPU handoff.</li>
 * </ul>
 */
public final class NiagaraSystemRuntime {
    private static final int CPU_RENDER_SYNC_CAP = 65536;

    public record SpawnInvocation(int count, float interpStartDt, float intervalDt, int spawnGroup) { }

    public record GpuTickRequest(float deltaTime, float age, int tickCounter,
                                 List<SpawnInvocation> spawns, boolean reset,
                                 Vec3 origin, int randomSeed,
                                 byte[] currentRuntime640, byte[] previousRuntime640,
                                 @Nullable DirectVectorDataSet cacheUpload,
                                 @Nullable int[] cacheIdTable,
                                 int cacheTotalSpawned,
                                 int cacheAcquireTag) { }

    private static final int PARAM_EXEC_COUNT = 1;
    private static final int PARAM_SPAWN_INTERVAL = 2;
    private static final int PARAM_INTERP_START = 3;
    private static final int PARAM_SPAWN_GROUP = 4;
    private static final int PARAM_SYS_FLOATS = 5;
    private static final int PARAM_SYS_INTS = 6;

    private record ParameterBindingPlan(int[] kinds, int[] offsets, int[] srcStarts, int[] counts) {
        static ParameterBindingPlan compile(DirectVectorProgram prog, Map<String, VariableSpec> systemVars, int constantLen) {
            int max = prog.parameters.size();
            int[] kinds = new int[max];
            int[] offsets = new int[max];
            int[] srcStarts = new int[max];
            int[] counts = new int[max];
            int n = 0;
            for (DirectVectorProgram.Parameter p : prog.parameters) {
                int offset = prog.runtimeBytes + p.offset();
                if (offset < 0 || offset + p.bytes() > constantLen) continue;
                String name = p.name();
                if (name.equalsIgnoreCase("Engine.ExecutionCount")) {
                    kinds[n] = PARAM_EXEC_COUNT; offsets[n] = offset; n++;
                } else if (name.endsWith(".SpawnInterval")) {
                    kinds[n] = PARAM_SPAWN_INTERVAL; offsets[n] = offset; n++;
                } else if (name.endsWith(".InterpSpawnStartDt")) {
                    kinds[n] = PARAM_INTERP_START; offsets[n] = offset; n++;
                } else if (name.endsWith(".SpawnGroup")) {
                    kinds[n] = PARAM_SPAWN_GROUP; offsets[n] = offset; n++;
                } else {
                    VariableSpec vs = systemVars.get(name.toLowerCase(Locale.ROOT));
                    if (vs != null) {
                        if (vs.floatCount() > 0 && p.bytes() == vs.floatCount() * 4) {
                            kinds[n] = PARAM_SYS_FLOATS; offsets[n] = offset;
                            srcStarts[n] = vs.floatStart(); counts[n] = vs.floatCount(); n++;
                        } else if (vs.intCount() > 0 && p.bytes() == vs.intCount() * 4) {
                            kinds[n] = PARAM_SYS_INTS; offsets[n] = offset;
                            srcStarts[n] = vs.intStart(); counts[n] = vs.intCount(); n++;
                        }
                    }
                }
            }
            if (n < max) {
                return new ParameterBindingPlan(
                        java.util.Arrays.copyOf(kinds, n),
                        java.util.Arrays.copyOf(offsets, n),
                        java.util.Arrays.copyOf(srcStarts, n),
                        java.util.Arrays.copyOf(counts, n));
            }
            return new ParameterBindingPlan(kinds, offsets, srcStarts, counts);
        }
    }

    private record StatelessParams(float spawnRate, int burstCount, float lifetime, float speed,
                                   float gravity, float drag, float radius, float vortex,
                                   float sizeStart, float sizeEnd,
                                   float r0, float g0, float b0, float a0,
                                   float r1, float g1, float b1, float a1) {
        static @Nullable StatelessParams parse(EmitterSpec spec, JsonObject rawNiagara) {
            if (spec.simTarget() != NiagaraSystemModel.SimTarget.STATELESS) return null;
            JsonObject st = null;
            if (rawNiagara != null && rawNiagara.has("emitters") && rawNiagara.get("emitters").isJsonArray()) {
                for (var el : rawNiagara.getAsJsonArray("emitters")) {
                    if (el.isJsonObject()) {
                        JsonObject em = el.getAsJsonObject();
                        String n = em.has("name") ? em.get("name").getAsString() : "";
                        if ((n.equals(spec.name()) || spec.name().isEmpty()) && em.has("stateless") && em.get("stateless").isJsonObject()) {
                            st = em.getAsJsonObject("stateless");
                            break;
                        }
                    }
                }
            }
            float spawnRate = st != null && st.has("spawn_rate") ? st.get("spawn_rate").getAsFloat()
                    : Math.max(0f, spec.explicitSpawnRate() > 0f ? spec.explicitSpawnRate() : 20f);
            int burst = st != null && st.has("burst_count") ? st.get("burst_count").getAsInt() : 0;
            float life = st != null && st.has("lifetime") ? st.get("lifetime").getAsFloat() : Math.max(0.25f, spec.defaultLifetime());
            float speed = st != null && st.has("speed") ? st.get("speed").getAsFloat() : 2.0f;
            float grav = st != null && st.has("gravity") ? st.get("gravity").getAsFloat() : -4.5f;
            float drag = st != null && st.has("drag") ? st.get("drag").getAsFloat() : 0.1f;
            float rad = st != null && st.has("radius") ? st.get("radius").getAsFloat() : 0.25f;
            float vortex = st != null && st.has("vortex_strength") ? st.get("vortex_strength").getAsFloat() : 0f;
            float s0 = st != null && st.has("size_start") ? st.get("size_start").getAsFloat() : 0.35f;
            float s1 = st != null && st.has("size_end") ? st.get("size_end").getAsFloat() : 0.05f;
            float[] c0 = readColor(st, "color_start", 1f, 1f, 1f, 1f);
            float[] c1 = readColor(st, "color_end", c0[0], c0[1], c0[2], 0f);
            return new StatelessParams(spawnRate, burst, life, speed, grav, drag, rad, vortex,
                    s0, s1, c0[0], c0[1], c0[2], c0[3], c1[0], c1[1], c1[2], c1[3]);
        }

        private static float[] readColor(@Nullable JsonObject o, String key, float dr, float dg, float db, float da) {
            if (o != null && o.has(key) && o.get(key).isJsonArray()) {
                JsonArray a = o.getAsJsonArray(key);
                if (a.size() >= 4) return new float[]{a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat(), a.get(3).getAsFloat()};
                if (a.size() == 3) return new float[]{a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat(), da};
            }
            return new float[]{dr, dg, db, da};
        }
    }

    public static final class EmitterState {
        public final EmitterSpec spec;
        public final RendererSpec primaryRenderer;
        public final MaterialModel material;
        public final List<ParticleInstance> cpuParticles = new ArrayList<>(64);
        private final ArrayDeque<ParticleInstance> particlePool = new ArrayDeque<>(128);
        public final List<GpuTickRequest> pendingGpuTicks = new ArrayList<>(4);
        public final AtomicInteger liveGpuParticleCount = new AtomicInteger(0);
        public final AtomicInteger liveGpuCapacity = new AtomicInteger(0);
        public final AtomicInteger liveGpuTotalSpawned = new AtomicInteger(0);
        public final AtomicInteger liveGpuAcquireTag = new AtomicInteger(0);
        public volatile List<ParticleInstance> gpuReadbackParticles = List.of();

        public final DirectVectorDataSet current;
        private final DirectVectorDataSet destination;
        private final @Nullable DirectVectorVm spawnVm;
        private final @Nullable DirectVectorVm updateVm;
        private final @Nullable ParameterBindingPlan spawnPlan;
        private final @Nullable ParameterBindingPlan updatePlan;
        private final @Nullable StatelessParams statelessParams;
        private final byte[] runtime640 = new byte[640];
        private final byte[] oldRuntime640 = new byte[640];
        private final ByteBuffer runtimeWords = ByteBuffer.wrap(runtime640).order(ByteOrder.LITTLE_ENDIAN);
        private final @Nullable byte[] spawnConstants;
        private final @Nullable ByteBuffer spawnWords;
        private final @Nullable byte[] updateConstants;
        private final @Nullable ByteBuffer updateWords;
        private final List<VariableSpec> spawnBindings;
        private final @Nullable VariableSpec executionStateBinding;
        private final int posComp, colComp, sizeComp, widthComp, rotComp, ageComp, lifeComp, materialRandomComp;

        private final SourceRandomStream rng;
        private final int instanceSeed;
        private float age;
        private float explicitSpawnCarry;
        private int tickCounter;
        private int totalSpawned;
        private boolean first = true;
        private boolean resetPending = true;
        private boolean statelessBurstFired;

        private int simCacheCapacityLimit = -1;
        private int cachedTotalSpawned;
        private int cachedAcquireTag;
        private int[] liveIdTable = new int[0];
        private boolean cacheActiveLastTick;
        private boolean pendingGpuCacheUpload;

        EmitterState(EmitterSpec spec, Map<String, MaterialModel> materials,
                     Map<String, VariableSpec> systemVars, JsonObject rawNiagara, int seed) {
            this.spec = spec;
            this.primaryRenderer = spec.renderers().isEmpty()
                    ? new RendererSpec(NiagaraSystemModel.RendererKind.SPRITE, spec.name(), "", null, null, Map.of())
                    : spec.renderers().get(0);
            this.material = materials.getOrDefault(primaryRenderer.materialName(),
                    MaterialModel.defaultMaterial(primaryRenderer.materialName()));
            this.rng = new SourceRandomStream(seed);
            this.instanceSeed = seed;
            this.current = new DirectVectorDataSet(spec.floatComponents(), spec.intComponents(), spec.halfComponents());
            this.destination = new DirectVectorDataSet(spec.floatComponents(), spec.intComponents(), spec.halfComponents());
            this.spawnVm = spec.spawnProgram() != null ? new DirectVectorVm(spec.spawnProgram()) : null;
            this.updateVm = spec.updateProgram() != null ? new DirectVectorVm(spec.updateProgram()) : null;
            this.spawnConstants = spec.spawnProgram() != null ? spec.spawnProgram().constantData.clone() : null;
            this.spawnWords = spawnConstants != null ? ByteBuffer.wrap(spawnConstants).order(ByteOrder.LITTLE_ENDIAN) : null;
            this.updateConstants = spec.updateProgram() != null ? spec.updateProgram().constantData.clone() : null;
            this.updateWords = updateConstants != null ? ByteBuffer.wrap(updateConstants).order(ByteOrder.LITTLE_ENDIAN) : null;
            this.spawnPlan = spec.spawnProgram() != null && spawnConstants != null
                    ? ParameterBindingPlan.compile(spec.spawnProgram(), systemVars, spawnConstants.length) : null;
            this.updatePlan = spec.updateProgram() != null && updateConstants != null
                    ? ParameterBindingPlan.compile(spec.updateProgram(), systemVars, updateConstants.length) : null;
            this.statelessParams = StatelessParams.parse(spec, rawNiagara);

            List<VariableSpec> sb = new ArrayList<>();
            for (String varName : spec.spawnInfoVariables()) {
                VariableSpec vs = findVar(systemVars, varName);
                if (vs != null) sb.add(vs);
            }
            this.spawnBindings = List.copyOf(sb);
            this.executionStateBinding = findVar(systemVars, spec.sourceName() + ".ExecutionState");

            this.posComp = findAttr(spec.attributes(), 0, "Position");
            this.colComp = findAttr(spec.attributes(), 0, "Color");
            this.sizeComp = findAttr(spec.attributes(), 0, "SpriteSize");
            this.widthComp = findAttr(spec.attributes(), 0, "RibbonWidth");
            this.rotComp = findAttr(spec.attributes(), 0, "SpriteRotation");
            this.ageComp = findAttr(spec.attributes(), 0, "Age");
            this.lifeComp = findAttr(spec.attributes(), 0, "Lifetime");
            this.materialRandomComp = findAttr(spec.attributes(), 0, "MaterialRandom");

            runtimeWords.putFloat(616, 1.0f);
        }

        ParticleInstance borrowParticle() {
            ParticleInstance p = particlePool.pollFirst();
            if (p == null) return new ParticleInstance();
            p.curveRandom = p.customX = p.customY = p.customZ = p.customW = 0f;
            return p;
        }

        void clearCpuParticlesToPool() {
            int n = cpuParticles.size();
            for (int i = 0; i < n; i++) {
                if (particlePool.size() < 4096) particlePool.addFirst(cpuParticles.get(i));
            }
            cpuParticles.clear();
        }

        public List<ParticleInstance> renderableParticles() {
            if (spec.isGpu()) {
                return cacheActiveLastTick ? cpuParticles : gpuReadbackParticles;
            }
            return cpuParticles;
        }

        public boolean isCacheActiveLastTick() {
            return cacheActiveLastTick;
        }

        public int particleCount() {
            if (spec.isGpu()) {
                return liveGpuParticleCount.get();
            }
            return Math.max(current.count(), cpuParticles.size());
        }

        public boolean hasPendingGpuTicks() {
            synchronized (pendingGpuTicks) {
                return !pendingGpuTicks.isEmpty();
            }
        }

        public void setSimCacheCapacityLimit(int limit) {
            this.simCacheCapacityLimit = Math.max(1, limit);
        }

        public int cachedTotalSpawned() { return cachedTotalSpawned; }
        public int cachedAcquireTag() { return cachedAcquireTag; }
        public void syncGpuAcquireTag(int tag) {
            if (Integer.compareUnsigned(tag, cachedAcquireTag) > 0) {
                cachedAcquireTag = tag;
            }
            liveGpuAcquireTag.set(cachedAcquireTag);
        }
        public int[] liveIdTableSnapshot() { return liveIdTable.clone(); }
        public int lookupIdRow(int id) {
            return (id >= 0 && id < liveIdTable.length) ? liveIdTable[id] : -1;
        }

        public float readFloatAttribute(String name, int row) {
            int comp = findAttr(spec.attributes(), 0, name);
            if (comp < 0 || row < 0 || row >= current.count()) return Float.NaN;
            return current.floating(comp, row);
        }

        public int readIntAttribute(String name, int row) {
            int comp = findAttr(spec.attributes(), 1, name);
            if (comp < 0 || row < 0 || row >= current.count()) return 0;
            return current.integer(comp, row);
        }

        public float readHalfAttribute(String name, int row) {
            int comp = findAttr(spec.attributes(), 2, name);
            if (comp < 0 || row < 0 || row >= current.count()) return Float.NaN;
            return current.half(comp, row);
        }
    }

    private final NiagaraSystemModel model;
    private final List<EmitterState> emitters;
    private final Map<String, VariableSpec> systemVars;
    private final DirectNiagaraFrame frame = new DirectNiagaraFrame();
    private final @Nullable DirectVectorVm systemSpawnVm;
    private final @Nullable DirectVectorVm systemUpdateVm;
    private DirectVectorDataSet systemCurrent;
    private DirectVectorDataSet systemDestination;
    private final DirectVectorDataSet spawnInputs;
    private final DirectVectorDataSet updateInputs;
    private final @Nullable byte[] sysSpawnConstants;
    private final @Nullable byte[] sysUpdateConstants;
    private final @Nullable byte[] sysSpawnScratch;
    private final @Nullable byte[] sysUpdateScratch;
    private final SourceRandomStream sysRng;
    private final int systemSeed;
    private boolean systemInitialized;
    private float cachePlaybackClock;
    private @Nullable NiagaraSimulationCache lastCache;

    public NiagaraSystemRuntime(NiagaraSystemModel model, Map<String, MaterialModel> materials, long seed) {
        this.model = model;
        this.systemSeed = (int) (seed ^ (seed >>> 32));
        this.sysRng = new SourceRandomStream(systemSeed);

        Map<String, VariableSpec> vars = new LinkedHashMap<>();
        for (VariableSpec v : model.variables()) {
            vars.put(v.name().toLowerCase(Locale.ROOT), v);
        }
        this.systemVars = vars;

        this.systemSpawnVm = model.systemSpawn() != null ? new DirectVectorVm(model.systemSpawn()) : null;
        this.systemUpdateVm = model.systemUpdate() != null ? new DirectVectorVm(model.systemUpdate()) : null;
        this.systemCurrent = createDataset(model.variables());
        this.systemDestination = createDataset(model.variables());
        this.spawnInputs = createDataset(model.spawnInputLayout());
        this.updateInputs = createDataset(model.updateInputLayout());
        this.sysSpawnConstants = model.systemSpawn() != null ? model.systemSpawn().constantData.clone() : null;
        this.sysUpdateConstants = model.systemUpdate() != null ? model.systemUpdate().constantData.clone() : null;
        this.sysSpawnScratch = sysSpawnConstants != null ? new byte[sysSpawnConstants.length] : null;
        this.sysUpdateScratch = sysUpdateConstants != null ? new byte[sysUpdateConstants.length] : null;

        this.emitters = new ArrayList<>(model.emitters().size());
        for (int i = 0; i < model.emitters().size(); i++) {
            EmitterSpec em = model.emitters().get(i);
            if (em.simTarget() == NiagaraSystemModel.SimTarget.DISABLED) continue;
            this.emitters.add(new EmitterState(em, materials, systemVars, model.rawNiagaraJson(), systemSeed + i * 0x9E3779B9));
        }
    }

    public NiagaraSystemModel model() { return model; }
    public List<EmitterState> emitters() { return emitters; }

    public int totalLiveGpuParticles() {
        int sum = 0;
        for (EmitterState e : emitters) {
            if (e.spec.isGpu()) sum += e.liveGpuParticleCount.get();
        }
        return sum;
    }

    public int totalParticles() {
        int sum = 0;
        for (EmitterState e : emitters) sum += e.particleCount();
        return sum;
    }

    public int estimatedWorkload() {
        int sum = 8;
        for (EmitterState e : emitters) {
            sum += e.spec.isGpu() ? 4 : Math.max(4, e.particleCount());
        }
        return sum;
    }

    public void tick(EffectHandle handle, EffectHandle.Snapshot snap, float dt, boolean stopping) {
        NiagaraSimulationCache.Playback playback = handle.niagaraCache();
        if (playback != null) {
            tickSimCache(playback, snap, dt);
            return;
        }
        if (lastCache != null) {
            lastCache = null;
            for (EmitterState e : emitters) {
                if (e.cacheActiveLastTick) {
                    e.cacheActiveLastTick = false;
                    e.pendingGpuCacheUpload = e.spec.isGpu();
                    e.resetPending = false;
                }
            }
        }
        tickLive(snap, dt, stopping);
    }

    private void tickSimCache(NiagaraSimulationCache.Playback playback, EffectHandle.Snapshot snap, float dt) {
        NiagaraSimulationCache cache = playback.cache();
        if (cache != lastCache) {
            lastCache = cache;
            cachePlaybackClock = cache.start();
        }
        float sampleAge;
        if (playback.age() != null) {
            sampleAge = playback.age();
        } else {
            sampleAge = cachePlaybackClock;
            cachePlaybackClock += dt;
        }

        List<Frame> frames = cache.frames();
        if (frames.isEmpty()) return;
        int idxA = 0;
        for (int i = 0; i < frames.size(); i++) {
            if (frames.get(i).age() <= sampleAge) idxA = i;
            else break;
        }
        int idxB = Math.min(idxA + 1, frames.size() - 1);
        Frame frameA = frames.get(idxA);
        Frame frameB = frames.get(idxB);
        float alpha = 0f;
        if (cache.interpolation() && idxB > idxA) {
            float span = frameB.age() - frameA.age();
            if (span > 1e-6f) alpha = Math.max(0f, Math.min(1f, (sampleAge - frameA.age()) / span));
        }

        for (int eIdx = 0; eIdx < emitters.size() && eIdx < frameA.emitters().size(); eIdx++) {
            EmitterState state = emitters.get(eIdx);
            Layout layout = cache.emitterLayouts().get(eIdx);
            EmitterFrame efA = frameA.emitters().get(eIdx);
            EmitterFrame efB = frameB.emitters().get(eIdx);
            Buffer bufA = efA.particles();
            Buffer bufB = efB.particles();

            int rawCount = bufA.count();
            int deviceCap = state.simCacheCapacityLimit > 0
                    ? state.simCacheCapacityLimit
                    : DirectGlNiagaraGpuRunner.deviceMaximumParticleCapacity(state.spec);
            int cap = Math.min(state.spec.declaredMaxParticles(), deviceCap);
            int retained = Math.max(0, Math.min(rawCount, cap));

            state.cachedTotalSpawned = Math.max(efA.totalSpawned(), rawCount);
            state.totalSpawned = state.cachedTotalSpawned;
            if (Integer.compareUnsigned(bufA.acquireTag(), state.cachedAcquireTag) > 0) {
                state.cachedAcquireTag = bufA.acquireTag();
            }
            state.cacheActiveLastTick = true;

            state.current.clear();
            state.current.count(retained);

            int copyFloats = Math.min(state.current.componentCount(0), layout.floatCount());
            int copyInts = Math.min(state.current.componentCount(1), layout.integerCount());
            int copyHalves = Math.min(state.current.componentCount(2), layout.halfCount());

            int[] nextRows = bufA.nextRows();
            boolean canInterp = alpha > 1e-5f && nextRows.length == rawCount && bufB.count() > 0;

            for (int c = 0; c < copyFloats; c++) {
                int[] dst = state.current.component(0, c);
                for (int r = 0; r < retained; r++) {
                    float va = bufA.floating(c, r);
                    if (canInterp) {
                        int nr = nextRows[r];
                        if (nr >= 0 && nr < bufB.count()) {
                            float vb = bufB.floating(c, nr);
                            va = va + (vb - va) * alpha;
                        }
                    }
                    dst[r] = Float.floatToRawIntBits(va);
                }
            }
            for (int c = 0; c < copyInts; c++) {
                int[] dst = state.current.component(1, c);
                for (int r = 0; r < retained; r++) {
                    dst[r] = bufA.integer(c, r);
                }
            }
            for (int c = 0; c < copyHalves; c++) {
                int[] dst = state.current.component(2, c);
                for (int r = 0; r < retained; r++) {
                    dst[r] = Short.toUnsignedInt(bufA.halves()[c * rawCount + r]);
                }
            }

            int[] srcIds = bufA.idTable();
            int[] outIds = new int[srcIds.length];
            for (int i = 0; i < srcIds.length; i++) {
                int mappedRow = srcIds[i];
                outIds[i] = (mappedRow >= 0 && mappedRow < retained) ? mappedRow : -1;
            }
            state.liveIdTable = outIds;

            if (state.spec.isGpu()) {
                state.liveGpuParticleCount.set(retained);
            }
            syncRenderParticlesFromDataset(state, snap);
        }
    }

    private void tickLive(EffectHandle.Snapshot snap, float dt, boolean stopping) {
        int totalBefore = totalParticles();
        frame.prepare(dt, snap.pose(), !stopping, emitters.size(), emitters.size(), totalBefore, systemSeed);

        for (EmitterState e : emitters) {
            System.arraycopy(e.runtime640, 0, e.oldRuntime640, 0, 640);
            System.arraycopy(frame.current, 0, e.runtime640, 0, 608);
            e.runtimeWords.putInt(608, e.particleCount());
            e.runtimeWords.putInt(612, e.totalSpawned);
            e.runtimeWords.putFloat(616, 1.0f);
            e.runtimeWords.putFloat(620, e.age);
            e.runtimeWords.putInt(624, e.spec.randomSeed());
            e.runtimeWords.putInt(628, e.instanceSeed);
            if (e.first) System.arraycopy(e.runtime640, 0, e.oldRuntime640, 0, 640);
        }

        if (systemSpawnVm != null && systemUpdateVm != null) {
            runSystemVm();
        }

        for (EmitterState state : emitters) {
            state.age += dt;
            List<SpawnInvocation> spawns = stopping ? List.of() : collectSpawns(state, dt);

            if (state.spec.isGpu()) {
                int sumSpawn = 0;
                for (SpawnInvocation si : spawns) sumSpawn += si.count();
                state.totalSpawned += sumSpawn;
                boolean doCacheUpload = state.pendingGpuCacheUpload;
                DirectVectorDataSet upload = doCacheUpload ? cloneDataset(state.current) : null;
                int[] idTableUpload = doCacheUpload ? state.liveIdTable.clone() : null;
                int cacheTotalSpawned = doCacheUpload ? state.cachedTotalSpawned : 0;
                int cacheAcquireTag = doCacheUpload ? state.cachedAcquireTag : 0;
                state.pendingGpuCacheUpload = false;
                if (doCacheUpload) {
                    state.current.clear();
                    state.clearCpuParticlesToPool();
                }
                if (doCacheUpload || sumSpawn > 0 || state.liveGpuParticleCount.get() > 0 || state.hasPendingGpuTicks()) {
                    synchronized (state.pendingGpuTicks) {
                        if (state.pendingGpuTicks.size() < 12) {
                            state.pendingGpuTicks.add(new GpuTickRequest(
                                    dt, state.age, state.tickCounter++, spawns,
                                    state.resetPending, snap.pose().position(), state.rng.nextUInt(),
                                    state.runtime640.clone(), state.oldRuntime640.clone(),
                                    upload, idTableUpload, cacheTotalSpawned, cacheAcquireTag));
                            state.resetPending = false;
                        }
                    }
                }
            } else if (state.spec.simTarget() == NiagaraSystemModel.SimTarget.STATELESS && state.statelessParams != null) {
                stepStatelessEmitter(state, snap, dt, stopping);
            } else if (state.spec.hasCpuVm()) {
                stepCpuVmEmitter(state, snap, spawns);
            } else if (state.spec.explicitSpawnRate() > 0f) {
                int sumSpawn = 0;
                for (SpawnInvocation si : spawns) sumSpawn += si.count();
                stepSyntheticFallbackEmitter(state, snap, dt, sumSpawn);
            }
            state.first = false;
        }
        frame.finish(dt);
    }

    private void runSystemVm() {
        if (!systemInitialized && systemSpawnVm != null && sysSpawnConstants != null && sysSpawnScratch != null) {
            spawnInputs.clear();
            spawnInputs.count(1);
            bindRuntimeInputs(model.spawnInputBindings(), spawnInputs);
            System.arraycopy(sysSpawnConstants, 0, sysSpawnScratch, 0, sysSpawnConstants.length);
            System.arraycopy(frame.current, 0, sysSpawnScratch, 0, Math.min(32, sysSpawnScratch.length));
            systemDestination.clear();
            DirectVectorDataSet[] in = model.systemSpawn().inputs.isEmpty()
                    ? new DirectVectorDataSet[0] : new DirectVectorDataSet[]{systemCurrent, spawnInputs};
            systemSpawnVm.execute(1, in, new DirectVectorDataSet[]{systemDestination}, 0, sysSpawnScratch, sysRng);
            swapSystem();
            systemInitialized = true;
        }
        if (systemUpdateVm != null && sysUpdateConstants != null && sysUpdateScratch != null) {
            updateInputs.clear();
            updateInputs.count(1);
            bindRuntimeInputs(model.updateInputBindings(), updateInputs);
            System.arraycopy(sysUpdateConstants, 0, sysUpdateScratch, 0, sysUpdateConstants.length);
            System.arraycopy(frame.current, 0, sysUpdateScratch, 0, Math.min(32, sysUpdateScratch.length));
            systemDestination.clear();
            systemUpdateVm.execute(1, new DirectVectorDataSet[]{systemCurrent, updateInputs},
                    new DirectVectorDataSet[]{systemDestination}, 0, sysUpdateScratch, sysRng);
            swapSystem();
        }
    }

    private void bindRuntimeInputs(List<BindingSpec> bindings, DirectVectorDataSet target) {
        for (BindingSpec b : bindings) {
            byte[] src;
            if (b.emitter() < 0) {
                src = frame.current;
            } else if (b.emitter() < emitters.size()) {
                src = emitters.get(b.emitter()).runtime640;
            } else {
                continue;
            }
            int off = b.runtimeOffset();
            if (off < 0 || off + 4 > src.length) continue;
            if (b.component() >= target.componentCount(b.type())) continue;
            int word = (src[off] & 0xFF) | ((src[off + 1] & 0xFF) << 8)
                    | ((src[off + 2] & 0xFF) << 16) | (src[off + 3] << 24);
            target.component(b.type(), b.component())[0] = word;
        }
    }

    private List<SpawnInvocation> collectSpawns(EmitterState state, float dt) {
        if (state.executionStateBinding != null && systemCurrent.count() > 0) {
            int execState = systemCurrent.integer(state.executionStateBinding.intStart(), 0);
            if (execState != 0) return List.of();
        }
        if (!state.spawnBindings.isEmpty() && systemCurrent.count() > 0) {
            List<SpawnInvocation> list = new ArrayList<>(state.spawnBindings.size());
            for (VariableSpec var : state.spawnBindings) {
                int count = systemCurrent.integer(var.intStart(), 0);
                if (count <= 0) continue;
                float startDt = var.floatCount() >= 1 ? systemCurrent.floating(var.floatStart(), 0) : 0f;
                float intervalDt = var.floatCount() >= 2 ? systemCurrent.floating(var.floatStart() + 1, 0) : 0f;
                int group = var.intCount() >= 2 ? systemCurrent.integer(var.intStart() + 1, 0) : 0;
                list.add(new SpawnInvocation(count, startDt, intervalDt, group));
            }
            return list;
        }
        if (state.spec.explicitSpawnRate() > 0f) {
            state.explicitSpawnCarry += state.spec.explicitSpawnRate() * dt;
            int count = (int) Math.floor(state.explicitSpawnCarry);
            state.explicitSpawnCarry -= count;
            if (count > 0) return List.of(new SpawnInvocation(count, 0f, dt / count, 0));
        }
        return List.of();
    }

    private void stepCpuVmEmitter(EmitterState state, EffectHandle.Snapshot snap, List<SpawnInvocation> spawns) {
        int existing = state.current.count();
        state.destination.count(0);
        if (existing > 0 && state.updateVm != null && state.updateConstants != null
                && state.updateWords != null && state.updatePlan != null) {
            prepareEmitterConstants(state, state.spec.updateProgram(), state.updatePlan,
                    state.updateConstants, state.updateWords, new SpawnInvocation(existing, 0f, 0f, 0));
            state.updateVm.execute(existing, new DirectVectorDataSet[]{state.current},
                    new DirectVectorDataSet[]{state.destination}, 0, state.updateConstants, state.rng);
        }
        // O(1) planar backing-array swap (zero particle-array copy!)
        state.current.swapWith(state.destination);

        int cap = Math.min(state.spec.declaredMaxParticles(), CPU_RENDER_SYNC_CAP);
        if (state.spawnVm != null && state.spawnConstants != null
                && state.spawnWords != null && state.spawnPlan != null) {
            for (SpawnInvocation inv : spawns) {
                int remaining = Math.max(0, cap - state.current.count());
                int count = Math.min(inv.count(), remaining);
                if (count <= 0) break;
                state.runtimeWords.putInt(612, state.totalSpawned);
                prepareEmitterConstants(state, state.spec.spawnProgram(), state.spawnPlan,
                        state.spawnConstants, state.spawnWords,
                        new SpawnInvocation(count, inv.interpStartDt(), inv.intervalDt(), inv.spawnGroup()));
                state.totalSpawned += count;
                state.spawnVm.execute(count, new DirectVectorDataSet[0],
                        new DirectVectorDataSet[]{state.current}, state.current.count(), state.spawnConstants, state.rng);
            }
        }
        syncRenderParticlesFromDataset(state, snap);
    }

    private void prepareEmitterConstants(EmitterState state, DirectVectorProgram prog,
                                         ParameterBindingPlan plan,
                                         byte[] constants, ByteBuffer words, SpawnInvocation inv) {
        System.arraycopy(prog.constantData, 0, constants, 0, Math.min(prog.constantData.length, constants.length));
        System.arraycopy(state.runtime640, 0, constants, 0, Math.min(640, constants.length));

        boolean hasSys = systemCurrent.count() > 0;
        int[] kinds = plan.kinds();
        int[] offsets = plan.offsets();
        int[] srcStarts = plan.srcStarts();
        int[] counts = plan.counts();
        for (int i = 0; i < kinds.length; i++) {
            int offset = offsets[i];
            switch (kinds[i]) {
                case PARAM_EXEC_COUNT -> words.putInt(offset, inv.count());
                case PARAM_SPAWN_INTERVAL -> words.putFloat(offset, inv.intervalDt());
                case PARAM_INTERP_START -> words.putFloat(offset, inv.interpStartDt());
                case PARAM_SPAWN_GROUP -> words.putInt(offset, inv.spawnGroup());
                case PARAM_SYS_FLOATS -> {
                    if (hasSys) {
                        int s = srcStarts[i], cnt = counts[i];
                        for (int c = 0; c < cnt; c++) words.putFloat(offset + c * 4, systemCurrent.floating(s + c, 0));
                    }
                }
                case PARAM_SYS_INTS -> {
                    if (hasSys) {
                        int s = srcStarts[i], cnt = counts[i];
                        for (int c = 0; c < cnt; c++) words.putInt(offset + c * 4, systemCurrent.integer(s + c, 0));
                    }
                }
                default -> { }
            }
        }

        if (prog.interpolated) {
            int oldOff = prog.runtimeBytes + prog.parameterBytes;
            if (oldOff + 640 <= constants.length) {
                System.arraycopy(state.oldRuntime640, 0, constants, oldOff, 640);
            }
        }
    }

    /** Deterministic analytical evaluation for Niagara {@code SimTarget.STATELESS} emitters. */
    private static void stepStatelessEmitter(EmitterState state, EffectHandle.Snapshot snap, float dt, boolean stopping) {
        StatelessParams sp = state.statelessParams;
        if (sp == null) return;
        Vec3 origin = snap.pose().position();

        // Advance analytical particles with drag, gravity, and optional vortex forcefield
        for (int i = state.cpuParticles.size() - 1; i >= 0; i--) {
            ParticleInstance p = state.cpuParticles.get(i);
            p.prevX = p.x; p.prevY = p.y; p.prevZ = p.z;
            p.age += dt;
            if (p.age >= p.lifetime) {
                int last = state.cpuParticles.size() - 1;
                ParticleInstance dead = state.cpuParticles.get(i);
                if (i != last) state.cpuParticles.set(i, state.cpuParticles.get(last));
                state.cpuParticles.remove(last);
                if (state.particlePool.size() < 4096) state.particlePool.addFirst(dead);
                continue;
            }
            float t = p.normalizedAge();
            float ax = 0f, ay = sp.gravity(), az = 0f;
            if (sp.vortex() != 0f) {
                double dx = p.x - origin.x;
                double dz = p.z - origin.z;
                double r = Math.sqrt(dx * dx + dz * dz + 1e-4);
                ax += (float) (-dz / r * sp.vortex());
                az += (float) (dx / r * sp.vortex());
            }
            p.vx += ax * dt; p.vy += ay * dt; p.vz += az * dt;
            if (sp.drag() > 0f) {
                float f = Math.max(0f, 1f - sp.drag() * dt);
                p.vx *= f; p.vy *= f; p.vz *= f;
            }
            p.x += p.vx * dt; p.y += p.vy * dt; p.z += p.vz * dt;
            float sz = sp.sizeStart() + (sp.sizeEnd() - sp.sizeStart()) * t;
            p.width = p.height = Math.max(0.01f, sz * snap.pose().scaleX());
            p.r = (sp.r0() + (sp.r1() - sp.r0()) * t) * snap.red();
            p.g = (sp.g0() + (sp.g1() - sp.g0()) * t) * snap.green();
            p.b = (sp.b0() + (sp.b1() - sp.b0()) * t) * snap.blue();
            p.a = (sp.a0() + (sp.a1() - sp.a0()) * t) * snap.alpha();
        }
        if (stopping) return;

        int toSpawn = 0;
        if (!state.statelessBurstFired && sp.burstCount() > 0) {
            state.statelessBurstFired = true;
            toSpawn += sp.burstCount();
        }
        if (sp.spawnRate() > 0f) {
            state.explicitSpawnCarry += sp.spawnRate() * dt;
            int c = (int) Math.floor(state.explicitSpawnCarry);
            if (c > 0) { state.explicitSpawnCarry -= c; toSpawn += c; }
        }
        int cap = Math.min(state.spec.declaredMaxParticles(), CPU_RENDER_SYNC_CAP);
        int spawn = Math.min(toSpawn, Math.max(0, cap - state.cpuParticles.size()));
        for (int s = 0; s < spawn; s++) {
            float az = state.rng.fraction() * (float) (Math.PI * 2.0);
            float el = (state.rng.fraction() * 0.6f + 0.4f);
            float h = (float) Math.sqrt(Math.max(0f, 1f - el * el));
            ParticleInstance p = state.borrowParticle();
            p.x = p.prevX = origin.x + Math.cos(az) * sp.radius();
            p.y = p.prevY = origin.y;
            p.z = p.prevZ = origin.z + Math.sin(az) * sp.radius();
            p.vx = (float) Math.cos(az) * h * sp.speed();
            p.vy = el * sp.speed();
            p.vz = (float) Math.sin(az) * h * sp.speed();
            p.age = 0f;
            p.lifetime = Math.max(0.05f, sp.lifetime());
            p.width = p.height = p.startWidth = p.startHeight = Math.max(0.01f, sp.sizeStart() * snap.pose().scaleX());
            p.r = p.startR = sp.r0() * snap.red();
            p.g = p.startG = sp.g0() * snap.green();
            p.b = p.startB = sp.b0() * snap.blue();
            p.a = p.startA = sp.a0() * snap.alpha();
            state.cpuParticles.add(p);
        }
    }

    private void syncRenderParticlesFromDataset(EmitterState state, EffectHandle.Snapshot snap) {
        int count = Math.min(state.current.count(), CPU_RENDER_SYNC_CAP);
        state.clearCpuParticlesToPool();
        if (count <= 0) return;

        Vec3 origin = snap.pose().position();
        boolean local = state.spec.localSpace();
        int fCount = state.current.componentCount(0);
        for (int i = 0; i < count; i++) {
            ParticleInstance p = state.borrowParticle();
            if (state.posComp >= 0 && state.posComp + 2 < fCount) {
                float ux = state.current.floating(state.posComp, i);
                float uy = state.current.floating(state.posComp + 1, i);
                float uz = state.current.floating(state.posComp + 2, i);
                double mx = ux * 0.01;
                double my = uz * 0.01;
                double mz = uy * 0.01;
                if (local) {
                    Vec3 w = snap.pose().transformPosition(new Vec3(mx, my, mz));
                    p.x = p.prevX = w.x; p.y = p.prevY = w.y; p.z = p.prevZ = w.z;
                } else {
                    p.x = p.prevX = mx; p.y = p.prevY = my; p.z = p.prevZ = mz;
                }
            } else {
                p.x = p.prevX = origin.x; p.y = p.prevY = origin.y; p.z = p.prevZ = origin.z;
            }
            if (state.colComp >= 0 && state.colComp + 3 < fCount) {
                p.r = state.current.floating(state.colComp, i) * snap.red();
                p.g = state.current.floating(state.colComp + 1, i) * snap.green();
                p.b = state.current.floating(state.colComp + 2, i) * snap.blue();
                p.a = clamp01(state.current.floating(state.colComp + 3, i)) * snap.alpha();
            } else {
                p.r = snap.red(); p.g = snap.green(); p.b = snap.blue(); p.a = snap.alpha();
            }
            float w = 0.25f;
            if (state.widthComp >= 0 && state.widthComp < fCount) {
                float rw = state.current.floating(state.widthComp, i);
                if (Float.isFinite(rw) && rw > 0f) w = rw > 4f ? rw * 0.01f : rw;
            } else if (state.sizeComp >= 0 && state.sizeComp < fCount) {
                float sw = state.current.floating(state.sizeComp, i);
                if (Float.isFinite(sw) && sw > 0f) w = sw > 4f ? sw * 0.01f : sw;
            }
            p.width = p.startWidth = Math.max(0.03f, Math.min(8f, w));
            p.height = p.startHeight = p.width;
            p.rotationDeg = state.rotComp >= 0 && state.rotComp < fCount
                    ? state.current.floating(state.rotComp, i) : 0f;
            p.age = state.ageComp >= 0 && state.ageComp < fCount
                    ? state.current.floating(state.ageComp, i) : 0f;
            p.lifetime = state.lifeComp >= 0 && state.lifeComp < fCount
                    ? Math.max(0.05f, state.current.floating(state.lifeComp, i)) : 1f;
            p.curveRandom = state.materialRandomComp >= 0 && state.materialRandomComp < fCount
                    ? state.current.floating(state.materialRandomComp, i) : 0f;
            p.setMaterialData(state.spec.materialData());
            state.cpuParticles.add(p);
        }
    }

    private static void stepSyntheticFallbackEmitter(EmitterState state, EffectHandle.Snapshot snap, float dt, int spawnCount) {
        Vec3 origin = snap.pose().position();
        for (int i = state.cpuParticles.size() - 1; i >= 0; i--) {
            ParticleInstance p = state.cpuParticles.get(i);
            p.prevX = p.x; p.prevY = p.y; p.prevZ = p.z;
            p.age += dt;
            if (p.age >= p.lifetime) {
                int last = state.cpuParticles.size() - 1;
                ParticleInstance dead = state.cpuParticles.get(i);
                if (i != last) state.cpuParticles.set(i, state.cpuParticles.get(last));
                state.cpuParticles.remove(last);
                if (state.particlePool.size() < 4096) state.particlePool.addFirst(dead);
                continue;
            }
            p.vy -= 4.5f * dt;
            p.x += p.vx * dt; p.y += p.vy * dt; p.z += p.vz * dt;
            p.a = p.startA * (1f - p.normalizedAge()) * snap.alpha();
        }
        int cap = Math.min(state.spec.declaredMaxParticles(), CPU_RENDER_SYNC_CAP);
        int toSpawn = Math.min(spawnCount, Math.max(0, cap - state.cpuParticles.size()));
        for (int s = 0; s < toSpawn; s++) {
            float angle = state.rng.fraction() * (float) (Math.PI * 2.0);
            ParticleInstance p = state.borrowParticle();
            p.x = p.prevX = origin.x; p.y = p.prevY = origin.y; p.z = p.prevZ = origin.z;
            p.vx = (float) Math.cos(angle) * 0.8f; p.vy = 2.5f; p.vz = (float) Math.sin(angle) * 0.8f;
            p.age = 0f; p.lifetime = Math.max(0.25f, state.spec.defaultLifetime());
            p.width = p.height = p.startWidth = p.startHeight = 0.25f;
            p.r = p.startR = snap.red(); p.g = p.startG = snap.green();
            p.b = p.startB = snap.blue(); p.a = p.startA = snap.alpha();
            state.cpuParticles.add(p);
        }
    }

    private void swapSystem() {
        DirectVectorDataSet tmp = systemCurrent;
        systemCurrent = systemDestination;
        systemDestination = tmp;
    }

    private static DirectVectorDataSet createDataset(List<VariableSpec> vars) {
        int f = 0, n = 0, h = 0;
        for (VariableSpec v : vars) {
            f = Math.max(f, v.floatStart() + v.floatCount());
            n = Math.max(n, v.intStart() + v.intCount());
            h = Math.max(h, v.halfStart() + v.halfCount());
        }
        return new DirectVectorDataSet(f, n, h);
    }

    private static DirectVectorDataSet cloneDataset(DirectVectorDataSet src) {
        DirectVectorDataSet copy = new DirectVectorDataSet(
                src.componentCount(0), src.componentCount(1), src.componentCount(2));
        copy.copyFrom(src);
        return copy;
    }

    private static @Nullable VariableSpec findVar(Map<String, VariableSpec> vars, String name) {
        return vars.get(name.toLowerCase(Locale.ROOT));
    }

    private static int findAttr(List<AttributeSpec> attrs, int type, String shortName) {
        for (AttributeSpec a : attrs) {
            if (a.type() != type) continue;
            if (a.name().equalsIgnoreCase(shortName) || a.name().equalsIgnoreCase("Particles." + shortName)) {
                return a.component();
            }
        }
        return -1;
    }

    private static float clamp01(float v) {
        return Float.isFinite(v) ? Math.max(0f, Math.min(1f, v)) : 1f;
    }

    public boolean isDone(boolean stopping) {
        if (!stopping) return false;
        if (totalParticles() > 0) return false;
        for (EmitterState e : emitters) {
            if (e.spec.isGpu() && e.hasPendingGpuTicks()) return false;
        }
        return true;
    }
}
