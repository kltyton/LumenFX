package com.kltyton.lumenfx.client.render.niagara.gpu.channel;

import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuPrograms;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.channel.NiagaraDataChannelGpuSnapshot;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.channel.NiagaraDataChannelState.GpuWriter;
import com.mojang.blaze3d.systems.CommandEncoder;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Per-system staging for GPU channel writers returning results to CPU or game consumers. */
public final class NiagaraGpuDataChannelWriters implements AutoCloseable {
    private static final class Staged implements AutoCloseable {
        final GpuWriter owner;
        final NiagaraGpuDataChannelColumns columns;
        Staged(GpuWriter owner, NiagaraGpuDataChannelColumns columns) { this.owner = owner; this.columns = columns; }
        @Override public void close() { columns.close(); }
    }

    private final NiagaraGpuPrograms programs;
    private final Set<GpuWriter> reserved = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<GpuWriter, Staged> staging = new LinkedHashMap<>();

    public NiagaraGpuDataChannelWriters(NiagaraGpuPrograms programs) { this.programs = programs; }

    void reserve(GpuWriter writer) {
        var world = writer.world();
        var definition = writer.definition();
        var address = writer.address();
        if (world != null && address != null && definition.publishGpu() && definition.allocationCount() > 0 && reserved.add(writer))
            NiagaraGpuDataChannelWorlds.reserve(world, address, definition.allocationCount());
    }

    @Nullable NiagaraGpuDataChannelColumns cpuColumns(String identity, GpuWriter writer) {
        var definition = writer.definition();
        var schema = definition.channel();
        if (writer.world() == null || writer.address() == null || schema == null
                || !definition.publishCpu() && !definition.publishGame() && writer.recording() == null
                || definition.allocationCount() == 0)
            return null;
        return staging.computeIfAbsent(writer, ignored -> new Staged(writer,
                new NiagaraGpuDataChannelColumns(programs, NiagaraDataChannelGpuSnapshot.empty(schema), definition.allocationCount(), identity))).columns;
    }

    public void endBatch(CommandEncoder encoder) {
        try {
            for (var entry : staging.values()) NiagaraGpuDataChannelReadbacks.submit(encoder, entry.owner, entry.columns);
        } finally { close(); }
    }

    @Override public void close() {
        reserved.clear();
        try { for (var entry : staging.values()) entry.close(); }
        finally { staging.clear(); }
    }
}
