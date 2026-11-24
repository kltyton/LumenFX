package com.kltyton.lumenfx.client.render.niagara.stateless;

import com.kltyton.lumenfx.client.render.compute.ComputeRuntime;
import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import com.kltyton.lumenfx.client.render.compute.ComputeKernel;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuPrograms;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Same-frame emitter requests share a result; unused allocations expire after the following frame. */
public final class NiagaraStatelessDataCache implements AutoCloseable {
    private record Entry(List<NiagaraDefinition.Attribute> layout, NiagaraStatelessDataBuffer buffer) { }
    private final ComputeRuntime runtime;
    private final Map<Long, Entry> used = new LinkedHashMap<>();
    private final List<Entry> free = new ArrayList<>();
    private final List<ComputeKernel.Dispatch> requests = new ArrayList<>();
    private @Nullable GpuBuffer counters;
    private int reservedSlots, nextSlot;
    private long frame;
    private boolean open, closed;
    private boolean deferredGeneration;

    public NiagaraStatelessDataCache(ComputeRuntime runtime) { this.runtime = Objects.requireNonNull(runtime); }

    public long frame() { requireFrame(frame); return frame; }

    public long beginFrame(int requests) {
        RenderSystem.assertOnRenderThread();
        if (closed || open) throw new IllegalStateException("Niagara analytic cache cannot begin another frame");
        if (requests < 0) throw new IllegalArgumentException("Negative Niagara analytic count reservation");
        long bytes = (long)requests * Integer.BYTES;
        if (bytes > runtime.maxStorageBufferBytes()) throw new IllegalArgumentException("Niagara analytic count reservation exceeds the device buffer limit");
        if (requests > 0 && (counters == null || counters.size() < bytes)) {
            GpuBuffer replacement = RenderSystem.getDevice().createBuffer(() -> "Niagara.Stateless.InstanceCounts",
                    ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_SRC | GpuBuffer.USAGE_COPY_DST), bytes);
            if (counters != null) counters.close();
            counters = replacement;
        }
        if (requests > 0) runtime.clearBuffer(Objects.requireNonNull(counters).slice(0, bytes), 0);
        reservedSlots = requests;
        nextSlot = 0;
        open = true;
        deferredGeneration = true;
        return ++frame;
    }
    public boolean allowsGpuGeneration(long frame) { requireFrame(frame); return deferredGeneration; }

    public @Nullable NiagaraStatelessDataBuffer request(long frame, long emitterKey,
            List<NiagaraDefinition.Attribute> layout, int activeEstimate, boolean reuseAllocations) {
        requireFrame(frame);
        if (activeEstimate < 0) throw new IllegalArgumentException("Negative Niagara analytic particle estimate");
        if (activeEstimate == 0) return null;
        Entry existing = used.get(emitterKey);
        if (existing != null) return existing.buffer();
        Entry entry = null;
        if (reuseAllocations) for (int index = 0; index < free.size(); index++) {
            if (!free.get(index).layout().equals(layout)) continue;
            entry = free.get(index);
            Entry last = free.remove(free.size() - 1);
            if (index < free.size()) free.set(index, last);
            break;
        }
        if (entry == null) {
            List<NiagaraDefinition.Attribute> owned = List.copyOf(layout);
            entry = new Entry(owned, new NiagaraStatelessDataBuffer("Niagara.Stateless.Result", owned));
        }
        try {
            boolean gpuSlot = nextSlot < reservedSlots;
            if (gpuSlot) entry.buffer().prepare(runtime, Objects.requireNonNull(counters).slice(), nextSlot, activeEstimate, false);
            else entry.buffer().prepareWithoutGpuCount(runtime, activeEstimate);
            used.put(emitterKey, entry);
            if (gpuSlot) nextSlot++;
            return entry.buffer();
        } catch (RuntimeException | Error error) {
            entry.buffer().close();
            throw error;
        }
    }

    public void enqueue(long frame, ComputeKernel.Dispatch request) {
        requireFrame(frame);
        if (!deferredGeneration) throw new IllegalStateException("Niagara analytic GPU generation window has closed");
        requests.add(Objects.requireNonNull(request));
    }
    public void flush(long frame, NiagaraGpuPrograms programs) {
        requireFrame(frame);
        deferredGeneration = false;
        if (requests.isEmpty()) return;
        try { programs.stateless().dispatchIndependentBatch(requests); }
        finally { requests.clear(); }
    }

    public void finishFrame(long frame) {
        if (closed) return;
        requireFrame(frame);
        if (!requests.isEmpty()) throw new IllegalStateException("Niagara analytic generation requests have not been dispatched");
        retireFrame();
    }
    public void abortFrame(long frame) {
        if (closed) return;
        requireFrame(frame);
        requests.clear();
        retireFrame();
    }
    private void retireFrame() {
        for (Entry entry : free) entry.buffer().close();
        free.clear();
        for (Entry entry : used.values()) {
            entry.buffer().retire();
            free.add(entry);
        }
        used.clear();
        open = false;
        deferredGeneration = false;
    }

    private void requireFrame(long frame) {
        RenderSystem.assertOnRenderThread();
        if (closed || !open || this.frame != frame) throw new IllegalStateException("Niagara analytic cache frame is no longer active");
    }
    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (closed) return;
        closed = true;
        for (Entry entry : used.values()) entry.buffer().close();
        for (Entry entry : free) entry.buffer().close();
        used.clear();
        free.clear();
        requests.clear();
        if (counters != null) { counters.close(); counters = null; }
        open = false;
        deferredGeneration = false;
    }
}
