package com.kltyton.lumenfx.client.simulation.niagara.render;

import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.api.client.component.PrimitiveOwnerVisibility;
import com.kltyton.lumenfx.client.component.mesh.StaticMeshComponentDraw;
import com.kltyton.lumenfx.client.playback.EffectTeardown;
import com.kltyton.lumenfx.client.render.ribbon.RibbonTessellation;
import com.kltyton.lumenfx.client.simulation.EmitterInstance;
import com.kltyton.lumenfx.client.simulation.niagara.NiagaraParticles;
import com.kltyton.lumenfx.client.simulation.niagara.NiagaraRendererParameters;
import com.kltyton.lumenfx.client.simulation.niagara.VectorDataSet;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraParameterStore;
import com.kltyton.lumenfx.client.simulation.niagara.bounds.NiagaraMeshBounds;
import com.kltyton.lumenfx.client.simulation.niagara.component.NiagaraComponentRenderer;
import com.kltyton.lumenfx.effect.EffectDefinition;
import com.kltyton.lumenfx.effect.EmitterDefinition;
import com.kltyton.lumenfx.effect.niagara.NiagaraBounds;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import com.kltyton.lumenfx.effect.niagara.parameter.RendererParameterDefaults;
import com.kltyton.lumenfx.effect.render.decal.ParticleDecal;
import com.kltyton.lumenfx.effect.render.light.scene.ScenePointLight;
import com.kltyton.lumenfx.effect.render.light.scene.SceneDirectionalLight;
import com.kltyton.lumenfx.effect.render.material.MaterialInstanceParameters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Renderer histories and component pools consume CPU datasets independently of their simulation backend. */
public final class NiagaraEmitterRenderState implements AutoCloseable {
    private record Visual(EmitterDefinition definition, NiagaraParticles particles, RibbonTessellation tessellation) { }
    private record ComponentVisual(NiagaraDefinition.Renderer definition, NiagaraComponentRenderer renderer) { }
    private final List<Visual> visuals;
    private final List<ComponentVisual> components;
    private final List<NiagaraMeshBounds> meshBounds;
    private final boolean localSpace;
    private final RendererParameterDefaults rendererDefaults;

    public NiagaraEmitterRenderState(NiagaraDefinition.Emitter source, List<EmitterDefinition> renderers, boolean localSpace,
                                    MaterialInstanceParameters.@Nullable TextureValues textures) {
        this(source, renderers, localSpace, textures, false);
    }

    public NiagaraEmitterRenderState(NiagaraDefinition.Emitter source, List<EmitterDefinition> renderers, boolean localSpace,
                                     MaterialInstanceParameters.@Nullable TextureValues textures, boolean cullProxy) {
        this.localSpace = localSpace;
        rendererDefaults = source.rendererBindings();
        var visuals = new ArrayList<Visual>();
        var components = new ArrayList<ComponentVisual>();
        Map<String, Set<Integer>> createdParticles = new HashMap<>();
        for (int index = 0; index < renderers.size(); index++) {
            var definition = renderers.get(index);
            var binding = source.renderers().get(index);
            if (cullProxy && !binding.allowInCullProxies()) continue;
            if (definition.renderer() == EmitterDefinition.Renderer.COMPONENT) {
                if (cullProxy) continue; // Spawned components are ticked by their owning system.
                // Native component rendering requires AsStateful(), even though this renderer selects CPU simulation for stateless emitters.
                if (source.simulation() instanceof NiagaraDefinition.StatelessSimulation) continue;
                var settings = Objects.requireNonNull(binding.component());
                Set<Integer> created = settings.templateKey().isEmpty() ? new HashSet<>()
                        : createdParticles.computeIfAbsent(settings.templateKey(), ignored -> new HashSet<>());
                components.add(new ComponentVisual(binding, new NiagaraComponentRenderer(settings, source.attributes(), localSpace, created)));
            } else {
                visuals.add(new Visual(definition, new NiagaraParticles(source, binding,
                        definition.renderer() == EmitterDefinition.Renderer.RIBBON, definition.localSpace(),
                        definition.renderer() == EmitterDefinition.Renderer.MESH, definition.lightSettings(),
                        definition.decalSettings(), definition.splatSettings(), textures),
                        new RibbonTessellation()));
            }
        }
        this.visuals = List.copyOf(visuals); this.components = List.copyOf(components);
        meshBounds = NiagaraMeshBounds.collect(visuals.stream().filter(visual -> !visual.particles().emitterSource()).map(Visual::definition).toList());
    }

    public boolean hasVisuals() { return !visuals.isEmpty(); }
    public List<NiagaraMeshBounds> meshBounds() { return meshBounds; }
    public boolean rendersParticles(EmitterDefinition.Renderer kind) {
        return visuals.stream().anyMatch(visual -> visual.definition().renderer() == kind && !visual.particles().emitterSource());
    }
    public @Nullable NiagaraBounds effectBounds(VectorDataSet particles) {
        NiagaraBounds result = null;
        for (var visual : visuals) {
            NiagaraBounds lights = visual.particles().lightBounds(particles);
            if (lights != null) result = result == null ? lights : result.union(lights);
            NiagaraBounds decals = visual.particles().decalBounds(particles);
            if (decals != null) result = result == null ? decals : result.union(decals);
            NiagaraBounds splats = visual.particles().splatBounds(particles);
            if (splats != null) result = result == null ? splats : result.union(splats);
        }
        return result;
    }

    public List<EmitterInstance.Snapshot> snapshots(VectorDataSet particles, double originX, double originY, double originZ,
            VectorDataSet system, Map<String, NiagaraDefinition.Variable> variables, EffectHandle.Input input, double now, double age) {
        return snapshots(particles, originX, originY, originZ, system, variables, input, now, age, null);
    }

    public List<EmitterInstance.Snapshot> snapshots(VectorDataSet particles, double originX, double originY, double originZ,
            VectorDataSet system, Map<String, NiagaraDefinition.Variable> variables, EffectHandle.Input input, double now, double age,
            @Nullable NiagaraParameterStore store) {
        return snapshots(particles, originX, originY, originZ, system, variables, input, now, age, store, Double.NaN);
    }

    public List<EmitterInstance.Snapshot> snapshots(VectorDataSet particles, double originX, double originY, double originZ,
            VectorDataSet system, Map<String, NiagaraDefinition.Variable> variables, EffectHandle.Input input, double now, double age,
            @Nullable NiagaraParameterStore store, double updateWorldSeconds) {
        var snapshots = new ArrayList<EmitterInstance.Snapshot>(visuals.size());
        for (var visual : visuals) {
            var definition = visual.definition();
            visual.particles().bind(particles, definition.localSpace() ? 0 : originX, definition.localSpace() ? 0 : originY,
                    definition.localSpace() ? 0 : originZ, system, variables, input, store);
            visual.particles().updateWorldSeconds(updateWorldSeconds);
            snapshots.add(new EmitterInstance.Snapshot(definition, List.of(), input, null, now, age, false, visual.tessellation(), visual.particles()));
        }
        return snapshots;
    }

    public void postTick(ClientLevel level, VectorDataSet particles, int spawned, double originX, double originY, double originZ,
                         EffectHandle.Input input, VectorDataSet system, Map<String, NiagaraDefinition.Variable> variables,
                         double realTime, float inactiveTimeLimit, PrimitiveOwnerVisibility ownerVisibility, @Nullable EffectHandle host) {
        postTick(level, particles, spawned, originX, originY, originZ, input, system, variables, realTime,
                inactiveTimeLimit, ownerVisibility, host, null);
    }

    public void postTick(ClientLevel level, VectorDataSet particles, int spawned, double originX, double originY, double originZ,
                         EffectHandle.Input input, VectorDataSet system, Map<String, NiagaraDefinition.Variable> variables,
                         double realTime, float inactiveTimeLimit, PrimitiveOwnerVisibility ownerVisibility, @Nullable EffectHandle host,
                         @Nullable NiagaraParameterStore store) {
        if (components.isEmpty()) return;
        Vec3 origin = localSpace ? Vec3.ZERO : new Vec3(originX, originY, originZ);
        for (var visual : components) {
            Integer enabled = NiagaraRendererParameters.integer(visual.definition().enabledBinding(), true, system, variables, input,
                    store, rendererDefaults);
            visual.renderer().postTick(level, particles, spawned, input, origin, realTime, inactiveTimeLimit,
                    enabled == null || enabled != 0, ownerVisibility, host);
        }
    }
    public void reset() {
        if (components.isEmpty()) return;
        try (var teardown = new EffectTeardown()) {
            for (var visual : components) teardown.run(visual.renderer()::reset);
        }
    }
    public void destroyRenderState() {
        if (components.isEmpty()) return;
        try (var teardown = new EffectTeardown()) {
            for (var visual : components) teardown.run(visual.renderer()::destroyRenderState);
        }
    }
    public void updateAttachment(EffectHandle.Pose pose) { for (var visual : components) visual.renderer().updateAttachment(pose); }
    public void finishFrame(boolean paused) { for (var visual : components) visual.renderer().finishRenderFrame(paused); }
    public void collectPointLights(EffectDefinition effect, List<ScenePointLight> output) {
        for (var visual : components) visual.renderer().collectPointLights(effect, output);
    }

    public void collectDirectionalLights(EffectDefinition effect, List<SceneDirectionalLight> output) {
        for (var visual : components) visual.renderer().collectDirectionalLights(effect, output);
    }
    public void collectDecals(EffectDefinition effect, List<ParticleDecal> output) {
        for (var visual : components) visual.renderer().collectDecals(effect, output);
    }
    public void collectMeshes(EffectDefinition effect, @Nullable UUID viewActor, List<StaticMeshComponentDraw> output) {
        for (var visual : components) visual.renderer().collectMeshes(effect, viewActor, output);
    }
    @Override public void close() { destroyRenderState(); }
}
