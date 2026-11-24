package com.kltyton.lumenfx.effect.simulation;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.api.client.EffectHandle.Pose;
import com.kltyton.lumenfx.api.client.UnityParticleCollisionEvent.ForceImpact;
import com.kltyton.lumenfx.api.client.parameter.UnityCollisionLayers;
import com.kltyton.lumenfx.effect.asset.mesh.StaticMeshAssetLoader;
import com.kltyton.lumenfx.effect.EmitterDefinition;
import com.kltyton.lumenfx.effect.asset.EffectEmitterJson;
import com.kltyton.lumenfx.effect.asset.EffectJsonFields;
import com.kltyton.lumenfx.effect.asset.VectorFieldLoader;
import com.kltyton.lumenfx.effect.asset.mesh.StaticMeshGeometryLoader;
import com.kltyton.lumenfx.effect.asset.unity.UnityEmitterJson;
import com.kltyton.lumenfx.effect.asset.unity.UnityShapeTextureLoader;
import com.kltyton.lumenfx.effect.render.mesh.StaticMeshAsset;
import com.kltyton.lumenfx.effect.render.mesh.StaticMeshGeometry;
import com.kltyton.lumenfx.effect.unity.UnityExternalForces.FieldState;
import com.kltyton.lumenfx.effect.unity.UnityRendererBinding;
import com.kltyton.lumenfx.effect.unity.UnityTrigger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.function.Function;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceProvider;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** CPU-only execution of authored Unity emitter graphs with world geometry supplied by the host. */
public final class UnityEffectSimulation {
    private static final long CHILD_SEED_SALT = 0x57ba94cbd2fa1273L;
    private final List<UnitySubEmitterGroup<ParticleEmitterSimulation>> groups;

    public record Emitter(EmitterDefinition simulation, @Nullable UnityRendererBinding binding) { }

    public record Definition(List<Emitter> emitters) {
        public Definition {
            emitters = List.copyOf(emitters);
            var graph = new LinkedHashMap<String, List<String>>();
            for (var emitter : emitters) {
                var binding = emitter.binding();
                if (graph.putIfAbsent(emitter.simulation().name(), binding == null ? List.of()
                        : binding.subEmitters().stream().map(link -> link.target()).toList()) != null)
                    throw new IllegalArgumentException("Duplicate Unity simulation emitter " + emitter.simulation().name());
            }
            Set<String> visited = new HashSet<>(), visiting = new HashSet<>();
            for (var name : graph.keySet()) validate(graph, name, visited, visiting);
        }

        private static void validate(Map<String, List<String>> graph, String current, Set<String> visited, Set<String> visiting) {
            if (visited.contains(current)) return;
            if (!visiting.add(current)) throw new IllegalArgumentException("Circular Unity sub-emitter chain " + current);
            var targets = graph.get(current);
            if (targets == null) throw new IllegalArgumentException("Unknown Unity sub-emitter target " + current);
            for (var target : targets) validate(graph, target, visited, visiting);
            visiting.remove(current);
            visited.add(current);
        }
    }

    public static Definition read(JsonObject root, ResourceProvider resources) {
        if (!root.has("format_version") || root.get("format_version").getAsInt() != 1 || root.has("niagara"))
            throw new IllegalArgumentException("Server collision simulation requires a Unity effect with format_version 1");
        Map<Identifier, StaticMeshAsset> assets = new HashMap<>();
        Map<Identifier, StaticMeshGeometry> meshes = new HashMap<>();
        Function<Identifier, StaticMeshAsset> resolveMesh = id -> assets.computeIfAbsent(id, key ->
                StaticMeshAssetLoader.load(resources, key,
                        mesh -> meshes.computeIfAbsent(mesh, location -> StaticMeshGeometryLoader.load(resources, location))));
        var emitters = new ArrayList<Emitter>();
        for (var element : root.getAsJsonArray("emitters")) {
            var json = element.getAsJsonObject();
            var renderer = EffectJsonFields.jsonObject(json, "unity_renderer");
            if (EffectJsonFields.jsonString(renderer, "source") != null) continue;
            var binding = renderer == null ? null : UnityEmitterJson.renderer(renderer, resolveMesh);
            var definition = EffectEmitterJson.read(json, id -> VectorFieldLoader.load(resources, id), resolveMesh,
                    id -> UnityShapeTextureLoader.load(resources, id), EffectEmitterJson.RendererMetadata.EMPTY);
            emitters.add(new Emitter(definition, binding));
        }
        return new Definition(emitters);
    }

    public UnityEffectSimulation(Definition definition, long seed) {
        var random = new SplittableRandom(seed);
        var byName = new LinkedHashMap<String, UnitySubEmitterGroup<ParticleEmitterSimulation>>();
        for (var source : definition.emitters()) {
            var authored = source.simulation();
            var binding = source.binding();
            long emitterSeed = random.nextLong();
            if (binding != null && binding.randomSeed() != null) emitterSeed = binding.randomSeed();
            var emitter = new ParticleEmitterSimulation(authored, emitterSeed, null, binding, null, null)
                    .collectAllEntityContacts();
            byName.put(authored.name(), new UnitySubEmitterGroup<>(emitter, binding == null ? null : binding.transform(),
                    emitterSeed ^ CHILD_SEED_SALT, (childSeed, start, capacity) ->
                            new ParticleEmitterSimulation(authored, childSeed, null, binding, start, capacity)
                                    .collectAllEntityContacts()));
        }
        for (var source : definition.emitters()) {
            if (source.binding() == null) continue;
            for (var link : source.binding().subEmitters())
                byName.get(link.target()).addParent(byName.get(source.simulation().name()));
        }
        groups = byName.values().stream().sorted(Comparator.comparingInt(UnitySubEmitterGroup::group)).toList();
    }

    public boolean finished(double time, boolean stopping) {
        for (var group : groups) if (!group.finished(time, stopping)) return false;
        return true;
    }

    public void step(double time, Pose pose, Level world, @Nullable Vec3 velocity, UnityCollisionLayers layers,
                     Map<Identifier, StaticMeshGeometry> emissionGeometry, Map<String, List<UnityTrigger.Collider>> triggerColliders,
                     Map<String, Integer> manualRequests, boolean emitting, boolean stopping, List<ForceImpact> forces) {
        step(time, pose, world, velocity, layers, emissionGeometry, triggerColliders, manualRequests, emitting, stopping,
                Map.of(), forces);
    }

    public void step(double time, Pose pose, Level world, @Nullable Vec3 velocity, UnityCollisionLayers layers,
                     Map<Identifier, StaticMeshGeometry> emissionGeometry, Map<String, List<UnityTrigger.Collider>> triggerColliders,
                     Map<String, Integer> manualRequests, boolean emitting, boolean stopping, Map<String, FieldState> forceFields,
                     List<ForceImpact> forces) {
        var frame = new UnitySubEmitterGroup.Frame(time, pose, emitting, stopping, true, velocity, world, layers,
                emissionGeometry, triggerColliders, manualRequests, forceFields);
        for (var group : groups) group.simulate(frame, (emitter, step) -> emitter.appendColliderForces(forces));
    }
}
