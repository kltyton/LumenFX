package com.kltyton.lumenfx.client.render.niagara.gpu;

import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import com.kltyton.lumenfx.effect.render.RibbonSettings;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Packs dataset bindings, geometry masks and the current view for the GPU ribbon vertex factory. */
public final class NiagaraGpuRibbonUniforms {
    private static final int BYTES = 20 * 4 * Integer.BYTES
            + com.kltyton.lumenfx.client.render.niagara.NiagaraComponentUniforms.BYTES;
    private NiagaraGpuRibbonUniforms() { }

    public static GpuBufferSlice write(CommandEncoder encoder, NiagaraDefinition.Emitter emitter,
                                       NiagaraGpuDataBuffer particles, NiagaraGpuRibbonBindings binding,
                                       NiagaraGpuRibbonIndices.Shape shape, RibbonSettings settings,
                                       EffectHandle.Input input, Vec3 camera,
                                       double originX, double originY, double originZ, double time) {
        return write(encoder, emitter, particles, binding, shape, settings, input, camera, originX, originY, originZ, time, null, input.pose());
    }

    public static GpuBufferSlice write(CommandEncoder encoder, NiagaraDefinition.Emitter emitter,
                                       NiagaraGpuDataBuffer particles, NiagaraGpuRibbonBindings binding,
                                       NiagaraGpuRibbonIndices.Shape shape, RibbonSettings settings,
                                       EffectHandle.Input input, Vec3 camera,
                                       double originX, double originY, double originZ, double time,
                                       NiagaraDefinition.@Nullable Renderer renderer, EffectHandle.Pose previousPose) {
        return write(encoder, emitter, particles, binding, shape, settings, input, camera, originX, originY, originZ,
                time, renderer, previousPose, true);
    }

    public static GpuBufferSlice write(CommandEncoder encoder, NiagaraDefinition.Emitter emitter,
                                       NiagaraGpuDataBuffer particles, NiagaraGpuRibbonBindings binding,
                                       NiagaraGpuRibbonIndices.Shape shape, RibbonSettings settings,
                                       EffectHandle.Input input, Vec3 camera,
                                       double originX, double originY, double originZ, double time,
                                       NiagaraDefinition.@Nullable Renderer renderer, EffectHandle.Pose previousPose, boolean componentVelocity) {
        RenderSystem.assertOnRenderThread();
        int facingMode = switch (settings.shape().facing()) {
            case SCREEN -> 0;
            case CUSTOM -> 1;
            case CUSTOM_SIDE_VECTOR -> 2;
        };
        int alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
        try (var mapped = encoder.transientMemory().allocateGpuMapped(BYTES, alignment, GpuBuffer.USAGE_UNIFORM)) {
            ByteBuffer data = mapped.data().order(ByteOrder.nativeOrder());
            ints(data, binding.position(), binding.color(), binding.width(), binding.twist());
            ints(data, facingMode == 0 ? -1 : binding.facing(), binding.normalizedAge(), binding.random(), binding.velocity());
            ints(data, binding.dynamic0(), binding.dynamic1(), binding.dynamic2(), binding.dynamic3());
            ints(data, particles.stride(), shape.segmentShift(), shape.segmentMask(), shape.subSegmentShift());
            ints(data, shape.subSegmentMask(), shape.shapeMask(), binding.idType() == NiagaraGpuRibbonSort.IdType.NONE ? 0 : 1, facingMode);
            ints(data, settings.uv0().uOverride() ? binding.u0() : -1,
                    settings.uv0().vOverride() ? binding.v0() : -1,
                    settings.uv1().uOverride() ? binding.u1() : -1,
                    settings.uv1().vOverride() ? binding.v1() : -1);
            ints(data, binding.distance(), 0, settings.uv0().distribution().ordinal(), settings.uv1().distribution().ordinal());
            ints(data, binding.dynamicMask(), emitter.localSpace() ? 1 : 0, binding.age(), binding.lifetime());
            floats(data, (float) (originX * .01 - camera.x), (float) (originZ * .01 - camera.y),
                    (float) (originY * .01 - camera.z), 0);
            var pose = input.pose();
            floats(data, (float) (pose.position().x - camera.x), (float) (pose.position().y - camera.y),
                    (float) (pose.position().z - camera.z), 0);
            floats(data, pose.x(), pose.y(), pose.z(), pose.w());
            floats(data, input.red(), input.green(), input.blue(), input.alpha());
            floats(data, (float) settings.uv0().scaleV(), (float) settings.uv0().offsetV(),
                    (float) settings.uv1().scaleV(), (float) settings.uv1().offsetV());
            var motion = renderer == null ? com.kltyton.lumenfx.effect.render.ParticleMotionSettings.DEFAULT : renderer.motion();
            floats(data, (float) time, pose.scale(), motion.precise() ? 1 : 2, componentVelocity ? 1 : 0);
            ints(data, renderer == null ? binding.position() : NiagaraGpuRibbonBindings.previous(emitter, renderer, "PreviousPosition", 3, binding.position()),
                    facingMode == 0 ? -1 : renderer == null ? binding.facing() : NiagaraGpuRibbonBindings.previous(emitter, renderer, "PreviousRibbonFacing", 3, binding.facing()),
                    renderer == null ? binding.twist() : NiagaraGpuRibbonBindings.previous(emitter, renderer, "PreviousRibbonTwist", 1, binding.twist()),
                    renderer == null ? binding.width() : NiagaraGpuRibbonBindings.previous(emitter, renderer, "PreviousRibbonWidth", 1, binding.width()));
            floats(data, (float)(previousPose.position().x - camera.x), (float)(previousPose.position().y - camera.y),
                    (float)(previousPose.position().z - camera.z), previousPose.scale());
            floats(data, previousPose.x(), previousPose.y(), previousPose.z(), previousPose.w());
            floats(data, settings.shape().usesGeometryNormals() ? 1 : 0, settings.shape().flipNormalToView() ? 1 : 0, 0, 0);
            floats(data, pose.scaleX(), pose.scaleY(), pose.scaleZ(), 0);
            floats(data, previousPose.scaleX(), previousPose.scaleY(), previousPose.scaleZ(), 0);
            com.kltyton.lumenfx.client.render.niagara.NiagaraComponentUniforms.write(data, pose, previousPose, originX, originY, originZ, camera);
            return mapped.slice();
        }
    }

    private static void ints(ByteBuffer out, int a, int b, int c, int d) {
        out.putInt(a).putInt(b).putInt(c).putInt(d);
    }

    private static void floats(ByteBuffer out, float a, float b, float c, float d) {
        out.putFloat(a).putFloat(b).putFloat(c).putFloat(d);
    }

    /**
     * Supplies default values for shared {@code NiagaraRibbonCommon.ush} parameters reflected in UE 5.9
     * Preview 2 ribbon compute {@code $Globals} blocks when a given pass does not directly consume them.
     */
    static byte @Nullable [] commonStageUniform(String field) {
        return switch (field) {
            case "RibbonIdDataOffset", "PositionDataOffset", "PrevPositionDataOffset", "VelocityDataOffset",
                 "WidthDataOffset", "PrevWidthDataOffset", "TwistDataOffset", "PrevTwistDataOffset",
                 "ColorDataOffset", "FacingDataOffset", "PrevFacingDataOffset", "NormalizedAgeDataOffset",
                 "MaterialRandomDataOffset", "MaterialParamDataOffset", "MaterialParam1DataOffset",
                 "MaterialParam2DataOffset", "MaterialParam3DataOffset", "DistanceFromStartOffset",
                 "U0OverrideDataOffset", "V0RangeOverrideDataOffset", "U1OverrideDataOffset",
                 "V1RangeOverrideDataOffset", "RibbonLinkOrderDataOffset" -> intWord(-1);
            case "NiagaraFloatDataStride", "NiagaraIntDataStride", "MaterialParamValidMask",
                 "U0DistributionMode", "U1DistributionMode", "RibbonLinkOrderIsFloat",
                 "RibbonTessellationMode", "CountOffset" -> intWord(0);
            default -> null;
        };
    }

    private static byte[] intWord(int value) {
        return ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
    }
}
