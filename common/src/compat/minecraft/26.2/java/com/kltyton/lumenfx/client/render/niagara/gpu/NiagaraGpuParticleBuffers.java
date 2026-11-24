package com.kltyton.lumenfx.client.render.niagara.gpu;

import com.kltyton.lumenfx.client.render.compute.ComputeRuntime;
import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.compute.ShaderProgram;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import com.kltyton.lumenfx.client.simulation.niagara.gpu.NiagaraGpuCountEstimate;
import com.kltyton.lumenfx.client.simulation.niagara.VectorDataSet;
import com.kltyton.lumenfx.client.render.niagara.gpu.cache.NiagaraGpuCacheReadbacks;
import com.kltyton.lumenfx.client.simulation.niagara.cache.NiagaraCacheRecorder;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache.Buffer;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache.Layout;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jspecify.annotations.Nullable;

/** Rendered particle storage plus two simulation buffers and GPU-owned live counts. */
public final class NiagaraGpuParticleBuffers implements NiagaraGpuParticleView, AutoCloseable {
    static final int DATA_BUFFERS = 3;
    private enum Mode { FULL, PARTIAL, READ_ONLY }
    private final String name;
    private final List<NiagaraDefinition.Attribute> attributes;
    private final NiagaraGpuDataBuffer[] data = new NiagaraGpuDataBuffer[DATA_BUFFERS];
    private final int[] countOffsets = new int[DATA_BUFFERS];
    private final @Nullable GpuBuffer ownedCounts;
    private final @Nullable NiagaraGpuCountArena sharedCounts;
    private final int countBase;
    private final int readerSnapshotCountOffset;
    private final NiagaraGpuCountReadback readback;
    private final @Nullable NiagaraGpuPersistentIds persistentIds;
    private final NiagaraGpuCountEstimate estimate = new NiagaraGpuCountEstimate();
    private final int[] spawnedInstances = new int[DATA_BUFFERS], acquireTags = new int[DATA_BUFFERS], estimatedInstances = new int[DATA_BUFFERS];
    private int current, destination, nextSimulation = 1, fullWrites;
    private @Nullable Mode mode;
    private boolean hasSourceCount, resetReaderPending, closed;
    private int resetReaderCountOffset = -1;
    private @Nullable RenderLease publishedRead;
    private boolean renderPublicationDeferred;

    /** The previous renderer data stays readable until the view has submitted its last draw. */
    public final class RenderLease implements NiagaraGpuParticleView, AutoCloseable {
        private final NiagaraGpuDataBuffer buffer = data[0];
        private final GpuBufferSlice counts = countsBuffer().slice();
        private final int offset = countOffsets[0];
        private final NiagaraGpuCountEstimate count = estimate.renderSnapshot();

        private RenderLease() { }
        @Override public NiagaraGpuDataBuffer current() { return buffer; }
        @Override public GpuBufferSlice countBuffer() { return counts; }
        @Override public int currentCountOffset() { return offset; }
        @Override public NiagaraGpuCountEstimate estimate() { return count; }

        @Override public void close() {
            RenderSystem.assertOnRenderThread();
            if (publishedRead != this) return;
            publishedRead = null;
            if (renderPublicationDeferred) {
                renderPublicationDeferred = false;
                if (!closed) publishRenderData();
            }
        }
    }

    public RenderLease retainRenderData() {
        RenderSystem.assertOnRenderThread();
        requireIdle();
        if (current != 0 || publishedRead != null) throw new IllegalStateException("Niagara renderer data is already retained or unpublished");
        publishedRead = new RenderLease();
        return publishedRead;
    }

    void detachPublishedWrite(CommandEncoder encoder, ComputeRuntime runtime) {
        if (publishedRead == null || current != 0) return;
        requireIdle();
        int working = 1;
        var published = data[0];
        if (data[working].stride() != published.stride()) {
            var replacement = new NiagaraGpuDataBuffer(name + "." + working, published.stride() - 1, attributes);
            data[working].close();
            data[working] = replacement;
        }
        var counts = countsBuffer();
        var workingCount = counts.slice((long)countOffsets[working] * Integer.BYTES, Integer.BYTES);
        if (hasSourceCount) {
            published.copyTo(encoder, data[working]);
            encoder.copyToBuffer(counts.slice((long)countOffsets[0] * Integer.BYTES, Integer.BYTES),
                    workingCount);
            if (persistentIds != null) persistentIds.copyTable(encoder, 0, working);
            spawnedInstances[working] = spawnedInstances[0];
            acquireTags[working] = acquireTags[0];
            estimatedInstances[working] = estimatedInstances[0];
        } else {
            runtime.clearBuffer(workingCount, 0);
            spawnedInstances[working] = acquireTags[working] = estimatedInstances[working] = 0;
        }
        current = working;
        nextSimulation = 2;
        renderPublicationDeferred = true;
    }

    public NiagaraGpuParticleBuffers(String name, List<NiagaraDefinition.Attribute> attributes,
                                     NiagaraGpuPrograms programs, boolean requiresPersistentIds) {
        this(name, attributes, programs.runtime(), programs, requiresPersistentIds, null, 0, -1);
    }

    public NiagaraGpuParticleBuffers(String name, List<NiagaraDefinition.Attribute> attributes,
                                     NiagaraGpuPrograms programs, boolean requiresPersistentIds,
                                     NiagaraGpuCountArena arena, int emitterIndex) {
        this(name, attributes, programs.runtime(), programs, requiresPersistentIds, arena, arena.emitterOffset(emitterIndex),
                arena.readerSnapshotOffset(emitterIndex));
    }

    public NiagaraGpuParticleBuffers(String name, List<NiagaraDefinition.Attribute> attributes, ComputeRuntime runtime) {
        this(name, attributes, runtime, null, false, null, 0, -1);
    }

    private NiagaraGpuParticleBuffers(String name, List<NiagaraDefinition.Attribute> attributes,
                                      ComputeRuntime runtime, @Nullable NiagaraGpuPrograms programs, boolean requiresPersistentIds,
                                      @Nullable NiagaraGpuCountArena sharedCounts, int countBase, int readerSnapshotCountOffset) {
        RenderSystem.assertOnRenderThread();
        this.name = name;
        this.attributes = List.copyOf(attributes);
        this.countBase = countBase;
        this.readerSnapshotCountOffset = sharedCounts == null ? DATA_BUFFERS : readerSnapshotCountOffset;
        this.sharedCounts = sharedCounts;
        GpuBuffer countBuffer = null;
        NiagaraGpuCountReadback feedback = null;
        NiagaraGpuPersistentIds ids = null;
        try {
            for (int i = 0; i < DATA_BUFFERS; i++) {
                data[i] = new NiagaraGpuDataBuffer(name + "." + i, 0, this.attributes);
                countOffsets[i] = countBase + i;
            }
            if (sharedCounts == null) {
                countBuffer = RenderSystem.getDevice().createBuffer(() -> name + ".Counts",
                        ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_COPY_SRC),
                        (DATA_BUFFERS + 1L) * Integer.BYTES);
                runtime.clearBuffer(countBuffer.slice(), 0);
            }
            feedback = new NiagaraGpuCountReadback(name);
            if (requiresPersistentIds) ids = new NiagaraGpuPersistentIds(name, java.util.Objects.requireNonNull(programs));
            ownedCounts = countBuffer;
            readback = feedback;
            persistentIds = ids;
        } catch (RuntimeException | Error error) {
            if (ids != null) ids.close();
            if (feedback != null) feedback.close();
            if (countBuffer != null) countBuffer.close();
            for (var buffer : data) if (buffer != null) buffer.close();
            throw error;
        }
    }

    private GpuBuffer countsBuffer() {
        return sharedCounts == null ? Objects.requireNonNull(ownedCounts) : sharedCounts.buffer();
    }

    public void begin(ComputeRuntime runtime, int maximumOutputParticles) {
        RenderSystem.assertOnRenderThread();
        requireIdle();
        if (maximumOutputParticles < 0) throw new IllegalArgumentException("Negative GPU output capacity");
        int deviceMaximum = NiagaraGpuDataBuffer.maximumParticleCapacity(runtime, attributes);
        if (maximumOutputParticles > deviceMaximum)
            throw new IllegalArgumentException("Niagara GPU output capacity exceeds device limit " + deviceMaximum);
        destination = nextSimulation;
        if ((long) maximumOutputParticles + 1 > data[destination].stride()) {
            NiagaraGpuDataBuffer replacement = new NiagaraGpuDataBuffer(name + "." + destination,
                    maximumOutputParticles, attributes);
            data[destination].close();
            data[destination] = replacement;
        }
        runtime.clearBuffer(countsBuffer().slice((long) countOffsets[destination] * Integer.BYTES, Integer.BYTES), 0);
        if (persistentIds != null) persistentIds.beginWrite(destination);
        mode = Mode.FULL;
        fullWrites++;
    }

    public void beginPartial() {
        RenderSystem.assertOnRenderThread();
        requireIdle();
        if (persistentIds != null) persistentIds.beginWrite(current);
        mode = Mode.PARTIAL;
    }

    public void uploadCache(CommandEncoder encoder, ComputeRuntime runtime, VectorDataSet source, int acquireTag, int[] idTable) {
        prepareIds(encoder, Math.max(source.count(), idTable.length));
        begin(runtime, source.count());
        try {
            NiagaraGpuDataUpload.data(encoder, source, data[destination]);
            NiagaraGpuDataUpload.count(encoder, counts(), writeCountOffset(), source.count());
            if (persistentIds != null) persistentIds.uploadCacheTable(encoder, destination, idTable);
            stageMetadata(0, acquireTag, estimatedInstances[current], source.count());
            estimate.reset();
            estimate.cpuCount(source.count());
        } catch (RuntimeException | Error failure) {
            cancelBeforeDispatch();
            throw failure;
        }
        publish();
        compactIds(encoder);
        publishRenderData();
    }
    public void beginReadOnly() { RenderSystem.assertOnRenderThread(); requireIdle(); mode = Mode.READ_ONLY; }
    public @Nullable NiagaraGpuDataBuffer input() { requireWriting(); return mode == Mode.PARTIAL ? null : data[current]; }
    public @Nullable NiagaraGpuDataBuffer output() {
        requireWriting();
        return mode == Mode.READ_ONLY ? null : data[mode == Mode.FULL ? destination : current];
    }
    public GpuBufferSlice counts() { requireWriting(); return countsBuffer().slice(); }
    public int readCountOffset() { requireWriting(); return hasSourceCount ? countOffsets[current] : -1; }
    public int writeCountOffset() {
        requireWriting();
        return mode == Mode.FULL ? countOffsets[destination] : hasSourceCount ? countOffsets[current] : -1;
    }

    void stageMetadata(int spawned, int acquireTag, int sourceEstimate, int destinationEstimate) {
        requireWriting();
        resetReaderPending = false;
        if (mode != Mode.PARTIAL) estimatedInstances[current] = sourceEstimate;
        if (mode == Mode.READ_ONLY) return;
        int target = mode == Mode.FULL ? destination : current;
        estimatedInstances[target] = destinationEstimate;
        spawnedInstances[target] = spawned;
        acquireTags[target] = acquireTag;
    }

    int readerSpawnedInstances(boolean self) {
        int index = self && mode == Mode.FULL ? destination : current;
        return spawnedInstances[index];
    }
    int readerAcquireTag(boolean self) {
        int index = self && mode == Mode.FULL ? destination : current;
        return acquireTags[index];
    }

    public int currentSpawnedInstances() { requireIdle(); return spawnedInstances[current]; }

    /** Binds only the particle dataset resources; interfaces and other shader inputs are supplied separately. */
    public void bindParticleData(ShaderProgram program, ComputeBindings.Builder bindings, GpuBufferSlice dummyInput) {
        requireWriting();
        NiagaraGpuDataBuffer input = input(), output = output();
        for (ShaderBinding binding : program.bindings()) {
            if (!(binding instanceof ShaderBinding.StorageBuffer storage)) continue;
            if (storage.name().equals("FreeIDList") || storage.name().equals("RWIDToIndexTable")) {
                if (persistentIds == null) {
                    if (storage.name().equals("FreeIDList")) bindings.storage(storage.name(), dummyInput, storage.access());
                    else throw new IllegalStateException("Niagara stage requires a persistent ID output table");
                } else persistentIds.bind(storage, bindings, mode == Mode.READ_ONLY ? -1 : mode == Mode.FULL ? destination : current);
                continue;
            }
            GpuBuffer buffer = switch (storage.name()) {
                case "InputFloat" -> input == null ? null : input.floats();
                case "InputInt" -> input == null ? null : input.integers();
                case "InputHalf" -> input == null ? null : input.halves();
                case "RWOutputFloat" -> output == null ? null : output.floats();
                case "RWOutputInt" -> output == null ? null : output.integers();
                case "RWOutputHalf" -> output == null ? null : output.halves();
                default -> null;
            };
            boolean inputName = storage.name().equals("InputFloat") || storage.name().equals("InputInt")
                    || storage.name().equals("InputHalf");
            boolean outputName = storage.name().equals("RWOutputFloat") || storage.name().equals("RWOutputInt")
                    || storage.name().equals("RWOutputHalf");
            if (inputName) bindings.storage(storage.name(), buffer == null ? dummyInput : buffer.slice(), storage.access());
            else if (outputName) {
                if (buffer == null) throw new IllegalStateException("Missing Niagara output buffer " + storage.name());
                bindings.storage(storage.name(), buffer.slice(), storage.access());
            } else if (storage.name().equals("RWInstanceCounts")) {
                bindings.storage(storage.name(), countsBuffer().slice(), storage.access());
            }
        }
    }

    public void publish() {
        RenderSystem.assertOnRenderThread();
        requireWriting();
        if (mode == Mode.FULL) {
            current = destination;
            nextSimulation = destination == 1 ? 2 : 1;
            hasSourceCount = true;
        }
        mode = null;
    }

    /** Publishes the final dispatch while retaining the old rendered allocation for reuse. */
    public void publishRenderData() {
        RenderSystem.assertOnRenderThread();
        requireIdle();
        if (publishedRead != null) {
            renderPublicationDeferred = true;
            return;
        }
        if (current != 0) {
            NiagaraGpuDataBuffer rendered = data[0]; data[0] = data[current]; data[current] = rendered;
            swap(countOffsets, 0, current); swap(spawnedInstances, 0, current);
            swap(acquireTags, 0, current); swap(estimatedInstances, 0, current);
            if (persistentIds != null) persistentIds.swapTables(0, current);
            current = 0;
        }
        nextSimulation = 1;
        if (fullWrites == 1) {
            if (data[2].hasExpandedStorage()) {
                NiagaraGpuDataBuffer empty = new NiagaraGpuDataBuffer(name + ".2", 0, attributes);
                data[2].close();
                data[2] = empty;
            }
            if (persistentIds != null) persistentIds.releaseTable(2);
        }
        fullWrites = 0;
    }

    private static void swap(int[] values, int a, int b) {
        int old = values[a]; values[a] = values[b]; values[b] = old;
    }

    public boolean requestCount(CommandEncoder encoder) {
        return requestCount(encoder, estimate.current());
    }

    public boolean requestCount(CommandEncoder encoder, int sourceEstimate) {
        RenderSystem.assertOnRenderThread();
        requireIdle();
        long sequence = estimate.beginReadback(sourceEstimate);
        if (sequence < 0) return false;
        try {
            boolean submitted = readback.submit(encoder, countsBuffer().slice((long) countOffsets[current] * Integer.BYTES, Integer.BYTES), sequence);
            if (!submitted) estimate.cancelReadback(sequence);
            return submitted;
        } catch (RuntimeException | Error error) {
            estimate.cancelReadback(sequence);
            throw error;
        }
    }

    public void cancelBeforeDispatch() { RenderSystem.assertOnRenderThread(); requireWriting(); mode = null; }
    public NiagaraGpuDataBuffer current() { requireIdle(); return data[current]; }
    public GpuBufferSlice currentCount() { requireIdle(); return countsBuffer().slice((long) countOffsets[current] * Integer.BYTES, Integer.BYTES); }
    public GpuBufferSlice countBuffer() { requireIdle(); return countsBuffer().slice(); }
    NiagaraGpuDataBuffer readerCurrentData() {
        if (closed) throw new IllegalStateException("Niagara particle buffer is closed");
        return data[current];
    }
    int readerCurrentCountOffset() { return resetReaderPending ? resetReaderCountOffset : hasSourceCount ? countOffsets[current] : -1; }
    int readerCurrentInstances() { return estimatedInstances[current]; }
    @Nullable GpuBuffer readerIdTable() { return persistentIds == null ? null : persistentIds.currentTable(current); }
    @Nullable GpuBuffer readerFreeIds() { return persistentIds == null ? null : persistentIds.freeIds(); }
    GpuBufferSlice readerCountBuffer() { return countsBuffer().slice(); }
    List<NiagaraDefinition.Attribute> readerAttributes() { return attributes; }
    int readerSnapshotCountOffset() { return readerSnapshotCountOffset; }
    public int currentCountOffset() { requireIdle(); return countOffsets[current]; }
    public boolean hasSourceCount() { requireIdle(); return hasSourceCount; }
    public CompletableFuture<Buffer> captureCache(CommandEncoder encoder, @Nullable ClientLevel world, Layout layout) {
        requireIdle();
        if (!hasSourceCount) return CompletableFuture.completedFuture(NiagaraCacheRecorder.empty());
        return NiagaraGpuCacheReadbacks.capture(encoder, world, layout, current(), currentCount(), readerAcquireTag(false), readerIdTable());
    }
    public NiagaraGpuCountEstimate estimate() {
        if (closed) throw new IllegalStateException("Niagara particle buffer is closed");
        return estimate;
    }
    public void pollCount() {
        NiagaraGpuCountReadback.Sample sample;
        while ((sample = readback.poll()) != null) estimate.accept(sample);
    }
    public void prepareIds(CommandEncoder encoder, int particleCapacity) {
        requireIdle();
        if (persistentIds != null) persistentIds.ensure(encoder, particleCapacity);
    }
    public void compactIds(CommandEncoder encoder) {
        requireIdle();
        if (persistentIds != null) persistentIds.compact(encoder, current);
    }
    public void reset(ComputeRuntime runtime) {
        RenderSystem.assertOnRenderThread();
        reset(runtime, null, false);
    }

    void clearForExecutionState(ComputeRuntime runtime) {
        RenderSystem.assertOnRenderThread();
        if (publishedRead == null) {
            reset(runtime);
            return;
        }
        requireIdle();
        // Keep the old view's count and buffer alive until its last draw.
        var counts = countsBuffer();
        for (int slot = 1; slot < DATA_BUFFERS; slot++) {
            runtime.clearBuffer(counts.slice((long) countOffsets[slot] * Integer.BYTES, Integer.BYTES), 0);
            spawnedInstances[slot] = acquireTags[slot] = estimatedInstances[slot] = 0;
        }
        current = 1;
        nextSimulation = 2;
        hasSourceCount = resetReaderPending = false;
        resetReaderCountOffset = -1;
        fullWrites = 0;
        if (persistentIds != null) persistentIds.reset();
        estimate.reset();
        renderPublicationDeferred = true;
    }

    void reset(ComputeRuntime runtime, @Nullable CommandEncoder encoder, boolean retainReaderData) {
        RenderSystem.assertOnRenderThread();
        requireIdle();
        int resetSnapshotOffset = -1;
        if (retainReaderData && hasSourceCount && readerSnapshotCountOffset >= 0) {
            java.util.Objects.requireNonNull(encoder, "Reset snapshot requires a command encoder").copyToBuffer(
                    countsBuffer().slice((long)countOffsets[current] * Integer.BYTES, Integer.BYTES),
                    countsBuffer().slice((long)readerSnapshotCountOffset * Integer.BYTES, Integer.BYTES));
            resetSnapshotOffset = readerSnapshotCountOffset;
        }
        if (!retainReaderData)
            runtime.clearBuffer(countsBuffer().slice((long) countBase * Integer.BYTES, (long)DATA_BUFFERS * Integer.BYTES), 0);
        resetReaderPending = retainReaderData;
        resetReaderCountOffset = resetSnapshotOffset;
        hasSourceCount = false;
        if (!retainReaderData) {
            current = 0;
            nextSimulation = 1;
            fullWrites = 0;
            java.util.Arrays.fill(spawnedInstances, 0);
            java.util.Arrays.fill(acquireTags, 0);
            java.util.Arrays.fill(estimatedInstances, 0);
        }
        if (!retainReaderData && persistentIds != null) persistentIds.reset();
        estimate.reset();
    }

    private void requireWriting() {
        if (closed || mode == null) throw new IllegalStateException("Niagara particle buffer has no active stage");
    }

    private void requireIdle() {
        if (closed || mode != null) throw new IllegalStateException("Niagara particle buffer already has an active stage");
    }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (closed) return;
        closed = true;
        readback.close();
        if (persistentIds != null) persistentIds.close();
        if (ownedCounts != null) ownedCounts.close();
        for (var buffer : data) buffer.close();
    }
}
