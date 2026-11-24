package com.kltyton.lumenfx.client.render.niagara.gpu.splat;

import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.api.client.parameter.NiagaraPlatforms;
import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ComputeRuntime;
import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraCpuParticleUpload;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuComputeStage;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuParticleView;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuPrograms;
import com.kltyton.lumenfx.client.render.niagara.splat.NiagaraGaussianSplatDraw;
import com.kltyton.lumenfx.client.simulation.niagara.VectorDataSet;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraParameterStore;
import com.kltyton.lumenfx.client.simulation.niagara.renderer.NiagaraRendererFields.Inputs;
import com.kltyton.lumenfx.client.simulation.niagara.splat.NiagaraSplatParticles;
import com.kltyton.lumenfx.effect.niagara.NiagaraBounds;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraPlatformEnvironment.ConsoleValue;
import com.kltyton.lumenfx.effect.render.splat.SplatRendererSettings;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Builds packed EFloat Gaussian splat buffers for CPU or GPU Niagara splat emitters. */
public final class NiagaraGpuSplatBuilder implements AutoCloseable {
    public static final String ENABLED = "fx.Niagara.SplatRenderer.Enabled";
    public static final String MAX_SPLATS_PER_EMITTER = "fx.Niagara.SplatRenderer.MaxSplatsPerEmitter";
    public static final int SPLAT_STRIDE = 56;
    private static final int ALIGNMENT_SPLATS = 1024;
    private static final int THREADS = 64;
    private static final int WRAPPED_STRIDE = 128;
    private static final float[] IDENTITY_MAT4 = {
            1, 0, 0, 0,
            0, 1, 0, 0,
            0, 0, 1, 0,
            0, 0, 0, 1
    };

    private final String name;
    private final NiagaraGpuPrograms programs;
    private final NiagaraDefinition.Emitter emitter;
    private final NiagaraDefinition.Renderer renderer;
    private final NiagaraSplatParticles fields;
    private @Nullable NiagaraCpuParticleUpload cpuUpload;
    private @Nullable GpuBuffer splatBuffer;
    private int splatCapacity;
    private @Nullable NiagaraGaussianSplatDraw frameDraw;
    private boolean closed;

    public NiagaraGpuSplatBuilder(String name, NiagaraGpuPrograms programs,
                                  NiagaraDefinition.Emitter emitter, NiagaraDefinition.Renderer renderer) {
        this.name = name;
        this.programs = programs;
        this.emitter = emitter;
        this.renderer = renderer;
        fields = new NiagaraSplatParticles(emitter, renderer);
    }

    public static boolean rendererEnabled() {
        ConsoleValue value = NiagaraPlatforms.snapshot().console(ENABLED);
        return value == null || value.bits() != 0;
    }

    public static int maxSplatsPerEmitter() {
        ConsoleValue value = NiagaraPlatforms.snapshot().console(MAX_SPLATS_PER_EMITTER);
        return value == null ? 4 * 1024 * 1024 : Math.max(0, value.bits());
    }

    public void clearFrame() { frameDraw = null; }

    public @Nullable NiagaraGaussianSplatDraw currentDraw() { return frameDraw; }

    public @Nullable NiagaraGaussianSplatDraw buildCpu(CommandEncoder encoder, SplatRendererSettings settings,
                                                       @Nullable VectorDataSet particles, VectorDataSet system,
                                                       Map<String, NiagaraDefinition.Variable> variables,
                                                       EffectHandle.Input input, double originX, double originY,
                                                       double originZ, @Nullable NiagaraBounds bounds) {
        return buildCpu(encoder, settings, particles, system, variables, input, null, originX, originY, originZ, bounds);
    }

    public @Nullable NiagaraGaussianSplatDraw buildCpu(CommandEncoder encoder, SplatRendererSettings settings,
                                                       @Nullable VectorDataSet particles, VectorDataSet system,
                                                       Map<String, NiagaraDefinition.Variable> variables,
                                                       EffectHandle.Input input, @Nullable NiagaraParameterStore store,
                                                       double originX, double originY,
                                                       double originZ, @Nullable NiagaraBounds bounds) {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("Niagara splat builder is closed");
        frameDraw = null;
        if (!fields.emitterSource() && (particles == null || particles.count() == 0)) return null;
        if (cpuUpload == null) cpuUpload = new NiagaraCpuParticleUpload(name + ".Upload", emitter.attributes(), programs.runtime());
        cpuUpload.upload(encoder, particles, !fields.emitterSource());
        return buildGpu(encoder, settings, cpuUpload.buffers(), system, variables, input, store, originX, originY, originZ, bounds);
    }

    public @Nullable NiagaraGaussianSplatDraw buildGpu(CommandEncoder encoder, SplatRendererSettings settings,
                                                       NiagaraGpuParticleView particles, VectorDataSet system,
                                                       Map<String, NiagaraDefinition.Variable> variables,
                                                       EffectHandle.Input input, double originX, double originY,
                                                       double originZ, @Nullable NiagaraBounds bounds) {
        return buildGpu(encoder, settings, particles, system, variables, input, null, originX, originY, originZ, bounds);
    }

    public @Nullable NiagaraGaussianSplatDraw buildGpu(CommandEncoder encoder, SplatRendererSettings settings,
                                                       NiagaraGpuParticleView particles, VectorDataSet system,
                                                       Map<String, NiagaraDefinition.Variable> variables,
                                                       EffectHandle.Input input, @Nullable NiagaraParameterStore store,
                                                       double originX, double originY,
                                                       double originZ, @Nullable NiagaraBounds bounds) {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("Niagara splat builder is closed");
        frameDraw = null;
        if (!rendererEnabled()) return null;
        int limit = maxSplatsPerEmitter();
        if (limit <= 0) return null;
        boolean emitterSource = fields.emitterSource();
        int rawCount = emitterSource ? 1 : particles.estimate().current();
        int numSplats = Math.min(rawCount, limit);
        if (numSplats <= 0) return null;

        ComputeRuntime runtime = programs.runtime();
        long maxByDevice = runtime.maxStorageBufferBytes() / SPLAT_STRIDE;
        int clamped = (int) Math.min(numSplats, maxByDevice);
        if (clamped <= 0) return null;
        ensureCapacity(clamped, maxByDevice);

        NiagaraSplatParticles.Defaults defaults = fields.resolveDefaults(new Inputs(system, variables, input, store), originX, originY, originZ);
        NiagaraSplatParticles.Registers registers = fields.registers();
        var data = particles.current();
        var dummy = programs.dummyBuffer().slice();
        var outputSlice = splatBuffer.slice(0, (long) clamped * SPLAT_STRIDE);

        NiagaraGpuComputeStage stage = programs.gaussianSplat("build_splats", "default");
        ComputeBindings.Builder bindings = ComputeBindings.builder();
        for (ShaderBinding binding : stage.shader().bindings()) {
            if (!(binding instanceof ShaderBinding.StorageBuffer storage)) continue;
            bindings.storage(storage.name(), switch (storage.name()) {
                case "NiagaraParticleDataFloat" -> data.floats() == null ? dummy : data.floats().slice();
                case "NiagaraParticleDataHalf" -> data.halves() == null ? dummy : data.halves().slice();
                case "NiagaraParticleDataInt" -> data.integers() == null ? dummy : data.integers().slice();
                case "ParticleCountBuffer" -> emitterSource ? dummy : particles.countBuffer();
                case "RWSplatBuffer" -> outputSlice;
                default -> throw new IllegalArgumentException("Unknown Niagara splat build buffer " + storage.name());
            }, storage.access());
        }

        int groupsX = Math.ceilDiv(clamped, THREADS), groupsY = 1, groupsZ = 1;
        if (groupsX > runtime.maxWorkGroups(0)) {
            groupsY = Math.ceilDiv(groupsX, WRAPPED_STRIDE);
            groupsX = WRAPPED_STRIDE;
        }
        if (groupsY > runtime.maxWorkGroups(1)) {
            groupsZ = Math.ceilDiv(groupsY, WRAPPED_STRIDE);
            groupsY = WRAPPED_STRIDE;
        }
        if (groupsZ > runtime.maxWorkGroups(2))
            throw new IllegalArgumentException("Niagara splat build dispatch exceeds device limit");

        int countOffset = emitterSource ? -1 : particles.currentCountOffset();
        stage.dispatch(encoder, bindings, qualified -> uniformValue(
                        qualified, registers, defaults, data.stride(), countOffset, clamped),
                Math.multiplyExact(groupsX, THREADS), groupsY, groupsZ);

        Vec3 tileOrigin = new Vec3(originX * .01, originZ * .01, originY * .01);
        frameDraw = new NiagaraGaussianSplatDraw(outputSlice, clamped, SPLAT_STRIDE, 0, 0, false, null,
                emitter.localSpace(), input.pose(), tileOrigin, bounds, settings.splatScale(),
                settings.opacityScale(), settings.invertToneCurve(), settings.inverseToneCurveStrength(),
                settings.invertExpandGamut());
        return frameDraw;
    }

    private @Nullable byte[] uniformValue(String qualified, NiagaraSplatParticles.Registers registers,
                                          NiagaraSplatParticles.Defaults defaults, int stride,
                                          int countOffset, int numSplats) {
        String field = qualified.substring(qualified.lastIndexOf('.') + 1);
        return switch (field) {
            case "PositionRegister" -> word(registers.position());
            case "ScaleRegister" -> word(registers.scale());
            case "RotationRegister" -> word(registers.rotation());
            case "ColorRegister" -> word(registers.color());
            case "VisibilityTagRegister" -> word(registers.visibilityTag());
            case "DefaultPosition" -> floats(defaults.position());
            case "DefaultScale" -> floats(defaults.scale());
            case "DefaultRotation" -> floats(defaults.rotation());
            case "DefaultColor" -> floats(defaults.color());
            case "DefaultVisibilityTag" -> word(defaults.visibilityTag());
            case "RendererVisibility" -> word(fields.rendererVisibility());
            case "ParticleCountOffset" -> word(countOffset);
            case "ParticleCount", "MaxSplats" -> word(numSplats);
            case "SplatStride" -> word(SPLAT_STRIDE);
            case "WorkingColorSpaceToSRGB" -> floats(IDENTITY_MAT4);
            case "NiagaraFloatDataStride", "NiagaraHalfDataStride", "NiagaraIntDataStride" -> word(stride);
            default -> null;
        };
    }

    private void ensureCapacity(int numSplats, long maxByDevice) {
        int aligned = Math.min((int) Math.min(Integer.MAX_VALUE, maxByDevice),
                Math.ceilDiv(Math.max(1, numSplats), ALIGNMENT_SPLATS) * ALIGNMENT_SPLATS);
        if (splatBuffer != null && aligned <= splatCapacity) return;
        long bytes = Math.multiplyExact((long) aligned, SPLAT_STRIDE);
        GpuBuffer next = RenderSystem.getDevice().createBuffer(() -> name + ".Splats",
                ComputeUsage.storageBuffer(0), bytes);
        if (splatBuffer != null) {
            GpuBuffer previous = splatBuffer;
            RenderSystem.queueFencedTask(previous::close);
        }
        splatBuffer = next;
        splatCapacity = aligned;
    }

    private static byte[] word(int value) {
        return ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
    }

    private static byte[] floats(float[] values) {
        ByteBuffer buffer = ByteBuffer.allocate(values.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : values) buffer.putFloat(v);
        return buffer.array();
    }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (closed) return;
        closed = true;
        frameDraw = null;
        if (cpuUpload != null) { cpuUpload.close(); cpuUpload = null; }
        if (splatBuffer != null) {
            GpuBuffer previous = splatBuffer;
            RenderSystem.queueFencedTask(previous::close);
            splatBuffer = null;
        }
        splatCapacity = 0;
    }
}
