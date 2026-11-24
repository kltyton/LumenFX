package com.kltyton.lumenfx.client.render.mesh.nanite;

import com.kltyton.lumenfx.client.render.compute.ComputeRuntime;
import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.client.render.mesh.CpuMeshSceneInput;
import com.kltyton.lumenfx.client.render.mesh.MeshDrawSelection;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuMeshCollector.RendererTask;
import com.kltyton.lumenfx.client.simulation.niagara.NiagaraRendererParameters;
import com.kltyton.lumenfx.client.resource.shader.PreparedNiagaraNaniteShaders;
import com.kltyton.lumenfx.client.simulation.niagara.render.NaniteInstanceFloatBindings.FloatComponent;
import com.kltyton.lumenfx.effect.EffectDefinition;
import com.kltyton.lumenfx.effect.EmitterDefinition;
import com.kltyton.lumenfx.effect.MaterialDefinition;
import com.kltyton.lumenfx.effect.niagara.NiagaraBounds;
import com.kltyton.lumenfx.effect.render.mesh.MeshRendererSettings.Entry;
import com.kltyton.lumenfx.effect.render.mesh.StaticMeshAsset;
import com.kltyton.lumenfx.effect.niagara.parameter.store.NiagaraParameterLayout.Key;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import net.minecraft.world.phys.Vec3;

/** Produces per-slot instance scene data while the corresponding simulation frame is still live. */
public final class NaniteInstanceCollector implements AutoCloseable {
    public record GpuInput(RendererTask task, List<FloatComponent> customFloats, Map<Key, byte[]> rendererValues) {
        public GpuInput { customFloats = List.copyOf(customFloats); rendererValues = Map.copyOf(rendererValues); }
    }
    public record Prepared(EffectDefinition effect, EmitterDefinition renderer, Entry entry, int slot,
                           StaticMeshAsset asset, EffectHandle.Input input, UnaryOperator<MaterialDefinition> materialInstances,
                           EffectHandle.Pose previousPose, NiagaraBounds bounds, NaniteGpuInstanceScene.Output scene,
                           boolean readsPartialDepth) { }

    private final NaniteCpuInstanceScene cpu;
    private final NaniteGpuInstanceScene gpu;

    public NaniteInstanceCollector(ComputeRuntime runtime, PreparedNiagaraNaniteShaders shaders) {
        RenderSystem.assertOnRenderThread();
        cpu = new NaniteCpuInstanceScene(runtime);
        gpu = new NaniteGpuInstanceScene(runtime, shaders);
    }

    public List<Prepared> prepareCpu(CommandEncoder encoder, List<CpuMeshSceneInput> sources,
                                     Vec3 camera, Vec3 previousCamera) {
        RenderSystem.assertOnRenderThread();
        var output = new ArrayList<Prepared>();
        for (var source : sources) {
            var snapshot = source.snapshot();
            var settings = snapshot.definition().meshSettings();
            if (settings == null || settings.nanite() == null) continue;
            var entries = MeshDrawSelection.entries(settings, snapshot.input());
            for (int slot = 0; slot < entries.size(); slot++) {
                var entry = MeshDrawSelection.nativeNaniteEntry(entries.get(slot), snapshot.input());
                var asset = entry.asset();
                if (asset == null || asset.nanite() == null) continue;
                var scene = cpu.prepare(encoder, snapshot, entry, slot, camera, previousCamera);
                if (scene != null) output.add(new Prepared(source.effect(), snapshot.definition(), entry, slot, asset,
                        snapshot.input(), snapshot.nativeParticles()::material, snapshot.nativeParticles().previousOwnerPose(),
                        snapshot.nativeParticles().componentBounds(), scene, false));
            }
        }
        return List.copyOf(output);
    }

    public List<Prepared> prepareGpu(CommandEncoder encoder, List<GpuInput> sources, Vec3 previousCamera) {
        RenderSystem.assertOnRenderThread();
        var output = new ArrayList<Prepared>();
        for (var input : sources) {
            var task = input.task();
            var settings = task.definition().meshSettings();
            if (settings == null || settings.nanite() == null) continue;
            Integer enabled = NiagaraRendererParameters.integer(task.renderer().enabledBinding(), true, task.system(),
                    task.variables(), task.input(), task.emitter().rendererBindings());
            if (enabled != null && enabled == 0) continue;
            var entries = MeshDrawSelection.entries(settings, task.input());
            NaniteGpuInstanceScene.Output emitterCustom = null;
            for (int slot = 0; slot < entries.size(); slot++) {
                var entry = MeshDrawSelection.nativeNaniteEntry(entries.get(slot), task.input());
                var asset = entry.asset();
                if (asset == null || asset.nanite() == null) continue;
                NaniteGpuInstanceScene.Output scene;
                if (task.renderer().sourceMode().equals("Emitter")) {
                    if (emitterCustom == null) emitterCustom = gpu.prepareEmitterFloats(encoder, task, input.customFloats());
                    scene = cpu.prepareEmitter(encoder, task, slot, input.rendererValues(), emitterCustom, previousCamera);
                } else if (task.renderer().sourceMode().equals("Particles"))
                    scene = gpu.prepareParticles(encoder, task, entry, slot, input.customFloats(), input.rendererValues(), previousCamera);
                else throw new IllegalArgumentException("Unknown Nanite renderer source mode " + task.renderer().sourceMode());
                if (scene != null) output.add(new Prepared(task.effect(), task.definition(), entry, slot, asset,
                        task.input(), task.materialInstances(), task.previousPose(), task.bounds().get(), scene,
                        task.draw().readsPartialDepth()));
            }
        }
        return List.copyOf(output);
    }

    @Override public void close() { gpu.close(); }
}
