package com.kltyton.lumenfx.client.render.ribbon;

import com.kltyton.lumenfx.client.render.light.shadow.geometry.ParticleShadowGeometry;
import com.kltyton.lumenfx.client.render.light.shadow.geometry.ParticleShadowGeometry.GpuSource;
import com.kltyton.lumenfx.client.render.mesh.MeshDrawSelection;
import com.kltyton.lumenfx.client.render.mesh.MeshView;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraCpuParticleUpload;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuPrograms;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuRibbonDraw;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuRibbonFrame;
import com.kltyton.lumenfx.client.simulation.EmitterInstance;
import com.kltyton.lumenfx.client.simulation.niagara.NiagaraCpuRenderData;
import com.kltyton.lumenfx.client.simulation.niagara.VectorDataSet;
import com.kltyton.lumenfx.effect.niagara.NiagaraComponentDefaults;
import com.kltyton.lumenfx.effect.MaterialDefinition;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import com.kltyton.lumenfx.effect.render.RibbonSettings;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Hands a CPU emitter's published dataset to the same ribbon kernels used by GPU emitters. */
public final class NiagaraCpuRibbonCompute implements AutoCloseable {
    public record Source(EmitterInstance.Snapshot snapshot, NiagaraCpuRenderData data,
                         MaterialDefinition material, RibbonSettings settings) { }

    private record EmitterKey(long system, NiagaraDefinition.Emitter emitter) {
        @Override public boolean equals(Object value) {
            return value instanceof EmitterKey other && system == other.system && emitter == other.emitter;
        }

        @Override public int hashCode() { return 31 * Long.hashCode(system) + System.identityHashCode(emitter); }
    }

    private static final class Ribbon {
        final NiagaraGpuRibbonDraw draw;
        long frame;

        Ribbon(NiagaraGpuRibbonDraw draw) { this.draw = draw; }
    }

    private static final class EmitterBuffers implements AutoCloseable {
        final String name;
        final NiagaraCpuParticleUpload upload;
        final Map<NiagaraDefinition.Renderer, Ribbon> ribbons = new IdentityHashMap<>();
        long frame;
        @Nullable VectorDataSet data;

        EmitterBuffers(EmitterKey source, NiagaraGpuPrograms programs) {
            name = "Niagara.CpuRibbon." + source.system() + "." + source.emitter().sourceName();
            upload = new NiagaraCpuParticleUpload(name, source.emitter().attributes(), programs.runtime());
        }

        @Override public void close() {
            ribbons.values().forEach(ribbon -> ribbon.draw.close());
            ribbons.clear();
            upload.close();
            data = null;
        }
    }

    private final NiagaraGpuPrograms programs;
    private final boolean ownsPrograms;
    private final Map<EmitterKey, EmitterBuffers> emitters = new HashMap<>();
    private long frame;

    public NiagaraCpuRibbonCompute(NiagaraGpuPrograms programs, boolean ownsPrograms) {
        this.programs = programs;
        this.ownsPrograms = ownsPrograms;
    }

    public void append(CommandEncoder encoder, List<Source> sources, Vec3 camera, @Nullable MeshView meshView, float lodDistanceFactor,
                       List<NiagaraGpuRibbonFrame> output, @Nullable List<GpuSource> shadows) {
        RenderSystem.assertOnRenderThread();
        frame++;
        for (var source : sources) {
            var data = source.data();
            var key = new EmitterKey(data.systemIdentity(), data.emitter());
            var buffers = emitters.computeIfAbsent(key, value -> new EmitterBuffers(value, programs));
            if (buffers.frame != frame) {
                buffers.upload.upload(encoder, data.particles(), true);
                buffers.frame = frame;
                buffers.data = data.particles();
            } else if (buffers.data != data.particles()) {
                throw new IllegalStateException("CPU ribbon renderers disagree on their emitter's current dataset");
            }
            var ribbon = buffers.ribbons.computeIfAbsent(data.renderer(), value -> new Ribbon(
                    NiagaraGpuRibbonDraw.forCpuUpload(buffers.name + "." + value.name(), programs, source.settings().shape())));
            if (ribbon.frame == frame) continue;
            ribbon.frame = frame;
            var snapshot = source.snapshot();
            var particles = snapshot.nativeParticles();
            var input = snapshot.input();
            var bounds = particles.componentBounds();
            double depth = MeshDrawSelection.depth(input.pose(), bounds, camera, meshView);
            float viewDistance = NiagaraComponentDefaults.RENDERER_LOD_DISTANCE_OVERRIDE;
            var draw = ribbon.draw.prepare(encoder, buffers.upload.buffers(), data.emitter(), data.renderer(),
                    snapshot.definition(), source.material(), input, camera, data.originX(), data.originY(), data.originZ(),
                    snapshot.time(), depth, viewDistance, lodDistanceFactor, false, particles.previousOwnerPose(),
                    particles.componentVelocity(), bounds, source.settings());
            if (draw == null) continue;
            output.add(draw);
            if (shadows != null && ParticleShadowGeometry.casts(snapshot.definition(), input)
                    && ParticleShadowGeometry.casts(source.material())) {
                var shadow = ribbon.draw.pointShadow(draw, false);
                shadows.add((commands, view, draws) -> draws.ribbons().add(shadow.prepare(commands, view.ribbonLodDistanceFactor())));
            }
        }
        for (var iterator = emitters.values().iterator(); iterator.hasNext();) {
            var buffers = iterator.next();
            if (buffers.frame != frame) {
                RenderSystem.queueFencedTask(buffers::close);
                iterator.remove();
                continue;
            }
            for (var ribbons = buffers.ribbons.values().iterator(); ribbons.hasNext();) {
                var ribbon = ribbons.next();
                if (ribbon.frame == frame) continue;
                RenderSystem.queueFencedTask(ribbon.draw::close);
                ribbons.remove();
            }
        }
    }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        emitters.values().forEach(EmitterBuffers::close);
        emitters.clear();
        if (ownsPrograms) programs.close();
    }
}
