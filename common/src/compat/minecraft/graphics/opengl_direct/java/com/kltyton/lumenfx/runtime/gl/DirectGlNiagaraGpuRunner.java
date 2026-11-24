package com.kltyton.lumenfx.runtime.gl;

import com.kltyton.lumenfx.runtime.model.NiagaraSystemModel.AttributeSpec;
import com.kltyton.lumenfx.runtime.model.NiagaraSystemModel.ComputeStageSpec;
import com.kltyton.lumenfx.runtime.model.NiagaraSystemModel.EmitterSpec;
import com.kltyton.lumenfx.runtime.model.NiagaraSystemModel.GpuProgramSpec;
import com.kltyton.lumenfx.runtime.model.NiagaraSystemModel.UniformBlockSpec;
import com.kltyton.lumenfx.runtime.niagara.DirectVectorDataSet;
import com.kltyton.lumenfx.runtime.niagara.HalfFloat;
import com.kltyton.lumenfx.runtime.simulation.EffectRuntimeInstance;
import com.kltyton.lumenfx.runtime.simulation.NiagaraSystemRuntime;
import com.kltyton.lumenfx.runtime.simulation.NiagaraSystemRuntime.EmitterState;
import com.kltyton.lumenfx.runtime.simulation.NiagaraSystemRuntime.GpuTickRequest;
import com.kltyton.lumenfx.runtime.simulation.NiagaraSystemRuntime.SpawnInvocation;
import com.kltyton.lumenfx.runtime.simulation.ParticleInstance;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryUtil;

/**
 * Executes baked Niagara GPU SPIR-V compute programs directly on OpenGL 4.6 / {@code GL_ARB_gl_spirv},
 * matching 26.2 {@code NiagaraGpuDataBuffer}/{@code NiagaraGpuParticleBuffers}/{@code NiagaraGpuPersistentIds}:
 * <ul>
 *   <li>Computes maximum particle capacity dynamically from live OpenGL {@code GL_MAX_SHADER_STORAGE_BLOCK_SIZE}
 *       and {@code GL_MAX_TEXTURE_BUFFER_SIZE} with 64-threadgroup alignment (no artificial 16,384 cap).</li>
 *   <li>Allocates, grows, binds, and uploads full Float, Int, <b>Half</b> ({@code InputHalf}/{@code RWOutputHalf}),
 *       persistent ID table ({@code IDToIndexTable}/{@code RWIDToIndexTable}), and compacted
 *       {@code FreeIDList}/{@code RWFreeIDListSizes} buffers.</li>
 *   <li>Preserves untruncated {@code totalSpawned} and monotonic unsigned {@code AcquireTag}
 *       ({@link #observeCachedParticleTag(int)}) seamlessly across SimCache-to-live-GPU handoff.</li>
 * </ul>
 */
public final class DirectGlNiagaraGpuRunner {
    public static final int THREADGROUP_ALIGNMENT = 64;
    private static final int PERSISTENT_ID_CHUNK = 1024;
    private static final int READBACK_PARTICLE_CAP = 2048;

    private static volatile long cachedMaxStorageBufferBytes = -1L;
    private static volatile long cachedMaxTexelBufferElements = -1L;

    public record GpuHandoffSnapshot(int count, int stride, int capacity, int totalSpawned, int acquireTag,
                                     int floatComponents, int intComponents, int halfComponents,
                                     int idCapacity, int freeIdCount,
                                     float[] probeFloats, int[] probeInts, float[] probeHalves,
                                     int[] probeIdRows) { }

    private record EngineSpan(int offset, int bytes, boolean isInt) { }

    private static final Map<String, EngineSpan> ENGINE_SPANS = buildEngineSpans();

    private static Map<String, EngineSpan> buildEngineSpans() {
        Map<String, EngineSpan> m = new HashMap<>();
        m.put("Engine_WorldDeltaTime", new EngineSpan(0, 4, false));
        m.put("Engine_DeltaTime", new EngineSpan(4, 4, false));
        m.put("Engine_InverseDeltaTime", new EngineSpan(8, 4, false));
        m.put("Engine_Time", new EngineSpan(12, 4, false));
        m.put("Engine_RealTime", new EngineSpan(16, 4, false));
        m.put("Engine_QualityLevel", new EngineSpan(20, 4, true));
        m.put("Engine_Owner_TimeSinceRendered", new EngineSpan(32, 4, false));
        m.put("Engine_Owner_LODDistance", new EngineSpan(36, 4, false));
        m.put("Engine_Owner_LODDistanceFraction", new EngineSpan(40, 4, false));
        m.put("Engine_System_Age", new EngineSpan(44, 4, false));
        m.put("Engine_Owner_ExecutionState", new EngineSpan(48, 4, true));
        m.put("Engine_System_TickCount", new EngineSpan(52, 4, true));
        m.put("Engine_System_NumEmitters", new EngineSpan(56, 4, true));
        m.put("Engine_System_NumEmittersAlive", new EngineSpan(60, 4, true));
        m.put("Engine_System_SignificanceIndex", new EngineSpan(64, 4, true));
        m.put("Engine_System_RandomSeed", new EngineSpan(68, 4, true));
        m.put("Engine_System_CurrentTimeStep", new EngineSpan(72, 4, true));
        m.put("Engine_System_NumTimeSteps", new EngineSpan(76, 4, true));
        m.put("Engine_System_TimeStepFraction", new EngineSpan(80, 4, false));
        m.put("Engine_System_NumParticles", new EngineSpan(84, 4, true));
        m.put("Engine_Owner_SystemLocalToWorld", new EngineSpan(96, 64, false));
        m.put("Engine_Owner_SystemWorldToLocal", new EngineSpan(160, 64, false));
        m.put("Engine_Owner_SystemLocalToWorldTransposed", new EngineSpan(224, 64, false));
        m.put("Engine_Owner_SystemWorldToLocalTransposed", new EngineSpan(288, 64, false));
        m.put("Engine_Owner_SystemLocalToWorldNoScale", new EngineSpan(352, 64, false));
        m.put("Engine_Owner_SystemWorldToLocalNoScale", new EngineSpan(416, 64, false));
        m.put("Engine_Owner_Rotation", new EngineSpan(480, 16, false));
        m.put("Engine_Owner_Position", new EngineSpan(496, 12, false));
        m.put("Engine_Owner_Velocity", new EngineSpan(512, 12, false));
        m.put("Engine_Owner_SystemXAxis", new EngineSpan(528, 12, false));
        m.put("Engine_Owner_SystemYAxis", new EngineSpan(544, 12, false));
        m.put("Engine_Owner_SystemZAxis", new EngineSpan(560, 12, false));
        m.put("Engine_Owner_Scale", new EngineSpan(576, 12, false));
        m.put("Engine_Owner_LWCTile", new EngineSpan(592, 16, false));
        m.put("Engine_Emitter_NumParticles", new EngineSpan(608, 4, true));
        m.put("Engine_Emitter_TotalSpawnedParticles", new EngineSpan(612, 4, true));
        m.put("Engine_Emitter_SpawnCountScale", new EngineSpan(616, 4, false));
        m.put("Emitter_Age", new EngineSpan(620, 4, false));
        m.put("Emitter_RandomSeed", new EngineSpan(624, 4, true));
        m.put("Engine_Emitter_InstanceSeed", new EngineSpan(628, 4, true));
        return m;
    }

    /** Queries and caches live OpenGL SSBO and texel-buffer hardware limits when a GL context is active. */
    public static void refreshDeviceLimits() {
        try {
            GLCapabilities caps = GL.getCapabilities();
            if (caps == null) return;
            long maxSsbo = caps.OpenGL43 || caps.GL_ARB_shader_storage_buffer_object
                    ? Integer.toUnsignedLong(GL11C.glGetInteger(GL43C.GL_MAX_SHADER_STORAGE_BLOCK_SIZE))
                    : 0L;
            long maxTexel = caps.OpenGL31 || caps.GL_ARB_texture_buffer_object
                    ? Integer.toUnsignedLong(GL11C.glGetInteger(GL31C.GL_MAX_TEXTURE_BUFFER_SIZE))
                    : 0L;
            if (maxSsbo > 0L) cachedMaxStorageBufferBytes = maxSsbo;
            if (maxTexel > 0L) cachedMaxTexelBufferElements = maxTexel;
        } catch (RuntimeException ignored) {
        }
    }

    /**
     * Exact 26.2 {@code NiagaraGpuDataBuffer.maximumParticleCapacity} formula:
     * computes the maximum 64-aligned particle capacity (excluding 1 scratch slot) that fits inside
     * both {@code maxStorageBufferBytes} and {@code maxTexelBufferElements * 4}.
     */
    public static int maximumParticleCapacity(int floatComponents, int intComponents, int halfComponents,
                                              long maxStorageBufferBytes, long maxTexelBufferElements) {
        long bytesPerInstance = Math.max(
                Math.max((long) Math.max(0, floatComponents) * Float.BYTES,
                        (long) Math.max(0, intComponents) * Integer.BYTES),
                (long) Math.max(0, halfComponents) * Short.BYTES);
        if (bytesPerInstance <= 0L) return Integer.MAX_VALUE - THREADGROUP_ALIGNMENT;
        long ssboBytes = maxStorageBufferBytes > 0L ? maxStorageBufferBytes : Long.MAX_VALUE;
        long texelBytes = maxTexelBufferElements > 0L
                ? (maxTexelBufferElements > Long.MAX_VALUE / Integer.BYTES ? Long.MAX_VALUE : maxTexelBufferElements * Integer.BYTES)
                : Long.MAX_VALUE;
        long maxBytes = Math.min(ssboBytes, texelBytes);
        if (maxBytes == Long.MAX_VALUE) return Integer.MAX_VALUE - THREADGROUP_ALIGNMENT;
        long instances = Math.min(Integer.MAX_VALUE, maxBytes / bytesPerInstance);
        long padded = (instances / THREADGROUP_ALIGNMENT) * THREADGROUP_ALIGNMENT;
        if (padded < THREADGROUP_ALIGNMENT) return 0;
        return Math.toIntExact(padded - 1L);
    }

    /** Returns the live hardware particle capacity for an emitter (or unbounded if no GL context has been initialized yet). */
    public static int deviceMaximumParticleCapacity(EmitterSpec spec) {
        if (cachedMaxStorageBufferBytes <= 0L || cachedMaxTexelBufferElements <= 0L) {
            refreshDeviceLimits();
        }
        int floats = spec.floatComponents();
        int ints = spec.intComponents();
        int halves = spec.halfComponents();
        GpuProgramSpec gp = spec.gpuProgram();
        if (gp != null) {
            floats = Math.max(floats, gp.floatComponentCount());
            ints = Math.max(ints, gp.intComponentCount());
            halves = Math.max(halves, gp.halfComponentCount());
        }
        if (cachedMaxStorageBufferBytes <= 0L && cachedMaxTexelBufferElements <= 0L) {
            return Integer.MAX_VALUE - THREADGROUP_ALIGNMENT;
        }
        return maximumParticleCapacity(floats, ints, halves, cachedMaxStorageBufferBytes, cachedMaxTexelBufferElements);
    }

    public static long cachedMaxStorageBufferBytes() { return cachedMaxStorageBufferBytes; }
    public static long cachedMaxTexelBufferElements() { return cachedMaxTexelBufferElements; }

    private static final class EmitterGpuBuffers {
        final int floatComponents;
        final int intComponents;
        final int halfComponents;
        final int maxAllowedParticles;

        int stride;
        int capacity;
        int floatBufA;
        int floatBufB;
        int intBufA;
        int intBufB;
        int halfBufA;
        int halfBufB;
        int staticFloatBuf;

        int idCapacity;
        int freeIdBuf;
        int idToIndexBuf;
        final int freeIdSizesBuf;
        final int indirectArgsBuf;
        final int instanceCountsBuf;
        final int dummySsbo;
        final Map<String, Integer> uboHandles = new HashMap<>();

        boolean ping;
        int currentCount;
        int totalSpawned;
        int acquireTag;
        int freeIdCount;
        boolean failed;

        EmitterGpuBuffers(int initialParticles, int maxAllowedParticles,
                          int floatComponents, int intComponents, int halfComponents) {
            this.maxAllowedParticles = Math.max(0, maxAllowedParticles);
            this.floatComponents = Math.max(0, floatComponents);
            this.intComponents = Math.max(0, intComponents);
            this.halfComponents = Math.max(0, halfComponents);
            this.freeIdSizesBuf = createZeroBuffer(16L);
            this.indirectArgsBuf = createZeroBuffer(64L);
            this.instanceCountsBuf = createZeroBuffer(64L);
            this.dummySsbo = createZeroBuffer(256L);
            ensureParticleCapacity(Math.min(Math.max(0, initialParticles), this.maxAllowedParticles), false);
            ensureIdCapacity(Math.max(1, Math.min(Math.max(0, initialParticles), this.maxAllowedParticles)));
        }

        void ensureParticleCapacity(int requiredParticles, boolean preserveLive) {
            int clamped = Math.max(0, Math.min(requiredParticles, maxAllowedParticles));
            long withScratch = (long) clamped + 1L;
            int requiredStride = (int) (((withScratch + THREADGROUP_ALIGNMENT - 1L) / THREADGROUP_ALIGNMENT) * THREADGROUP_ALIGNMENT);
            if (requiredStride <= stride && stride > 0) return;

            int oldStride = stride;
            int oldFloatA = floatBufA, oldFloatB = floatBufB;
            int oldIntA = intBufA, oldIntB = intBufB;
            int oldHalfA = halfBufA, oldHalfB = halfBufB;
            int oldStatic = staticFloatBuf;

            int newFloatA = floatComponents > 0 ? createGpuZeroBuffer((long) requiredStride * floatComponents * Float.BYTES) : 0;
            int newFloatB = floatComponents > 0 ? createGpuZeroBuffer((long) requiredStride * floatComponents * Float.BYTES) : 0;
            int newIntA = intComponents > 0 ? createGpuZeroBuffer((long) requiredStride * intComponents * Integer.BYTES) : 0;
            int newIntB = intComponents > 0 ? createGpuZeroBuffer((long) requiredStride * intComponents * Integer.BYTES) : 0;
            int newHalfA = halfComponents > 0 ? createGpuZeroBuffer((long) requiredStride * halfComponents * Short.BYTES) : 0;
            int newHalfB = halfComponents > 0 ? createGpuZeroBuffer((long) requiredStride * halfComponents * Short.BYTES) : 0;
            int newStatic = createGpuZeroBuffer(Math.max(64L, (long) requiredStride * Math.max(1, floatComponents)));

            if (preserveLive && oldStride > 0 && currentCount > 0) {
                int activeReadFloat = ping ? oldFloatB : oldFloatA;
                int targetReadFloat = ping ? newFloatB : newFloatA;
                copyPlanar(activeReadFloat, targetReadFloat, floatComponents, oldStride, requiredStride, currentCount, Float.BYTES);

                int activeReadInt = ping ? oldIntB : oldIntA;
                int targetReadInt = ping ? newIntB : newIntA;
                copyPlanar(activeReadInt, targetReadInt, intComponents, oldStride, requiredStride, currentCount, Integer.BYTES);

                int activeReadHalf = ping ? oldHalfB : oldHalfA;
                int targetReadHalf = ping ? newHalfB : newHalfA;
                copyPlanar(activeReadHalf, targetReadHalf, halfComponents, oldStride, requiredStride, currentCount, Short.BYTES);
            }

            if (oldFloatA != 0) GL15C.glDeleteBuffers(oldFloatA);
            if (oldFloatB != 0) GL15C.glDeleteBuffers(oldFloatB);
            if (oldIntA != 0) GL15C.glDeleteBuffers(oldIntA);
            if (oldIntB != 0) GL15C.glDeleteBuffers(oldIntB);
            if (oldHalfA != 0) GL15C.glDeleteBuffers(oldHalfA);
            if (oldHalfB != 0) GL15C.glDeleteBuffers(oldHalfB);
            if (oldStatic != 0) GL15C.glDeleteBuffers(oldStatic);

            this.stride = requiredStride;
            this.capacity = Math.max(0, requiredStride - 1);
            this.floatBufA = newFloatA;
            this.floatBufB = newFloatB;
            this.intBufA = newIntA;
            this.intBufB = newIntB;
            this.halfBufA = newHalfA;
            this.halfBufB = newHalfB;
            this.staticFloatBuf = newStatic;
        }

        void ensureIdCapacity(int requiredIds) {
            long req = Math.max(1L, requiredIds);
            int targetCap = (int) (((req + PERSISTENT_ID_CHUNK - 1L) / PERSISTENT_ID_CHUNK) * PERSISTENT_ID_CHUNK);
            long maxIds = Math.min(
                    cachedMaxStorageBufferBytes > 0L ? cachedMaxStorageBufferBytes / Integer.BYTES : Integer.MAX_VALUE,
                    cachedMaxTexelBufferElements > 0L ? cachedMaxTexelBufferElements : Integer.MAX_VALUE);
            targetCap = (int) Math.min(targetCap, Math.max(PERSISTENT_ID_CHUNK, maxIds));
            if (targetCap <= idCapacity && idToIndexBuf != 0 && freeIdBuf != 0) return;

            int oldIdToIndex = idToIndexBuf;
            int oldFreeId = freeIdBuf;
            int newIdToIndex = createFilledIntBuffer(targetCap, -1);
            int newFreeId = createFreeIdBuffer(targetCap);

            if (oldIdToIndex != 0 && idCapacity > 0) {
                GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER, oldIdToIndex);
                GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, newIdToIndex);
                GL31C.glCopyBufferSubData(GL31C.GL_COPY_READ_BUFFER, GL31C.GL_COPY_WRITE_BUFFER,
                        0L, 0L, (long) Math.min(idCapacity, targetCap) * Integer.BYTES);
                GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER, 0);
                GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, 0);
            }
            if (oldIdToIndex != 0) GL15C.glDeleteBuffers(oldIdToIndex);
            if (oldFreeId != 0) GL15C.glDeleteBuffers(oldFreeId);

            this.idToIndexBuf = newIdToIndex;
            this.freeIdBuf = newFreeId;
            this.idCapacity = targetCap;
            this.freeIdCount = targetCap;
            writeSingleInt(this.freeIdSizesBuf, targetCap);
        }

        void dispose() {
            if (floatBufA != 0) GL15C.glDeleteBuffers(floatBufA);
            if (floatBufB != 0) GL15C.glDeleteBuffers(floatBufB);
            if (intBufA != 0) GL15C.glDeleteBuffers(intBufA);
            if (intBufB != 0) GL15C.glDeleteBuffers(intBufB);
            if (halfBufA != 0) GL15C.glDeleteBuffers(halfBufA);
            if (halfBufB != 0) GL15C.glDeleteBuffers(halfBufB);
            if (staticFloatBuf != 0) GL15C.glDeleteBuffers(staticFloatBuf);
            if (freeIdBuf != 0) GL15C.glDeleteBuffers(freeIdBuf);
            if (idToIndexBuf != 0) GL15C.glDeleteBuffers(idToIndexBuf);
            GL15C.glDeleteBuffers(freeIdSizesBuf);
            GL15C.glDeleteBuffers(indirectArgsBuf);
            GL15C.glDeleteBuffers(instanceCountsBuf);
            GL15C.glDeleteBuffers(dummySsbo);
            for (int ubo : uboHandles.values()) GL15C.glDeleteBuffers(ubo);
            uboHandles.clear();
        }
    }

    private final Map<String, List<DirectGlComputeKernel>> kernelCache = new HashMap<>();
    private final Map<EmitterState, EmitterGpuBuffers> emitterBuffers = new IdentityHashMap<>();
    private final Map<Integer, Integer> texelTextures = new HashMap<>();
    private int globalAcquireTag;

    /** Monotonically advances the runner's unsigned AcquireTag past any cached particle AcquireTag (26.2 parity). */
    public void observeCachedParticleTag(int tag) {
        if (Integer.compareUnsigned(tag, globalAcquireTag) > 0) {
            globalAcquireTag = tag;
        }
    }

    public int nextAcquireTag() {
        return ++globalAcquireTag;
    }

    public int currentAcquireTag() {
        return globalAcquireTag;
    }

    public void stepPending(List<EffectRuntimeInstance> instances) {
        refreshDeviceLimits();
        if (!DirectGlComputeKernel.isSupported()) return;
        for (EffectRuntimeInstance inst : instances) {
            NiagaraSystemRuntime niagara = inst.niagaraRuntime();
            if (niagara == null) continue;
            for (EmitterState state : niagara.emitters()) {
                if (!state.spec.isGpu()) continue;
                GpuProgramSpec gp = state.spec.gpuProgram();
                if (gp == null) continue;
                List<GpuTickRequest> pending;
                synchronized (state.pendingGpuTicks) {
                    if (state.pendingGpuTicks.isEmpty()) continue;
                    pending = new ArrayList<>(state.pendingGpuTicks);
                    state.pendingGpuTicks.clear();
                }
                stepEmitter(state, gp, pending);
            }
        }
    }

    private void stepEmitter(EmitterState state, GpuProgramSpec gp, List<GpuTickRequest> ticks) {
        List<DirectGlComputeKernel> kernels = gp.hasSpirvShaders() ? resolveKernels(gp) : List.of();
        EmitterGpuBuffers bufs = emitterBuffers.computeIfAbsent(state, s -> allocateBuffers(s.spec, gp));
        if (bufs.failed) return;

        try {
            for (GpuTickRequest tick : ticks) {
                if (tick.cacheUpload() != null) {
                    uploadCacheDataset(state, bufs, tick.cacheUpload(), tick.cacheIdTable(),
                            tick.cacheTotalSpawned(), tick.cacheAcquireTag());
                } else if (tick.reset()) {
                    bufs.currentCount = 0;
                    bufs.totalSpawned = 0;
                    writeCounts(bufs.instanceCountsBuf, 0, 0);
                }

                if (kernels.isEmpty()) {
                    // Cache handoff succeeded into GPU SSBOs even if this synthetic emitter has no SPIR-V stage
                    state.liveGpuParticleCount.set(bufs.currentCount);
                    state.liveGpuCapacity.set(bufs.capacity);
                    state.liveGpuTotalSpawned.set(bufs.totalSpawned);
                    state.liveGpuAcquireTag.set(bufs.acquireTag);
                    continue;
                }

                int requestedSpawn = 0;
                for (SpawnInvocation si : tick.spawns()) requestedSpawn += Math.max(0, si.count());
                int spawn = Math.min(requestedSpawn, Math.max(0, bufs.maxAllowedParticles - bufs.currentCount));
                int threadCount = bufs.currentCount + spawn;
                if (threadCount <= 0) {
                    state.liveGpuParticleCount.set(0);
                    state.liveGpuCapacity.set(bufs.capacity);
                    state.liveGpuTotalSpawned.set(bufs.totalSpawned);
                    state.liveGpuAcquireTag.set(bufs.acquireTag);
                    state.gpuReadbackParticles = List.of();
                    continue;
                }

                bufs.ensureParticleCapacity(threadCount, true);
                bufs.ensureIdCapacity(threadCount);
                int readOffset = bufs.ping ? 1 : 0;
                int writeOffset = bufs.ping ? 0 : 1;
                writeCounts(bufs.instanceCountsBuf, readOffset == 0 ? bufs.currentCount : 0,
                        readOffset == 1 ? bufs.currentCount : 0);

                int readFloat = bufs.floatComponents > 0 ? (bufs.ping ? bufs.floatBufB : bufs.floatBufA) : bufs.dummySsbo;
                int writeFloat = bufs.floatComponents > 0 ? (bufs.ping ? bufs.floatBufA : bufs.floatBufB) : bufs.dummySsbo;
                int readInt = bufs.intComponents > 0 ? (bufs.ping ? bufs.intBufB : bufs.intBufA) : bufs.dummySsbo;
                int writeInt = bufs.intComponents > 0 ? (bufs.ping ? bufs.intBufA : bufs.intBufB) : bufs.dummySsbo;
                int readHalf = bufs.halfComponents > 0 ? (bufs.ping ? bufs.halfBufB : bufs.halfBufA) : bufs.dummySsbo;
                int writeHalf = bufs.halfComponents > 0 ? (bufs.ping ? bufs.halfBufA : bufs.halfBufB) : bufs.dummySsbo;

                bufs.totalSpawned += spawn;
                int dispatchTag = nextAcquireTag();
                bufs.acquireTag = dispatchTag;

                Map<String, Integer> ssbos = new HashMap<>();
                ssbos.put("InputFloat", readFloat);
                ssbos.put("RWOutputFloat", writeFloat);
                ssbos.put("InputInt", readInt);
                ssbos.put("RWOutputInt", writeInt);
                ssbos.put("InputHalf", readHalf);
                ssbos.put("RWOutputHalf", writeHalf);
                ssbos.put("StaticInputFloat", bufs.staticFloatBuf);
                ssbos.put("FreeIDList", bufs.freeIdBuf);
                ssbos.put("RWFreeIDList", bufs.freeIdBuf);
                ssbos.put("RWFreeIDListSizes", bufs.freeIdSizesBuf);
                ssbos.put("IDToIndexTable", bufs.idToIndexBuf);
                ssbos.put("RWIDToIndexTable", bufs.idToIndexBuf);
                ssbos.put("IndirectDispatchArgs", bufs.indirectArgsBuf);
                ssbos.put("RWInstanceCounts", bufs.instanceCountsBuf);

                Map<String, Object> globals = buildGlobalsMap(bufs, tick, spawn, readOffset, writeOffset, threadCount, dispatchTag);

                for (DirectGlComputeKernel kernel : kernels) {
                    ComputeStageSpec stage = kernel.spec();
                    stage.bindings().forEach(b -> {
                        if ("storageBuffer".equals(b.kind())) ssbos.putIfAbsent(b.name(), bufs.dummySsbo);
                    });
                    Map<String, Integer> ubos = new HashMap<>();
                    for (Map.Entry<String, UniformBlockSpec> entry : stage.uniformBlocks().entrySet()) {
                        byte[] packed = DirectGlComputeKernel.packUniformBlock(entry.getValue(), globals);
                        int ubo = uploadUbo(bufs, entry.getKey(), packed);
                        ubos.put(entry.getKey(), ubo);
                    }
                    kernel.dispatch(threadCount, ubos, ssbos, texelTextures);
                }

                int written = readCount(bufs.instanceCountsBuf, writeOffset);
                bufs.currentCount = Math.max(0, Math.min(bufs.capacity, written));
                bufs.ping = !bufs.ping;
                state.liveGpuParticleCount.set(bufs.currentCount);
                state.liveGpuCapacity.set(bufs.capacity);
                state.liveGpuTotalSpawned.set(bufs.totalSpawned);
                state.syncGpuAcquireTag(dispatchTag);

                if (bufs.currentCount > 0 && bufs.floatComponents > 0) {
                    readbackParticles(state, writeFloat, bufs.stride, bufs.floatComponents, bufs.currentCount, tick);
                } else {
                    state.gpuReadbackParticles = List.of();
                }
            }
        } catch (RuntimeException ignored) {
            bufs.failed = true;
        }
    }

    /**
     * Full 26.2-parity GPU SimCache handoff: dynamically grows GPU buffers up to hardware device limits,
     * uploads planar Float, Int, AND Half components, restores the persistent ID table
     * ({@code IDToIndexTable}/{@code RWIDToIndexTable}) with dead-gap & truncated IDs mapped to {@code -1},
     * rebuilds compacted {@code FreeIDList}/{@code RWFreeIDListSizes}, preserves untruncated {@code totalSpawned},
     * and observes the cached {@code AcquireTag} monotonically.
     */
    private void uploadCacheDataset(EmitterState state, EmitterGpuBuffers bufs, DirectVectorDataSet ds,
                                    @Nullable int[] cacheIdTable, int cacheTotalSpawned, int cacheAcquireTag) {
        int count = Math.max(0, Math.min(ds.count(), bufs.maxAllowedParticles));
        bufs.ensureParticleCapacity(count, false);
        int idLen = cacheIdTable != null ? cacheIdTable.length : 0;
        bufs.ensureIdCapacity(Math.max(count, idLen));

        int targetFloat = bufs.ping ? bufs.floatBufB : bufs.floatBufA;
        int targetInt = bufs.ping ? bufs.intBufB : bufs.intBufA;
        int targetHalf = bufs.ping ? bufs.halfBufB : bufs.halfBufA;
        int fComps = Math.min(ds.componentCount(0), bufs.floatComponents);
        int iComps = Math.min(ds.componentCount(1), bufs.intComponents);
        int hComps = Math.min(ds.componentCount(2), bufs.halfComponents);

        if (count > 0) {
            if ((fComps > 0 && targetFloat != 0) || (iComps > 0 && targetInt != 0)) {
                IntBuffer ib = MemoryUtil.memAllocInt(count);
                try {
                    if (fComps > 0 && targetFloat != 0) {
                        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, targetFloat);
                        for (int c = 0; c < fComps; c++) {
                            ib.clear();
                            ib.put(ds.component(0, c), 0, count).flip();
                            GL15C.glBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER,
                                    (long) c * bufs.stride * Integer.BYTES, ib);
                        }
                    }
                    if (iComps > 0 && targetInt != 0) {
                        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, targetInt);
                        for (int c = 0; c < iComps; c++) {
                            ib.clear();
                            ib.put(ds.component(1, c), 0, count).flip();
                            GL15C.glBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER,
                                    (long) c * bufs.stride * Integer.BYTES, ib);
                        }
                    }
                    GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
                } finally {
                    MemoryUtil.memFree(ib);
                }
            }
            if (hComps > 0 && targetHalf != 0) {
                ShortBuffer sb = MemoryUtil.memAllocShort(count);
                try {
                    GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, targetHalf);
                    for (int c = 0; c < hComps; c++) {
                        sb.clear();
                        int[] src = ds.component(2, c);
                        for (int r = 0; r < count; r++) {
                            sb.put((short) src[r]);
                        }
                        sb.flip();
                        GL15C.glBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER,
                                (long) c * bufs.stride * Short.BYTES, sb);
                    }
                    GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
                } finally {
                    MemoryUtil.memFree(sb);
                }
            }
        }

        // Restore persistent ID-to-index table (retained rows valid, dead gaps & truncated IDs -> -1)
        // and rebuild compacted FreeIDList + RWFreeIDListSizes.
        int idCap = bufs.idCapacity;
        int[] restoredIds = new int[idCap];
        Arrays.fill(restoredIds, -1);
        if (cacheIdTable != null && cacheIdTable.length > 0) {
            int limit = Math.min(cacheIdTable.length, idCap);
            for (int id = 0; id < limit; id++) {
                int mappedRow = cacheIdTable[id];
                if (mappedRow >= 0 && mappedRow < count) {
                    restoredIds[id] = mappedRow;
                }
            }
        } else {
            int limit = Math.min(count, idCap);
            for (int id = 0; id < limit; id++) {
                restoredIds[id] = id;
            }
        }

        int[] freeIds = new int[idCap];
        int freeCount = 0;
        for (int id = 0; id < idCap; id++) {
            if (restoredIds[id] < 0) {
                freeIds[freeCount++] = id;
            }
        }
        uploadIntArray(bufs.idToIndexBuf, restoredIds, idCap);
        uploadIntArray(bufs.freeIdBuf, freeIds, freeCount);
        writeSingleInt(bufs.freeIdSizesBuf, freeCount);
        bufs.freeIdCount = freeCount;

        // Preserve live particle count, untruncated totalSpawned, and monotonic AcquireTag
        bufs.currentCount = count;
        bufs.totalSpawned = Math.max(cacheTotalSpawned, count);
        int readOffset = bufs.ping ? 1 : 0;
        writeCounts(bufs.instanceCountsBuf, readOffset == 0 ? count : 0, readOffset == 1 ? count : 0);

        observeCachedParticleTag(cacheAcquireTag);
        if (Integer.compareUnsigned(cacheAcquireTag, bufs.acquireTag) > 0) {
            bufs.acquireTag = cacheAcquireTag;
        }
        state.liveGpuParticleCount.set(count);
        state.liveGpuCapacity.set(bufs.capacity);
        state.liveGpuTotalSpawned.set(bufs.totalSpawned);
        state.syncGpuAcquireTag(bufs.acquireTag);
    }

    /**
     * Reads back live GPU SSBO contents directly from OpenGL memory for verification/diagnostics
     * (Float, Int, Half, persistent ID table, FreeIDList count, capacity, totalSpawned, and AcquireTag).
     */
    public @Nullable GpuHandoffSnapshot inspectEmitterGpuState(EmitterState state, int probeRow, int[] probeIds) {
        EmitterGpuBuffers bufs = emitterBuffers.get(state);
        if (bufs == null) return null;
        int activeFloat = bufs.ping ? bufs.floatBufB : bufs.floatBufA;
        int activeInt = bufs.ping ? bufs.intBufB : bufs.intBufA;
        int activeHalf = bufs.ping ? bufs.halfBufB : bufs.halfBufA;

        float[] pFloats = new float[Math.min(bufs.floatComponents, 8)];
        if (probeRow >= 0 && probeRow < bufs.currentCount && activeFloat != 0) {
            for (int c = 0; c < pFloats.length; c++) {
                pFloats[c] = readSingleFloat(activeFloat, (long) c * bufs.stride + probeRow);
            }
        }
        int[] pInts = new int[bufs.intComponents];
        if (probeRow >= 0 && probeRow < bufs.currentCount && activeInt != 0) {
            for (int c = 0; c < pInts.length; c++) {
                pInts[c] = readSingleInt(activeInt, (long) c * bufs.stride + probeRow);
            }
        }
        float[] pHalves = new float[Math.min(bufs.halfComponents, 8)];
        if (probeRow >= 0 && probeRow < bufs.currentCount && activeHalf != 0) {
            for (int c = 0; c < pHalves.length; c++) {
                short raw = readSingleShort(activeHalf, (long) c * bufs.stride + probeRow);
                pHalves[c] = HalfFloat.toFloat(raw);
            }
        }
        int[] pIds = new int[probeIds != null ? probeIds.length : 0];
        for (int i = 0; i < pIds.length; i++) {
            int id = probeIds[i];
            pIds[i] = (id >= 0 && id < bufs.idCapacity && bufs.idToIndexBuf != 0)
                    ? readSingleInt(bufs.idToIndexBuf, id) : -1;
        }
        int gpuFreeCount = readSingleInt(bufs.freeIdSizesBuf, 0);
        return new GpuHandoffSnapshot(
                bufs.currentCount, bufs.stride, bufs.capacity, bufs.totalSpawned, bufs.acquireTag,
                bufs.floatComponents, bufs.intComponents, bufs.halfComponents,
                bufs.idCapacity, gpuFreeCount, pFloats, pInts, pHalves, pIds);
    }

    private List<DirectGlComputeKernel> resolveKernels(GpuProgramSpec gp) {
        String key = gp.namespace() + ":" + gp.pipeline();
        List<DirectGlComputeKernel> cached = kernelCache.get(key);
        if (cached != null) return cached;
        List<DirectGlComputeKernel> built = new ArrayList<>();
        for (ComputeStageSpec stage : gp.stages()) {
            DirectGlComputeKernel k = DirectGlComputeKernel.compile(stage);
            if (k != null) built.add(k);
        }
        kernelCache.put(key, built);
        return built;
    }

    private static EmitterGpuBuffers allocateBuffers(EmitterSpec spec, GpuProgramSpec gp) {
        refreshDeviceLimits();
        int floatComps = Math.max(spec.floatComponents(), gp.floatComponentCount());
        int intComps = Math.max(spec.intComponents(), gp.intComponentCount());
        int halfComps = Math.max(spec.halfComponents(), gp.halfComponentCount());
        int deviceCap = maximumParticleCapacity(floatComps, intComps, halfComps,
                cachedMaxStorageBufferBytes, cachedMaxTexelBufferElements);
        int maxAllowed = Math.max(0, Math.min(spec.declaredMaxParticles(), deviceCap));
        int initialParticles = Math.min(maxAllowed, 63);
        return new EmitterGpuBuffers(initialParticles, maxAllowed, floatComps, intComps, halfComps);
    }

    private static Map<String, Object> buildGlobalsMap(EmitterGpuBuffers bufs, GpuTickRequest tick,
                                                       int spawn, int readOffset, int writeOffset,
                                                       int threadCount, int dispatchTag) {
        Map<String, Object> m = new HashMap<>();
        float dt = Math.max(1e-4f, tick.deltaTime());

        m.put("SimStart", tick.reset() ? 1 : 0);
        m.put("EmitterTickCounter", dispatchTag);
        m.put("SpawnedParticlesAcquireTag", dispatchTag);
        m.put("IDAcquireTag", dispatchTag);
        m.put("Emitter_IDAcquireTag", dispatchTag);
        m.put("NumSpawnedInstances", spawn);
        m.put("ComponentBufferSizeRead", bufs.stride);
        m.put("ComponentBufferSizeWrite", bufs.stride);
        m.put("ReadInstanceCountOffset", readOffset);
        m.put("WriteInstanceCountOffset", writeOffset);
        m.put("DispatchThreadIdBounds", new int[]{threadCount, 1, 1});
        m.put("DispatchThreadIdToLinear", new int[]{1, 0, 0});
        m.put("IndirectDispatchArgsOffset", 0);

        int[] spawnOffsets = new int[8];
        float[] spawnParams = new float[32];
        int running = 0;
        List<SpawnInvocation> invs = tick.spawns();
        for (int i = 0; i < 8; i++) {
            if (i < invs.size()) {
                SpawnInvocation si = invs.get(i);
                int c = Math.max(0, Math.min(si.count(), spawn - running));
                running += c;
                spawnOffsets[i] = running;
                spawnParams[i * 4] = si.intervalDt() > 0f ? si.intervalDt() : (c > 0 ? dt / c : dt);
                spawnParams[i * 4 + 1] = si.interpStartDt();
                spawnParams[i * 4 + 2] = Float.intBitsToFloat(si.spawnGroup());
                spawnParams[i * 4 + 3] = (float) c;
            } else {
                spawnOffsets[i] = running;
            }
        }
        m.put("EmitterSpawnInfoOffsets", spawnOffsets);
        m.put("EmitterSpawnInfoParams", spawnParams);
        m.put("SimulationStageIterationInfo", new int[]{0, 1, 0, 0});
        m.put("ParticleIterationStateInfo", new int[]{0, 0});

        populateEngineUniforms(m, "", tick.currentRuntime640());
        populateEngineUniforms(m, "PREV_", tick.previousRuntime640());
        m.put("Engine_Emitter_TotalSpawnedParticles", bufs.totalSpawned);
        return m;
    }

    private static void populateEngineUniforms(Map<String, Object> m, String prefix, byte[] raw640) {
        if (raw640 == null || raw640.length < 640) return;
        ByteBuffer bb = ByteBuffer.wrap(raw640).order(ByteOrder.LITTLE_ENDIAN);
        for (Map.Entry<String, EngineSpan> e : ENGINE_SPANS.entrySet()) {
            EngineSpan s = e.getValue();
            String key = prefix + e.getKey();
            if (s.bytes() == 4) {
                if (s.isInt()) m.put(key, bb.getInt(s.offset()));
                else m.put(key, bb.getFloat(s.offset()));
            } else {
                int count = s.bytes() / 4;
                float[] arr = new float[count];
                for (int i = 0; i < count; i++) arr[i] = bb.getFloat(s.offset() + i * 4);
                m.put(key, arr);
            }
        }
    }

    private static void readbackParticles(EmitterState state, int floatSsbo, int stride, int floatComps,
                                          int liveCount, GpuTickRequest tick) {
        int count = Math.min(liveCount, READBACK_PARTICLE_CAP);
        int posComp = findFloatComponent(state.spec.attributes(), "Position");
        int colComp = findFloatComponent(state.spec.attributes(), "Color");
        int sizeComp = findFloatComponent(state.spec.attributes(), "SpriteSize");
        int widthComp = findFloatComponent(state.spec.attributes(), "RibbonWidth");
        int ageComp = findFloatComponent(state.spec.attributes(), "Age");
        int lifeComp = findFloatComponent(state.spec.attributes(), "Lifetime");
        if (posComp < 0) return;

        float[] px = readComponentSlice(floatSsbo, (long) posComp * stride, count);
        float[] py = readComponentSlice(floatSsbo, (long) (posComp + 1) * stride, count);
        float[] pz = readComponentSlice(floatSsbo, (long) (posComp + 2) * stride, count);
        float[] cr = colComp >= 0 && colComp + 3 < floatComps ? readComponentSlice(floatSsbo, (long) colComp * stride, count) : null;
        float[] cg = colComp >= 0 && colComp + 3 < floatComps ? readComponentSlice(floatSsbo, (long) (colComp + 1) * stride, count) : null;
        float[] cb = colComp >= 0 && colComp + 3 < floatComps ? readComponentSlice(floatSsbo, (long) (colComp + 2) * stride, count) : null;
        float[] ca = colComp >= 0 && colComp + 3 < floatComps ? readComponentSlice(floatSsbo, (long) (colComp + 3) * stride, count) : null;
        float[] sw = widthComp >= 0 ? readComponentSlice(floatSsbo, (long) widthComp * stride, count)
                : (sizeComp >= 0 ? readComponentSlice(floatSsbo, (long) sizeComp * stride, count) : null);
        float[] ages = ageComp >= 0 ? readComponentSlice(floatSsbo, (long) ageComp * stride, count) : null;
        float[] lifes = lifeComp >= 0 ? readComponentSlice(floatSsbo, (long) lifeComp * stride, count) : null;

        List<ParticleInstance> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            float ux = px[i], uy = py[i], uz = pz[i];
            if (!Float.isFinite(ux) || !Float.isFinite(uy) || !Float.isFinite(uz)) continue;
            ParticleInstance p = new ParticleInstance();
            p.x = p.prevX = state.spec.localSpace() ? tick.origin().x + ux * 0.01 : ux * 0.01;
            p.y = p.prevY = state.spec.localSpace() ? tick.origin().y + uz * 0.01 : uz * 0.01;
            p.z = p.prevZ = state.spec.localSpace() ? tick.origin().z + uy * 0.01 : uy * 0.01;
            float sz = (sw != null && Float.isFinite(sw[i]) && sw[i] > 0f) ? (sw[i] > 4f ? sw[i] * 0.01f : sw[i]) : 0.25f;
            p.width = p.startWidth = Math.max(0.05f, Math.min(4.0f, sz));
            p.height = p.startHeight = p.width;
            p.r = cr != null && Float.isFinite(cr[i]) ? clamp01(cr[i]) : 1f;
            p.g = cg != null && Float.isFinite(cg[i]) ? clamp01(cg[i]) : 1f;
            p.b = cb != null && Float.isFinite(cb[i]) ? clamp01(cb[i]) : 1f;
            p.a = ca != null && Float.isFinite(ca[i]) ? clamp01(ca[i]) : 1f;
            p.age = ages != null && Float.isFinite(ages[i]) ? ages[i] : 0f;
            p.lifetime = lifes != null && Float.isFinite(lifes[i]) && lifes[i] > 0f ? lifes[i] : 1f;
            out.add(p);
        }
        state.gpuReadbackParticles = out;
    }

    private static void copyPlanar(int srcBuf, int dstBuf, int components, int oldStride, int newStride,
                                   int liveCount, int elemBytes) {
        if (srcBuf == 0 || dstBuf == 0 || components <= 0 || liveCount <= 0) return;
        long rowBytes = (long) Math.min(liveCount, oldStride) * elemBytes;
        GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER, srcBuf);
        GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, dstBuf);
        for (int c = 0; c < components; c++) {
            GL31C.glCopyBufferSubData(GL31C.GL_COPY_READ_BUFFER, GL31C.GL_COPY_WRITE_BUFFER,
                    (long) c * oldStride * elemBytes, (long) c * newStride * elemBytes, rowBytes);
        }
        GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER, 0);
        GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, 0);
    }

    private static float[] readComponentSlice(int ssbo, long floatOffset, int count) {
        float[] arr = new float[count];
        FloatBuffer buf = MemoryUtil.memAllocFloat(count);
        try {
            GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, ssbo);
            GL15C.glGetBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, floatOffset * Float.BYTES, buf);
            buf.get(arr);
        } finally {
            GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
            MemoryUtil.memFree(buf);
        }
        return arr;
    }

    private static float readSingleFloat(int ssbo, long floatIndex) {
        FloatBuffer buf = MemoryUtil.memAllocFloat(1);
        try {
            GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, ssbo);
            GL15C.glGetBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, floatIndex * Float.BYTES, buf);
            return buf.get(0);
        } finally {
            GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
            MemoryUtil.memFree(buf);
        }
    }

    private static int readSingleInt(int ssbo, long intIndex) {
        IntBuffer buf = MemoryUtil.memAllocInt(1);
        try {
            GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, ssbo);
            GL15C.glGetBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, intIndex * Integer.BYTES, buf);
            return buf.get(0);
        } finally {
            GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
            MemoryUtil.memFree(buf);
        }
    }

    private static short readSingleShort(int ssbo, long shortIndex) {
        ShortBuffer buf = MemoryUtil.memAllocShort(1);
        try {
            GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, ssbo);
            GL15C.glGetBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, shortIndex * Short.BYTES, buf);
            return buf.get(0);
        } finally {
            GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
            MemoryUtil.memFree(buf);
        }
    }

    private static int findFloatComponent(List<AttributeSpec> attrs, String shortName) {
        for (AttributeSpec a : attrs) {
            if (a.type() != 0) continue;
            if (a.name().equalsIgnoreCase(shortName) || a.name().equalsIgnoreCase("Particles." + shortName)) {
                return a.component();
            }
        }
        return -1;
    }

    private static int uploadUbo(EmitterGpuBuffers bufs, String name, byte[] data) {
        int ubo = bufs.uboHandles.computeIfAbsent(name, k -> GL15C.glGenBuffers());
        ByteBuffer tmp = MemoryUtil.memAlloc(data.length);
        try {
            tmp.put(data).flip();
            GL15C.glBindBuffer(GL31C.GL_UNIFORM_BUFFER, ubo);
            GL15C.glBufferData(GL31C.GL_UNIFORM_BUFFER, tmp, GL15C.GL_DYNAMIC_DRAW);
            GL15C.glBindBuffer(GL31C.GL_UNIFORM_BUFFER, 0);
        } finally {
            MemoryUtil.memFree(tmp);
        }
        return ubo;
    }

    private static int createZeroBuffer(long bytes) {
        long clamped = Math.max(16L, bytes);
        int id = GL15C.glGenBuffers();
        ByteBuffer zeros = MemoryUtil.memCalloc((int) Math.min(Integer.MAX_VALUE, clamped));
        try {
            GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, id);
            GL15C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, zeros, GL15C.GL_DYNAMIC_COPY);
            GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
        } finally {
            MemoryUtil.memFree(zeros);
        }
        return id;
    }

    /** Allocates an SSBO directly in VRAM and clears it on the GPU without staging gigabytes of CPU RAM. */
    private static int createGpuZeroBuffer(long bytes) {
        long clamped = Math.max(16L, ((bytes + 3L) / 4L) * 4L);
        int id = GL15C.glGenBuffers();
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, id);
        GL15C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, clamped, GL15C.GL_DYNAMIC_COPY);
        IntBuffer zeroWord = MemoryUtil.memCallocInt(1);
        try {
            GL43C.glClearBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, GL30C.GL_R32UI,
                    GL30C.GL_RED_INTEGER, GL11C.GL_UNSIGNED_INT, zeroWord);
        } finally {
            MemoryUtil.memFree(zeroWord);
            GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
        }
        return id;
    }

    private static int createFilledIntBuffer(int count, int fillValue) {
        int id = GL15C.glGenBuffers();
        long bytes = Math.max(16L, (long) count * Integer.BYTES);
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, id);
        GL15C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, bytes, GL15C.GL_DYNAMIC_COPY);
        IntBuffer val = MemoryUtil.memAllocInt(1);
        try {
            val.put(0, fillValue);
            GL43C.glClearBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, GL30C.GL_R32I,
                    GL30C.GL_RED_INTEGER, GL11C.GL_INT, val);
        } finally {
            MemoryUtil.memFree(val);
            GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
        }
        return id;
    }

    private static int createFreeIdBuffer(int capacity) {
        int id = GL15C.glGenBuffers();
        IntBuffer buf = MemoryUtil.memAllocInt(capacity);
        try {
            for (int i = 0; i < capacity; i++) buf.put(i);
            buf.flip();
            GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, id);
            GL15C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, buf, GL15C.GL_DYNAMIC_COPY);
            GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
        } finally {
            MemoryUtil.memFree(buf);
        }
        return id;
    }

    private static void uploadIntArray(int ssbo, int[] data, int length) {
        if (ssbo == 0 || length <= 0) return;
        IntBuffer buf = MemoryUtil.memAllocInt(length);
        try {
            buf.put(data, 0, length).flip();
            GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, ssbo);
            GL15C.glBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, 0L, buf);
            GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
        } finally {
            MemoryUtil.memFree(buf);
        }
    }

    private static void writeSingleInt(int ssbo, int value) {
        IntBuffer buf = MemoryUtil.memAllocInt(1);
        try {
            buf.put(0, value);
            GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, ssbo);
            GL15C.glBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, 0L, buf);
            GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
        } finally {
            MemoryUtil.memFree(buf);
        }
    }

    private static void writeCounts(int ssbo, int c0, int c1) {
        IntBuffer buf = MemoryUtil.memAllocInt(4);
        try {
            buf.put(c0).put(c1).put(0).put(0).flip();
            GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, ssbo);
            GL15C.glBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, 0L, buf);
            GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
        } finally {
            MemoryUtil.memFree(buf);
        }
    }

    private static int readCount(int ssbo, int index) {
        IntBuffer buf = MemoryUtil.memAllocInt(1);
        try {
            GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, ssbo);
            GL15C.glGetBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, (long) index * Integer.BYTES, buf);
            GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
            return buf.get(0);
        } finally {
            MemoryUtil.memFree(buf);
        }
    }

    private static float clamp01(float v) { return Math.max(0f, Math.min(1f, v)); }

    public void disposeEmitter(EmitterState state) {
        EmitterGpuBuffers bufs = emitterBuffers.remove(state);
        if (bufs != null) bufs.dispose();
    }

    public void clear() {
        for (EmitterGpuBuffers bufs : emitterBuffers.values()) bufs.dispose();
        emitterBuffers.clear();
        for (List<DirectGlComputeKernel> list : kernelCache.values()) {
            for (DirectGlComputeKernel k : list) k.close();
        }
        kernelCache.clear();
        for (int tex : texelTextures.values()) GL11C.glDeleteTextures(tex);
        texelTextures.clear();
    }
}
