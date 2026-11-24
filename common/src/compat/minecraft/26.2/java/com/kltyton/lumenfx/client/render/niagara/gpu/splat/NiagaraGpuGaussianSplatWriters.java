package com.kltyton.lumenfx.client.render.niagara.gpu.splat;

import com.kltyton.lumenfx.api.client.EffectHandle.Pose;
import com.kltyton.lumenfx.api.client.parameter.NiagaraPlatforms;
import com.kltyton.lumenfx.client.render.compute.ComputeRuntime;
import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import com.kltyton.lumenfx.client.render.niagara.splat.NiagaraGaussianSplatDraw;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.splat.NiagaraGaussianSplatWriter;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.splat.NiagaraGaussianSplatWriter.Config;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.splat.NiagaraGaussianSplatWriter.Snapshot;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.phys.Vec3;

/** One persistent output buffer per writer object, shared by all referencing GPU scripts. */
public final class NiagaraGpuGaussianSplatWriters implements AutoCloseable {
    private static final int DEFAULT_MAX_SPLATS = 4 * 1024 * 1024;
    private static final class State {
        final GpuBuffer buffer;
        final Config config;
        long clearEpoch = Long.MIN_VALUE;
        boolean rendered;

        State(GpuBuffer buffer, Config config) { this.buffer = buffer; this.config = config; }
    }

    private final Map<String, State> states = new LinkedHashMap<>();
    private final ComputeRuntime runtime;

    public NiagaraGpuGaussianSplatWriters(ComputeRuntime runtime) { this.runtime = runtime; }

    GpuBufferSlice rows(String identity, Snapshot snapshot) {
        var config = snapshot.config();
        var state = states.get(identity);
        if (state == null || state.config != config) {
            var buffer = RenderSystem.getDevice().createBuffer(() -> "Niagara.GaussianSplat.Writer",
                    ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_DST), config.storageBytes());
            var previous = states.put(identity, state = new State(buffer, config));
            if (previous != null) retire(previous.buffer);
        }
        var slice = state.buffer.slice();
        if (state.clearEpoch != snapshot.clearEpoch()) {
            runtime.clearBuffer(slice, 0);
            state.clearEpoch = snapshot.clearEpoch();
        }
        state.rendered = true;
        return slice;
    }

    public void applyClears(Map<String, Snapshot> snapshots) {
        states.entrySet().removeIf(entry -> {
            var current = snapshots.get(entry.getKey());
            if (current == null || current.config() != entry.getValue().config) {
                retire(entry.getValue().buffer);
                return true;
            }
            if (current.clearEpoch() > entry.getValue().clearEpoch) rows(entry.getKey(), current);
            return false;
        });
    }

    public List<NiagaraGaussianSplatDraw> draw(Pose pose, Vec3 tileOrigin) {
        var environment = NiagaraPlatforms.snapshot();
        var enabled = environment.console(NiagaraGaussianSplatWriter.ENABLED);
        if (enabled != null && enabled.bits() == 0) return List.of();
        var maximum = environment.console(NiagaraGaussianSplatWriter.MAX_COUNT);
        int maxSplats = maximum == null ? DEFAULT_MAX_SPLATS : Math.max(0, maximum.bits());
        if (maxSplats <= 0) return List.of();
        var result = new ArrayList<NiagaraGaussianSplatDraw>();
        for (var state : states.values()) {
            int count = Math.min(state.config.count(), maxSplats);
            if (!state.rendered || count <= 0) continue;
            var source = state.config.source();
            result.add(new NiagaraGaussianSplatDraw(
                    state.buffer.slice(),
                    count,
                    state.config.stride(),
                    state.config.degree(),
                    state.config.coordinateSpace(),
                    state.config.quantized(),
                    state.config.parameters(),
                    source.localSpace(),
                    pose,
                    tileOrigin,
                    null,
                    source.splatScale(),
                    source.opacityScale(),
                    source.inverseToneCurve() > 0f,
                    source.inverseToneCurve(),
                    source.invertExpandGamut()
            ));
        }
        return List.copyOf(result);
    }

    private static void retire(GpuBuffer buffer) { RenderSystem.queueFencedTask(buffer::close); }

    @Override public void close() {
        states.values().forEach(state -> retire(state.buffer));
        states.clear();
    }
}
