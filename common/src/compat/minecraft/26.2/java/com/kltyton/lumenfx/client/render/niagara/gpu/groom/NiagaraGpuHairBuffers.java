package com.kltyton.lumenfx.client.render.niagara.gpu.groom;

import com.kltyton.lumenfx.api.client.groom.NiagaraGroomGuides;
import com.kltyton.lumenfx.api.client.groom.NiagaraGroomInputs.Group;
import com.kltyton.lumenfx.api.client.groom.NiagaraGroomRoots;
import com.kltyton.lumenfx.api.client.groom.NiagaraGroomRoots.Column;
import com.kltyton.lumenfx.client.render.compute.ComputeRuntime;
import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.groom.HairStrandsFrame;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

public final class NiagaraGpuHairBuffers implements AutoCloseable {
    enum Resource {
        DEFORMED("DeformedPositionBuffer"), REST("RestPositionBuffer"), CURVES("CurvesOffsetsBuffer"),
        OFFSET("DeformedPositionOffset"), BOUNDS("BoundingBoxBuffer"), SCALE("ParamsScaleBuffer"),
        REST_TRIANGLES("RestTrianglePositionBuffer", Column.REST_TRIANGLES),
        DEFORMED_TRIANGLES("DeformedTrianglePositionBuffer", Column.DEFORMED_TRIANGLES),
        BARYCENTRICS("RootBarycentricCoordinatesBuffer", Column.BARYCENTRICS),
        ROOT_TO_TRIANGLE("RootToUniqueTriangleIndexBuffer", Column.ROOT_TO_TRIANGLE),
        REST_SAMPLES("RestSamplePositionsBuffer", Column.REST_SAMPLES),
        SAMPLE_WEIGHTS("MeshSampleWeightsBuffer", Column.SAMPLE_WEIGHTS),
        DEFORMED_SAMPLES("DeformedSamplePositionsBuffer", Column.DEFORMED_SAMPLES),
        PREVIOUS("PreviousDeformedPositionBuffer"), POINT_TO_CURVE("PointToCurveIndexBuffer");

        final String symbol;
        final @Nullable Column root;
        Resource(String symbol) { this(symbol, null); }
        Resource(String symbol, @Nullable Column root) {
            this.symbol = symbol;
            this.root = root;
        }
    }

    private record Uploaded(GpuBuffer buffer, Object source) { }

    static final class Entry {
        private final String id;
        private final long limit;
        private final @Nullable NiagaraGroomGuides guides;
        private final @Nullable String groupIdentity;
        private final Map<Resource, Uploaded> buffers = new EnumMap<>(Resource.class);
        private int boxBase;
        private int resetCount;
        private boolean shouldReset = true;
        private boolean resetActive;
        private @Nullable HairStrandsFrame step;
        private Vec3 currentOffset;
        private Vec3 previousOffset;

        Entry(String id, long limit, @Nullable Group group) {
            this.id = id;
            this.limit = limit;
            guides = group == null ? null : group.guides();
            groupIdentity = group == null ? null : group.identity();
            currentOffset = previousOffset = guides == null ? Vec3.ZERO : guides.deformedOffset();
        }

        boolean matches(@Nullable Group group) {
            return group == null ? guides == null : group.guides() == guides && group.identity().equals(groupIdentity);
        }

        void beginStep(CommandEncoder encoder, HairStrandsFrame frame) {
            if (step == frame) return;
            step = frame;
            shouldReset |= frame.reset();
            if (guides == null) return;

            var current = deformed(encoder);
            var previous = upload(encoder, Resource.PREVIOUS, guides.pointBytes(), guides, guides::writeInitialDeformed, 0);
            encoder.copyToBuffer(current, previous);
            previousOffset = currentOffset;
            currentOffset = Objects.requireNonNull(frame.group()).deformedPositionOffset();
            boxBase = (boxBase + 1) & 3;
        }

        void beginCache(HairStrandsFrame frame, Group group) {
            step = frame;
            currentOffset = group.deformedPositionOffset();
            previousOffset = currentOffset;
        }

        void synchronizeCacheHistory(CommandEncoder encoder, HairStrandsFrame frame) {
            encoder.copyToBuffer(deformed(encoder), buffer(encoder, Resource.PREVIOUS, frame));
        }

        void beginDispatch(int delay) {
            if (guides == null) return;
            if (shouldReset) resetCount = 0;
            else if (resetCount <= delay && resetCount < Integer.MAX_VALUE) resetCount++;
            resetActive = resetCount <= delay;
            shouldReset = false;
        }

        boolean resetActive() { return guides != null && resetActive; }

        byte[] boundsOffsets() {
            return ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(boxBase)
                    .putInt((boxBase + 1) & 3).putInt((boxBase + 2) & 3).putInt((boxBase + 3) & 3).array();
        }

        GpuBufferSlice buffer(CommandEncoder encoder, Resource kind, HairStrandsFrame frame) {
            var group = frame.group();
            if (kind.root != null) {
                NiagaraGroomRoots roots = group == null ? null : group.roots();
                return roots == null || roots.bytes(kind.root) == 0 ? dummy(encoder, kind)
                        : upload(encoder, kind, roots.bytes(kind.root), roots, target -> roots.write(kind.root, target), 0);
            }
            if (guides == null || group == null) return dummy(encoder, kind);
            return switch (kind) {
                case DEFORMED -> deformed(encoder);
                case PREVIOUS -> upload(encoder, kind, guides.pointBytes(), guides, guides::writeInitialDeformed, 0);
                case REST -> upload(encoder, kind, guides.pointBytes(), guides, guides::writeRest, 0);
                case CURVES -> upload(encoder, kind, guides.curveBytes(), guides, guides::writeCurves, 0);
                case POINT_TO_CURVE -> upload(encoder, kind, guides.pointToCurveBytes(), guides, guides::writePointToCurve, 0);
                case OFFSET -> upload(encoder, kind, 16, currentOffset, target -> target.putFloat((float) currentOffset.x)
                        .putFloat((float) currentOffset.y).putFloat((float) currentOffset.z).putFloat(0), 0);
                case SCALE -> {
                    var scales = group.physics().scales();
                    yield upload(encoder, kind, scales.bytes(), scales, scales::write, 0);
                }
                case BOUNDS -> upload(encoder, kind, 96, Resource.BOUNDS, target -> {
                    for (int box = 0; box < 4; box++) {
                        target.putInt(-1).putInt(-1).putInt(-1).putInt(0).putInt(0).putInt(0);
                    }
                }, 0);
                default -> throw new IllegalArgumentException("Unknown groom buffer " + kind);
            };
        }

        void publish(CommandEncoder encoder, HairStrandsFrame frame) {
            var group = frame.group();
            if (guides == null || group == null || group.output() == null || step != frame) return;
            var previous = buffer(encoder, Resource.PREVIOUS, frame);
            group.output().publish(encoder, group.identity(), guides.points(), deformed(encoder), previous, currentOffset, previousOffset);
        }

        private GpuBufferSlice deformed(CommandEncoder encoder) {
            var guide = guides;
            if (guide == null) return dummy(encoder, Resource.DEFORMED);
            return upload(encoder, Resource.DEFORMED, guide.pointBytes(), guide, guide::writeInitialDeformed, GpuBuffer.USAGE_COPY_SRC);
        }

        private GpuBufferSlice dummy(CommandEncoder encoder, Resource kind) {
            int size = kind == Resource.BOUNDS ? 96 : kind == Resource.SCALE ? 128 * Float.BYTES : 16;
            return upload(encoder, kind, size, kind, target -> {
                for (int i = 0; i < size; i += Integer.BYTES) target.putInt(0);
            }, 0);
        }

        private GpuBufferSlice upload(CommandEncoder encoder, Resource kind, int bytes, Object source,
                                      Consumer<ByteBuffer> writer, int flags) {
            var old = buffers.get(kind);
            if (old != null && source.equals(old.source())) return old.buffer().slice(0, bytes);
            if (bytes > limit) throw new IllegalArgumentException("Niagara HairStrands buffer exceeds device limit: " + id + "." + kind.symbol);
            boolean allocate = old == null || old.buffer().size() < bytes;
            var buffer = allocate ? RenderSystem.getDevice().createBuffer(() -> id + "." + kind.symbol,
                    ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_DST | flags), bytes) : old.buffer();
            var slice = buffer.slice(0, bytes);
            try {
                var staging = MemoryUtil.memAlloc(bytes).order(ByteOrder.LITTLE_ENDIAN);
                try {
                    writer.accept(staging);
                    staging.flip();
                    encoder.writeToBuffer(slice, staging);
                } finally {
                    MemoryUtil.memFree(staging);
                }
            } catch (RuntimeException | Error failure) {
                if (allocate) RenderSystem.queueFencedTask(buffer::close);
                throw failure;
            }
            buffers.put(kind, new Uploaded(buffer, source));
            if (allocate && old != null) RenderSystem.queueFencedTask(old.buffer()::close);
            return slice;
        }

        void retire() {
            for (var uploaded : buffers.values()) RenderSystem.queueFencedTask(uploaded.buffer()::close);
            buffers.clear();
        }
    }

    private final ComputeRuntime runtime;
    private final Map<String, Entry> entries = new HashMap<>();

    public NiagaraGpuHairBuffers(ComputeRuntime runtime) { this.runtime = runtime; }

    Entry prepare(String identity, HairStrandsFrame frame) {
        var previous = entries.get(identity);
        if (previous != null && previous.matches(frame.group())) return previous;
        var result = new Entry(identity, runtime.maxStorageBufferBytes(), frame.group());
        entries.put(identity, result);
        if (previous != null) previous.retire();
        return result;
    }

    @Override public void close() {
        entries.values().forEach(Entry::retire);
        entries.clear();
    }
}
