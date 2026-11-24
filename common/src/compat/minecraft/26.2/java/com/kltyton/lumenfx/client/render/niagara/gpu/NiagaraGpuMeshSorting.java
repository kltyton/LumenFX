package com.kltyton.lumenfx.client.render.niagara.gpu;

import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.client.render.mesh.MeshDrawSelection;
import com.kltyton.lumenfx.client.render.mesh.MeshView;
import com.kltyton.lumenfx.client.render.mesh.MeshLodSelection;
import com.kltyton.lumenfx.client.simulation.EmitterInstance;
import com.kltyton.lumenfx.effect.EffectDefinition;
import com.kltyton.lumenfx.effect.EmitterDefinition;
import com.kltyton.lumenfx.effect.MaterialDefinition;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraPlatformEnvironment;
import com.kltyton.lumenfx.effect.render.mesh.MeshBatchGeometry;
import com.kltyton.lumenfx.effect.render.mesh.MeshRendererSettings;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/** Mesh-specific sphere, LOD, mesh-index and visibility inputs for the shared key generator. */
public final class NiagaraGpuMeshSorting {
    private NiagaraGpuMeshSorting() { }

    public static boolean hasCpuMeshWork(Collection<EffectDefinition> effects) {
        for (var effect : effects) {
            if (effect.niagara() == null) continue;
            for (var emitter : effect.niagara().emitters()) {
                if (emitter.simulation() instanceof NiagaraDefinition.GpuSimulation
                        || emitter.simulation() instanceof NiagaraDefinition.DisabledSimulation
                        || emitter.simulation() instanceof NiagaraDefinition.StatelessSimulation stateless
                        && stateless.target().equals("GPUComputeSim")) continue;
                for (var renderer : emitter.renderers()) {
                    if (!renderer.sourceMode().equals("Particles")) continue;
                    for (var definition : effect.emitters()) {
                        var settings = definition.meshSettings();
                        if (settings == null || !definition.name().equals(renderer.name())
                                || settings.sceneTransforms() != MeshRendererSettings.SceneTransforms.LEGACY) continue;
                        if (NiagaraGpuSpriteSorting.sortOffset(emitter.attributes(), renderer, definition.sortMode(), true) != -1
                                || needsCull(emitter.attributes(), renderer, settings)
                                || settings.meshesBinding() != null || settings.entries().stream().anyMatch(entry ->
                                        entry.lodMode() == MeshRendererSettings.LodMode.PER_PARTICLE)) return true;
                    }
                }
            }
        }
        return false;
    }

    public static boolean dispatchCpu(EffectDefinition effect, EmitterInstance.Snapshot snapshot, MeshView view,
                                      NiagaraPlatformEnvironment platform) {
        var particles = Objects.requireNonNull(snapshot.nativeParticles());
        if (particles.emitterSource() || particles.count() == 0) return false;
        var definition = snapshot.definition();
        var settings = Objects.requireNonNull(definition.meshSettings());
        var data = particles.renderData();
        boolean sort = particles.supportsSort(definition.sortMode()) && (!particles.sortOnlyWhenTranslucent()
                || MeshDrawSelection.translucent(effect, definition, snapshot.input()));
        var lodView = view.withLodPolicy(effect.materials().get(definition.material()).renderSettings().meshLod());
        boolean particleLods = false;
        for (var configured : MeshDrawSelection.entries(settings, snapshot.input())) {
            if (configured.lodMode() != MeshRendererSettings.LodMode.PER_PARTICLE) continue;
            var entry = MeshDrawSelection.entry(configured, snapshot.input(), settings);
            if (usesParticleLods(entry, lodView)) {
                particleLods = true;
                break;
            }
        }
        boolean cull = particleLods || needsCull(data.emitter().attributes(), data.renderer(), settings);
        return platform.sortOrCullCpuParticlesOnGpu(sort, cull, particles.count(), particleLods);
    }

    public static NiagaraGpuSortKeyGenerator.@Nullable Input input(NiagaraGpuParticleView particles,
            NiagaraDefinition.Emitter emitter, NiagaraDefinition.Renderer renderer, EmitterDefinition definition,
            MaterialDefinition material, EffectHandle.Input effect, Vec3 camera, Quaternionf orientation,
            double originX, double originY, double originZ, boolean defaultHighPrecision,
            int batchSize, int elementIndex, int outputOffset, int culledOffset,
            MeshBatchGeometry geometry, int meshIndex, int lod, MeshView view) {
        return input(particles, emitter, renderer, definition, material, effect, camera, orientation, originX, originY, originZ,
                defaultHighPrecision, batchSize, elementIndex, outputOffset, culledOffset, geometry, meshIndex, lod, view,
                material.blend().translucent());
    }

    public static NiagaraGpuSortKeyGenerator.@Nullable Input input(NiagaraGpuParticleView particles,
            NiagaraDefinition.Emitter emitter, NiagaraDefinition.Renderer renderer, EmitterDefinition definition,
            MaterialDefinition material, EffectHandle.Input effect, Vec3 camera, Quaternionf orientation,
            double originX, double originY, double originZ, boolean defaultHighPrecision,
            int batchSize, int elementIndex, int outputOffset, int culledOffset,
            MeshBatchGeometry geometry, int meshIndex, int lod, MeshView view, boolean rendererTranslucent) {
        if (geometry.renderer().sceneTransforms() != MeshRendererSettings.SceneTransforms.LEGACY)
            return NiagaraGpuSpriteSorting.input(particles, emitter, renderer, definition, effect, camera,
                    orientation, originX, originY, originZ, defaultHighPrecision, batchSize, elementIndex, outputOffset,
                    culledOffset, false, false, rendererTranslucent);
        var entry = geometry.mesh();
        var asset = entry.asset();
        boolean particleLods = usesParticleLods(entry, view);
        var attributes = emitter.attributes();
        boolean needsCull = particleLods || needsCull(attributes, renderer, geometry.renderer());
        var base = NiagaraGpuSpriteSorting.input(particles, emitter, renderer, definition, effect, camera,
                orientation, originX, originY, originZ, defaultHighPrecision, batchSize, elementIndex, outputOffset, culledOffset,
                needsCull, needsCull, rendererTranslucent);
        if (base == null || !needsCull) return base;
        var bounds = geometry.sourceBounds();
        Vec3 centre = bounds.origin().multiply(entry.scale());
        Vec3 extent = bounds.extent().multiply(entry.scale()).multiply(geometry.renderer().boundsScale());
        Vec3 pivot = entry.pivot();
        float[] worldOffset = {0, 0, 0};
        if (entry.pivotSpace() == MeshRendererSettings.PivotSpace.MESH) centre = centre.add(pivot);
        else {
            if (entry.pivotSpace() == MeshRendererSettings.PivotSpace.LOCAL
                    || emitter.localSpace() && entry.pivotSpace() == MeshRendererSettings.PivotSpace.SIMULATION) {
                var pose = effect.pose();
                var rotation = new Quaternionf(-pose.x(), -pose.z(), -pose.y(), pose.w());
                Vector3f offset = rotation.transform(new Vector3f((float)pivot.x * pose.scaleX(),
                        (float)pivot.y * pose.scaleZ(), (float)pivot.z * pose.scaleY()));
                pivot = new Vec3(offset.x, offset.y, offset.z);
            }
            worldOffset = new float[]{(float)pivot.x, (float)pivot.y, (float)pivot.z};
        }
        int position = floating(attributes, renderer, "Position", 3);
        int rotation = floating(attributes, renderer, "MeshOrientation", 4);
        int scale = floating(attributes, renderer, "Scale", 3);
        int meshOffset = meshIndexOffset(attributes, renderer);
        float[] lodSize = {0, 2, 1, 0};
        if (particleLods) {
            var lodAsset = Objects.requireNonNull(asset);
            var range = MeshLodSelection.particleRange(lodAsset, entry, view);
            float lower = MeshLodSelection.particleLowerScreenSize(lodAsset, range, lod, view);
            float upper = MeshLodSelection.screenSize(lodAsset, lod, view) * .5f;
            float projected = lodAsset.renderBounds().radius() * view.screenMultiple();
            lodSize = new float[]{lower * lower, lod > range.first() ? upper * upper : Float.MAX_VALUE,
                    projected * projected, view.perspective() * view.lodDistanceFactor() * entry.lodDistanceFactor()};
        }
        float[] planes = geometry.renderer().frustumCulling()
                ? view.sourcePlanes(effect.pose(), emitter.localSpace(), camera, originX, originY, originZ) : new float[0];
        var original = Objects.requireNonNull(base.culling());
        var culling = new NiagaraGpuSortKeyGenerator.Culling(position, rotation, scale, original.visibility(), original.visibilityOffset(),
                meshIndex, meshOffset, original.minDistanceSquared(), original.maxDistanceSquared(), lodSize,
                new float[]{(float)centre.x, (float)centre.y, (float)centre.z, (float)extent.length()}, worldOffset, planes);
        return new NiagaraGpuSortKeyGenerator.Input(base.particles(), base.particleCount(), base.sortMode(), base.sortAttributeOffset(),
                base.cameraPosition(), base.cameraDirection(), batchSize, elementIndex, outputOffset, base.highPrecision(), culling, culledOffset);
    }

    static boolean usesParticleLods(MeshRendererSettings.Entry entry, MeshView view) {
        return entry.lodMode() == MeshRendererSettings.LodMode.PER_PARTICLE && entry.asset() != null
                && MeshLodSelection.particleRange(entry.asset(), entry, view).drawCount() > 1;
    }

    static boolean needsCull(List<NiagaraDefinition.Attribute> attributes, NiagaraDefinition.Renderer renderer,
                             MeshRendererSettings settings) {
        return settings.frustumCulling() || renderer.distanceCulling() || meshIndexOffset(attributes, renderer) != -1
                || renderer.visibilityParticleBinding()
                && NiagaraGpuSpriteSorting.integerOffset(attributes, renderer.visibilityBinding()) != -1;
    }

    private static int meshIndexOffset(List<NiagaraDefinition.Attribute> attributes, NiagaraDefinition.Renderer renderer) {
        var binding = renderer.attributeBindings().get("MeshIndex");
        return binding != null && !binding.particle() ? -1
                : NiagaraGpuSpriteSorting.integerOffset(attributes, binding == null ? "Particles.MeshIndex" : binding.name());
    }

    private static int floating(List<NiagaraDefinition.Attribute> attributes, NiagaraDefinition.Renderer renderer,
                                String semantic, int components) {
        var binding = renderer.attributeBindings().get(semantic);
        return binding != null && !binding.particle() ? -1 : NiagaraGpuSpriteSorting.floatingOffset(attributes,
                binding == null ? "Particles." + semantic : binding.name(), components);
    }
}
