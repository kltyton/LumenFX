package com.kltyton.lumenfx.client.render.niagara.gpu;

import com.kltyton.lumenfx.client.resource.shader.PreparedNiagaraCurves.Curve;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/** A system shares each external DI's LUT across emitters without sharing mutable bindings. */
public final class NiagaraGpuCurveRegistry implements AutoCloseable {
    private record Entry(Curve curve, GpuBuffer buffer) { }
    public record Snapshot(Curve curve, GpuBufferSlice buffer) { }
    private final NiagaraGpuPrograms programs;
    private final Function<String, @Nullable Curve> source;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private boolean closed;

    public NiagaraGpuCurveRegistry(NiagaraGpuPrograms programs, Function<String, @Nullable Curve> source) {
        this.programs = programs; this.source = source;
    }
    public @Nullable Snapshot prepare(String identity) {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("Niagara curve registry is closed");
        Curve curve = source.apply(identity);
        if (curve == null) return null;
        String key = SourceNames.canonical(identity);
        Entry entry = entries.get(key);
        if (entry == null || entry.curve() != curve) {
            GpuBuffer buffer = NiagaraGpuCurves.upload("Niagara.Curve." + identity, curve.words(), programs.runtime());
            if (entry != null) entry.buffer().close();
            entry = new Entry(curve, buffer);
            entries.put(key, entry);
        }
        return new Snapshot(curve, entry.buffer().slice());
    }
    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (closed) return;
        closed = true;
        entries.values().forEach(entry -> entry.buffer().close());
        entries.clear();
    }
}
