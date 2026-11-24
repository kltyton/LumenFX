package com.kltyton.lumenfx.client.render.niagara.gpu;

import com.kltyton.lumenfx.api.client.parameter.NiagaraPlatforms;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraRibbonScalability;
import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.effect.EmitterDefinition;
import com.kltyton.lumenfx.effect.MaterialDefinition;
import com.kltyton.lumenfx.effect.niagara.NiagaraBounds;
import com.kltyton.lumenfx.client.render.scene.material.MaterialPrimitiveFrame;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import com.kltyton.lumenfx.effect.render.RibbonSettings;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.Objects;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Generates a GPU ribbon from the current emitter data without reading particles back. */
public final class NiagaraGpuRibbonDraw implements AutoCloseable {
    private final String name;
    private final NiagaraGpuPrograms programs;
    private final NiagaraGpuRibbonSort sort;
    private final NiagaraGpuRibbonAggregation aggregation;
    private final NiagaraGpuRibbonIndices indices;
    private final NiagaraGpuRibbonIndices shadowIndices;
    private NiagaraGpuRibbonShape shape;
    private RibbonSettings.Shape shapeSettings;
    private final boolean partialDepth;
    private final boolean cpuUpload;
    private NiagaraGpuRibbonIndices.@Nullable Input currentIndexInput;
    private boolean closed;

    public NiagaraGpuRibbonDraw(String name, NiagaraGpuPrograms programs, RibbonSettings.Shape settings) {
        this(name, programs, settings, false);
    }

    public NiagaraGpuRibbonDraw(String name, NiagaraGpuPrograms programs, RibbonSettings.Shape settings, boolean partialDepth) {
        this(name, programs, settings, partialDepth, false);
    }

    public static NiagaraGpuRibbonDraw forCpuUpload(String name, NiagaraGpuPrograms programs, RibbonSettings.Shape settings) {
        return new NiagaraGpuRibbonDraw(name, programs, settings, false, true);
    }

    private NiagaraGpuRibbonDraw(String name, NiagaraGpuPrograms programs, RibbonSettings.Shape settings,
                                 boolean partialDepth, boolean cpuUpload) {
        this.name = name;
        this.programs = programs;
        this.partialDepth = partialDepth;
        this.cpuUpload = cpuUpload;
        sort = new NiagaraGpuRibbonSort(name, programs);
        aggregation = new NiagaraGpuRibbonAggregation(name, programs);
        indices = new NiagaraGpuRibbonIndices(name, programs);
        shadowIndices = new NiagaraGpuRibbonIndices(name + ".Shadow", programs);
        shape = NiagaraGpuRibbonShape.fromSettings(name, settings);
        shapeSettings = settings;
    }

    public @Nullable NiagaraGpuRibbonFrame prepare(CommandEncoder encoder, NiagaraGpuParticleView particles,
            NiagaraDefinition.Emitter emitter, NiagaraDefinition.Renderer renderer, EmitterDefinition definition,
            MaterialDefinition material, EffectHandle.Input input, Vec3 camera, double originX, double originY,
            double originZ, double time, double depth, float viewDistanceSquared, float lodDistanceFactor, boolean stereo) {
        return prepare(encoder, particles, emitter, renderer, definition, material, input, camera, originX, originY,
                originZ, time, depth, viewDistanceSquared, lodDistanceFactor, stereo, input.pose());
    }

    public @Nullable NiagaraGpuRibbonFrame prepare(CommandEncoder encoder, NiagaraGpuParticleView particles,
            NiagaraDefinition.Emitter emitter, NiagaraDefinition.Renderer renderer, EmitterDefinition definition,
            MaterialDefinition material, EffectHandle.Input input, Vec3 camera, double originX, double originY,
            double originZ, double time, double depth, float viewDistanceSquared, float lodDistanceFactor, boolean stereo,
            EffectHandle.Pose previousPose) {
        return prepare(encoder, particles, emitter, renderer, definition, material, input, camera, originX, originY,
                originZ, time, depth, viewDistanceSquared, lodDistanceFactor, stereo, previousPose, true);
    }

    public @Nullable NiagaraGpuRibbonFrame prepare(CommandEncoder encoder, NiagaraGpuParticleView particles,
            NiagaraDefinition.Emitter emitter, NiagaraDefinition.Renderer renderer, EmitterDefinition definition,
            MaterialDefinition material, EffectHandle.Input input, Vec3 camera, double originX, double originY,
            double originZ, double time, double depth, float viewDistanceSquared, float lodDistanceFactor, boolean stereo,
            EffectHandle.Pose previousPose, boolean componentVelocity) {
        return prepare(encoder, particles, emitter, renderer, definition, material, input, camera, originX, originY,
                originZ, time, depth, viewDistanceSquared, lodDistanceFactor, stereo, previousPose, componentVelocity, null);
    }

    public @Nullable NiagaraGpuRibbonFrame prepare(CommandEncoder encoder, NiagaraGpuParticleView particles,
            NiagaraDefinition.Emitter emitter, NiagaraDefinition.Renderer renderer, EmitterDefinition definition,
            MaterialDefinition material, EffectHandle.Input input, Vec3 camera, double originX, double originY,
            double originZ, double time, double depth, float viewDistanceSquared, float lodDistanceFactor, boolean stereo,
            EffectHandle.Pose previousPose, boolean componentVelocity, @Nullable NiagaraBounds componentBounds) {
        return prepare(encoder, particles, emitter, renderer, definition, material, input, camera, originX, originY,
                originZ, time, depth, viewDistanceSquared, lodDistanceFactor, stereo, previousPose, componentVelocity,
                componentBounds, Objects.requireNonNull(definition.ribbonSettings()));
    }

    public @Nullable NiagaraGpuRibbonFrame prepare(CommandEncoder encoder, NiagaraGpuParticleView particles,
            NiagaraDefinition.Emitter emitter, NiagaraDefinition.Renderer renderer, EmitterDefinition definition,
            MaterialDefinition material, EffectHandle.Input input, Vec3 camera, double originX, double originY,
            double originZ, double time, double depth, float viewDistanceSquared, float lodDistanceFactor, boolean stereo,
            EffectHandle.Pose previousPose, boolean componentVelocity, @Nullable NiagaraBounds componentBounds,
            RibbonSettings settings) {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("Niagara GPU ribbon draw is closed");
        var platform = NiagaraPlatforms.snapshot();
        if (!NiagaraRibbonScalability.renderingEnabled(platform) || !NiagaraRibbonScalability.gpuEnabled(platform)) {
            currentIndexInput = null;
            aggregation.reset();
            return null;
        }
        int instances = particles.estimate().current();
        if (instances < 2) { currentIndexInput = null; aggregation.reset(); return null; }
        if (!shapeSettings.equals(settings.shape())) {
            var next = NiagaraGpuRibbonShape.fromSettings(name, settings.shape());
            RenderSystem.queueFencedTask(shape::close);
            shape = next;
            shapeSettings = settings.shape();
        }
        NiagaraGpuRibbonBindings binding = NiagaraGpuRibbonBindings.resolve(emitter, renderer);
        if (binding.position() == -1 || binding.linkOffset() == -1)
            throw new IllegalStateException("Niagara GPU ribbon is missing its position or link-order dataset binding");
        var scalability = NiagaraRibbonScalability.from(platform);
        boolean constant = scalability.constant(settings);
        int ribbonMode = settings.tessellationMode() == RibbonSettings.TessellationMode.DISABLED ? 0 : constant ? 2 : 1;
        int maxSubSegments = scalability.maximum(settings);
        NiagaraGpuRibbonIndices.Shape shapeInput = shape.indexShape(instances, maxSubSegments);
        int linkOffset = cpuUpload ? binding.cpuUploadLinkOffset(emitter, renderer, settings) : binding.linkOffset();
        GpuBuffer sorted = sort.sort(encoder, particles, instances, binding.idType(), binding.idOffset(),
                linkOffset, binding.linkFloat());
        int tessellationTwist = binding.width() == -1 ? -1 : binding.twist();
        var vertexInput = new NiagaraGpuRibbonAggregation.Input(particles, sorted, instances, binding.idType(),
                binding.idOffset(), binding.position(), binding.width(), tessellationTwist, binding.normalizedAge(),
                settings.uv0().uOverride() ? binding.u0() : -1, settings.uv1().uOverride() ? binding.u1() : -1,
                ribbonMode, Math.min((float)settings.tension(), .99f), uv(settings.uv0()), uv(settings.uv1()));
        var generated = aggregation.generate(encoder, vertexInput);
        var tessellation = new NiagaraGpuRibbonIndices.Tessellation(ribbonMode, settings.tessellationMode().ordinal(),
                constant, settings.maximumTessellation(), (float)settings.tessellationAngle(),
                settings.screenSpace(), scalability.maximumFactor(), scalability.angle(), scalability.screenPercentage(),
                scalability.enabled(), scalability.minimumDisplacementCm(), maxSubSegments,
                viewDistanceSquared, lodDistanceFactor);
        var indexInput = new NiagaraGpuRibbonIndices.Input(particles, sorted, generated, instances,
                binding.idType(), shapeInput, tessellation);
        var generatedIndices = indices.generate(encoder, indexInput);
        currentIndexInput = indexInput;
        NiagaraGpuDataBuffer data = particles.current();
        GpuBufferSlice uniforms = NiagaraGpuRibbonUniforms.write(encoder, emitter, data, binding, shapeInput,
                settings, input, camera, originX, originY, originZ, time, renderer, previousPose, componentVelocity);
        GpuBuffer dummy = programs.dummyBuffer();
        long drawOffset = (stereo ? 8L : 3L) * Integer.BYTES;
        boolean multiPlane = settings.shape().mode() == RibbonSettings.ShapeMode.MULTI_PLANE
                && settings.shape().planeCount() > 1 && settings.shape().planeCount() <= 16;
        boolean disableCull = !settings.shape().materialBackfaceCulling()
                && (!multiPlane || !settings.shape().accurateGeometry());
        return new NiagaraGpuRibbonFrame(material, definition.sortOrder(), renderer.coverage(), depth, disableCull, uniforms,
                data.floats() == null ? dummy : data.floats(), data.halves() == null ? dummy : data.halves(),
                sorted, generated.ribbonIndices(), generated.ribbonData(), generated.tangents(),
                generatedIndices.indirectArguments(), shape.vertices(), generatedIndices.indices(),
                generatedIndices.indirectArguments().slice(drawOffset, 5L * Integer.BYTES), input.receivesDecals(),
                MaterialPrimitiveFrame.forMaterial(material, input, previousPose, () -> componentBounds), input.firstPerson(), partialDepth);
    }

    @FunctionalInterface
    public interface PointShadow {
        NiagaraGpuRibbonFrame prepare(CommandEncoder encoder, float lodDistanceFactor);
    }

    public PointShadow pointShadow(NiagaraGpuRibbonFrame frame, boolean stereo) {
        var input = java.util.Objects.requireNonNull(currentIndexInput, "Point shadow requires the current ribbon geometry");
        return (encoder, factor) -> {
            if (!stereo && factor == input.tessellation().lodDistanceFactor()) return frame;
            var shadow = new NiagaraGpuRibbonIndices.Input(input.particles(), input.sortedIndices(), input.vertices(),
                    input.executableInstances(), input.idType(), input.shape(), input.tessellation().withLodDistanceFactor(factor));
            return frame.withPointShadowIndices(shadowIndices.generate(encoder, shadow));
        };
    }

    private static NiagaraGpuRibbonAggregation.Uv uv(RibbonSettings.Uv uv) {
        return new NiagaraGpuRibbonAggregation.Uv((float) uv.offsetU(), (float) uv.offsetV(), (float) uv.scaleU(),
                (float) uv.scaleV(), (float) (uv.tileLength() * 100), uv.distribution().ordinal(), uv.leadingEdge().ordinal(),
                uv.trailingEdge().ordinal(), uv.uOverride(), uv.vOverride());
    }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (closed) return;
        closed = true; currentIndexInput = null; shape.close(); shadowIndices.close(); indices.close(); aggregation.close(); sort.close();
    }
}
