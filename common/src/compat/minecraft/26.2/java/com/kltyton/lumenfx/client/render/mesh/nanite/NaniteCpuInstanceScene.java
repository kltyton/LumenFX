package com.kltyton.lumenfx.client.render.mesh.nanite;

import com.kltyton.lumenfx.api.client.EffectHandle.Pose;
import com.kltyton.lumenfx.client.render.compute.ComputeRuntime;
import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuMeshCollector.RendererTask;
import com.kltyton.lumenfx.client.simulation.EmitterInstance;
import com.kltyton.lumenfx.client.simulation.niagara.NiagaraParticles;
import com.kltyton.lumenfx.client.transform.NiagaraComponentMatrices;
import com.kltyton.lumenfx.effect.render.mesh.MeshRendererSettings.Entry;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.CommandEncoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.Map;
import com.kltyton.lumenfx.effect.niagara.parameter.store.NiagaraParameterLayout.Key;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Matrix4dc;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3d;
import org.jspecify.annotations.Nullable;

/** The native CPU path writes compact scene transforms and instance payloads for each mesh slot. */
final class NaniteCpuInstanceScene {
    private static final int INSTANCE_BYTES = 9 * 16;
    private record Defaults(float[] position, float[] rotation, float[] scale) { }
    private final ComputeRuntime runtime;

    NaniteCpuInstanceScene(ComputeRuntime runtime) { this.runtime = runtime; }

    NaniteGpuInstanceScene.@Nullable Output prepareEmitter(CommandEncoder encoder, RendererTask task, int slot,
            Map<Key, byte[]> rendererValues, NaniteGpuInstanceScene.Output custom, Vec3 previousCamera) {
        var renderer = task.renderer();
        var values = new NaniteRendererValues(renderer, rendererValues);
        if (!values.selectsMesh(slot) || !values.visible()) return null;
        var currentDefaults = defaults(values, false);
        var previousDefaults = defaults(values, true);
        Pose pose = task.input().pose();
        boolean localSpace = task.emitter().localSpace();
        var origin = new Vec3(task.originX(), task.originY(), task.originZ());
        var toComponent = localSpace ? new Matrix4d()
                : NiagaraComponentMatrices.inverse(NiagaraComponentMatrices.source(pose, origin.x, origin.y, origin.z,
                        true, new Matrix4d()), new Matrix4d());
        var inverseRotation = localSpace ? new Quaternionf() : sourceRotation(pose).conjugate();
        var currentOwner = NiagaraComponentMatrices.source(pose, task.camera().x * 100, task.camera().z * 100,
                task.camera().y * 100, true, new Matrix4d());
        var previousOwner = NiagaraComponentMatrices.source(task.previousPose(), previousCamera.x * 100, previousCamera.z * 100,
                previousCamera.y * 100, true, new Matrix4d());
        var current = emitterMatrix(currentDefaults, toComponent, inverseRotation, currentOwner);
        var previous = emitterMatrix(previousDefaults, toComponent, inverseRotation, previousOwner);
        int usage = ComputeUsage.storageBuffer(GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER);
        try (var transforms = encoder.transientMemory().allocateGpuMapped(INSTANCE_BYTES,
                Math.max(16, runtime.storageBufferAlignment()), usage)) {
            writeMatrices(transforms.data().order(ByteOrder.nativeOrder()), current, previous);
            var instances = new NaniteHierarchyCulling.Instances(transforms.slice(), 1, null, 0, null, 0);
            return new NaniteGpuInstanceScene.Output(instances, custom.customFloats(), custom.customFloatCount());
        }
    }

    private static Matrix4f emitterMatrix(Defaults values, Matrix4dc toComponent, Quaternionfc inverseOwner, Matrix4dc owner) {
        var position = new Vector3d(values.position()[0], values.position()[1], values.position()[2]);
        toComponent.transformPosition(position);
        var rotation = new Quaternionf(values.rotation()[0], values.rotation()[1], values.rotation()[2], values.rotation()[3]);
        if (rotation.lengthSquared() >= 1.0e-8f) rotation.normalize();
        else rotation.identity();
        rotation.premul(inverseOwner);
        var matrix = new Matrix4d();
        NiagaraComponentMatrices.srt(rotation.x, rotation.y, rotation.z, rotation.w,
                values.scale()[0], values.scale()[1], values.scale()[2], position.x, position.y, position.z, matrix);
        return new Matrix4f(owner.mul(matrix, matrix));
    }

    NaniteGpuInstanceScene.@Nullable Output prepare(CommandEncoder encoder, EmitterInstance.Snapshot snapshot,
            Entry entry, int slot, Vec3 camera, Vec3 previousCamera) {
        var particles = Objects.requireNonNull(snapshot.nativeParticles());
        int capacity = particles.count();
        if (capacity == 0 || !particles.naniteEnabled()) return null;
        var renderer = particles.renderData().renderer();
        var values = new NaniteRendererValues(renderer, particles.naniteRendererValues());
        if (!values.selectsMesh(slot) || !values.visible()) return null;
        var currentDefaults = defaults(values, false);
        var previousDefaults = defaults(values, true);
        boolean localSpace = snapshot.definition().localSpace();
        boolean emitterSource = particles.emitterSource();
        var currentOwner = particles.ownerPose();
        var previousOwner = particles.previousOwnerPose();
        var current = NiagaraComponentMatrices.source(currentOwner, camera.x * 100, camera.z * 100, camera.y * 100, true, new Matrix4d());
        var previous = NiagaraComponentMatrices.source(previousOwner, previousCamera.x * 100, previousCamera.z * 100,
                previousCamera.y * 100, true, new Matrix4d());
        var origin = particles.meshSimulationOrigin();
        var toComponent = localSpace ? new Matrix4d()
                : NiagaraComponentMatrices.inverse(NiagaraComponentMatrices.source(currentOwner,
                        origin.x, origin.y, origin.z, true, new Matrix4d()), new Matrix4d());
        Quaternionf inverseOwner = localSpace ? new Quaternionf() : sourceRotation(currentOwner).conjugate();
        int customCount = particles.naniteInstanceFloatCount();
        long transformBytes = Math.multiplyExact((long) capacity, INSTANCE_BYTES);
        long customBytes = Math.max(16L, (long) capacity * customCount * Float.BYTES);
        if (transformBytes > runtime.maxStorageBufferBytes() || customBytes > runtime.maxStorageBufferBytes()
                || customBytes / Float.BYTES > runtime.maxTexelBufferElements())
            throw new IllegalArgumentException("Nanite CPU instance scene exceeds the device limit");
        int alignment = Math.max(16, Math.toIntExact(runtime.storageBufferAlignment()));
        int usage = ComputeUsage.storageBuffer(GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER);
        try (var transforms = encoder.transientMemory().allocateGpuMapped(transformBytes, alignment, usage);
             var custom = encoder.transientMemory().allocateGpuMapped(customBytes, alignment, usage)) {
            var transformData = transforms.data().order(ByteOrder.nativeOrder());
            var customData = custom.data().order(ByteOrder.nativeOrder());
            int count = 0;
            var position = new Vector3d();
            var rotation = new Quaternionf();
            var matrix = new Matrix4d();
            var currentFloat = new Matrix4f();
            var previousFloat = new Matrix4f();
            boolean precise = particles.nanitePreciseMotion();
            for (int row = 0; row < capacity; row++) {
                if (!emitterSource && !particles.selectsMeshParticle(row, slot)) continue;
                transform(particles, entry, row, false, precise, emitterSource, currentDefaults, toComponent, inverseOwner,
                        current, position, rotation, matrix, currentFloat);
                transform(particles, entry, row, true, precise, emitterSource, previousDefaults, toComponent, inverseOwner,
                        previous, position, rotation, matrix, previousFloat);
                writeMatrices(transformData, currentFloat, previousFloat);
                for (int component = 0; component < customCount; component++)
                    customData.putFloat(particles.naniteInstanceFloat(row, component));
                count++;
            }
            if (count == 0) return null;
            var instances = new NaniteHierarchyCulling.Instances(transforms.slice(), count, null, 0, null, 0);
            return new NaniteGpuInstanceScene.Output(instances, custom.slice(), customCount);
        }
    }

    private static Quaternionf sourceRotation(Pose pose) {
        return new Quaternionf(-pose.x(), -pose.z(), -pose.y(), pose.w());
    }

    private static Defaults defaults(NaniteRendererValues values, boolean previous) {
        var position = new float[] {0, 0, 0};
        var rotation = new float[] {0, 0, 0, 1};
        var scale = new float[] {1, 1, 1};
        values.floating(previous ? "PreviousPosition" : "Position", position);
        values.floating(previous ? "PreviousMeshOrientation" : "MeshOrientation", rotation);
        values.floating(previous ? "PreviousScale" : "Scale", scale);
        return new Defaults(position, rotation, scale);
    }

    private static void transform(NiagaraParticles particles, Entry entry, int row, boolean previous, boolean precise,
                                  boolean emitterSource, Defaults defaults, Matrix4dc toComponent, Quaternionfc inverseOwner, Matrix4dc owner,
                                  Vector3d position, Quaternionf rotation, Matrix4d matrix, Matrix4f output) {
        position.set(particles.nanitePosition(row, 0, previous, precise, defaults.position()[0]),
                particles.nanitePosition(row, 1, previous, precise, defaults.position()[1]), particles.nanitePosition(row, 2, previous, precise, defaults.position()[2]));
        toComponent.transformPosition(position);
        rotation.set(particles.naniteOrientation(row, 0, previous, precise, defaults.rotation()[0]),
                particles.naniteOrientation(row, 1, previous, precise, defaults.rotation()[1]), particles.naniteOrientation(row, 2, previous, precise, defaults.rotation()[2]),
                particles.naniteOrientation(row, 3, previous, precise, defaults.rotation()[3]));
        var scale = entry.scale();
        double sx = particles.naniteScale(row, 0, previous, precise, defaults.scale()[0]);
        double sy = particles.naniteScale(row, 1, previous, precise, defaults.scale()[1]);
        double sz = particles.naniteScale(row, 2, previous, precise, defaults.scale()[2]);
        if (!emitterSource) {
            var meshRotation = entry.rotation();
            rotation.mul(meshRotation.x(), meshRotation.y(), meshRotation.z(), meshRotation.w());
            sx *= scale.x; sy *= scale.y; sz *= scale.z;
        }
        if (rotation.lengthSquared() >= 1.0e-8f) rotation.normalize();
        else rotation.identity();
        rotation.premul(inverseOwner);
        NiagaraComponentMatrices.srt(rotation.x, rotation.y, rotation.z, rotation.w,
                sx, sy, sz, position.x, position.y, position.z, matrix);
        owner.mul(matrix, matrix);
        output.set(matrix);
    }

    private static void writeMatrices(ByteBuffer bytes, Matrix4f current, Matrix4f previous) {
        int offset = bytes.position();
        current.get(offset, bytes);
        bytes.position(offset + 64);
        bytes.putFloat(inverseLength(current.m00(), current.m01(), current.m02()));
        bytes.putFloat(inverseLength(current.m10(), current.m11(), current.m12()));
        bytes.putFloat(inverseLength(current.m20(), current.m21(), current.m22()));
        bytes.putFloat(current.determinant3x3() < 0 ? -1 : 1);
        previous.get(offset + 80, bytes);
        bytes.position(offset + INSTANCE_BYTES);
    }

    private static float inverseLength(float x, float y, float z) {
        float squared = x * x + y * y + z * z;
        return squared == 0 ? 0 : 1 / (float) Math.sqrt(squared);
    }
}
