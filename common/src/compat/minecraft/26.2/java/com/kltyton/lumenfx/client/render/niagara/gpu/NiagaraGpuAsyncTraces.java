package com.kltyton.lumenfx.client.render.niagara.gpu;

import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.effect.niagara.collision.NiagaraAsyncGpuTraceDefinition;
import com.kltyton.lumenfx.effect.niagara.collision.NiagaraAsyncGpuTraceDefinition.Provider;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraPlatformEnvironment;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Owns the request arena and the previous-render-frame results of each physical trace interface. */
final class NiagaraGpuAsyncTraces implements AutoCloseable {
    static final int RAY_STRIDE = 8 * Integer.BYTES;
    static final int RESULT_STRIDE = 8 * Integer.BYTES;
    static final int COUNTS_BYTES = 3 * Integer.BYTES;
    private static final int THREADS = 32;

    final class Batch implements AutoCloseable {
        private final String identity;
        private NiagaraAsyncGpuTraceDefinition definition;
        private @Nullable GpuBuffer requests, counts, previous, output;
        private long touchedFrame = Long.MIN_VALUE, lastOutputFrame = Long.MIN_VALUE, allocatedFrame = Long.MIN_VALUE;
        private long touchedSubmission = Long.MIN_VALUE;
        private int maximum, allocatedMaximum, lastOutputCount, previousCount;

        Batch(String identity, NiagaraAsyncGpuTraceDefinition definition) {
            this.identity = identity;
            this.definition = definition;
        }

        void add(int particles) {
            touchedSubmission = submission;
            if (touchedFrame != frame) {
                touchedFrame = frame;
                maximum = 0;
                previousCount = 0;
                if (lastOutputFrame == frame - 1) {
                    var old = previous;
                    previous = output;
                    output = old;
                    previousCount = lastOutputCount;
                }
            }
            if (definition.provider() != Provider.NONE)
                maximum = Math.addExact(maximum, Math.multiplyExact(particles, definition.maxTracesPerParticle()));
        }

        void finalizeRequests(CommandEncoder encoder) {
            if (allocatedFrame != frame) {
                counts = ensure(encoder, counts, "Counts", COUNTS_BYTES, 0);
                programs.runtime().clearBuffer(counts.slice(), 0);
                if (requests != null) programs.runtime().clearBuffer(requests.slice(), 0);
                if (previousCount == 0 && previous != null) programs.runtime().clearBuffer(previous.slice(), 0);
                allocatedMaximum = 0;
            } else if (allocatedMaximum == maximum) return;
            requests = ensure(encoder, requests, "Requests", bytes(maximum, RAY_STRIDE), bytesOrZero(allocatedMaximum, RAY_STRIDE));
            previous = ensure(encoder, previous, "Previous", bytes(maximum, RESULT_STRIDE), bytesOrZero(previousCount, RESULT_STRIDE));
            int resultPrefix = Math.max(allocatedMaximum, previousCount);
            if (maximum > resultPrefix)
                programs.runtime().clearBuffer(previous.slice(bytesOrZero(resultPrefix, RESULT_STRIDE),
                        bytesOrZero(maximum - resultPrefix, RESULT_STRIDE)), 0);
            allocatedMaximum = maximum;
            allocatedFrame = frame;
        }

        int maximum() { return maximum; }
        GpuBufferSlice requests() { return Objects.requireNonNull(requests).slice(0, bytes(maximum, RAY_STRIDE)); }
        GpuBufferSlice counts() { return Objects.requireNonNull(counts).slice(); }
        GpuBufferSlice previous() { return Objects.requireNonNull(previous).slice(0, bytes(maximum, RESULT_STRIDE)); }

        private void dispatch(CommandEncoder encoder, NiagaraGpuView view, NiagaraSceneInputs scene, NiagaraPlatformEnvironment platform) {
            if (maximum == 0) return;
            output = ensure(encoder, output, "Results", bytes(maximum, RESULT_STRIDE), 0);
            var results = output.slice(0, bytes(maximum, RESULT_STRIDE));
            programs.runtime().clearBuffer(results, 0);
            var hardware = scene.hardwareTrace();
            Provider requested = definition.provider() == Provider.DEFAULT ? defaultProvider : definition.provider();
            // Like the native helper, resolve Default once and clear the results if that provider becomes unavailable.
            Provider provider = platform.asyncTraceSettings().select(requested,
                    hardware != null && platform.hardwareRayTraceSupported(), distanceFieldAvailable(platform));
            if (provider == Provider.HARDWARE_RAY_TRACING)
                Objects.requireNonNull(hardware).trace(encoder, view, requests(), counts(), results, maximum, definition.maxRetraces());
            else if (provider == Provider.GLOBAL_DISTANCE_FIELD)
                traceDistanceField(encoder, view, NiagaraGlobalDistanceField.orEmpty(scene.globalDistanceField()),
                        requests(), counts(), results, maximum);
        }

        private void abort() {
            if (lastOutputFrame == frame) lastOutputFrame = Long.MIN_VALUE;
            allocatedFrame = Long.MIN_VALUE;
            maximum = 0;
        }

        private void publish() {
            if (maximum == 0) return;
            lastOutputFrame = frame;
            lastOutputCount = maximum;
        }

        private GpuBuffer ensure(CommandEncoder encoder, @Nullable GpuBuffer current, String suffix, long required, long keep) {
            if (current != null && current.size() >= required) return current;
            if (required > programs.runtime().maxStorageBufferBytes())
                throw new IllegalArgumentException("Niagara asynchronous GPU trace buffer exceeds device capacity: " + identity);
            var replacement = RenderSystem.getDevice().createBuffer(() -> "Niagara.AsyncTrace." + identity + "." + suffix,
                    ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_COPY_SRC), required);
            try {
                programs.runtime().clearBuffer(replacement.slice(), 0);
                if (current != null && keep != 0) encoder.copyToBuffer(current.slice(0, keep), replacement.slice(0, keep));
            } catch (RuntimeException | Error failure) {
                RenderSystem.queueFencedTask(replacement::close);
                throw failure;
            }
            retire(current);
            return replacement;
        }

        @Override public void close() {
            retire(requests);
            retire(counts);
            retire(previous);
            retire(output);
            requests = counts = previous = output = null;
        }
    }

    private final NiagaraGpuPrograms programs;
    private final Map<Object, Batch> batches = new LinkedHashMap<>();
    private final Batch dummy = new Batch("Dummy", new NiagaraAsyncGpuTraceDefinition(0, 0, Provider.NONE));
    private long frame = -1;
    private long submission;
    private long lastRenderedFrame = Long.MIN_VALUE;
    private long providerFrame = Long.MIN_VALUE;
    private Provider defaultProvider = Provider.NONE;
    private @Nullable NiagaraPlatformEnvironment deferredPlatform;
    private boolean synthetic;
    private boolean open;

    NiagaraGpuAsyncTraces(NiagaraGpuPrograms programs) { this.programs = programs; }

    void beginFrame(long renderFrame, NiagaraSceneInputs scene, NiagaraPlatformEnvironment platform) {
        if (open) throw new IllegalStateException("Niagara asynchronous GPU trace frame is already open");
        frameBoundary(renderFrame);
        if (providerFrame != frame) {
            defaultProvider = platform.asyncTraceSettings().select(Provider.DEFAULT,
                    scene.hardwareTrace() != null && platform.hardwareRayTraceSupported(), distanceFieldAvailable(platform));
            providerFrame = frame;
        }
        submission = Math.incrementExact(submission);
        open = true;
    }

    private static boolean distanceFieldAvailable(NiagaraPlatformEnvironment platform) {
        String format = platform.shaderPlatforms().featureLevel();
        // GSDF remains available during clipmap initialization on feature level SM5 and above.
        return platform.globalDistanceFieldTraceSupported() && !format.contains("ES3_1") && !format.equals("METAL_SIM");
    }

    void frameBoundary(long renderFrame) {
        if (renderFrame != Long.MIN_VALUE && renderFrame < lastRenderedFrame)
            throw new IllegalStateException("Niagara asynchronous GPU trace frame moved backward");
        if (renderFrame != Long.MIN_VALUE && renderFrame == lastRenderedFrame && !synthetic) return;
        abortFrame();
        if (renderFrame == Long.MIN_VALUE) {
            frame = Math.incrementExact(frame);
            synthetic = true;
        } else {
            long elapsed = synthetic || lastRenderedFrame == Long.MIN_VALUE ? 1 : renderFrame - lastRenderedFrame;
            frame = Math.addExact(frame, elapsed);
            lastRenderedFrame = renderFrame;
            synthetic = false;
        }
        retireInactive();
    }

    Batch prepare(Object proxy, String identity, NiagaraAsyncGpuTraceDefinition definition, int particles) {
        if (!open) throw new IllegalStateException("No active Niagara asynchronous GPU trace frame");
        if (definition.maxTracesPerParticle() == 0 || definition.provider() == Provider.NONE) return prepareDummy();
        var batch = batches.computeIfAbsent(proxy, key -> new Batch(identity, definition));
        batch.definition = definition;
        batch.add(particles);
        return batch;
    }

    Batch prepareDummy() {
        if (!open) throw new IllegalStateException("No active Niagara asynchronous GPU trace frame");
        dummy.add(0);
        return dummy;
    }

    private void retireInactive() {
        var iterator = batches.values().iterator();
        while (iterator.hasNext()) {
            Batch batch = iterator.next();
            if (batch.touchedFrame >= frame - 1) continue;
            batch.close();
            iterator.remove();
        }
    }

    void finishFrame(CommandEncoder encoder, NiagaraGpuView view, NiagaraSceneInputs scene, NiagaraPlatformEnvironment platform) {
        if (!open) throw new IllegalStateException("No active Niagara asynchronous GPU trace frame");
        for (var batch : batches.values()) if (batch.touchedSubmission == submission) batch.dispatch(encoder, view, scene, platform);
        for (var batch : batches.values()) if (batch.touchedSubmission == submission) batch.publish();
        open = false;
    }

    void deferSubmission(NiagaraPlatformEnvironment platform) {
        if (!open || synthetic) throw new IllegalStateException("No rendered Niagara trace submission to defer");
        if (deferredPlatform == null) deferredPlatform = platform;
        open = false;
    }

    boolean hasDeferredFrame(long renderFrame) {
        return !synthetic && renderFrame == lastRenderedFrame && deferredPlatform != null;
    }

    boolean deferredFrameNeedsDistanceField() {
        for (var batch : batches.values()) {
            if (batch.touchedFrame != frame || batch.maximum == 0) continue;
            Provider requested = batch.definition.provider();
            if (requested == Provider.GLOBAL_DISTANCE_FIELD
                    || requested == Provider.DEFAULT && defaultProvider == Provider.GLOBAL_DISTANCE_FIELD) return true;
        }
        return false;
    }

    void finishDeferredFrame(CommandEncoder encoder, NiagaraGpuView view) {
        if (!hasDeferredFrame(view.renderFrameSequence())) return;
        if (open) throw new IllegalStateException("Cannot trace while Niagara is submitting rays");
        var platform = Objects.requireNonNull(deferredPlatform);
        try {
            for (var batch : batches.values())
                if (batch.touchedFrame == frame) batch.dispatch(encoder, view, view.sceneInputs(), platform);
            for (var batch : batches.values())
                if (batch.touchedFrame == frame) batch.publish();
        } catch (RuntimeException | Error failure) {
            abortFrame();
            throw failure;
        } finally {
            deferredPlatform = null;
        }
    }

    void abortSubmission() {
        if (open) abortFrame();
    }

    void abortFrame() {
        if (!open && deferredPlatform == null) return;
        for (var batch : batches.values()) if (batch.touchedFrame == frame) batch.abort();
        open = false;
        deferredPlatform = null;
    }

    private void traceDistanceField(CommandEncoder encoder, NiagaraGpuView view, NiagaraGlobalDistanceField field,
                                    GpuBufferSlice requests, GpuBufferSlice counts, GpuBufferSlice results, int maximum) {
        var shader = programs.globalDistanceFieldTrace();
        var resources = ComputeBindings.builder();
        for (var binding : shader.shader().bindings()) {
            if (binding instanceof ShaderBinding.StorageBuffer storage) {
                var buffer = switch (storage.name()) {
                    case "Traces" -> requests;
                    case "TraceCounts" -> counts;
                    case "Results" -> results;
                    default -> throw new IllegalArgumentException("Unexpected Niagara async trace buffer " + storage.name());
                };
                resources.storage(storage.name(), buffer, storage.access());
            } else if (binding instanceof ShaderBinding.SampledTexture sampled) {
                var volume = Objects.requireNonNull(field.texture(sampled.name(), programs), "Unknown distance field texture: " + sampled.name());
                resources.volume(sampled.name(), volume, 0);
            }
        }
        int groups = Math.ceilDiv(maximum, THREADS);
        int width = Math.min(groups, programs.runtime().maxWorkGroups(0));
        int height = Math.ceilDiv(groups, width);
        int rowThreads = Math.multiplyExact(width, THREADS);
        shader.dispatch(encoder, resources, name -> switch (name) {
            case "TracesOffset", "TraceCountsOffset", "ResultsOffset" -> word(0);
            case "DistanceThreshold" -> word(Float.floatToRawIntBits(1f));
            case "MaxTraceCount" -> word(maximum);
            case "TraceRowThreads" -> word(rowThreads);
            default -> {
                byte[] value = field.value(name);
                yield value == null ? view.value(name) : value;
            }
        }, width, height, 1);
    }

    static byte[] word(int value) { return ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array(); }
    private static long bytes(int count, int stride) { return bytesOrZero(Math.max(count, 1), stride); }
    private static long bytesOrZero(int count, int stride) { return Math.multiplyExact((long)count, stride); }
    private static void retire(@Nullable GpuBuffer buffer) {
        if (buffer != null) RenderSystem.queueFencedTask(buffer::close);
    }

    @Override public void close() {
        try { batches.values().forEach(Batch::close); }
        finally {
            try { dummy.close(); }
            finally { batches.clear(); open = false; deferredPlatform = null; }
        }
    }
}
