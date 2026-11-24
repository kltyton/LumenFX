package com.kltyton.lumenfx.client.render.niagara.gpu;

import com.kltyton.lumenfx.api.client.parameter.NiagaraPlatforms;
import com.kltyton.lumenfx.client.render.light.shadow.geometry.ParticleShadowGeometry;
import com.kltyton.lumenfx.client.render.light.shadow.geometry.ParticleShadowGeometry.GpuSource;
import com.kltyton.lumenfx.client.render.mesh.MeshDrawSelection;
import com.kltyton.lumenfx.client.render.mesh.MeshView;
import com.kltyton.lumenfx.client.simulation.EmitterInstance;
import com.kltyton.lumenfx.client.simulation.niagara.NiagaraCpuRenderData;
import com.kltyton.lumenfx.client.simulation.niagara.VectorDataSet;
import com.kltyton.lumenfx.effect.MaterialDefinition;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

/** Transfers CPU-simulated sprites selected for the shared GPU sort and draw. */
public final class NiagaraCpuSpriteCompute implements AutoCloseable {
    public record Source(EmitterInstance.Snapshot snapshot, NiagaraCpuRenderData data, MaterialDefinition material) { }

    private record EmitterKey(long system, NiagaraDefinition.Emitter emitter) {
        @Override public boolean equals(Object value) {
            return value instanceof EmitterKey other && system == other.system && emitter == other.emitter;
        }

        @Override public int hashCode() { return 31 * Long.hashCode(system) + System.identityHashCode(emitter); }
    }

    private static final class Draw implements AutoCloseable {
        final NiagaraGpuSpriteDraw main;
        final String name;
        long frame;
        @Nullable NiagaraGpuSpriteDraw shadow;

        Draw(String name, NiagaraGpuPrograms programs) {
            this.name = name;
            this.main = new NiagaraGpuSpriteDraw(name, programs);
        }

        NiagaraGpuSpriteDraw pointShadow(NiagaraGpuPrograms programs) {
            if (shadow == null) shadow = new NiagaraGpuSpriteDraw(name + ".Shadow", programs);
            return shadow;
        }

        @Override public void close() {
            main.close();
            if (shadow != null) shadow.close();
        }
    }

    private static final class EmitterBuffers implements AutoCloseable {
        final String name;
        final NiagaraCpuParticleUpload upload;
        final Map<NiagaraDefinition.Renderer, Draw> draws = new IdentityHashMap<>();
        long frame;
        @Nullable VectorDataSet data;

        EmitterBuffers(EmitterKey source, NiagaraGpuPrograms programs) {
            name = "Niagara.CpuSprite." + source.system() + "." + source.emitter().sourceName();
            upload = new NiagaraCpuParticleUpload(name, source.emitter().attributes(), programs.runtime());
        }

        @Override public void close() {
            draws.values().forEach(Draw::close);
            draws.clear();
            upload.close();
            data = null;
        }
    }

    private final NiagaraGpuPrograms programs;
    private final Map<EmitterKey, EmitterBuffers> emitters = new HashMap<>();
    private long frame;

    public NiagaraCpuSpriteCompute(NiagaraGpuPrograms programs) { this.programs = programs; }

    public void append(CommandEncoder encoder, List<Source> sources, Vec3 camera, Quaternionf orientation,
                       @Nullable MeshView view, List<NiagaraGpuSpriteCollector.Task> output,
                       @Nullable List<GpuSource> shadows) {
        RenderSystem.assertOnRenderThread();
        frame++;
        boolean highPrecision = NiagaraPlatforms.snapshot().defaultHighSortPrecision();
        for (var source : sources) {
            var data = source.data();
            var key = new EmitterKey(data.systemIdentity(), data.emitter());
            var buffers = emitters.computeIfAbsent(key, value -> new EmitterBuffers(value, programs));
            if (buffers.frame != frame) {
                buffers.upload.upload(encoder, data.particles(), true);
                buffers.frame = frame;
                buffers.data = data.particles();
            } else if (buffers.data != data.particles()) {
                throw new IllegalStateException("CPU sprite renderers disagree on their emitter's current dataset");
            }
            var draw = buffers.draws.computeIfAbsent(data.renderer(), value ->
                    new Draw(buffers.name + "." + value.name(), programs));
            if (draw.frame == frame) continue;
            draw.frame = frame;
            var snapshot = source.snapshot();
            var particles = Objects.requireNonNull(snapshot.nativeParticles());
            var input = snapshot.input();
            var bounds = particles.componentBounds();
            var task = new NiagaraGpuSpriteCollector.Task(draw.main, buffers.upload.buffers(), data.emitter(),
                    data.renderer(), snapshot.definition(), source.material(), data.system(), data.variables(),
                    input, data.rendererStore(), camera, orientation, data.originX(), data.originY(), data.originZ(),
                    snapshot.time(), MeshDrawSelection.depth(input.pose(), bounds, camera, view), highPrecision, false, false,
                    particles.previousOwnerPose(), particles.componentVelocity(), bounds);
            output.add(task);
            if (shadows != null && ParticleShadowGeometry.casts(snapshot.definition(), input)
                    && ParticleShadowGeometry.casts(source.material())) {
                var shadow = draw.pointShadow(programs);
                shadows.add((commands, shadowView, draws) -> draws.sprites().add(task.forPointShadow(shadow, shadowView)));
            }
        }
        for (var iterator = emitters.values().iterator(); iterator.hasNext();) {
            var buffers = iterator.next();
            if (buffers.frame != frame) {
                RenderSystem.queueFencedTask(buffers::close);
                iterator.remove();
                continue;
            }
            for (var draws = buffers.draws.values().iterator(); draws.hasNext();) {
                var draw = draws.next();
                if (draw.frame == frame) continue;
                RenderSystem.queueFencedTask(draw::close);
                draws.remove();
            }
        }
    }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        emitters.values().forEach(EmitterBuffers::close);
        emitters.clear();
    }
}
