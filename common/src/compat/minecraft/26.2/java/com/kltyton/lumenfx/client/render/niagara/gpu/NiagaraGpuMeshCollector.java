package com.kltyton.lumenfx.client.render.niagara.gpu;

import com.kltyton.lumenfx.client.render.light.shadow.geometry.ParticleShadowGeometry;
import com.kltyton.lumenfx.client.render.light.shadow.geometry.PointShadowParticleView;
import com.kltyton.lumenfx.client.render.niagara.gpu.mesh.dynamic.NiagaraDynamicMeshRenderSource;
import java.util.function.Function;
import java.util.function.Predicate;

import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.api.client.parameter.NiagaraPlatforms;
import com.kltyton.lumenfx.client.render.mesh.MeshView;
import com.kltyton.lumenfx.client.render.mesh.MeshDrawSelection;
import com.kltyton.lumenfx.client.render.mesh.MeshLodSelection;
import com.kltyton.lumenfx.client.simulation.niagara.VectorDataSet;
import com.kltyton.lumenfx.client.simulation.niagara.NiagaraRendererParameters;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraParameterStore;
import com.kltyton.lumenfx.effect.EffectDefinition;
import com.kltyton.lumenfx.effect.EmitterDefinition;
import com.kltyton.lumenfx.effect.MaterialDefinition;
import com.kltyton.lumenfx.effect.niagara.NiagaraComponentDefaults;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import com.kltyton.lumenfx.effect.render.mesh.MeshBatchGeometry;
import com.kltyton.lumenfx.effect.render.mesh.MeshRendererSettings;
import com.kltyton.lumenfx.effect.niagara.NiagaraBounds;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

public final class NiagaraGpuMeshCollector implements AutoCloseable {
    public record Task(NiagaraGpuMeshDraw draw, NiagaraGpuParticleView particles,
                       NiagaraDefinition.Emitter emitter, NiagaraDefinition.Renderer renderer,
                       EmitterDefinition definition, MaterialDefinition material, VectorDataSet system,
                       Map<String, NiagaraDefinition.Variable> variables, EffectHandle.Input input,
                       @Nullable NiagaraParameterStore store,
                       Vec3 camera, Quaternionf orientation, double originX, double originY, double originZ,
                       double time, double depth, boolean defaultHighPrecision, boolean stereo, boolean postOpaque,
                       MeshBatchGeometry geometry, int meshIndex, int lod, MeshView view, EffectHandle.Pose previousPose,
                       boolean componentVelocity, boolean rendererTranslucent, @Nullable NiagaraBounds componentBounds) {
        public Task(NiagaraGpuMeshDraw draw, NiagaraGpuParticleView particles,
                    NiagaraDefinition.Emitter emitter, NiagaraDefinition.Renderer renderer,
                    EmitterDefinition definition, MaterialDefinition material, VectorDataSet system,
                    Map<String, NiagaraDefinition.Variable> variables, EffectHandle.Input input,
                    Vec3 camera, Quaternionf orientation, double originX, double originY, double originZ,
                    double time, double depth, boolean defaultHighPrecision, boolean stereo, boolean postOpaque,
                    MeshBatchGeometry geometry, int meshIndex, int lod, MeshView view, EffectHandle.Pose previousPose,
                    boolean componentVelocity, boolean rendererTranslucent, @Nullable NiagaraBounds componentBounds) {
            this(draw, particles, emitter, renderer, definition, material, system, variables, input, null,
                    camera, orientation, originX, originY, originZ, time, depth, defaultHighPrecision, stereo, postOpaque,
                    geometry, meshIndex, lod, view, previousPose, componentVelocity, rendererTranslucent, componentBounds);
        }
        public Task(NiagaraGpuMeshDraw draw, NiagaraGpuParticleView particles,
                    NiagaraDefinition.Emitter emitter, NiagaraDefinition.Renderer renderer,
                    EmitterDefinition definition, MaterialDefinition material, VectorDataSet system,
                    Map<String, NiagaraDefinition.Variable> variables, EffectHandle.Input input,
                    Vec3 camera, Quaternionf orientation, double originX, double originY, double originZ,
                    double time, double depth, boolean defaultHighPrecision, boolean stereo, boolean postOpaque,
                    MeshBatchGeometry geometry, int meshIndex, int lod, MeshView view, EffectHandle.Pose previousPose,
                    boolean componentVelocity, boolean rendererTranslucent) {
            this(draw, particles, emitter, renderer, definition, material, system, variables, input, camera, orientation,
                    originX, originY, originZ, time, depth, defaultHighPrecision, stereo, postOpaque, geometry, meshIndex, lod,
                    view, previousPose, componentVelocity, rendererTranslucent, null);
        }

        public Task(NiagaraGpuMeshDraw draw, NiagaraGpuParticleView particles,
                    NiagaraDefinition.Emitter emitter, NiagaraDefinition.Renderer renderer,
                    EmitterDefinition definition, MaterialDefinition material, VectorDataSet system,
                    Map<String, NiagaraDefinition.Variable> variables, EffectHandle.Input input,
                    Vec3 camera, Quaternionf orientation, double originX, double originY, double originZ,
                    double time, double depth, boolean defaultHighPrecision, boolean stereo, boolean postOpaque,
                    MeshBatchGeometry geometry, int meshIndex, int lod, MeshView view, EffectHandle.Pose previousPose,
                    boolean componentVelocity) {
            this(draw, particles, emitter, renderer, definition, material, system, variables, input, camera, orientation,
                    originX, originY, originZ, time, depth, defaultHighPrecision, stereo, postOpaque, geometry, meshIndex, lod,
                    view, previousPose, componentVelocity, material.blend().translucent());
        }
        public Task(NiagaraGpuMeshDraw draw, NiagaraGpuParticleView particles,
                    NiagaraDefinition.Emitter emitter, NiagaraDefinition.Renderer renderer,
                    EmitterDefinition definition, MaterialDefinition material, VectorDataSet system,
                    Map<String, NiagaraDefinition.Variable> variables, EffectHandle.Input input,
                    Vec3 camera, Quaternionf orientation, double originX, double originY, double originZ,
                    double time, double depth, boolean defaultHighPrecision, boolean stereo, boolean postOpaque,
                    MeshBatchGeometry geometry, int meshIndex, int lod, MeshView view, EffectHandle.Pose previousPose) {
            this(draw, particles, emitter, renderer, definition, material, system, variables, input, camera, orientation,
                    originX, originY, originZ, time, depth, defaultHighPrecision, stereo, postOpaque, geometry, meshIndex, lod,
                    view, previousPose, true);
        }
    }
    public record RendererTask(NiagaraGpuMeshDraw draw, NiagaraGpuParticleView particles,
                               NiagaraDefinition.Emitter emitter, NiagaraDefinition.Renderer renderer,
                               EffectDefinition effect, EmitterDefinition definition, VectorDataSet system,
                               Map<String, NiagaraDefinition.Variable> variables, EffectHandle.Input input,
                               @Nullable NiagaraParameterStore store,
                               Vec3 camera, Quaternionf orientation, double originX, double originY, double originZ,
                               double time, double depth, boolean defaultHighPrecision, boolean stereo, boolean postOpaque,
                               MeshView view, java.util.function.Supplier<NiagaraBounds> bounds, EffectHandle.Pose previousPose,
                               java.util.function.UnaryOperator<MaterialDefinition> materialInstances,
                               Function<String, @Nullable NiagaraDynamicMeshRenderSource> dynamicMeshes) {
        public RendererTask forPointShadow(NiagaraGpuMeshDraw shadow, PointShadowParticleView shadowView) {
            return new RendererTask(shadow, particles, emitter, renderer, effect, definition, system, variables, input, store,
                    shadowView.camera(), shadowView.orientation(), originX, originY, originZ, time, depth, defaultHighPrecision,
                    false, false, shadowView.meshView(), bounds, previousPose, materialInstances, dynamicMeshes);
        }

        public RendererTask(NiagaraGpuMeshDraw draw, NiagaraGpuParticleView particles,
                            NiagaraDefinition.Emitter emitter, NiagaraDefinition.Renderer renderer,
                            EffectDefinition effect, EmitterDefinition definition, VectorDataSet system,
                            Map<String, NiagaraDefinition.Variable> variables, EffectHandle.Input input,
                            Vec3 camera, Quaternionf orientation, double originX, double originY, double originZ,
                            double time, double depth, boolean defaultHighPrecision, boolean stereo, boolean postOpaque,
                            MeshView view, java.util.function.Supplier<NiagaraBounds> bounds, EffectHandle.Pose previousPose,
                            java.util.function.UnaryOperator<MaterialDefinition> materialInstances,
                            Function<String, @Nullable NiagaraDynamicMeshRenderSource> dynamicMeshes) {
            this(draw, particles, emitter, renderer, effect, definition, system, variables, input, null,
                    camera, orientation, originX, originY, originZ, time, depth, defaultHighPrecision, stereo, postOpaque,
                    view, bounds, previousPose, materialInstances, dynamicMeshes);
        }

        public RendererTask(NiagaraGpuMeshDraw draw, NiagaraGpuParticleView particles,
                            NiagaraDefinition.Emitter emitter, NiagaraDefinition.Renderer renderer,
                            EffectDefinition effect, EmitterDefinition definition, VectorDataSet system,
                            Map<String, NiagaraDefinition.Variable> variables, EffectHandle.Input input,
                            Vec3 camera, Quaternionf orientation, double originX, double originY, double originZ,
                            double time, double depth, boolean defaultHighPrecision, boolean stereo, boolean postOpaque,
                            MeshView view, java.util.function.Supplier<NiagaraBounds> bounds, EffectHandle.Pose previousPose,
                            java.util.function.UnaryOperator<MaterialDefinition> materialInstances) {
            this(draw, particles, emitter, renderer, effect, definition, system, variables, input, camera, orientation,
                    originX, originY, originZ, time, depth, defaultHighPrecision, stereo, postOpaque, view, bounds,
                    previousPose, materialInstances, ignored -> null);
        }
        public RendererTask(NiagaraGpuMeshDraw draw, NiagaraGpuParticleView particles,
                            NiagaraDefinition.Emitter emitter, NiagaraDefinition.Renderer renderer,
                            EffectDefinition effect, EmitterDefinition definition, VectorDataSet system,
                            Map<String, NiagaraDefinition.Variable> variables, EffectHandle.Input input,
                            Vec3 camera, Quaternionf orientation, double originX, double originY, double originZ,
                            double time, double depth, boolean defaultHighPrecision, boolean stereo, boolean postOpaque,
                            MeshView view, java.util.function.Supplier<NiagaraBounds> bounds, EffectHandle.Pose previousPose) {
            this(draw, particles, emitter, renderer, effect, definition, system, variables, input, camera, orientation,
                    originX, originY, originZ, time, depth, defaultHighPrecision, stereo, postOpaque, view, bounds,
                    previousPose, java.util.function.UnaryOperator.identity());
        }
        public RendererTask {
            java.util.Objects.requireNonNull(materialInstances);
            java.util.Objects.requireNonNull(dynamicMeshes);
        }
    }
    private final NiagaraGpuSortCollector sorting;
    private record SortSource(NiagaraGpuMeshDraw draw, NiagaraGpuParticleView particles, int mesh, int lod,
                              MeshView view, boolean postOpaque) { }
    private record EmitterTransformKey(NiagaraGpuParticleView particles, NiagaraDefinition.Renderer renderer,
                                       MeshRendererSettings.Entry entry, boolean localSpace) { }
    private record DynamicDraw(NiagaraDynamicMeshRenderSource source, Map<Integer, MaterialDefinition> materials) { }
    private boolean closed;

    public NiagaraGpuMeshCollector(NiagaraGpuPrograms programs) { sorting = new NiagaraGpuSortCollector("Niagara.MeshSort", programs); }

    /** A renderer has one particle-data selection even when its material slots span multiple LODs. */
    public static boolean usesOnlyMaterials(RendererTask source, Predicate<MaterialDefinition> condition) {
        var settings = java.util.Objects.requireNonNull(source.definition().meshSettings());
        boolean hasMaterial = false;
        for (var configured : MeshDrawSelection.entries(settings, source.input())) {
            String binding = configured.assetBinding();
            var dynamic = settings.nanite() != null || binding == null || MeshDrawSelection.hasStaticOverride(source.input(), binding)
                    ? null : source.dynamicMeshes().apply(binding);
            if (dynamic != null) {
                for (int section = 0; section < dynamic.geometry().sections().size(); section++) {
                    var material = MeshDrawSelection.dynamicMaterial(source.effect(), source.definition(), dynamic, section, source.input());
                    if (!condition.test(source.materialInstances().apply(material))) return false;
                    hasMaterial = true;
                }
                continue;
            }
            var entry = MeshDrawSelection.entry(configured, source.input(), settings);
            var asset = entry.asset();
            if (asset == null) continue;
            if (asset.materials().isEmpty()) {
                for (var lod : asset.lods()) for (var section : lod.sections()) {
                    var material = MeshDrawSelection.material(source.effect(), source.definition(), asset, entry, section, source.input());
                    if (!condition.test(source.materialInstances().apply(material))) return false;
                    hasMaterial = true;
                }
            } else {
                for (int slot = 0; slot < asset.materials().size(); slot++) {
                    var material = MeshDrawSelection.materialAtSlot(source.effect(), source.definition(), asset, entry, slot, source.input());
                    if (!condition.test(source.materialInstances().apply(material))) return false;
                    hasMaterial = true;
                }
            }
        }
        return hasMaterial;
    }

    public static void append(List<Task> output, RendererTask source) {
        boolean componentVelocity = source.effect().niagara().emitters().stream()
                .flatMap(emitter -> emitter.renderers().stream()).anyMatch(renderer -> renderer.motion().enabled())
                || com.kltyton.lumenfx.client.simulation.niagara.NiagaraVelocityPolicy.moved(source.input().pose(), source.previousPose());
        MeshView view = source.view().withLodPolicy(source.effect().materials().get(source.definition().material()).renderSettings().meshLod());
        if (source.renderer().sourceMode().equals("Emitter") && source.renderer().distanceCulling()) {
            float distance = NiagaraComponentDefaults.RENDERER_LOD_DISTANCE_OVERRIDE;
            float squared = distance * distance;
            float minimum = source.renderer().minCameraDistance(), maximum = source.renderer().maxCameraDistance();
            if (squared < minimum * minimum || squared > maximum * maximum) return;
        }
        var settings = java.util.Objects.requireNonNull(source.definition().meshSettings());
        var indexBinding = source.renderer().attributeBindings().get("MeshIndex");
        Integer hostMeshIndex = indexBinding != null && !indexBinding.particle()
                ? NiagaraRendererParameters.integer(indexBinding.name(), false, source.system(), source.variables(), source.input(),
                    source.store(), source.emitter().rendererBindings()) : null;
        var entries = MeshDrawSelection.entries(settings, source.input());
        boolean rendererTranslucent = MeshDrawSelection.translucent(source.effect(), source.definition(), source.input());
        var dynamicEntries = new java.util.HashMap<Integer, DynamicDraw>();
        for (int index = 0; index < entries.size(); index++) {
            String binding = entries.get(index).assetBinding();
            if (settings.nanite() != null || binding == null || MeshDrawSelection.hasStaticOverride(source.input(), binding)) continue;
            var dynamic = source.dynamicMeshes().apply(binding);
            if (dynamic == null) continue;
            var materials = new java.util.LinkedHashMap<Integer, MaterialDefinition>();
            for (int section = 0; section < dynamic.geometry().sections().size(); section++) {
                var sourceSection = dynamic.geometry().sections().get(section);
                if (sourceSection.indexCount() == 0 || view.shadowPass() && !ParticleShadowGeometry.casts(sourceSection)) continue;
                var material = source.materialInstances().apply(MeshDrawSelection.dynamicMaterial(source.effect(),
                        source.definition(), dynamic, section, source.input()));
                if (view.shadowPass() && !ParticleShadowGeometry.casts(material)) continue;
                materials.put(section, material);
                rendererTranslucent |= material.blend().translucent();
            }
            dynamicEntries.put(index, new DynamicDraw(dynamic, materials));
        }
        boolean particleSource = source.renderer().sourceMode().equals("Particles");
        boolean firstMeshOnly = false;
        if (particleSource && settings.sceneTransforms() == MeshRendererSettings.SceneTransforms.LEGACY) {
            var attributes = source.emitter().attributes();
            boolean needsSort = NiagaraGpuSpriteSorting.sortOffset(attributes, source.renderer(),
                    source.definition().sortMode(), rendererTranslucent) != -1;
            boolean needsCull = NiagaraGpuMeshSorting.needsCull(attributes, source.renderer(), settings);
            if (!needsCull) for (int index = 0; index < entries.size(); index++) {
                if (dynamicEntries.containsKey(index)) continue;
                var entry = MeshDrawSelection.entry(entries.get(index), source.input(), settings);
                if (NiagaraGpuMeshSorting.usesParticleLods(entry, view)) {
                    needsCull = true;
                    break;
                }
            }
            firstMeshOnly = needsCull && !NiagaraPlatforms.snapshot().canSortOrCullParticlesOnGpu(needsSort, true);
        }
        int nextDrawIndex = 0;
        int meshCount = firstMeshOnly ? Math.min(1, entries.size()) : entries.size();
        for (int meshIndex = 0; meshIndex < meshCount; meshIndex++) {
            var entry = MeshDrawSelection.entry(entries.get(meshIndex), source.input(), settings);
            var live = dynamicEntries.get(meshIndex);
            if (live != null) {
                if (!MeshDrawSelection.selectsDraw(hostMeshIndex, nextDrawIndex++)) continue;
                var oneLod = new MeshRendererSettings.Entry(null, entry.assetBinding(), entry.materials(), entry.scale(),
                        entry.rotation(), entry.pivot(), entry.pivotSpace(), MeshRendererSettings.LodMode.LOD_LEVEL,
                        new MeshRendererSettings.IntegerBinding(null, 0), entry.lodBias(), entry.lodDistanceFactor(), false, 0, 0);
                for (var section : live.materials().entrySet()) {
                    var batch = new MeshBatchGeometry(live.source().geometry(), settings, oneLod, section.getKey(), source.emitter().localSpace(),
                            source.renderer().subImageColumns(), source.renderer().subImageRows(), source.renderer().subImageBlend(),
                            source.renderer().motion(), live.source().bounds());
                    output.add(task(source, section.getValue(), batch, meshIndex, 0, view, componentVelocity, rendererTranslucent));
                }
                continue;
            }
            var asset = entry.asset();
            if (asset == null) continue;
            boolean perParticleLod = entry.lodMode() == MeshRendererSettings.LodMode.PER_PARTICLE;
            var lodRange = MeshLodSelection.particleRange(asset, entry, view);
            int firstDrawIndex = nextDrawIndex;
            nextDrawIndex += perParticleLod ? lodRange.drawCount() : 1;
            if (!perParticleLod && !MeshDrawSelection.selectsDraw(hostMeshIndex, firstDrawIndex)) continue;
            int selected = MeshDrawSelection.lod(asset, entry, settings, view, source.input(), source.camera(), binding -> {
                Integer value = NiagaraRendererParameters.integer(binding.name(), false, source.system(), source.variables(), source.input(),
                        source.store(), source.emitter().rendererBindings());
                return value == null ? binding.value() : value;
            }, source.bounds());
            var resident = asset.resident();
            int firstLod = perParticleLod ? lodRange.first() : selected;
            int endLod = perParticleLod && particleSource && !firstMeshOnly ? lodRange.end() : firstLod + 1;
            for (int lod = Math.max(0, firstLod); lod < Math.min(endLod, asset.lods().size()); lod++) {
                if (!MeshDrawSelection.selectsDraw(hostMeshIndex, firstDrawIndex + lod - firstLod)) continue;
                int available = asset.firstResidentLod(Math.max(lod, asset.sampling().firstAvailableLod()), resident);
                var geometry = asset.residentGeometry(available, resident);
                if (geometry == null) continue;
                for (int section = 0; section < geometry.sections().size(); section++) {
                    var sourceSection = geometry.sections().get(section);
                    if (sourceSection.indexCount() == 0 || view.shadowPass() && !ParticleShadowGeometry.casts(sourceSection)) continue;
                    var material = source.materialInstances().apply(MeshDrawSelection.material(source.effect(), source.definition(), asset, entry, sourceSection, source.input()));
                    if (view.shadowPass() && !ParticleShadowGeometry.casts(material)) continue;
                    var batch = new MeshBatchGeometry(geometry, settings, entry, section, source.emitter().localSpace(),
                            source.renderer().subImageColumns(), source.renderer().subImageRows(), source.renderer().subImageBlend(), source.renderer().motion());
                    output.add(task(source, material, batch, meshIndex, lod, view, componentVelocity, rendererTranslucent));
                }
            }
        }
    }

    private static Task task(RendererTask source, MaterialDefinition material, MeshBatchGeometry batch, int meshIndex, int lod,
                             MeshView view, boolean componentVelocity, boolean rendererTranslucent) {
        return new Task(source.draw(), source.particles(), source.emitter(), source.renderer(), source.definition(), material,
                source.system(), source.variables(), source.input(), source.store(), source.camera(), source.orientation(),
                source.originX(), source.originY(), source.originZ(),
                source.time(), source.depth(), source.defaultHighPrecision(), source.stereo(), source.postOpaque(), batch, meshIndex, lod,
                view, source.previousPose(), componentVelocity, rendererTranslucent,
                material.translucencyVolume().usesSurfaceEnvironment()
                        || material.vertexMaterial() != null && material.vertexMaterial().primitiveMatrices()
                        ? source.bounds().get() : null);
    }

    public List<NiagaraGpuMeshFrame> prepare(CommandEncoder encoder, List<Task> tasks) {
        return prepare(encoder, tasks, null, null, null);
    }

    public List<NiagaraGpuMeshFrame> prepare(CommandEncoder encoder, List<Task> tasks,
            com.kltyton.lumenfx.client.render.mesh.scene.@Nullable MeshScenePrograms scenePrograms,
            com.kltyton.lumenfx.client.render.mesh.scene.@Nullable MeshSceneView sceneView,
            @Nullable NiagaraGpuView nativeView) {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("Niagara mesh collector is closed");
        var visible = tasks.stream().filter(task -> NiagaraGpuSpriteDraw.hostVisible(task.renderer(), task.system(), task.variables(),
                task.input(), task.store(), task.emitter().rendererBindings())
                && (!task.renderer().sourceMode().equals("Particles") || task.particles().estimate().current() != 0)).toList();
        Map<SortSource, Task> representatives = new java.util.LinkedHashMap<>();
        for (var task : visible) representatives.putIfAbsent(sortSource(task), task);
        var sorted = sorting.prepare(encoder, new ArrayList<>(representatives.values()), NiagaraGpuMeshCollector::sortInput, Task::postOpaque);
        if (scenePrograms != null) scenePrograms.beginDraw();
        List<NiagaraGpuMeshFrame> frames = new ArrayList<>(visible.size());
        Map<EmitterTransformKey, com.kltyton.lumenfx.client.render.mesh.scene.MeshEmitterSceneData> emitterTransforms = new java.util.HashMap<>();
        for (Task task : visible) {
            var order = sorted.get(representatives.get(sortSource(task)));
            com.kltyton.lumenfx.client.render.mesh.scene.MeshEmitterSceneData emitterScene = null;
            if (com.kltyton.lumenfx.client.render.mesh.scene.MeshEmitterSceneData.applies(task.geometry().renderer(), task.renderer().sourceMode().equals("Emitter")))
                emitterScene = emitterTransforms.computeIfAbsent(new EmitterTransformKey(task.particles(), task.renderer(),
                        task.geometry().mesh(), task.emitter().localSpace()), ignored -> NiagaraMeshEmitterScene.prepare(task));
            var frame = task.draw().prepare(encoder, task, order, emitterScene);
            if (task.geometry().renderer().sceneTransforms() != MeshRendererSettings.SceneTransforms.LEGACY) {
                var programs = java.util.Objects.requireNonNull(scenePrograms, "GPUScene collection requires its prepared programs");
                var capturedView = java.util.Objects.requireNonNull(sceneView, "GPUScene collection requires the current and previous camera frame");
                if (!task.view().shadowPass()) capturedView = capturedView.withView(
                        java.util.Objects.requireNonNull(nativeView, "GPUScene collection requires its source view history"));
                var uniforms = com.kltyton.lumenfx.client.render.mesh.MeshRendererUniforms.write(encoder, task.geometry());
                var matrices = programs.prepareGpu(encoder, frame, uniforms, capturedView, MeshDrawSelection.needsPreviousPayload(task.material()));
                var compacted = programs.culling().prepare(encoder, task, frame, matrices, order, nativeView);
                frame = frame.withScene(programs.withCompaction(encoder, matrices, compacted), compacted);
            }
            frames.add(frame);
        }
        return List.copyOf(frames);
    }

    private static NiagaraGpuSortKeyGenerator.@Nullable Input sortInput(Task task, int size, int index, int offset, int culledOffset) {
        return NiagaraGpuMeshSorting.input(task.particles(), task.emitter(), task.renderer(), task.definition(), task.material(), task.input(),
                task.camera(), task.orientation(), task.originX(), task.originY(), task.originZ(), task.defaultHighPrecision(),
                size, index, offset, culledOffset, task.geometry(), task.meshIndex(), task.lod(), task.view(), task.rendererTranslucent());
    }

    private static SortSource sortSource(Task task) {
        return new SortSource(task.draw(), task.particles(), task.meshIndex(), task.lod(), task.view(), task.postOpaque());
    }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (closed) return;
        closed = true;
        sorting.close();
    }
}
