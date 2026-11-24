package com.kltyton.lumenfx.client.render.niagara.gpu;

import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.client.simulation.niagara.NiagaraCpuRenderData;
import com.kltyton.lumenfx.effect.EffectDefinition;
import com.kltyton.lumenfx.effect.EmitterDefinition;
import com.kltyton.lumenfx.effect.MaterialDefinition;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import com.kltyton.lumenfx.effect.niagara.NiagaraParticleReadTypes;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraPlatformEnvironment;
import java.util.Collection;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/** Builds source-space sort and visibility inputs for one GPU sprite renderer. */
public final class NiagaraGpuSpriteSorting {
    private NiagaraGpuSpriteSorting() { }

    public static boolean hasCpuSpriteWork(Collection<EffectDefinition> effects) {
        for (var effect : effects) {
            if (effect.niagara() == null) continue;
            for (var emitter : effect.niagara().emitters()) {
                var simulation = emitter.simulation();
                if (simulation instanceof NiagaraDefinition.GpuSimulation
                        || simulation instanceof NiagaraDefinition.DisabledSimulation
                        || simulation instanceof NiagaraDefinition.StatelessSimulation stateless
                        && stateless.target().equals("GPUComputeSim")) continue;
                for (var renderer : emitter.renderers()) {
                    if (!renderer.sourceMode().equals("Particles")) continue;
                    for (var definition : effect.emitters()) {
                        if (!definition.name().equals(renderer.name()) || definition.renderer() != EmitterDefinition.Renderer.SPRITE)
                            continue;
                        if (sortOffset(emitter.attributes(), renderer, definition.sortMode(), true) != -1
                                || needsCull(emitter.attributes(), renderer)) return true;
                    }
                }
            }
        }
        return false;
    }

    public static boolean dispatchCpu(NiagaraCpuRenderData data, EmitterDefinition definition, MaterialDefinition material,
                                      NiagaraPlatformEnvironment platform) {
        var renderer = data.renderer();
        var attributes = data.emitter().attributes();
        int count = data.particles().count();
        return renderer.sourceMode().equals("Particles") && count != 0
                && platform.sortOrCullCpuParticlesOnGpu(
                        sortOffset(attributes, renderer, definition.sortMode(), usesTranslucentSort(material)) != -1,
                        needsCull(attributes, renderer), count);
    }

    public static boolean usesTranslucentSort(MaterialDefinition material) {
        return switch (material.blend()) {
            case ALPHA, PREMULTIPLIED, HOLDOUT, COLORED_TRANSMITTANCE -> true;
            default -> false;
        };
    }

    public static NiagaraGpuSortKeyGenerator.@Nullable Input input(NiagaraGpuParticleView particles,
            NiagaraDefinition.Emitter emitter, NiagaraDefinition.Renderer renderer,
            EmitterDefinition definition, MaterialDefinition material, EffectHandle.Input effect,
            Vec3 camera, Quaternionf cameraOrientation, double originX, double originY, double originZ,
            boolean defaultHighPrecision, int batchSize, int elementIndex, int outputOffset, int culledOffset) {
        return input(particles, emitter, renderer, definition, material, effect, camera, cameraOrientation,
                originX, originY, originZ, defaultHighPrecision, batchSize, elementIndex, outputOffset, culledOffset, false);
    }

    static NiagaraGpuSortKeyGenerator.@Nullable Input input(NiagaraGpuParticleView particles,
            NiagaraDefinition.Emitter emitter, NiagaraDefinition.Renderer renderer,
            EmitterDefinition definition, MaterialDefinition material, EffectHandle.Input effect,
            Vec3 camera, Quaternionf cameraOrientation, double originX, double originY, double originZ,
            boolean defaultHighPrecision, int batchSize, int elementIndex, int outputOffset, int culledOffset, boolean forceCull) {
        return input(particles, emitter, renderer, definition, material, effect, camera, cameraOrientation,
                originX, originY, originZ, defaultHighPrecision, batchSize, elementIndex, outputOffset, culledOffset, forceCull, true);
    }

    static NiagaraGpuSortKeyGenerator.@Nullable Input input(NiagaraGpuParticleView particles,
            NiagaraDefinition.Emitter emitter, NiagaraDefinition.Renderer renderer,
            EmitterDefinition definition, MaterialDefinition material, EffectHandle.Input effect,
            Vec3 camera, Quaternionf cameraOrientation, double originX, double originY, double originZ,
            boolean defaultHighPrecision, int batchSize, int elementIndex, int outputOffset, int culledOffset,
            boolean forceCull, boolean legacyCulling) {
        return input(particles, emitter, renderer, definition, effect, camera, cameraOrientation, originX, originY, originZ,
                defaultHighPrecision, batchSize, elementIndex, outputOffset, culledOffset, forceCull, legacyCulling,
                usesTranslucentSort(material));
    }

    static NiagaraGpuSortKeyGenerator.@Nullable Input input(NiagaraGpuParticleView particles,
            NiagaraDefinition.Emitter emitter, NiagaraDefinition.Renderer renderer,
            EmitterDefinition definition, EffectHandle.Input effect, Vec3 camera, Quaternionf cameraOrientation,
            double originX, double originY, double originZ, boolean defaultHighPrecision, int batchSize, int elementIndex,
            int outputOffset, int culledOffset, boolean forceCull, boolean legacyCulling, boolean translucent) {
        if (renderer.sourceMode().equals("Emitter")) return null;
        List<NiagaraDefinition.Attribute> attributes = emitter.attributes();
        int visibilityOffset = renderer.visibilityParticleBinding()
                ? integerOffset(attributes, renderer.visibilityBinding()) : -1;
        boolean needsCull = legacyCulling && (forceCull || renderer.distanceCulling() || visibilityOffset != -1);
        int positionOffset = positionOffset(attributes, renderer);
        int sortOffset = sortOffset(attributes, renderer, definition.sortMode(), translucent);
        boolean needsSort = sortOffset != -1;
        if (!needsSort && !needsCull) return null;
        if (!needsSort) sortOffset = positionOffset;
        Vector3f direction = cameraOrientation.transform(new Vector3f(0, 0, -1));
        Vector3f position;
        Vector3f sourceDirection = new Vector3f(direction.x, direction.z, direction.y);
        if (emitter.localSpace()) {
            var pose = effect.pose();
            var owner = com.kltyton.lumenfx.client.transform.NiagaraComponentMatrices.source(pose, 0, 0, 0, true, new org.joml.Matrix4d());
            var inverseOwner = com.kltyton.lumenfx.client.transform.NiagaraComponentMatrices.inverse(owner, new org.joml.Matrix4d());
            var localCamera = inverseOwner.transformPosition(new org.joml.Vector3d(camera.x * 100, camera.z * 100, camera.y * 100));
            position = new Vector3f((float)localCamera.x, (float)localCamera.y, (float)localCamera.z);
            var localDirection = new org.joml.Vector3d(sourceDirection.x, sourceDirection.y, sourceDirection.z);
            owner.transpose().transformDirection(localDirection);
            sourceDirection.set((float)localDirection.x, (float)localDirection.y, (float)localDirection.z);
        } else {
            position = new Vector3f((float) (camera.x * 100 - originX),
                    (float) (camera.z * 100 - originY), (float) (camera.y * 100 - originZ));
        }
        float[] cameraPosition = {position.x, position.y, position.z};
        float[] cameraDirection = {sourceDirection.x, sourceDirection.y, sourceDirection.z};
        NiagaraGpuSortKeyGenerator.Culling culling = null;
        if (needsCull) {
            float minimum = renderer.distanceCulling() ? renderer.minCameraDistance() : 0;
            float maximum = renderer.distanceCulling() ? renderer.maxCameraDistance() : Float.MAX_VALUE;
            culling = new NiagaraGpuSortKeyGenerator.Culling(
                    renderer.distanceCulling() || forceCull ? positionOffset : -1,
                    -1, -1, renderer.rendererVisibility(), visibilityOffset, 0, -1,
                    minimum * minimum, maximum * maximum, new float[]{0, 2, 1, 0},
                    new float[]{0, 0, 0, 0}, new float[]{0, 0, 0}, new float[0]);
        }
        int mode = !needsSort ? 0 : switch (definition.sortMode()) {
            case NONE -> 0;
            case VIEW_DEPTH -> 1;
            case DISTANCE -> 2;
            case CUSTOM_ASCENDING -> 3;
            case CUSTOM_DESCENDING -> 4;
        };
        boolean highPrecision = switch (renderer.sortPrecision()) {
            case "High" -> true;
            case "Low" -> false;
            case "Default" -> defaultHighPrecision;
            default -> throw new IllegalArgumentException("Unknown Niagara sort precision");
        };
        return new NiagaraGpuSortKeyGenerator.Input(particles, particles.estimate().current(), mode, sortOffset,
                cameraPosition, cameraDirection, batchSize, elementIndex, outputOffset, highPrecision, culling, culledOffset);
    }

    private static boolean needsCull(List<NiagaraDefinition.Attribute> attributes, NiagaraDefinition.Renderer renderer) {
        return renderer.distanceCulling() || renderer.visibilityParticleBinding()
                && integerOffset(attributes, renderer.visibilityBinding()) != -1;
    }

    static int sortOffset(List<NiagaraDefinition.Attribute> attributes, NiagaraDefinition.Renderer renderer,
                          EmitterDefinition.SortMode mode, boolean translucent) {
        if (!translucent && renderer.sortOnlyWhenTranslucent()) return -1;
        return switch (mode) {
            case CUSTOM_ASCENDING, CUSTOM_DESCENDING -> renderer.customSortParticleBinding()
                    ? floatingOffset(attributes, renderer.customSortBinding(), 1) : -1;
            case DISTANCE, VIEW_DEPTH -> positionOffset(attributes, renderer);
            case NONE -> -1;
        };
    }

    private static int positionOffset(List<NiagaraDefinition.Attribute> attributes, NiagaraDefinition.Renderer renderer) {
        var binding = renderer.attributeBindings().get("Position");
        return binding != null && !binding.particle() ? -1
                : floatingOffset(attributes, binding == null ? "Particles.Position" : binding.name(), 3);
    }

    static int floatingOffset(List<NiagaraDefinition.Attribute> attributes, @Nullable String name, int count) {
        NiagaraDefinition.Attribute value = find(attributes, name);
        if (value == null || value.component() < 0 || value.count() != count || (value.type() != 0 && value.type() != 2)) return -1;
        return value.component() | (value.type() == 2 ? Integer.MIN_VALUE : 0);
    }

    static int integerOffset(List<NiagaraDefinition.Attribute> attributes, @Nullable String name) {
        var value = NiagaraParticleReadTypes.renderer(attributes, name, "Int");
        return value == null ? -1 : value.component();
    }

    private static NiagaraDefinition.@Nullable Attribute find(List<NiagaraDefinition.Attribute> attributes, @Nullable String name) {
        if (name == null) return null;
        String bare = name.regionMatches(true, 0, "Particles.", 0, "Particles.".length())
                ? name.substring("Particles.".length()) : name;
        for (var attribute : attributes) {
            String attrBare = attribute.name().regionMatches(true, 0, "Particles.", 0, "Particles.".length())
                    ? attribute.name().substring("Particles.".length()) : attribute.name();
            if (com.kltyton.lumenfx.effect.niagara.parameter.SourceNames.equal(attrBare, bare))
                return attribute;
        }
        return null;
    }
}
