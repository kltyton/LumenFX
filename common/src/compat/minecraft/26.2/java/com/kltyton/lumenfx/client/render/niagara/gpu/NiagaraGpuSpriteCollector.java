package com.kltyton.lumenfx.client.render.niagara.gpu;

import com.kltyton.lumenfx.client.render.light.shadow.geometry.PointShadowParticleView;
import com.kltyton.lumenfx.effect.niagara.NiagaraComponentDefaults;
import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.client.simulation.niagara.VectorDataSet;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraParameterStore;
import com.kltyton.lumenfx.effect.EmitterDefinition;
import com.kltyton.lumenfx.effect.MaterialDefinition;
import com.kltyton.lumenfx.effect.niagara.NiagaraBounds;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

/** Batches Niagara sprite sorting and prepares GPU-owned indirect draws for one view. */
public final class NiagaraGpuSpriteCollector implements AutoCloseable {
    public record Task(NiagaraGpuSpriteDraw draw, NiagaraGpuParticleView particles,
                       NiagaraDefinition.Emitter emitter, NiagaraDefinition.Renderer renderer,
                       EmitterDefinition definition, MaterialDefinition material, VectorDataSet system,
                       Map<String, NiagaraDefinition.Variable> variables, EffectHandle.Input input,
                       @Nullable NiagaraParameterStore store,
                       Vec3 camera, Quaternionf cameraOrientation, double originX, double originY, double originZ,
                       double time, double depth, boolean defaultHighPrecision, boolean stereo, boolean postOpaque,
                       EffectHandle.Pose previousPose, boolean componentVelocity, @Nullable NiagaraBounds componentBounds) {
        public Task forPointShadow(NiagaraGpuSpriteDraw shadow, PointShadowParticleView view) {
            return new Task(shadow, particles, emitter, renderer, definition, material, system, variables, input, store,
                    view.camera(), view.orientation(), originX, originY, originZ, time, depth, defaultHighPrecision,
                    false, false, previousPose, componentVelocity, componentBounds);
        }

        public Task(NiagaraGpuSpriteDraw draw, NiagaraGpuParticleView particles,
                    NiagaraDefinition.Emitter emitter, NiagaraDefinition.Renderer renderer,
                    EmitterDefinition definition, MaterialDefinition material, VectorDataSet system,
                    Map<String, NiagaraDefinition.Variable> variables, EffectHandle.Input input,
                    Vec3 camera, Quaternionf cameraOrientation, double originX, double originY, double originZ,
                    double time, double depth, boolean defaultHighPrecision, boolean stereo, boolean postOpaque,
                    EffectHandle.Pose previousPose, boolean componentVelocity, @Nullable NiagaraBounds componentBounds) {
            this(draw, particles, emitter, renderer, definition, material, system, variables, input, null,
                    camera, cameraOrientation, originX, originY, originZ, time, depth, defaultHighPrecision, stereo,
                    postOpaque, previousPose, componentVelocity, componentBounds);
        }

        public Task(NiagaraGpuSpriteDraw draw, NiagaraGpuParticleView particles,
                    NiagaraDefinition.Emitter emitter, NiagaraDefinition.Renderer renderer,
                    EmitterDefinition definition, MaterialDefinition material, VectorDataSet system,
                    Map<String, NiagaraDefinition.Variable> variables, EffectHandle.Input input,
                    Vec3 camera, Quaternionf cameraOrientation, double originX, double originY, double originZ,
                    double time, double depth, boolean defaultHighPrecision, boolean stereo, boolean postOpaque,
                    EffectHandle.Pose previousPose, boolean componentVelocity) {
            this(draw, particles, emitter, renderer, definition, material, system, variables, input, camera, cameraOrientation,
                    originX, originY, originZ, time, depth, defaultHighPrecision, stereo, postOpaque, previousPose, componentVelocity, null);
        }

        public Task(NiagaraGpuSpriteDraw draw, NiagaraGpuParticleView particles,
                    NiagaraDefinition.Emitter emitter, NiagaraDefinition.Renderer renderer,
                    EmitterDefinition definition, MaterialDefinition material, VectorDataSet system,
                    Map<String, NiagaraDefinition.Variable> variables, EffectHandle.Input input,
                    Vec3 camera, Quaternionf cameraOrientation, double originX, double originY, double originZ,
                    double time, double depth, boolean defaultHighPrecision, boolean stereo, boolean postOpaque,
                    EffectHandle.Pose previousPose) {
            this(draw, particles, emitter, renderer, definition, material, system, variables, input, camera, cameraOrientation,
                    originX, originY, originZ, time, depth, defaultHighPrecision, stereo, postOpaque, previousPose, true);
        }
        public Task(NiagaraGpuSpriteDraw draw, NiagaraGpuParticleView particles,
                    NiagaraDefinition.Emitter emitter, NiagaraDefinition.Renderer renderer,
                    EmitterDefinition definition, MaterialDefinition material, VectorDataSet system,
                    Map<String, NiagaraDefinition.Variable> variables, EffectHandle.Input input,
                    Vec3 camera, Quaternionf cameraOrientation, double originX, double originY, double originZ,
                    double time, double depth, boolean defaultHighPrecision, boolean stereo, boolean postOpaque) {
            this(draw, particles, emitter, renderer, definition, material, system, variables, input, camera, cameraOrientation,
                    originX, originY, originZ, time, depth, defaultHighPrecision, stereo, postOpaque, input.pose());
        }
    }
    private final NiagaraGpuSortCollector sorting;
    private boolean closed;

    public NiagaraGpuSpriteCollector(NiagaraGpuPrograms programs) { sorting = new NiagaraGpuSortCollector("Niagara.SpriteSort", programs); }

    public List<NiagaraGpuSpriteFrame> prepare(CommandEncoder encoder, List<Task> tasks) {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("Niagara GPU sprite collector is closed");
        List<Task> visible = new ArrayList<>();
        for (Task task : tasks) {
            if (!NiagaraGpuSpriteDraw.hostVisible(task.renderer(), task.system(), task.variables(), task.input(),
                    task.store(), task.emitter().rendererBindings())
                    || task.renderer().sourceMode().equals("Particles") && task.particles().estimate().current() == 0
                    || !emitterVisible(task)) continue;
            visible.add(task);
        }
        var sorted = sorting.prepare(encoder, visible, NiagaraGpuSpriteCollector::sortInput, Task::postOpaque);
        List<NiagaraGpuSpriteFrame> frames = new ArrayList<>(visible.size());
        for (Task task : visible) {
            var result = sorted.get(task);
            frames.add(task.draw().prepare(encoder, task.particles(), task.emitter(), task.renderer(), task.definition(),
                    task.material(), task.system(), task.variables(), task.input(), task.store(), task.camera(),
                    task.originX(), task.originY(), task.originZ(), task.time(), task.depth(),
                    result == null ? null : result.indices(), result == null ? 0 : result.offset(),
                    result == null ? null : result.culledCounts(), result == null ? 0 : result.culledOffset(), task.stereo(),
                    task.postOpaque(), task.previousPose(), task.componentVelocity(), task.componentBounds()));
        }
        return List.copyOf(frames);
    }

    private static boolean emitterVisible(Task task) {
        var renderer = task.renderer();
        if (!renderer.sourceMode().equals("Emitter") || !renderer.distanceCulling()) return true;
        float distance = NiagaraComponentDefaults.RENDERER_LOD_DISTANCE_OVERRIDE;
        float squared = distance * distance;
        float minimum = renderer.minCameraDistance(), maximum = renderer.maxCameraDistance();
        return squared >= minimum * minimum && squared <= maximum * maximum;
    }

    private static NiagaraGpuSortKeyGenerator.@Nullable Input sortInput(Task task, int size, int index, int offset, int culledOffset) {
        return NiagaraGpuSpriteSorting.input(task.particles(), task.emitter(), task.renderer(), task.definition(), task.material(),
                task.input(), task.camera(), task.cameraOrientation(), task.originX(), task.originY(), task.originZ(),
                task.defaultHighPrecision(), size, index, offset, culledOffset);
    }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (closed) return;
        closed = true;
        sorting.close();
    }
}
