package com.kltyton.lumenfx.effect;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import com.kltyton.lumenfx.effect.niagara.component.NiagaraRendererDrawOrder;
import com.kltyton.lumenfx.api.client.parameter.RendererParameter;
import com.kltyton.lumenfx.api.client.physics.AuthoredPhysicsFieldComponent;
import com.kltyton.lumenfx.api.client.groom.NiagaraGroomAsset;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.kltyton.lumenfx.effect.render.light.PrimitiveLighting;
import com.kltyton.lumenfx.effect.render.depth.CustomDepthStencil;
import com.kltyton.lumenfx.effect.render.mesh.MeshRendererSettings.SceneTransforms;
import com.kltyton.lumenfx.effect.unity.UnityParticleTrail;
import com.kltyton.lumenfx.effect.unity.UnityRendererBinding;
import com.kltyton.lumenfx.effect.unity.UnityRendererBinding.AtlasRowSelection;
import com.kltyton.lumenfx.effect.unity.UnitySubEmitter;
import net.minecraft.resources.Identifier;

public record EffectDefinition(Map<String, MaterialDefinition> materials, List<EmitterDefinition> emitters, NiagaraDefinition niagara,
                                Map<String, RendererParameter> rendererObjects, RenderSettings renderSettings,
                                Map<String, AuthoredPhysicsFieldComponent> physicsFieldComponents,
                                Map<String, NiagaraGroomAsset> groomAssets,
                                Map<String, Map<Integer, Identifier>> materialFonts, PrimitiveLighting primitiveLighting,
                                boolean receivesDecals, CustomDepthStencil customDepthStencil,
                                Map<String, UnityRendererBinding> unityRenderers,
                                Map<String, EmitterDefinition> niagaraRenderers) {
    public EffectDefinition(Map<String, MaterialDefinition> materials, List<EmitterDefinition> emitters, NiagaraDefinition niagara,
                            Map<String, RendererParameter> rendererObjects, RenderSettings renderSettings,
                            Map<String, AuthoredPhysicsFieldComponent> physicsFieldComponents,
                            Map<String, NiagaraGroomAsset> groomAssets, Map<String, Map<Integer, Identifier>> materialFonts,
                            PrimitiveLighting primitiveLighting, boolean receivesDecals, CustomDepthStencil customDepthStencil,
                            Map<String, UnityRendererBinding> unityRenderers) {
        this(materials, emitters, niagara, rendererObjects, renderSettings, physicsFieldComponents, groomAssets, materialFonts,
                primitiveLighting, receivesDecals, customDepthStencil, unityRenderers, Map.of());
    }
    public EffectDefinition(Map<String, MaterialDefinition> materials, List<EmitterDefinition> emitters, NiagaraDefinition niagara,
                            Map<String, RendererParameter> rendererObjects, RenderSettings renderSettings,
                            Map<String, AuthoredPhysicsFieldComponent> physicsFieldComponents,
                            Map<String, NiagaraGroomAsset> groomAssets, Map<String, Map<Integer, Identifier>> materialFonts,
                            PrimitiveLighting primitiveLighting, boolean receivesDecals, CustomDepthStencil customDepthStencil) {
        this(materials, emitters, niagara, rendererObjects, renderSettings, physicsFieldComponents, groomAssets, materialFonts,
                primitiveLighting, receivesDecals, customDepthStencil, Map.of());
    }

    public EffectDefinition(Map<String, MaterialDefinition> materials, List<EmitterDefinition> emitters, NiagaraDefinition niagara,
                            Map<String, RendererParameter> rendererObjects, RenderSettings renderSettings,
                            Map<String, AuthoredPhysicsFieldComponent> physicsFieldComponents,
                            Map<String, NiagaraGroomAsset> groomAssets, Map<String, Map<Integer, Identifier>> materialFonts,
                            PrimitiveLighting primitiveLighting, boolean receivesDecals) {
        this(materials, emitters, niagara, rendererObjects, renderSettings, physicsFieldComponents, groomAssets, materialFonts,
                primitiveLighting, receivesDecals, CustomDepthStencil.DISABLED);
    }

    public EffectDefinition(Map<String, MaterialDefinition> materials, List<EmitterDefinition> emitters, NiagaraDefinition niagara,
                            Map<String, RendererParameter> rendererObjects, RenderSettings renderSettings,
                            Map<String, AuthoredPhysicsFieldComponent> physicsFieldComponents,
                            Map<String, NiagaraGroomAsset> groomAssets, Map<String, Map<Integer, Identifier>> materialFonts,
                            PrimitiveLighting primitiveLighting) {
        this(materials, emitters, niagara, rendererObjects, renderSettings, physicsFieldComponents, groomAssets, materialFonts,
                primitiveLighting, true);
    }

    public EffectDefinition(Map<String, MaterialDefinition> materials, List<EmitterDefinition> emitters, NiagaraDefinition niagara,
                            Map<String, RendererParameter> rendererObjects, RenderSettings renderSettings,
                            Map<String, AuthoredPhysicsFieldComponent> physicsFieldComponents,
                            Map<String, NiagaraGroomAsset> groomAssets, Map<String, Map<Integer, Identifier>> materialFonts) {
        this(materials, emitters, niagara, rendererObjects, renderSettings, physicsFieldComponents, groomAssets,
                materialFonts, PrimitiveLighting.DEFAULT);
    }
    public EffectDefinition(Map<String, MaterialDefinition> materials, List<EmitterDefinition> emitters, NiagaraDefinition niagara,
                            Map<String, RendererParameter> rendererObjects, RenderSettings renderSettings,
                            Map<String, AuthoredPhysicsFieldComponent> physicsFieldComponents,
                            Map<String, NiagaraGroomAsset> groomAssets) {
        this(materials, emitters, niagara, rendererObjects, renderSettings, physicsFieldComponents, groomAssets, Map.of());
    }
    public EffectDefinition(Map<String, MaterialDefinition> materials, List<EmitterDefinition> emitters, NiagaraDefinition niagara,
                            Map<String, RendererParameter> rendererObjects, RenderSettings renderSettings,
                            Map<String, AuthoredPhysicsFieldComponent> physicsFieldComponents) {
        this(materials, emitters, niagara, rendererObjects, renderSettings, physicsFieldComponents, Map.of());
    }
    public EffectDefinition(Map<String, MaterialDefinition> materials, List<EmitterDefinition> emitters, NiagaraDefinition niagara,
                            Map<String, RendererParameter> rendererObjects, RenderSettings renderSettings) {
        this(materials, emitters, niagara, rendererObjects, renderSettings, Map.of());
    }
    public EffectDefinition(Map<String, MaterialDefinition> materials, List<EmitterDefinition> emitters, NiagaraDefinition niagara,
                            Map<String, RendererParameter> rendererObjects) {
        this(materials, emitters, niagara, rendererObjects,
                materials.values().stream().findFirst().map(MaterialDefinition::renderSettings).orElse(RenderSettings.STANDARD));
    }
    public EffectDefinition(Map<String, MaterialDefinition> materials, List<EmitterDefinition> emitters, NiagaraDefinition niagara) {
        this(materials, emitters, niagara, Map.of());
    }
    public EffectDefinition(Map<String, MaterialDefinition> materials, List<EmitterDefinition> emitters) { this(materials, emitters, null); }

    public EmitterDefinition niagaraRenderer(String name) {
        var renderer = niagaraRenderers.get(name);
        if (renderer == null) throw new IllegalArgumentException("Missing Niagara renderer " + name);
        return renderer;
    }

    public EffectDefinition {
        java.util.Objects.requireNonNull(renderSettings, "renderSettings");
        java.util.Objects.requireNonNull(primitiveLighting, "primitiveLighting");
        java.util.Objects.requireNonNull(customDepthStencil, "customDepthStencil");
        materials = Map.copyOf(materials);
        emitters = List.copyOf(emitters);
        rendererObjects = Map.copyOf(rendererObjects);
        physicsFieldComponents = Map.copyOf(physicsFieldComponents);
        groomAssets = Map.copyOf(groomAssets);
        unityRenderers = Map.copyOf(unityRenderers);
        niagaraRenderers = NiagaraRendererDrawOrder.resolve(niagara, emitters);
        var fonts = new LinkedHashMap<String, Map<Integer, Identifier>>();
        materialFonts.forEach((source, pages) -> {
            for (int page : pages.keySet())
                if (page < 0) throw new IllegalArgumentException("Invalid offline font page " + page + " in " + source);
            if (fonts.putIfAbsent(SourceNames.canonical(source), Map.copyOf(pages)) != null)
                throw new IllegalArgumentException("Duplicate offline material font " + source);
        });
        materialFonts = Map.copyOf(fonts);
        if (emitters.isEmpty() && niagara == null || emitters.size() > 256) throw new IllegalArgumentException("Invalid VFX emitter count");
        var names = new HashSet<String>();
        for (EmitterDefinition emitter : emitters) {
            if (!names.add(emitter.name())) throw new IllegalArgumentException("Duplicate emitter " + emitter.name());
            if (emitter.renderer() == EmitterDefinition.Renderer.MESH
                    && emitter.integration() == EmitterDefinition.Integration.ANALYTIC
                    && !unityRenderers.containsKey(emitter.name()))
                throw new IllegalArgumentException("Analytic mesh needs a Unity renderer binding: " + emitter.name());
            if (emitter.renderer() == EmitterDefinition.Renderer.SIMULATION && !unityRenderers.containsKey(emitter.name()))
                throw new IllegalArgumentException("Simulation-only emitter needs a Unity binding: " + emitter.name());
            if (emitter.renderer().usesMaterial() && !materials.containsKey(emitter.material()))
                throw new IllegalArgumentException("Unknown emitter material " + emitter.material());
            if (emitter.renderer().usesMaterial() && materials.get(emitter.material()).lightFunction())
                throw new IllegalArgumentException("Geometry renderer cannot use a light-function material " + emitter.material());
            if (emitter.renderer().usesMaterial() && materials.get(emitter.material()).runtimeVirtualTextureOutputs() != 0)
                throw new IllegalArgumentException("A particle emitter cannot use an RVT page writer " + emitter.material());
        }
        if (!unityRenderers.isEmpty()) validateUnityRenderers(emitters, niagara, unityRenderers);
    }

    private static void validateUnityRenderers(List<EmitterDefinition> emitters, NiagaraDefinition niagara,
                                               Map<String, UnityRendererBinding> bindings) {
        if (niagara != null) throw new IllegalArgumentException("Niagara effects cannot use Unity renderer bindings");
        var definitions = emitters.stream().collect(Collectors.toMap(EmitterDefinition::name, Function.identity()));
        var trailSources = new HashSet<String>();
        bindings.forEach((name, binding) -> {
            var renderer = definitions.get(name);
            if (renderer == null || renderer.integration() != EmitterDefinition.Integration.ANALYTIC)
                throw new IllegalArgumentException("Unknown Unity renderer " + name);
            if (binding.billboard() != null && renderer.renderer() != EmitterDefinition.Renderer.SPRITE
                    && renderer.renderer() != EmitterDefinition.Renderer.SIMULATION)
                throw new IllegalArgumentException("Invalid Unity billboard renderer " + name);
            if (binding.prewarm() && (binding.source() != null || !renderer.looping() || renderer.delay() != 0))
                throw new IllegalArgumentException("Unity prewarm requires a looping emitter without a start delay: " + name);
            if (binding.startDelayMaximum() != null && binding.startDelayMaximum() < renderer.delay())
                throw new IllegalArgumentException("Unity start delay range is reversed: " + name);
            if ((binding.meshMotion() != null) != (renderer.renderer() == EmitterDefinition.Renderer.MESH))
                throw new IllegalArgumentException("Invalid Unity mesh renderer " + name);
            if (binding.atlasRowSelection() != AtlasRowSelection.DEFAULT && (renderer.atlasRow() >= 0
                    || binding.source() != null || renderer.renderer() != EmitterDefinition.Renderer.SPRITE && binding.meshMotion() == null))
                throw new IllegalArgumentException("Invalid Unity particle atlas row selection " + name);
            if ((binding.atlasFps() != null || binding.atlasSpeed() != null) && (binding.source() != null
                    || renderer.renderer() != EmitterDefinition.Renderer.SPRITE && binding.meshMotion() == null))
                throw new IllegalArgumentException("Invalid Unity particle atlas playback " + name);
            if (!binding.spriteFrames().isEmpty() && (binding.source() != null
                    || renderer.renderer() != EmitterDefinition.Renderer.SPRITE && binding.meshMotion() == null
                    || renderer.atlasRows() != 1 || renderer.atlasColumns() != binding.spriteFrames().size()
                    || renderer.atlasRow() != -1 || binding.atlasRowSelection() != AtlasRowSelection.DEFAULT))
                throw new IllegalArgumentException("Invalid Unity particle sprite frame sequence " + name);
            if (binding.meshMotion() != null) {
                var mesh = renderer.meshSettings();
                if (binding.source() != null || mesh == null || mesh.entries().isEmpty()
                        || mesh.entries().stream().anyMatch(entry -> entry.asset() == null
                            || entry.asset().lods().isEmpty() || entry.asset().lods().getFirst().geometry() == null)
                        || mesh.sceneTransforms() != SceneTransforms.LEGACY)
                    throw new IllegalArgumentException("Unity mesh renderer requires static meshes with render geometry: " + name);
            }
            if (binding.atlasRowSelection() == AtlasRowSelection.MESH_INDEX && binding.meshMotion() == null)
                throw new IllegalArgumentException("Unity mesh-index atlas needs a mesh renderer: " + name);
            boolean simulatedRenderer = renderer.renderer() == EmitterDefinition.Renderer.RIBBON
                    || renderer.renderer() == EmitterDefinition.Renderer.SPRITE
                    || renderer.renderer() == EmitterDefinition.Renderer.SIMULATION || binding.meshMotion() != null;
            if (binding.inheritVelocity() != null && (binding.source() != null || renderer.localSpace() || !simulatedRenderer))
                throw new IllegalArgumentException("Invalid Unity velocity inheritance for " + name);
            if ((binding.velocityLimit() != null || binding.spaceMotion() != null || binding.collision() != null
                    || binding.speedModules() != null || binding.lifetimeByEmitterSpeed() != null
                    || !binding.sizeOverLifetimeAxes().isEmpty() || !binding.subEmitters().isEmpty()
                    || binding.trigger() != null || binding.lights() != null || binding.noise() != null
                    || binding.externalForces() != null || binding.transform() != null || binding.emissionTransform() != null)
                    && (binding.source() != null
                    || !simulatedRenderer))
                throw new IllegalArgumentException("Invalid Unity motion settings for " + name);
            if (binding.emissionTransform() != null && !renderer.localSpace())
                throw new IllegalArgumentException("Unity emission transform requires custom/local simulation space: " + name);
            for (UnitySubEmitter link : binding.subEmitters()) {
                var target = bindings.get(link.target());
                if (target == null || target.source() != null)
                    throw new IllegalArgumentException("Unknown Unity sub-emitter " + link.target() + " of " + name);
            }
            if ((binding.ribbonCount() != null || binding.trailTexture() != null)
                    && (renderer.renderer() != EmitterDefinition.Renderer.RIBBON
                    || renderer.ribbonSettings() != null))
                throw new IllegalArgumentException("Invalid Unity trail renderer " + name);
            if (binding.particleTrail() != null && (renderer.renderer() != EmitterDefinition.Renderer.RIBBON
                    || renderer.ribbonSettings() != null
                    || binding.particleTrail().mode() == UnityParticleTrail.Mode.PER_PARTICLE
                        && (renderer.attachHead() || !trailSources.add(binding.source() == null ? name : binding.source()))))
                throw new IllegalArgumentException("Invalid Unity particle trail renderer " + name);
            if (binding.source() == null) return;
            var source = definitions.get(binding.source());
            var sourceBinding = bindings.get(binding.source());
            if (renderer.renderer() != EmitterDefinition.Renderer.RIBBON || source == null
                    || source.renderer() != EmitterDefinition.Renderer.SPRITE && source.renderer() != EmitterDefinition.Renderer.MESH
                    || sourceBinding == null
                    || sourceBinding.source() != null || renderer.localSpace() != source.localSpace())
                throw new IllegalArgumentException("Invalid Unity particle source for " + name + ": " + binding.source());
        });
        var visited = new HashSet<String>();
        var visiting = new HashSet<String>();
        for (String name : bindings.keySet()) visitUnityEmitter(name, bindings, visited, visiting);
    }

    private static void visitUnityEmitter(String name, Map<String, UnityRendererBinding> bindings,
                                          HashSet<String> visited, HashSet<String> visiting) {
        if (visited.contains(name)) return;
        if (!visiting.add(name)) throw new IllegalArgumentException("Cyclic Unity sub-emitter at " + name);
        for (UnitySubEmitter link : bindings.get(name).subEmitters())
            visitUnityEmitter(link.target(), bindings, visited, visiting);
        visiting.remove(name);
        visited.add(name);
    }
}
