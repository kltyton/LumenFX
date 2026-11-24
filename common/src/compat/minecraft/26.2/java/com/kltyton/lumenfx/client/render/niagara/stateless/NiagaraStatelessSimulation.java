package com.kltyton.lumenfx.client.render.niagara.stateless;

import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ComputeKernel;
import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuPrograms;
import com.kltyton.lumenfx.client.simulation.niagara.VectorDataSet;
import com.kltyton.lumenfx.client.simulation.niagara.stateless.StatelessCpuProgram;
import com.kltyton.lumenfx.client.simulation.niagara.stateless.StatelessEmitterFrame;
import com.kltyton.lumenfx.client.simulation.niagara.stateless.StatelessEmitterState;
import com.kltyton.lumenfx.client.simulation.niagara.stateless.StatelessSpawnSchedule;
import com.kltyton.lumenfx.client.simulation.niagara.stateless.shader.StatelessGpuModuleParameters;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

/** Demand generation uses the native shader or its permitted analytic CPU path; it never advances the emitter clock. */
public final class NiagaraStatelessSimulation implements AutoCloseable {
    public record Generation(boolean available, ComputeKernel.@Nullable Dispatch dispatch) { }
    private static final Generation NO_DATA = new Generation(false, null), AVAILABLE = new Generation(true, null);
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int THREADS = 64, WRAP = 128;
    private final NiagaraDefinition.Emitter source;
    private final int[] staticWords;
    private final @Nullable StatelessGpuModuleParameters shaderParameters;
    private final @Nullable StatelessCpuProgram cpu;
    private final @Nullable VectorDataSet cpuParticles;
    private StatelessEmitterFrame.@Nullable Bindings previousBindings;
    private @Nullable NiagaraGpuPrograms owner;
    private @Nullable GpuBuffer bindingBuffer;
    private int[] bindings = new int[0];
    private float[] pose = new float[0];
    private long inputVersion, bindingVersion, shaderVersion = -1, gpuBindingVersion = -1;
    private boolean closed;

    public NiagaraStatelessSimulation(NiagaraDefinition.Emitter source, StatelessEmitterState state) {
        this.source = source;
        var metadata = ((NiagaraDefinition.StatelessSimulation)source.simulation()).metadata();
        staticWords = metadata.getAsJsonArray("static_words").asList().stream().mapToInt(value -> (int)value.getAsLong()).toArray();
        shaderParameters = state.canEverExecute() && (state.features() & 1) != 0 ? new StatelessGpuModuleParameters(metadata) : null;
        if (state.canEverExecute() && (state.features() & 2) != 0) {
            int floats = 0, integers = 0;
            for (var attribute : source.attributes()) {
                int end = Math.addExact(attribute.component(), attribute.count());
                if (attribute.type() == 0) floats = Math.max(floats, end);
                else if (attribute.type() == 1) integers = Math.max(integers, end);
                else throw new IllegalArgumentException("Invalid analytic CPU component type " + attribute.type());
            }
            cpuParticles = new VectorDataSet(floats, integers, 0);
            cpu = new StatelessCpuProgram(source, cpuParticles, state);
        } else { cpuParticles = null; cpu = null; }
    }

    public boolean generate(CommandEncoder encoder, NiagaraGpuPrograms programs, NiagaraStatelessDataBuffer destination,
                            StatelessEmitterFrame frame, StatelessSpawnSchedule.Plan plan, boolean allowGpuGeneration, int cpuThreshold) {
        Generation generation = prepare(encoder, programs, destination, frame, plan, allowGpuGeneration, cpuThreshold);
        var dispatch = generation.dispatch();
        if (dispatch != null) programs.stateless().dispatchIndependentBatch(List.of(dispatch));
        return generation.available();
    }

    public Generation prepare(CommandEncoder encoder, NiagaraGpuPrograms programs, NiagaraStatelessDataBuffer destination,
                            StatelessEmitterFrame frame, StatelessSpawnSchedule.Plan plan, boolean allowGpuGeneration, int cpuThreshold) {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("Niagara analytic simulation is closed");
        if (!frame.eligible() || frame.executionState() == 3 || frame.executionState() == 4 || plan.active() == 0) return NO_DATA;
        if (!destination.claimGeneration()) return AVAILABLE;
        if (owner == null) owner = programs;
        else if (owner != programs) throw new IllegalStateException("Stale Niagara analytic resource generation");
        boolean gpu = (frame.features() & 1) != 0 && allowGpuGeneration;
        boolean useCpu = (frame.features() & 2) != 0 && (!gpu || plan.active() <= Integer.toUnsignedLong(cpuThreshold));
        if (gpu && !useCpu && !destination.gpuCountAvailable()) {
            gpu = false;
            useCpu = (frame.features() & 2) != 0;
            LOGGER.warn("Reserved GPU count slot was unavailable for analytic emitter '{}'", source.sourceName());
        }
        if (!useCpu && !gpu) {
            destination.noData();
            LOGGER.warn("No permitted analytic execution path for emitter '{}' in this rendering window", source.sourceName());
            return NO_DATA;
        }
        updateInputs(frame);
        if (useCpu) {
            if (cpu == null || cpuParticles == null) throw new IllegalStateException("Missing permitted analytic CPU executor");
            cpu.evaluateFrozen(frame.seed(), frame.age(), frame.delta(), plan, bindings, pose);
            destination.upload(encoder, cpuParticles);
            return cpuParticles.count() > 0 ? AVAILABLE : NO_DATA;
        }
        if (shaderParameters == null) throw new IllegalStateException("Missing analytic GPU module parameters");
        if (shaderVersion != inputVersion) {
            shaderParameters.prepare(bindings, pose);
            shaderVersion = inputVersion;
        }
        updateBindingBuffer(programs);
        var stage = programs.stateless();
        var data = destination.current();
        shaderParameters.common(frame, plan, data.stride(), destination.currentCountOffset());
        GpuBufferSlice dummy = programs.dummyBuffer().slice();
        ComputeBindings.Builder resources = ComputeBindings.builder();
        for (var binding : stage.shader().bindings()) {
            if (!(binding instanceof ShaderBinding.StorageBuffer storage)) continue;
            GpuBufferSlice buffer = switch (storage.name()) {
                case "Common_FloatOutputBuffer" -> data.floats() == null ? dummy : data.floats().slice();
                case "Common_IntOutputBuffer" -> data.integers() == null ? dummy : data.integers().slice();
                case "Common_GPUCountBuffer" -> destination.countBuffer();
                case "Common_StaticDataBuffer" -> programs.statelessStaticData(source, staticWords).slice();
                case "Common_DynamicDataBuffer" -> bindingBuffer == null ? dummy : bindingBuffer.slice();
                case "SolveVelocitiesAndForces_NoiseLUT" -> programs.statelessNoise().slice();
                default -> throw new IllegalArgumentException("Unknown analytic shader resource " + storage.name());
            };
            resources.storage(storage.name(), buffer, storage.access());
        }
        var runtime = programs.runtime();
        if (runtime.maxWorkGroups(0) < WRAP || runtime.maxWorkGroups(1) < WRAP)
            throw new IllegalStateException("Device cannot satisfy the native analytic wrapped dispatch stride");
        long x = Math.ceilDiv(plan.active(), THREADS), y = 1, z = 1;
        if (x > runtime.maxWorkGroups(0)) { y = Math.ceilDiv(x, WRAP); x = WRAP; }
        if (y > runtime.maxWorkGroups(1)) { z = Math.ceilDiv(y, WRAP); y = WRAP; }
        if (z > runtime.maxWorkGroups(2)) throw new IllegalArgumentException("Analytic dispatch exceeds the device group limit");
        return new Generation(true, stage.prepareDispatch(encoder, resources, shaderParameters::value,
                Math.toIntExact(x * THREADS), Math.toIntExact(y), Math.toIntExact(z)));
    }

    private void updateInputs(StatelessEmitterFrame frame) {
        if (previousBindings != frame.bindings()) {
            previousBindings = frame.bindings();
            bindings = previousBindings.copyWords();
            bindingVersion++; inputVersion++;
        }
        float[] current = frame.sourcePose();
        boolean changed = pose.length != current.length;
        for (int index = 0; !changed && index < current.length; index++)
            changed = Float.floatToRawIntBits(pose[index]) != Float.floatToRawIntBits(current[index]);
        if (changed) { pose = current; inputVersion++; }
    }
    private void updateBindingBuffer(NiagaraGpuPrograms programs) {
        if (gpuBindingVersion == bindingVersion) return;
        GpuBuffer replacement = null;
        if (bindings.length > 0) {
            long bytes = (long)bindings.length * Integer.BYTES;
            if (bytes > programs.runtime().maxStorageBufferBytes()) throw new IllegalArgumentException("Analytic parameter data exceeds the device buffer limit");
            ByteBuffer data = MemoryUtil.memAlloc(Math.toIntExact(bytes)).order(ByteOrder.LITTLE_ENDIAN);
            try {
                for (int word : bindings) data.putInt(word);
                data.flip();
                replacement = RenderSystem.getDevice().createBuffer(() -> "Niagara.Stateless.DynamicData." + source.sourceName(),
                        ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_DST), data);
            } finally { MemoryUtil.memFree(data); }
        }
        if (bindingBuffer != null) bindingBuffer.close();
        bindingBuffer = replacement;
        gpuBindingVersion = bindingVersion;
    }
    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (closed) return;
        closed = true;
        if (bindingBuffer != null) { bindingBuffer.close(); bindingBuffer = null; }
    }
}
