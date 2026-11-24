package com.kltyton.lumenfx.client.render.mesh;

import com.kltyton.lumenfx.api.client.parameter.NiagaraPlatforms;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraCpuParticleUpload;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuMeshCollector;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuMeshDraw;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuPrograms;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuSpriteDraw;
import com.kltyton.lumenfx.client.simulation.niagara.NiagaraCpuRenderData;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

/** Render-thread CPU dataset transfer into the same mesh scene collector used by GPU simulation. */
public final class CpuMeshSceneCollector implements AutoCloseable {
    private record Source(long system, NiagaraDefinition.Emitter emitter) {
        @Override public boolean equals(Object value) {
            return value instanceof Source other && system == other.system && emitter == other.emitter;
        }
        @Override public int hashCode() { return 31 * Long.hashCode(system) + System.identityHashCode(emitter); }
    }
    private record Input(CpuMeshSceneInput source, NiagaraCpuRenderData data, Source key) { }
    private record Plan(NiagaraCpuRenderData data, boolean particles) { }
    private static final class Draw {
        final NiagaraGpuMeshDraw renderer;
        long seen;
        Draw(String name, NiagaraGpuPrograms programs) { renderer = new NiagaraGpuMeshDraw(name, programs); }
    }
    private static final class Upload implements AutoCloseable {
        final NiagaraCpuParticleUpload particles;
        final Map<NiagaraDefinition.Renderer, Draw> draws = new IdentityHashMap<>();
        final String name;
        long seen;
        Upload(Source source, NiagaraGpuPrograms programs) {
            name = "Niagara.CpuMesh." + source.system() + "." + source.emitter().sourceName();
            particles = new NiagaraCpuParticleUpload(name, source.emitter().attributes(), programs.runtime());
        }
        @Override public void close() {
            draws.values().forEach(draw -> draw.renderer.close()); draws.clear(); particles.close();
        }
    }
    private final NiagaraGpuPrograms programs;
    private final Map<Source, Upload> uploads = new LinkedHashMap<>();
    private long frame;
    private boolean closed;

    public CpuMeshSceneCollector(NiagaraGpuPrograms programs) { this.programs = programs; }

    public void append(CommandEncoder encoder, List<CpuMeshSceneInput> sources, Vec3 camera, Quaternionf orientation,
                       @Nullable MeshView view, List<NiagaraGpuMeshCollector.Task> output) {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("CPU mesh scene collector has been released");
        frame++;
        List<Input> inputs = new ArrayList<>(sources.size());
        Map<Source, Plan> plans = new LinkedHashMap<>();
        for (var source : sources) {
            var snapshot = source.snapshot();
            var reader = Objects.requireNonNull(snapshot.nativeParticles(), "CPU scene meshes require their VM renderer snapshot");
            var data = reader.renderData();
            if (!NiagaraGpuSpriteDraw.hostVisible(data.renderer(), data.system(), data.variables(), snapshot.input(),
                    data.rendererStore(), data.emitter().rendererBindings())) continue;
            Source key = new Source(data.systemIdentity(), data.emitter());
            boolean particleSource = switch (data.renderer().sourceMode()) {
                case "Particles" -> true;
                case "Emitter" -> false;
                default -> throw new IllegalArgumentException("Unknown mesh source mode " + data.renderer().sourceMode());
            };
            Plan previous = plans.get(key);
            if (previous != null && previous.data().particles() != data.particles())
                throw new IllegalStateException("CPU renderers disagree on their emitter's current dataset");
            plans.put(key, new Plan(data, particleSource || previous != null && previous.particles()));
            inputs.add(new Input(source, data, key));
        }
        for (var plan : plans.entrySet()) {
            Upload upload = uploads.computeIfAbsent(plan.getKey(), key -> new Upload(key, programs));
            upload.seen = frame;
            upload.particles.upload(encoder, plan.getValue().data().particles(), plan.getValue().particles());
        }
        boolean highPrecision = NiagaraPlatforms.snapshot().defaultHighSortPrecision();
        for (var input : inputs) {
            Upload upload = uploads.get(input.key());
            var data = input.data();
            Draw draw = upload.draws.computeIfAbsent(data.renderer(), renderer -> new Draw(upload.name + "." + renderer.name(), programs));
            draw.seen = frame;
            var snapshot = input.source().snapshot();
            var reader = snapshot.nativeParticles();
            double depth = MeshDrawSelection.depth(snapshot.input().pose(), reader.componentBounds(), camera, view);
            NiagaraGpuMeshCollector.append(output, new NiagaraGpuMeshCollector.RendererTask(draw.renderer,
                    upload.particles.buffers(), data.emitter(), data.renderer(), input.source().effect(), snapshot.definition(),
                    data.system(), data.variables(), snapshot.input(), data.rendererStore(), camera, orientation,
                    data.originX(), data.originY(), data.originZ(),
                    snapshot.time(), depth, highPrecision, false, false, Objects.requireNonNull(view,
                            "CPU mesh collection requires its captured projection"), reader::componentBounds, reader.previousOwnerPose(), reader::material,
                    name -> reader.dynamicMeshes() == null ? null : reader.dynamicMeshes().resolve(encoder, programs, name)));
        }
        for (var iterator = uploads.values().iterator(); iterator.hasNext();) {
            Upload upload = iterator.next();
            if (upload.seen != frame) { upload.close(); iterator.remove(); }
            else for (var draws = upload.draws.values().iterator(); draws.hasNext();) {
                Draw draw = draws.next();
                if (draw.seen != frame) { draw.renderer.close(); draws.remove(); }
            }
        }
    }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (closed) return;
        closed = true;
        uploads.values().forEach(Upload::close); uploads.clear();
    }
}
