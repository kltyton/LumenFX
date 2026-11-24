package com.kltyton.lumenfx.client.render.compute;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import java.util.Objects;
import java.util.List;

/** Compiled native compute pipeline. Dispatch is recorded on the host's current command stream. */
public abstract class ComputeKernel implements AutoCloseable {
    public record Dispatch(ComputeBindings bindings, int width, int height, int depth) {
        public Dispatch {
            Objects.requireNonNull(bindings);
            if (width <= 0 || height <= 0 || depth <= 0) throw new IllegalArgumentException("Compute dispatch extent must be positive");
        }
    }
    private final ShaderProgram program;

    protected ComputeKernel(final ShaderProgram program) {
        this.program = Objects.requireNonNull(program, "program");
    }

    public final ShaderProgram program() {
        return this.program;
    }

    public final void dispatch(final ComputeBindings bindings, final int width, final int height) {
        this.dispatch(bindings, width, height, 1);
    }

    public abstract void dispatch(ComputeBindings bindings, int width, int height, int depth);

    /**
     * Records one shader's independent requests between a single pair of memory barriers.
     * Requests must not consume another request's writes; shared atomic outputs must address disjoint elements.
     */
    public abstract void dispatchIndependentBatch(List<Dispatch> requests);

    /**
     * Dispatch from the first 12 bytes of a GPU-written indirect argument buffer.
     * The buffer must be a storage buffer and host INDIRECT_PARAMETERS buffer; no CPU readback occurs.
     */
    public final void dispatchIndirect(final ComputeBindings bindings, final GpuBufferSlice args) {
        Objects.requireNonNull(bindings, "bindings");
        Objects.requireNonNull(args, "args");
        final GpuBuffer buffer = args.buffer();
        if (buffer.isClosed()) {
            throw new IllegalStateException("Indirect dispatch arguments buffer is closed");
        }
        if (args.length() < 12L) {
            throw new IllegalArgumentException("Indirect dispatch arguments require at least 12 bytes");
        }
        if (args.offset() % 4L != 0L) {
            throw new IllegalArgumentException("Indirect dispatch offset must be a multiple of 4");
        }
        if ((buffer.usage() & GpuBuffer.USAGE_INDIRECT_PARAMETERS) == 0) {
            throw new IllegalArgumentException("Indirect dispatch buffer lacks USAGE_INDIRECT_PARAMETERS");
        }
        if ((buffer.usage() & ComputeUsage.STORAGE_BUFFER) == 0) {
            throw new IllegalArgumentException("Indirect dispatch buffer lacks the compute storage marker");
        }
        this.dispatchIndirectInternal(bindings, args);
    }

    protected abstract void dispatchIndirectInternal(ComputeBindings bindings, GpuBufferSlice args);

    public abstract boolean isClosed();

    @Override
    public abstract void close();

    protected final int groups(final int size, final int workgroupSize) {
        if (size <= 0) {
            throw new IllegalArgumentException("Compute dispatch extent must be positive");
        }
        return Math.ceilDiv(size, workgroupSize);
    }
}
