package com.kltyton.lumenfx.client.render.niagara.gpu;

import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.client.simulation.niagara.VectorDataSet;
import com.kltyton.lumenfx.client.simulation.niagara.NiagaraRendererParameters;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraParameterStore;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Shared GPU particle descriptors and host values, with sprite and mesh vertex inputs. */
public final class NiagaraGpuSpriteUniforms {
    private static final int BYTES = 29 * 4 * Integer.BYTES
            + com.kltyton.lumenfx.client.render.niagara.NiagaraComponentUniforms.BYTES;
    public static final int MESH_BYTES = BYTES + 176;
    private static final int MISSING = -1;

    private NiagaraGpuSpriteUniforms() { }

    public static GpuBufferSlice write(CommandEncoder encoder, NiagaraDefinition.Emitter emitter,
                                       NiagaraDefinition.Renderer renderer, NiagaraGpuDataBuffer particles,
                                       VectorDataSet system, Map<String, NiagaraDefinition.Variable> variables,
                                       EffectHandle.Input input, Vec3 camera, double originX, double originY,
                                       double originZ, double time, boolean sorted) {
        return write(encoder, emitter, renderer, particles, system, variables, input, camera, originX, originY,
                originZ, time, sorted, 0);
    }

    public static GpuBufferSlice write(CommandEncoder encoder, NiagaraDefinition.Emitter emitter,
                                       NiagaraDefinition.Renderer renderer, NiagaraGpuDataBuffer particles,
                                       VectorDataSet system, Map<String, NiagaraDefinition.Variable> variables,
                                       EffectHandle.Input input, Vec3 camera, double originX, double originY,
                                       double originZ, double time, boolean sorted, int sortedOffset) {
        return write(encoder, emitter, renderer, particles, system, variables, input, camera, originX, originY,
                originZ, time, sorted, sortedOffset, input.pose());
    }

    public static GpuBufferSlice write(CommandEncoder encoder, NiagaraDefinition.Emitter emitter,
                                       NiagaraDefinition.Renderer renderer, NiagaraGpuDataBuffer particles,
                                       VectorDataSet system, Map<String, NiagaraDefinition.Variable> variables,
                                       EffectHandle.Input input, Vec3 camera, double originX, double originY,
                                       double originZ, double time, boolean sorted, int sortedOffset, EffectHandle.Pose previousPose) {
        return write(encoder, emitter, renderer, particles, system, variables, input, camera, originX, originY,
                originZ, time, sorted, sortedOffset, previousPose, true);
    }

    public static GpuBufferSlice write(CommandEncoder encoder, NiagaraDefinition.Emitter emitter,
                                       NiagaraDefinition.Renderer renderer, NiagaraGpuDataBuffer particles,
                                       VectorDataSet system, Map<String, NiagaraDefinition.Variable> variables,
                                       EffectHandle.Input input, Vec3 camera, double originX, double originY,
                                       double originZ, double time, boolean sorted, int sortedOffset, EffectHandle.Pose previousPose,
                                       boolean componentVelocity) {
        return write(encoder, emitter, renderer, particles, system, variables, input, null, camera,
                originX, originY, originZ, time, sorted, sortedOffset, previousPose, componentVelocity);
    }

    public static GpuBufferSlice write(CommandEncoder encoder, NiagaraDefinition.Emitter emitter,
                                       NiagaraDefinition.Renderer renderer, NiagaraGpuDataBuffer particles,
                                       VectorDataSet system, Map<String, NiagaraDefinition.Variable> variables,
                                       EffectHandle.Input input, @Nullable NiagaraParameterStore store,
                                       Vec3 camera, double originX, double originY, double originZ, double time,
                                       boolean sorted, int sortedOffset, EffectHandle.Pose previousPose, boolean componentVelocity) {
        return writeInternal(encoder, emitter, renderer, particles, system, variables, input, store, camera,
                originX, originY, originZ, time, sorted, sortedOffset, false, previousPose, componentVelocity);
    }

    public static GpuBufferSlice writeMesh(CommandEncoder encoder, NiagaraDefinition.Emitter emitter,
                                           NiagaraDefinition.Renderer renderer, NiagaraGpuDataBuffer particles,
                                           VectorDataSet system, Map<String, NiagaraDefinition.Variable> variables,
                                           EffectHandle.Input input, Vec3 camera, double originX, double originY,
                                           double originZ, double time, boolean sorted, int sortedOffset) {
        return writeMesh(encoder, emitter, renderer, particles, system, variables, input, camera,
                originX, originY, originZ, time, sorted, sortedOffset, input.pose());
    }

    public static GpuBufferSlice writeMesh(CommandEncoder encoder, NiagaraDefinition.Emitter emitter,
                                           NiagaraDefinition.Renderer renderer, NiagaraGpuDataBuffer particles,
                                           VectorDataSet system, Map<String, NiagaraDefinition.Variable> variables,
                                           EffectHandle.Input input, Vec3 camera, double originX, double originY,
                                           double originZ, double time, boolean sorted, int sortedOffset, EffectHandle.Pose previousPose) {
        return writeMesh(encoder, emitter, renderer, particles, system, variables, input, camera, originX, originY,
                originZ, time, sorted, sortedOffset, previousPose, true);
    }

    public static GpuBufferSlice writeMesh(CommandEncoder encoder, NiagaraDefinition.Emitter emitter,
                                           NiagaraDefinition.Renderer renderer, NiagaraGpuDataBuffer particles,
                                           VectorDataSet system, Map<String, NiagaraDefinition.Variable> variables,
                                           EffectHandle.Input input, Vec3 camera, double originX, double originY,
                                           double originZ, double time, boolean sorted, int sortedOffset, EffectHandle.Pose previousPose,
                                           boolean componentVelocity) {
        return writeMesh(encoder, emitter, renderer, particles, system, variables, input, null, camera,
                originX, originY, originZ, time, sorted, sortedOffset, previousPose, componentVelocity);
    }

    public static GpuBufferSlice writeMesh(CommandEncoder encoder, NiagaraDefinition.Emitter emitter,
                                           NiagaraDefinition.Renderer renderer, NiagaraGpuDataBuffer particles,
                                           VectorDataSet system, Map<String, NiagaraDefinition.Variable> variables,
                                           EffectHandle.Input input, @Nullable NiagaraParameterStore store,
                                           Vec3 camera, double originX, double originY, double originZ, double time,
                                           boolean sorted, int sortedOffset, EffectHandle.Pose previousPose, boolean componentVelocity) {
        return writeInternal(encoder, emitter, renderer, particles, system, variables, input, store, camera,
                originX, originY, originZ, time, sorted, sortedOffset, true, previousPose, componentVelocity);
    }

    private static GpuBufferSlice writeInternal(CommandEncoder encoder, NiagaraDefinition.Emitter emitter,
                                                NiagaraDefinition.Renderer renderer, NiagaraGpuDataBuffer particles,
                                                VectorDataSet system, Map<String, NiagaraDefinition.Variable> variables,
                                                EffectHandle.Input input, @Nullable NiagaraParameterStore store,
                                                Vec3 camera, double originX, double originY, double originZ, double time,
                                                boolean sorted, int sortedOffset, boolean mesh, EffectHandle.Pose previousPose,
                                                boolean componentVelocity) {
        RenderSystem.assertOnRenderThread();
        if (sortedOffset < 0) throw new IllegalArgumentException("Negative Niagara sorted index offset");
        List<NiagaraDefinition.Attribute> attributes = emitter.attributes();
        boolean emitterSource = renderer.sourceMode().equals("Emitter");
        int position = descriptor(attributes, renderer, "Position", 3, emitterSource);
        int size = descriptor(attributes, renderer, "SpriteSize", 2, emitterSource);
        int rotation = descriptor(attributes, renderer, "SpriteRotation", 1, emitterSource);
        int color = descriptor(attributes, renderer, "Color", 4, emitterSource);
        int normalizedAge = descriptor(attributes, renderer, "NormalizedAge", 1, emitterSource);
        int random = descriptor(attributes, renderer, "MaterialRandom", 1, emitterSource);
        int lifetime = emitterSource ? MISSING : descriptor(attributes, "Lifetime", 1);
        int age = emitterSource ? MISSING : descriptor(attributes, "Age", 1);
        int subImage = !emitterSource && renderer.subImageParticleBinding() ? descriptor(attributes, renderer.subImageBinding(), 1) : MISSING;
        int uvScale = !emitterSource && renderer.uvScaleParticleBinding() ? descriptor(attributes, renderer.uvScaleBinding(), 2) : MISSING;
        int dynamic = !emitterSource && renderer.dynamicParameterParticleBinding() ? descriptor(attributes, renderer.dynamicParameterBinding(), 4) : MISSING;
        int velocity = descriptor(attributes, renderer, "Velocity", 3, emitterSource);
        int facing = descriptor(attributes, renderer, "SpriteFacing", 3, emitterSource);
        int alignmentBinding = descriptor(attributes, renderer, "SpriteAlignment", 3, emitterSource);
        int cameraOffset = descriptor(attributes, renderer, "CameraOffset", 1, emitterSource);
        int pivot = descriptor(attributes, renderer, "PivotOffset", 2, emitterSource);

        float[] hostPosition = new float[3], hostSize = {50, 50}, hostRotation = {0}, hostColor = {1, 1, 1, 1};
        if (!emitter.localSpace()) {
            var owner = input.pose().position();
            hostPosition[0] = (float)(owner.x * 100 - originX);
            hostPosition[1] = (float)(owner.z * 100 - originY);
            hostPosition[2] = (float)(owner.y * 100 - originZ);
        }
        float[] hostAge = {0}, hostRandom = {0};
        float[] hostVelocity = {0, 0, 0}, hostFacing = {1, 0, 0}, hostAlignment = {1, 0, 0};
        float[] hostOffset = {0}, hostPivot = {renderer.pivotX(), renderer.pivotY()};
        int[] extraDynamic = new int[3];
        float[][] hostExtra = new float[3][4];
        boolean[] hasHostExtra = new boolean[3];
        for (int i = 0; i < 3; i++) {
            String semantic = "DynamicMaterial" + (i + 1);
            extraDynamic[i] = descriptor(attributes, renderer, semantic, 4, emitterSource);
            hasHostExtra[i] = host(renderer, semantic, system, variables, input, store, hostExtra[i]);
        }
        boolean hasHostPosition = host(renderer, "Position", system, variables, input, store, hostPosition);
        host(renderer, "SpriteSize", system, variables, input, store, hostSize);
        host(renderer, "SpriteRotation", system, variables, input, store, hostRotation);
        host(renderer, "Color", system, variables, input, store, hostColor);
        host(renderer, "NormalizedAge", system, variables, input, store, hostAge);
        host(renderer, "MaterialRandom", system, variables, input, store, hostRandom);
        host(renderer, "Velocity", system, variables, input, store, hostVelocity);
        boolean hasHostFacing = host(renderer, "SpriteFacing", system, variables, input, store, hostFacing);
        boolean hasHostAlignment = host(renderer, "SpriteAlignment", system, variables, input, store, hostAlignment);
        host(renderer, "CameraOffset", system, variables, input, store, hostOffset);
        host(renderer, "PivotOffset", system, variables, input, store, hostPivot);
        int facingMode = mesh ? 0 : switch (renderer.facingMode()) {
            case "FaceCamera" -> 0;
            case "FaceCameraPlane" -> 1;
            case "CustomFacingVector" -> facing != MISSING || hasHostFacing ? 2 : 0;
            case "FaceCameraPosition" -> 3;
            case "FaceCameraDistanceBlend" -> 4;
            case "Automatic" -> (emitterSource ? hasHostFacing : facing != MISSING) ? 2 : 0;
            default -> throw new IllegalArgumentException("Unknown Niagara sprite facing mode");
        };
        int alignmentMode = mesh ? 0 : switch (renderer.alignment()) {
            case "Unaligned" -> 0;
            case "VelocityAligned" -> 1;
            case "CustomAlignment" -> alignmentBinding != MISSING || hasHostAlignment ? 2 : 0;
            case "Automatic" -> (emitterSource ? hasHostAlignment : alignmentBinding != MISSING) ? 2 : 0;
            default -> throw new IllegalArgumentException("Unknown Niagara sprite alignment");
        };

        float subImageValue = renderer.subImageParticleBinding() ? 0 : renderer.subImageDefault();
        if (!renderer.subImageParticleBinding() && renderer.subImageBinding() != null) {
            float[] resolvedSubImage = { subImageValue };
            NiagaraRendererParameters.floating(renderer.subImageBinding(), system, variables, input, store, resolvedSubImage);
            subImageValue = resolvedSubImage[0];
        }
        float[] hostUV = {1, 1};
        if (!renderer.uvScaleParticleBinding() && renderer.uvScaleBinding() != null)
            NiagaraRendererParameters.floating(renderer.uvScaleBinding(), system, variables, input, store, hostUV);
        float[] hostDynamic = new float[4];
        int dynamicMask = dynamic != MISSING ? renderer.dynamicParameterChannelMask() : 0;
        if (!renderer.dynamicParameterParticleBinding() && renderer.dynamicParameterBinding() != null
                && NiagaraRendererParameters.floating(renderer.dynamicParameterBinding(), system, variables, input, store, hostDynamic))
            dynamicMask = 15;
        for (int i = 0; i < 3; i++) {
            var binding = renderer.attributeBindings().get("DynamicMaterial" + (i + 1));
            int mask = extraDynamic[i] != MISSING ? binding == null ? 15 : binding.channelMask() : hasHostExtra[i] ? 15 : 0;
            dynamicMask |= mask << ((i + 1) * 4);
        }

        int alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
        try (var mapped = encoder.transientMemory().allocateGpuMapped(BYTES + (mesh ? 176 : 144), alignment, GpuBuffer.USAGE_UNIFORM)) {
            ByteBuffer data = mapped.data().order(ByteOrder.nativeOrder());
            ints(data, position, size, rotation, color);
            ints(data, normalizedAge, random, lifetime, age);
            ints(data, subImage, uvScale, dynamic, particles.stride());
            ints(data, dynamicMask, sorted ? 1 : 0, emitter.localSpace() ? 1 : 0, sortedOffset);
            floats(data, (float)(originX * .01 - camera.x), (float)(originZ * .01 - camera.y),
                    (float)(originY * .01 - camera.z), 0);
            var pose = input.pose();
            floats(data, (float)(pose.position().x - camera.x), (float)(pose.position().y - camera.y),
                    (float)(pose.position().z - camera.z), 0);
            floats(data, pose.x(), pose.y(), pose.z(), pose.w());
            floats(data, input.red(), input.green(), input.blue(), input.alpha());
            floats(data, renderer.subImageColumns(), renderer.subImageRows(), subImageValue, renderer.subImageBlend() ? 1 : 0);
            floats(data, hostUV[0], hostUV[1], 0, 0);
            floats(data, hostDynamic[0], hostDynamic[1], hostDynamic[2], hostDynamic[3]);
            floats(data, (float)time, pose.scale(), 0, componentVelocity ? 1 : 0);
            floats(data, hostPosition[0], hostPosition[1], hostPosition[2], hasHostPosition || !emitter.localSpace() ? 1 : 0);
            floats(data, hostSize[0], hostSize[1], hostRotation[0], 0);
            floats(data, hostColor[0], hostColor[1], hostColor[2], hostColor[3]);
            floats(data, hostAge[0], hostRandom[0], 0, 0);
            ints(data, extraDynamic[0], extraDynamic[1], extraDynamic[2], 0);
            for (float[] value : hostExtra) floats(data, value[0], value[1], value[2], value[3]);
            ints(data, velocity, facing, alignmentBinding, cameraOffset);
            ints(data, pivot, facingMode, alignmentMode, 0);
            floats(data, hostVelocity[0], hostVelocity[1], hostVelocity[2], 0);
            floats(data, hostFacing[0], hostFacing[1], hostFacing[2], 0);
            floats(data, hostAlignment[0], hostAlignment[1], hostAlignment[2], 0);
            floats(data, hostOffset[0], hostPivot[0], hostPivot[1], 0);
            float min = renderer.facingBlendMin(), max = renderer.facingBlendMax();
            float inverseRange = 1f / Math.max(max * max - min * min, 1f);
            floats(data, inverseRange, min * min * inverseRange, 0, 0);
            floats(data, pose.scaleX(), pose.scaleY(), pose.scaleZ(), 0);
            floats(data, previousPose.scaleX(), previousPose.scaleY(), previousPose.scaleZ(), 0);
            com.kltyton.lumenfx.client.render.niagara.NiagaraComponentUniforms.write(data, pose, previousPose, originX, originY, originZ, camera);
            if (mesh) {
                ints(data, descriptor(attributes, renderer, "Scale", 3, emitterSource),
                        descriptor(attributes, renderer, "MeshOrientation", 4, emitterSource), 0, 0);
                float[] hostScale = {1, 1, 1}, hostOrientation = {0, 0, 0, 1};
                host(renderer, "Scale", system, variables, input, store, hostScale);
                host(renderer, "MeshOrientation", system, variables, input, store, hostOrientation);
                floats(data, hostScale[0], hostScale[1], hostScale[2], 0);
                floats(data, hostOrientation[0], hostOrientation[1], hostOrientation[2], hostOrientation[3]);
                int scale = descriptor(attributes, renderer, "Scale", 3, emitterSource);
                int orientation = descriptor(attributes, renderer, "MeshOrientation", 4, emitterSource);
                ints(data, previousDescriptor(attributes, renderer, "PreviousPosition", 3, emitterSource, position),
                        previousDescriptor(attributes, renderer, "PreviousScale", 3, emitterSource, scale),
                        previousDescriptor(attributes, renderer, "PreviousMeshOrientation", 4, emitterSource, orientation),
                        previousDescriptor(attributes, renderer, "PreviousVelocity", 3, emitterSource, velocity));
                ints(data, previousDescriptor(attributes, renderer, "PreviousCameraOffset", 1, emitterSource, cameraOffset), 0, 0, 0);
                float[] oldPosition = hostPosition.clone(), oldScale = hostScale.clone(), oldOrientation = hostOrientation.clone();
                float[] oldVelocity = hostVelocity.clone(), oldOffset = hostOffset.clone();
                boolean hasOldPosition = previousHostPosition(renderer, system, variables, input, store, oldPosition) || hasHostPosition || !emitter.localSpace();
                host(renderer, "PreviousScale", system, variables, input, store, oldScale);
                host(renderer, "PreviousMeshOrientation", system, variables, input, store, oldOrientation);
                host(renderer, "PreviousVelocity", system, variables, input, store, oldVelocity);
                host(renderer, "PreviousCameraOffset", system, variables, input, store, oldOffset);
                floats(data, oldPosition[0], oldPosition[1], oldPosition[2], hasOldPosition ? 1 : 0);
                floats(data, oldScale[0], oldScale[1], oldScale[2], oldOffset[0]);
                floats(data, oldOrientation[0], oldOrientation[1], oldOrientation[2], oldOrientation[3]);
                floats(data, oldVelocity[0], oldVelocity[1], oldVelocity[2], 0);
                floats(data, (float)((previousPose.position().x - camera.x) * 100),
                        (float)((previousPose.position().z - camera.z) * 100), (float)((previousPose.position().y - camera.y) * 100), previousPose.scale());
                floats(data, -previousPose.x(), -previousPose.z(), -previousPose.y(), previousPose.w());
            } else {
                ints(data, previousDescriptor(attributes, renderer, "PreviousPosition", 3, emitterSource, position),
                        previousDescriptor(attributes, renderer, "PreviousSpriteSize", 2, emitterSource, size),
                        previousDescriptor(attributes, renderer, "PreviousSpriteRotation", 1, emitterSource, rotation),
                        previousDescriptor(attributes, renderer, "PreviousVelocity", 3, emitterSource, velocity));
                ints(data, previousDescriptor(attributes, renderer, "PreviousSpriteFacing", 3, emitterSource, facing),
                        previousDescriptor(attributes, renderer, "PreviousSpriteAlignment", 3, emitterSource, alignmentBinding),
                        previousDescriptor(attributes, renderer, "PreviousCameraOffset", 1, emitterSource, cameraOffset),
                        renderer.motion().precise() ? 1 : 2);
                float[] oldPosition = hostPosition.clone(), oldSize = hostSize.clone(), oldRotation = hostRotation.clone();
                float[] oldVelocity = hostVelocity.clone(), oldFacing = hostFacing.clone(), oldAlignment = hostAlignment.clone(), oldOffset = hostOffset.clone();
                boolean oldPositionBound = previousHostPosition(renderer, system, variables, input, store, oldPosition) || hasHostPosition || !emitter.localSpace();
                host(renderer, "PreviousSpriteSize", system, variables, input, store, oldSize);
                host(renderer, "PreviousSpriteRotation", system, variables, input, store, oldRotation);
                host(renderer, "PreviousVelocity", system, variables, input, store, oldVelocity);
                host(renderer, "PreviousSpriteFacing", system, variables, input, store, oldFacing);
                host(renderer, "PreviousSpriteAlignment", system, variables, input, store, oldAlignment);
                host(renderer, "PreviousCameraOffset", system, variables, input, store, oldOffset);
                floats(data, oldPosition[0], oldPosition[1], oldPosition[2], oldPositionBound ? 1 : 0);
                floats(data, oldSize[0], oldSize[1], oldRotation[0], oldOffset[0]);
                floats(data, oldVelocity[0], oldVelocity[1], oldVelocity[2], 0);
                floats(data, oldFacing[0], oldFacing[1], oldFacing[2], 0);
                floats(data, oldAlignment[0], oldAlignment[1], oldAlignment[2], 0);
                floats(data, (float)(previousPose.position().x - camera.x), (float)(previousPose.position().y - camera.y),
                        (float)(previousPose.position().z - camera.z), previousPose.scale());
                floats(data, previousPose.x(), previousPose.y(), previousPose.z(), previousPose.w());
            }
            return mapped.slice();
        }
    }

    private static int previousDescriptor(List<NiagaraDefinition.Attribute> attributes, NiagaraDefinition.Renderer renderer,
            String semantic, int count, boolean emitterSource, int current) {
        var binding = renderer.attributeBindings().get(semantic);
        if (binding != null && !binding.particle()) return MISSING;
        int value = descriptor(attributes, renderer, semantic, count, emitterSource);
        return value == MISSING ? current : value;
    }

    private static boolean previousHostPosition(NiagaraDefinition.Renderer renderer, VectorDataSet system,
            Map<String, NiagaraDefinition.Variable> variables, EffectHandle.Input input,
            @Nullable NiagaraParameterStore store, float[] position) {
        boolean resolved = host(renderer, "PreviousPosition", system, variables, input, store, position);
        var binding = renderer.attributeBindings().get("PreviousPosition");
        return resolved || binding != null && !binding.particle();
    }

    public static int descriptor(List<NiagaraDefinition.Attribute> attributes, NiagaraDefinition.Renderer renderer,
                                 String semantic, int count, boolean emitterSource) {
        if (emitterSource) return MISSING;
        var binding = renderer.attributeBindings().get(semantic);
        if (binding != null && !binding.particle()) return MISSING;
        String fallback = semantic.startsWith("DynamicMaterial") ? "DynamicMaterialParameter" + semantic.substring("DynamicMaterial".length()) : semantic;
        String name = binding == null ? fallback : binding.name();
        return descriptor(attributes, name, count);
    }

    static boolean host(NiagaraDefinition.Renderer renderer, String semantic, VectorDataSet system,
                                Map<String, NiagaraDefinition.Variable> variables, EffectHandle.Input input, float[] target) {
        return host(renderer, semantic, system, variables, input, null, target);
    }

    static boolean host(NiagaraDefinition.Renderer renderer, String semantic, VectorDataSet system,
                                Map<String, NiagaraDefinition.Variable> variables, EffectHandle.Input input,
                                @Nullable NiagaraParameterStore store, float[] target) {
        var binding = renderer.attributeBindings().get(semantic);
        return binding != null && !binding.particle()
                && NiagaraRendererParameters.floating(binding.name(), system, variables, input, store, target);
    }

    public static int descriptor(List<NiagaraDefinition.Attribute> attributes, @Nullable String name, int count) {
        if (name == null) return MISSING;
        String bare = unqualifiedParticle(name);
        for (var attribute : attributes) {
            if (!SourceNames.equal(unqualifiedParticle(attribute.name()), bare)) continue;
            return (attribute.type() == 0 || attribute.type() == 2) && attribute.count() == count && attribute.component() >= 0
                    ? attribute.component() | (attribute.type() == 2 ? Integer.MIN_VALUE : 0) : MISSING;
        }
        return MISSING;
    }

    private static String unqualifiedParticle(String name) {
        return name.regionMatches(true, 0, "Particles.", 0, "Particles.".length())
                ? name.substring("Particles.".length()) : name;
    }

    private static void ints(ByteBuffer data, int x, int y, int z, int w) { data.putInt(x).putInt(y).putInt(z).putInt(w); }
    private static void floats(ByteBuffer data, float x, float y, float z, float w) { data.putFloat(x).putFloat(y).putFloat(z).putFloat(w); }
}
