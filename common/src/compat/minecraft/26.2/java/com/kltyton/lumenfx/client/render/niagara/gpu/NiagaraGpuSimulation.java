package com.kltyton.lumenfx.client.render.niagara.gpu;

import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ShaderProgram;
import com.kltyton.lumenfx.client.resource.shader.PreparedNiagaraShaders;
import com.kltyton.lumenfx.client.render.niagara.gpu.channel.NiagaraGpuDataChannelWorlds;
import com.kltyton.lumenfx.client.simulation.niagara.gpu.NiagaraGpuEngineParameters;
import com.kltyton.lumenfx.client.simulation.niagara.gpu.NiagaraGpuSpawnInfo;
import com.kltyton.lumenfx.client.simulation.niagara.gpu.NiagaraGpuStageSchedule;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/** Render-thread execution of the saved Niagara GPU simulation stages for one emitter. */
public final class NiagaraGpuSimulation implements AutoCloseable {
    public record InterfaceIteration(NiagaraGpuDispatchGrid.@Nullable Extent elements,
                                     @Nullable GpuBufferSlice indirectArguments,
                                     int countOffset) { }

    public interface Interfaces {
        default void textureSnapshots(Map<String, com.kltyton.lumenfx.api.client.parameter.TextureParameter.Snapshot> textures) { }
        void beginTick(CommandEncoder encoder);
        void beforeParticleStage(CommandEncoder encoder, NiagaraGpuParticleBuffers particles,
                                 NiagaraGpuStageSchedule.Invocation stage);
        String variant(int stageIndex);
        GpuBufferSlice dummyInput();
        @Nullable InterfaceIteration iteration(CommandEncoder encoder, NiagaraGpuStageSchedule.Invocation stage,
                                               int iteration, int currentParticles,
                                               com.kltyton.lumenfx.client.render.compute.ShaderProgram shader);
        Map<String, GpuBufferSlice> curveOverrides();
        @Nullable byte[] value(String name);
        void preStage(NiagaraGpuStageSchedule.Invocation stage, int iteration);
        default void preStage(NiagaraGpuStageSchedule.Invocation stage, int iteration, int destinationParticles) {
            preStage(stage, iteration);
        }
        default void preStage(NiagaraGpuStageSchedule.Invocation stage, int iteration, int destinationParticles,
                              NiagaraGpuDispatchGrid.Extent elements) {
            preStage(stage, iteration, destinationParticles);
        }
        default void preStage(NiagaraGpuStageSchedule.Invocation stage, int iteration, int sourceParticles, int destinationParticles,
                              NiagaraGpuDispatchGrid.Extent elements, NiagaraGpuComputeStage shader) {
            preStage(stage, iteration, destinationParticles, elements);
        }
        default void acquireSharedStageInputs(NiagaraGpuDataChannelWorlds.StageAccess access) { }
        void finalizePreStage();
        void reset(Supplier<NiagaraGpuComputeStage> initialStage);
        void bind(NiagaraGpuStageSchedule.Invocation stage, int iteration, ShaderProgram shader, ComputeBindings.Builder resources);
        void postStage(NiagaraGpuStageSchedule.Invocation stage, int iteration);
        void postSimulate(CommandEncoder encoder, boolean dataToRender);
        void finalizePostStage();
    }

    private final Identifier id;
    private final NiagaraGpuPrograms programs;
    private final PreparedNiagaraShaders prepared;
    private final NiagaraGpuParticleBuffers particles;
    private final List<NiagaraDefinition.Attribute> attributes;
    private boolean hasFullWriteThisFrame;
    private boolean closed;

    public NiagaraGpuSimulation(Identifier id, NiagaraGpuPrograms programs, PreparedNiagaraShaders prepared,
                                List<NiagaraDefinition.Attribute> attributes) {
        this(id, programs, prepared, attributes, false);
    }

    public NiagaraGpuSimulation(Identifier id, NiagaraGpuPrograms programs, PreparedNiagaraShaders prepared,
                                List<NiagaraDefinition.Attribute> attributes, boolean requiresPersistentIds) {
        this(id, programs, prepared, attributes, requiresPersistentIds, null, 0);
    }

    public NiagaraGpuSimulation(Identifier id, NiagaraGpuPrograms programs, PreparedNiagaraShaders prepared,
                                List<NiagaraDefinition.Attribute> attributes, boolean requiresPersistentIds,
                                @Nullable NiagaraGpuCountArena arena, int emitterIndex) {
        RenderSystem.assertOnRenderThread();
        this.id = id;
        this.programs = programs;
        this.prepared = prepared;
        this.attributes = List.copyOf(attributes);
        particles = arena == null ? new NiagaraGpuParticleBuffers(id.toString(), this.attributes, programs, requiresPersistentIds)
                : new NiagaraGpuParticleBuffers(id.toString(), this.attributes, programs, requiresPersistentIds, arena, emitterIndex);
    }

    public void tick(CommandEncoder encoder, NiagaraGpuStageSchedule.Parameters parameters, Interfaces interfaces,
                     NiagaraGpuEngineParameters engine, Function<String, byte[]> externalParameters,
                     NiagaraGpuSpawnInfo spawn, int eventSpawns, int maximumInstances, int allocationEstimate,
                     boolean freeBufferEarly, boolean reset, boolean finalTick, boolean dataToRender) {
        if (closed) throw new IllegalStateException("Niagara GPU simulation is closed");
        List<NiagaraGpuStageSchedule.Invocation> stages = new ArrayList<>();
        prepared.schedule().forEach(parameters, reset, stages::add);
        tick(encoder, stages, interfaces, engine, externalParameters, spawn, eventSpawns, maximumInstances,
                allocationEstimate, freeBufferEarly, reset, finalTick, dataToRender);
    }

    public void tick(CommandEncoder encoder, List<NiagaraGpuStageSchedule.Invocation> stages, Interfaces interfaces,
                     NiagaraGpuEngineParameters engine, Function<String, byte[]> externalParameters,
                     NiagaraGpuSpawnInfo spawn, int eventSpawns, int maximumInstances, int allocationEstimate,
                     boolean freeBufferEarly, boolean reset, boolean finalTick, boolean dataToRender) {
        Tick execution = beginTick(encoder, stages, interfaces, engine, externalParameters, spawn, eventSpawns,
                maximumInstances, allocationEstimate, freeBufferEarly, reset, finalTick, dataToRender);
        for (int index = 0; index < stages.size(); index++) {
            var stage = stages.get(index);
            for (int iteration = 0; iteration < stage.iterations(); iteration++) {
                var invocation = execution.prepare(stage, iteration, index == stages.size() - 1 && iteration == stage.iterations() - 1);
                invocation.acquireSharedStageInputs(new NiagaraGpuDataChannelWorlds.StageAccess());
                invocation.finalizePreStage();
                invocation.dispatch();
                invocation.postStage();
                invocation.finalizePostStage();
            }
        }
        execution.finish();
    }

    public Tick beginTick(CommandEncoder encoder, List<NiagaraGpuStageSchedule.Invocation> stages, Interfaces interfaces,
                          NiagaraGpuEngineParameters engine, Function<String, byte[]> externalParameters,
                          NiagaraGpuSpawnInfo spawn, int eventSpawns, int maximumInstances, int allocationEstimate,
                          boolean freeBufferEarly, boolean reset, boolean finalTick, boolean dataToRender) {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("Niagara GPU simulation is closed");
        particles.pollCount();
        if (reset) particles.reset(programs.runtime(), encoder, true);
        if (!stages.isEmpty()) interfaces.beginTick(encoder);
        int previous = particles.estimate().current();
        int maximum = Math.min(maximumInstances,
                NiagaraGpuDataBuffer.maximumParticleCapacity(programs.runtime(), attributes));
        int next = stages.isEmpty() ? previous
                : particles.estimate().advance(spawn.count(), eventSpawns, maximum, allocationEstimate, freeBufferEarly);
        int capacity = Math.min(maximum, particles.estimate().allocationHighWater());
        if (!stages.isEmpty()) particles.prepareIds(encoder, capacity);
        return new Tick(encoder, interfaces, engine, externalParameters, spawn, eventSpawns, reset, finalTick,
                dataToRender, stages.isEmpty(), previous, next, capacity);
    }

    public final class Tick {
        private final CommandEncoder encoder;
        private final Interfaces interfaces;
        private final NiagaraGpuEngineParameters engine;
        private final Function<String, byte[]> externalParameters;
        private final NiagaraGpuSpawnInfo spawn;
        private final int eventSpawns;
        private final boolean reset;
        private final boolean finalTick;
        private final boolean dataToRender;
        private final boolean empty;
        private final int previous;
        private final int next;
        private final int capacity;

        private Tick(CommandEncoder encoder, Interfaces interfaces, NiagaraGpuEngineParameters engine,
                     Function<String, byte[]> externalParameters, NiagaraGpuSpawnInfo spawn, int eventSpawns,
                     boolean reset, boolean finalTick, boolean dataToRender, boolean empty,
                     int previous, int next, int capacity) {
            this.encoder = encoder;
            this.interfaces = interfaces;
            this.engine = engine;
            this.externalParameters = externalParameters;
            this.spawn = spawn;
            this.eventSpawns = eventSpawns;
            this.reset = reset;
            this.finalTick = finalTick;
            this.dataToRender = dataToRender;
            this.empty = empty;
            this.previous = previous;
            this.next = next;
            this.capacity = capacity;
        }

        public Dispatch prepare(NiagaraGpuStageSchedule.Invocation stage, int iteration, boolean last) {
            interfaces.beforeParticleStage(encoder, particles, stage);
            if (finalTick && particles.hasSourceCount() && stage.access() == NiagaraGpuStageSchedule.ParticleAccess.FULL)
                particles.requestCount(encoder, stage.stageIndex() == 0 && iteration == 0 ? previous : next);
            switch (stage.access()) {
                case FULL -> {
                    particles.begin(programs.runtime(), capacity);
                    hasFullWriteThisFrame = true;
                }
                case PARTIAL -> {
                    particles.detachPublishedWrite(encoder, programs.runtime());
                    particles.beginPartial();
                }
                case READ_ONLY -> particles.beginReadOnly();
            }
            var shader = programs.stage(id, stage.stageIndex(), interfaces.variant(stage.stageIndex()));
            InterfaceIteration source = stage.iterationSource().equals("DataInterface")
                    ? interfaces.iteration(encoder, stage, iteration, particles.readerCurrentInstances(), shader.shader()) : null;
            boolean indirect = stage.indirect();
            var plan = indirect || stage.iterationSource().equals("DataInterface") && source == null
                    ? null : programs.grid().direct(stage, shader.shader(), next, source == null ? null : source.elements());
            var extent = plan == null ? null : plan.extent();
            var elements = source != null && !indirect ? source.elements()
                    : extent == null ? new NiagaraGpuDispatchGrid.Extent(0, 0, 0) : extent;
            int sourceEstimate = stage.access() == NiagaraGpuStageSchedule.ParticleAccess.FULL
                    && stage.stageIndex() == 0 && iteration == 0 ? previous : next;
            interfaces.preStage(stage, iteration, sourceEstimate, next, elements, shader);
            int iterationCount = 0;
            if ((stage.iterationSource().equals("DirectSet") || source != null && source.countOffset() == -1) && plan != null)
                iterationCount = plan.logicalCount();
            return new Dispatch(this, stage, iteration, shader, extent,
                    source == null ? null : source.indirectArguments(), source == null ? -1 : source.countOffset(),
                    iterationCount, sourceEstimate, last);
        }

        public void finish() {
            if (!empty) {
                if (hasFullWriteThisFrame) particles.compactIds(encoder);
                if (dataToRender) particles.publishRenderData();
            }
            if (finalTick) hasFullWriteThisFrame = false;
        }
    }

    public final class Dispatch {
        private final Tick tick;
        private final NiagaraGpuStageSchedule.Invocation stage;
        private final int iteration;
        private final NiagaraGpuComputeStage shader;
        private final NiagaraGpuDispatchGrid.@Nullable Extent extent;
        private final @Nullable GpuBufferSlice indirectArguments;
        private final int iterationCountOffset;
        private final int iterationCount;
        private final int sourceEstimate;
        private final boolean last;

        private Dispatch(Tick tick, NiagaraGpuStageSchedule.Invocation stage, int iteration,
                         NiagaraGpuComputeStage shader, NiagaraGpuDispatchGrid.@Nullable Extent extent,
                         @Nullable GpuBufferSlice indirectArguments, int iterationCountOffset,
                         int iterationCount, int sourceEstimate, boolean last) {
            this.tick = tick;
            this.stage = stage;
            this.iteration = iteration;
            this.shader = shader;
            this.extent = extent;
            this.indirectArguments = indirectArguments;
            this.iterationCountOffset = iterationCountOffset;
            this.iterationCount = iterationCount;
            this.sourceEstimate = sourceEstimate;
            this.last = last;
        }

        public void acquireSharedStageInputs(NiagaraGpuDataChannelWorlds.StageAccess access) {
            tick.interfaces.acquireSharedStageInputs(access);
        }

        public void finalizePreStage() { tick.interfaces.finalizePreStage(); }

        public void dispatch() {
            var interfaces = tick.interfaces;
            int dispatchTick = programs.nextTick();
            if (tick.reset && stage.first() && iteration == 0)
                interfaces.reset(() -> stage.stageIndex() == 0 ? shader : programs.stage(id, 0, interfaces.variant(0)));
            int x = extent == null ? 0 : extent.x();
            int y = extent == null ? 0 : extent.y();
            int z = extent == null ? 0 : extent.z();
            int actualSpawns = stage.first() && iteration == 0 && stage.stageIndex() == 0
                    && stage.access() == NiagaraGpuStageSchedule.ParticleAccess.FULL ? tick.next - tick.previous : 0;
            particles.stageMetadata(actualSpawns, dispatchTick, sourceEstimate, tick.next);
            var input = particles.input();
            var output = particles.output();
            boolean particleIteration = stage.iterationSource().equals("Particles");
            var parameters = new NiagaraGpuDispatchParameters(tick.spawn, tick.eventSpawns, actualSpawns, dispatchTick, tick.reset,
                    input == null ? 0 : input.stride(), output == null ? 0 : output.stride(),
                    particleIteration ? particles.readCountOffset() : -1,
                    particleIteration ? particles.writeCountOffset() : -1, x, y, z,
                    iterationCountOffset, iterationCount, stage.iterations(), iteration,
                    stage.loops(), stage.loopIndex(), stage.particleStateComponent(),
                    stage.particleStateMinimum(), stage.particleStateMaximum());
            if (stage.indirect() ? indirectArguments == null : extent == null) return;
            ComputeBindings.Builder bindings = ComputeBindings.builder();
            interfaces.bind(stage, iteration, shader.shader(), bindings);
            Function<String, byte[]> additional = name -> {
                byte[] value = tick.externalParameters.apply(name);
                return value == null ? interfaces.value(name) : value;
            };
            if (stage.indirect()) {
                NiagaraGpuPass.dispatchIndirect(shader, particles, programs.curves(id), tick.encoder, bindings,
                        interfaces.dummyInput(), interfaces.curveOverrides(), tick.engine, parameters,
                        prepared.sprites(), additional, Objects.requireNonNull(indirectArguments));
            } else {
                NiagaraGpuPass.dispatch(shader, particles, programs.curves(id), tick.encoder, bindings,
                        interfaces.dummyInput(), interfaces.curveOverrides(), tick.engine, parameters,
                        prepared.sprites(), additional, x, y, z);
            }
        }

        public void postStage() {
            tick.interfaces.postStage(stage, iteration);
            if (last)
                tick.interfaces.postSimulate(tick.encoder, tick.dataToRender);
            particles.publish();
        }

        public void finalizePostStage() { tick.interfaces.finalizePostStage(); }
    }


    public NiagaraGpuParticleBuffers particles() {
        if (closed) throw new IllegalStateException("Niagara GPU simulation is closed");
        return particles;
    }

    public void finishBatch() {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("Niagara GPU simulation is closed");
        particles.publishRenderData();
        hasFullWriteThisFrame = false;
    }

    public void reset() {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("Niagara GPU simulation is closed");
        particles.reset(programs.runtime());
        hasFullWriteThisFrame = false;
    }

    public void clearForExecutionState() {
        particles.clearForExecutionState(programs.runtime());
        hasFullWriteThisFrame = false;
    }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (closed) return;
        closed = true;
        particles.close();
    }
}
