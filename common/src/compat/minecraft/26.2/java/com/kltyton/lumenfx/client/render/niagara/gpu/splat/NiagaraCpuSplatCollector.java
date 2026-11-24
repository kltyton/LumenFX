package com.kltyton.lumenfx.client.render.niagara.gpu.splat;

import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuPrograms;
import com.kltyton.lumenfx.client.render.niagara.splat.NiagaraGaussianSplatDraw;
import com.kltyton.lumenfx.client.simulation.niagara.VectorDataSet;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraParameterStore;
import com.kltyton.lumenfx.effect.niagara.NiagaraBounds;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import com.kltyton.lumenfx.effect.render.splat.SplatRendererSettings;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Manages pooled GPU uploads and BuildSplats dispatches for CPU Niagara splat emitters. */
public final class NiagaraCpuSplatCollector implements AutoCloseable {
    public record Source(
            Object key,
            NiagaraDefinition.Emitter emitter,
            NiagaraDefinition.Renderer renderer,
            SplatRendererSettings settings,
            @Nullable VectorDataSet particles,
            VectorDataSet system,
            Map<String, NiagaraDefinition.Variable> variables,
            EffectHandle.Input input,
            @Nullable NiagaraParameterStore store,
            double originX,
            double originY,
            double originZ,
            @Nullable NiagaraBounds bounds
    ) {
        public Source(
                Object key,
                NiagaraDefinition.Emitter emitter,
                NiagaraDefinition.Renderer renderer,
                SplatRendererSettings settings,
                @Nullable VectorDataSet particles,
                VectorDataSet system,
                Map<String, NiagaraDefinition.Variable> variables,
                EffectHandle.Input input,
                double originX,
                double originY,
                double originZ,
                @Nullable NiagaraBounds bounds
        ) {
            this(key, emitter, renderer, settings, particles, system, variables, input, null,
                    originX, originY, originZ, bounds);
        }
    }

    private final NiagaraGpuPrograms programs;
    private final Map<Object, NiagaraGpuSplatBuilder> builders = new IdentityHashMap<>();
    private boolean closed;

    public NiagaraCpuSplatCollector(NiagaraGpuPrograms programs) {
        this.programs = programs;
    }

    public List<NiagaraGaussianSplatDraw> prepare(CommandEncoder encoder, List<Source> sources) {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("Niagara CPU splat collector is closed");
        if (sources.isEmpty()) return List.of();
        if (!NiagaraGpuSplatBuilder.rendererEnabled()) return List.of();
        var draws = new java.util.ArrayList<NiagaraGaussianSplatDraw>(sources.size());
        for (int i = 0; i < sources.size(); i++) {
            var source = sources.get(i);
            var builder = builders.computeIfAbsent(source.key(), ignored -> new NiagaraGpuSplatBuilder(
                    "Niagara.CpuSplat." + source.emitter().name() + "." + source.renderer().name(),
                    programs, source.emitter(), source.renderer()));
            var draw = builder.buildCpu(encoder, source.settings(), source.particles(), source.system(),
                    source.variables(), source.input(), source.store(), source.originX(), source.originY(), source.originZ(),
                    source.bounds());
            if (draw != null) draws.add(draw);
        }
        return List.copyOf(draws);
    }

    public void retainActive(Iterable<Object> activeKeys) {
        RenderSystem.assertOnRenderThread();
        var active = new IdentityHashMap<Object, Boolean>();
        for (var key : activeKeys) active.put(key, Boolean.TRUE);
        var it = builders.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            if (!active.containsKey(entry.getKey())) {
                entry.getValue().close();
                it.remove();
            }
        }
    }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (closed) return;
        closed = true;
        builders.values().forEach(NiagaraGpuSplatBuilder::close);
        builders.clear();
    }
}
