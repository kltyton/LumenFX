package com.kltyton.lumenfx.client.render.niagara.debug;

import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.compute.ShaderProgram;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuPrograms;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuView;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.NiagaraPositionParameters;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;

/** One draw stream per simulated system, shared by all its GPU emitter stages. */
public final class NiagaraGpuDebugLines implements AutoCloseable {
    public static final String ENABLED = "fx.Niagara.DebugDraw.Enabled";
    public static final String DRAW_ENABLED = "fx.Niagara.GpuComputeDebug.DrawDebugEnabled";
    public static final String MAX_LINES = "fx.Niagara.GpuComputeDebug.MaxLineInstances";
    public static final String ARGUMENTS = "RWNDIDebugDrawArgs";
    public static final String VERTICES = "RWNDIDebugDrawLineVertex";
    public static final int LINE_BYTES = 7 * Integer.BYTES;
    private static final int ARGUMENT_BYTES = 5 * Integer.BYTES;

    public record Frame(GpuBufferSlice vertices, GpuBufferSlice arguments, Vec3 sourceTile) { }

    private @Nullable GpuBuffer vertices, arguments;
    private int capacity, writable;
    private Vec3 tile = Vec3.ZERO;

    public void begin(CommandEncoder encoder, NiagaraGpuPrograms programs, NiagaraGpuView view, int requested, boolean enabled) {
        long available = Math.min(programs.runtime().maxStorageBufferBytes() / LINE_BYTES,
                programs.runtime().maxTexelBufferElements() / 7);
        int count = (int)Math.min(Math.max(0, requested), Math.min(available, Integer.MAX_VALUE / 2));
        if (enabled && count > capacity) {
            var buffer = RenderSystem.getDevice().createBuffer(() -> "Niagara.Debug.LineVertices",
                    ComputeUsage.storageBuffer(GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER), (long)count * LINE_BYTES);
            try {
                if (arguments == null) arguments = RenderSystem.getDevice().createBuffer(() -> "Niagara.Debug.LineArguments",
                        ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_INDIRECT_PARAMETERS), ARGUMENT_BYTES);
            } catch (RuntimeException | Error failure) {
                buffer.close();
                throw failure;
            }
            if (vertices != null) vertices.close();
            vertices = buffer;
            capacity = count;
        }
        writable = enabled ? capacity : 0;
        if (arguments != null) try (var stack = MemoryStack.stackPush()) {
            var data = stack.malloc(ARGUMENT_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            data.putInt(2).putInt(0).putInt(0).putInt(0).putInt(0).flip();
            encoder.writeToBuffer(arguments.slice(), data);
        }
        var camera = view.camera(false);
        tile = new Vec3(NiagaraPositionParameters.tile(camera.x()), NiagaraPositionParameters.tile(camera.y()),
                NiagaraPositionParameters.tile(camera.z()));
    }

    public @Nullable Frame frame() {
        return writable != 0 && vertices != null && arguments != null ? new Frame(vertices.slice(), arguments.slice(), tile) : null;
    }

    public @Nullable byte[] value(String name) {
        return switch (name.substring(name.lastIndexOf('.') + 1)) {
            case "NDIDebugDrawLineMaxInstances" -> ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(writable).array();
            case "LfxDebugLineWorldTile" -> ByteBuffer.allocate(3 * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN)
                    .putFloat((float)tile.x).putFloat((float)tile.y).putFloat((float)tile.z).array();
            default -> null;
        };
    }

    public void bind(NiagaraGpuPrograms programs, ShaderProgram shader, ComputeBindings.Builder bindings) {
        for (var descriptor : shader.bindings()) if (descriptor instanceof ShaderBinding.StorageBuffer buffer) {
            GpuBuffer selected = switch (buffer.name()) {
                case ARGUMENTS -> arguments == null ? programs.dummyBuffer() : arguments;
                case VERTICES -> vertices == null ? programs.dummyBuffer() : vertices;
                default -> null;
            };
            if (selected != null) bindings.storage(buffer.name(), selected.slice(), buffer.access());
        }
    }

    @Override public void close() {
        if (vertices != null) { vertices.close(); vertices = null; }
        if (arguments != null) { arguments.close(); arguments = null; }
        capacity = writable = 0;
    }
}
