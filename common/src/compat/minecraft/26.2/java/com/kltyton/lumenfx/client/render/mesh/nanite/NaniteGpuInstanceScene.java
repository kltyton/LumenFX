package com.kltyton.lumenfx.client.render.mesh.nanite;

import com.kltyton.lumenfx.api.client.EffectHandle.Pose;
import com.kltyton.lumenfx.client.render.compute.ComputeRuntime;
import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuParticleView;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuMeshCollector.RendererTask;
import com.kltyton.lumenfx.client.resource.shader.PreparedNiagaraNaniteShaders;
import com.kltyton.lumenfx.client.simulation.niagara.render.NaniteInstanceFloatBindings.FloatComponent;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition.Attribute;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.kltyton.lumenfx.effect.niagara.parameter.store.NiagaraParameterLayout.Key;
import com.kltyton.lumenfx.effect.render.mesh.MeshRendererSettings.Entry;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;

/** NiagaraNanite particle data written to the per-view native instance scene. */
final class NaniteGpuInstanceScene implements AutoCloseable {
    private static final int THREADS = 64;
    private static final int CUSTOM_FLOAT_LIMIT = 64;
    private static final int MATRIX_STRIDE = 9 * 16;

    record Attributes(int position, int rotation, int scale) { }
    record Transform(Vector3fc position, Vector4fc rotation, Vector3fc scale) { }
    record Mesh(int slot, int visibility, int meshIndexRegister, int visibilityRegister,
                Vector4fc rotation, Vector3fc scale, List<FloatComponent> customFloats) {
        Mesh { customFloats = List.copyOf(customFloats); }
    }

    record Placement(Transform simulationToComponent, Vec3 tileOriginRelativeToCamera) { }
    record Output(NaniteHierarchyCulling.Instances instances, GpuBufferSlice customFloats, int customFloatCount) { }

    private final ComputeRuntime runtime;
    private final NaniteComputeProgram program;
    private final GpuBuffer unusedInput;

    NaniteGpuInstanceScene(ComputeRuntime runtime, PreparedNiagaraNaniteShaders shaders) {
        RenderSystem.assertOnRenderThread();
        this.runtime = runtime;
        program = new NaniteComputeProgram(runtime, shaders.instanceScene(), THREADS);
        try {
            unusedInput = RenderSystem.getDevice().createBuffer(() -> "Niagara Nanite unused dataset storage",
                    ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER), 16);
        } catch (RuntimeException | Error failure) {
            program.close();
            throw failure;
        }
    }

    @Nullable Output prepareParticles(CommandEncoder encoder, RendererTask task, Entry entry, int slot,
                                      List<FloatComponent> customFloats, Map<Key, byte[]> rendererValues, Vec3 previousCamera) {
        var renderer = task.renderer();
        var host = new NaniteRendererValues(renderer, rendererValues);
        if (!host.selectsMesh(slot) || !host.visible()) return null;
        var current = new Attributes(register(task, "Position", 3, false),
                register(task, "MeshOrientation", 4, false), register(task, "Scale", 3, false));
        var previous = new Attributes(register(task, "PreviousPosition", 3, false),
                register(task, "PreviousMeshOrientation", 4, false), register(task, "PreviousScale", 3, false));
        float[] position = {0, 0, 0}, orientation = {0, 0, 0, 1}, scale = {1, 1, 1};
        host.floating("Position", position);
        host.floating("MeshOrientation", orientation);
        host.floating("Scale", scale);
        var defaults = new Transform(new Vector3f(position[0], position[1], position[2]),
                new Vector4f(orientation[0], orientation[1], orientation[2], orientation[3]),
                new Vector3f(scale[0], scale[1], scale[2]));
        var rotation = entry.rotation();
        var mesh = new Mesh(slot, renderer.rendererVisibility(), register(task, "MeshIndex", 1, true),
                register(task, "RendererVisibilityTag", 1, true),
                new Vector4f(rotation.x(), rotation.y(), rotation.z(), rotation.w()),
                new Vector3f((float) entry.scale().x, (float) entry.scale().y, (float) entry.scale().z), customFloats);
        return prepare(encoder, task.particles(), current, previous, defaults, mesh,
                placement(task, task.input().pose(), task.camera()), placement(task, task.input().pose(), previousCamera), false);
    }

    Output prepareEmitterFloats(CommandEncoder encoder, RendererTask task, List<FloatComponent> customFloats) {
        var identity = new Transform(new Vector3f(), new Vector4f(0, 0, 0, 1), new Vector3f(1));
        var mesh = new Mesh(0, 0, -1, -1, new Vector4f(0, 0, 0, 1), new Vector3f(1), customFloats);
        var placement = new Placement(identity, Vec3.ZERO);
        if (task.particles().current().stride() == 0 && customFloats.stream().anyMatch(value -> value.particleRegister() != -1))
            throw new IllegalStateException("Nanite emitter custom data requires its particle dataset");
        return Objects.requireNonNull(prepare(encoder, task.particles(), new Attributes(-1, -1, -1),
                new Attributes(-1, -1, -1), identity, mesh, placement, placement, true));
    }

    private static Placement placement(RendererTask task, Pose pose, Vec3 camera) {
        Transform transform;
        if (task.emitter().localSpace()) {
            var position = pose.position();
            transform = new Transform(new Vector3f((float) (position.x * 100 - task.originX()),
                    (float) (position.z * 100 - task.originY()), (float) (position.y * 100 - task.originZ())),
                    new Vector4f(-pose.x(), -pose.z(), -pose.y(), pose.w()),
                    new Vector3f(pose.scaleX(), pose.scaleZ(), pose.scaleY()));
        } else transform = new Transform(new Vector3f(), new Vector4f(0, 0, 0, 1), new Vector3f(1));
        return new Placement(transform, new Vec3(task.originX() - camera.x * 100,
                task.originY() - camera.z * 100, task.originZ() - camera.y * 100));
    }

    private static int register(RendererTask task, String semantic, int width, boolean integer) {
        var binding = task.renderer().attributeBindings().get(semantic);
        if (binding == null || !binding.particle()) return -1;
        String name = unqualifiedParticle(binding.name());
        for (Attribute value : task.emitter().attributes()) {
            if (!SourceNames.equal(unqualifiedParticle(value.name()), name) || value.count() != width) continue;
            if (integer) return value.type() == 1 ? value.component() : -1;
            if (value.type() == 1) return -1;
            return value.type() == 2 ? value.component() | Integer.MIN_VALUE : value.component();
        }
        return -1;
    }

    private static String unqualifiedParticle(String name) {
        return name.length() > 10 && SourceNames.equal(name.substring(0, 10), "Particles.") ? name.substring(10) : name;
    }

    private @Nullable Output prepare(CommandEncoder encoder, NiagaraGpuParticleView particles,
                             Attributes currentAttributes, Attributes previousAttributes, Transform defaults,
                             Mesh mesh, Placement current, Placement previous, boolean emitterSource) {
        RenderSystem.assertOnRenderThread();
        var data = particles.current();
        int capacity = emitterSource ? 1 : Math.min(data.stride(), particles.estimate().current());
        if (capacity == 0) return null;
        int groups = Math.ceilDiv(capacity, THREADS);
        int columns = Math.min(groups, runtime.maxWorkGroups(0));
        int rows = Math.ceilDiv(groups, columns);
        if (rows > runtime.maxWorkGroups(1)) throw new IllegalArgumentException("Nanite instance scene dispatch exceeds the device limits");
        int width = Math.multiplyExact(columns, THREADS);
        int countOffset = particles.currentCountOffset();
        int customCount = Math.min(mesh.customFloats().size(), CUSTOM_FLOAT_LIMIT);

        var transforms = allocate(encoder, Math.multiplyExact((long) capacity, MATRIX_STRIDE));
        var custom = customCount == 0 ? unusedInput.slice() : allocate(encoder, (long) capacity * customCount * Float.BYTES);
        var buffers = Map.of(
                "NaniteParticleFloats", slice(data.floats()),
                "NaniteParticleHalves", slice(data.halves()),
                "NaniteParticleIntegers", slice(data.integers()),
                "NaniteParticleCounts", particles.countBuffer(),
                "NaniteCustomFloatBindings", writeCustomBindings(encoder, mesh.customFloats(), customCount),
                "NaniteInstanceTransforms", transforms,
                "NaniteInstanceCustomFloats", custom);
        var uniforms = new LinkedHashMap<String, byte[]>();
        uniforms.put("InstanceDispatch", ints(capacity, countOffset, width, customCount));
        uniforms.put("InstanceParticleLayout", ints(data.stride(), mesh.slot(), mesh.visibility(), emitterSource ? 1 : 0));
        uniforms.put("InstanceCurrentAttributes", ints(currentAttributes.position(), currentAttributes.rotation(),
                currentAttributes.scale(), mesh.meshIndexRegister()));
        uniforms.put("InstancePreviousAttributes", ints(previousAttributes.position(), previousAttributes.rotation(),
                previousAttributes.scale(), mesh.visibilityRegister()));
        putTransform(uniforms, "Instance", defaults);
        uniforms.put("InstanceMeshRotation", floats(mesh.rotation()));
        uniforms.put("InstanceMeshScale", floats(mesh.scale()));
        putPlacement(uniforms, "InstanceCurrent", current);
        putPlacement(uniforms, "InstancePrevious", previous);
        program.dispatch(encoder, buffers, uniforms, width, rows);
        return new Output(new NaniteHierarchyCulling.Instances(transforms, capacity, null, 0, particles.countBuffer(), countOffset),
                custom, customCount);
    }

    private GpuBufferSlice writeCustomBindings(CommandEncoder encoder, List<FloatComponent> bindings, int count) {
        if (count == 0) return unusedInput.slice();
        try (var upload = encoder.transientMemory().allocateGpuMapped(count * 2L * Integer.BYTES,
                Math.max(16, runtime.storageBufferAlignment()), ComputeUsage.storageBuffer(0))) {
            var bytes = upload.data().order(ByteOrder.nativeOrder());
            for (int i = 0; i < count; i++) {
                var binding = bindings.get(i);
                bytes.putInt(binding.particleRegister()).putFloat(binding.hostValue());
            }
            return upload.slice();
        }
    }

    private GpuBufferSlice allocate(CommandEncoder encoder, long bytes) {
        if (bytes > runtime.maxStorageBufferBytes())
            throw new IllegalArgumentException("Nanite instance scene storage exceeds the device limit");
        return encoder.transientMemory().allocateGpu(bytes, Math.max(16, runtime.storageBufferAlignment()),
                ComputeUsage.storageBuffer(GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER));
    }

    private GpuBufferSlice slice(@Nullable GpuBuffer buffer) { return (buffer == null ? unusedInput : buffer).slice(); }

    private static void putTransform(Map<String, byte[]> uniforms, String name, Transform value) {
        uniforms.put(name + "Position", floats(value.position()));
        uniforms.put(name + "Rotation", floats(value.rotation()));
        uniforms.put(name + "Scale", floats(value.scale()));
    }

    private static void putPlacement(Map<String, byte[]> uniforms, String prefix, Placement placement) {
        var transform = placement.simulationToComponent();
        uniforms.put(prefix + "OwnerTranslation", floats(transform.position()));
        uniforms.put(prefix + "OwnerRotation", floats(transform.rotation()));
        uniforms.put(prefix + "OwnerScale", floats(transform.scale()));
        Vec3 origin = placement.tileOriginRelativeToCamera();
        float highX = (float) origin.x, highY = (float) origin.y, highZ = (float) origin.z;
        uniforms.put(prefix + "OriginHigh", floats(highX, highY, highZ, 0));
        uniforms.put(prefix + "OriginLow", floats((float) (origin.x - highX), (float) (origin.y - highY),
                (float) (origin.z - highZ), 0));
    }

    private static byte[] ints(int x, int y, int z, int w) {
        return ByteBuffer.allocate(16).order(ByteOrder.nativeOrder()).putInt(x).putInt(y).putInt(z).putInt(w).array();
    }

    private static byte[] floats(Vector3fc vector) { return floats(vector.x(), vector.y(), vector.z(), 0); }
    private static byte[] floats(Vector4fc vector) { return floats(vector.x(), vector.y(), vector.z(), vector.w()); }
    private static byte[] floats(float x, float y, float z, float w) {
        return ByteBuffer.allocate(16).order(ByteOrder.nativeOrder()).putFloat(x).putFloat(y).putFloat(z).putFloat(w).array();
    }

    @Override public void close() {
        try { program.close(); }
        finally { unusedInput.close(); }
    }
}
