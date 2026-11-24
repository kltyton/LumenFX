package com.kltyton.lumenfx.client.render.motion;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.textures.GpuTextureView;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/** Triple-buffered GPU-vertex history and per-frame run metadata for one submission. */
final class SubmissionHistory {
    final SubmitKey key;
    final FloatHistoryBuffer[] ring;
    int current;
    int previous;
    int next;
    long firstFrame;
    boolean captureStarted;
    int logicalCount;
    final Int2IntOpenHashMap vertexOccurrences = new Int2IntOpenHashMap();
    final Long2IntOpenHashMap previousClipByVertex = new Long2IntOpenHashMap();
    long previousClipIndexFrame = -1;
    final List<RunRecord> currentRuns = new ArrayList<>();
    List<RunRecord> previousRuns = List.of();

    SubmissionHistory(SubmitKey key, FloatHistoryBuffer a, FloatHistoryBuffer b, FloatHistoryBuffer c, long firstFrame) {
        this.key = key;
        this.ring = new FloatHistoryBuffer[] {a, b, c};
        this.current = 0;
        this.previous = 1;
        this.next = 2;
        this.firstFrame = firstFrame;
    }

    FloatHistoryBuffer writeSlot() {
        return this.ring[this.current];
    }

    FloatHistoryBuffer previousSlot() {
        return this.ring[this.previous];
    }

    void rotate() {
        int oldPrevious = this.previous;
        this.previous = this.current;
        this.current = this.next;
        this.next = oldPrevious;
    }

    long nextVertex(int sourceVertex) {
        if (sourceVertex == 0) return 0;
        int occurrence = this.vertexOccurrences.get(sourceVertex);
        this.vertexOccurrences.put(sourceVertex, occurrence + 1);
        return ((long)sourceVertex << 32) | (occurrence & 0xffffffffL);
    }

    void preparePreviousClipIndex(long frame, long committedFrame) {
        if (this.previousClipIndexFrame == frame) return;
        this.previousClipByVertex.clear();
        FloatHistoryBuffer previous = previousSlot();
        if (previous.frame == committedFrame) {
            for (int vertex = 0; vertex < previous.count / 3; vertex++) {
                if (previous.vertexIds[vertex] != 0 && previous.clipTexels[vertex] != 0)
                    this.previousClipByVertex.put(previous.vertexIds[vertex], previous.clipTexels[vertex]);
            }
        }
        this.previousClipIndexFrame = frame;
    }
}

/** Mutable per-frame run; counts are finalized before publication. */
final class RunRecord {
    final SubmitKey key;
    final StagedVertexBuffer.Draw draw;
    final @Nullable PreparedIdentity prepared;
    int logicalOffset;
    int historyVertexOffset;
    int gpuVertexOffset;
    final PrimitiveTopology primitiveTopology;
    int logicalVertexCount;
    int gpuVertexCount;

    RunRecord(
            SubmitKey key,
            StagedVertexBuffer.Draw draw,
            @Nullable PreparedIdentity prepared,
            int logicalOffset,
            int historyVertexOffset,
            int gpuVertexOffset,
            PrimitiveTopology primitiveTopology) {
        this.key = key;
        this.draw = draw;
        this.prepared = prepared;
        this.logicalOffset = logicalOffset;
        this.historyVertexOffset = historyVertexOffset;
        this.gpuVertexOffset = gpuVertexOffset;
        this.primitiveTopology = primitiveTopology;
    }

    RunMotion freeze() {
        return new RunMotion(
                this.key,
                this.draw,
                this.logicalOffset,
                this.logicalVertexCount,
                this.gpuVertexOffset,
                this.gpuVertexCount,
                this.primitiveTopology,
                this.prepared == null ? "" : this.prepared.display(),
                this.primitiveTopology == PrimitiveTopology.LINES);
    }
}

/** Per-frame accumulator and validation state for one native StagedVertexBuffer.Draw. */
final class DrawCapture {
    final StagedVertexBuffer.Draw draw;
    @Nullable PreparedIdentity prepared;
    boolean preparedMismatch;
    final List<RunRecord> runs = new ArrayList<>();
    int capturedGpuCount;
    boolean validated;
    boolean countValid;
    int actualVertexCount;
    StagedVertexBuffer.@Nullable ExecuteInfo execution;

    DrawCapture(StagedVertexBuffer.Draw draw) {
        this.draw = draw;
    }
}

/**
 * Stable identity of the actual prepared render type (pipeline, output target and real texture
 * views). Dynamic transforms/scissor are deliberately excluded; the supplier identity is never used.
 */
final class PreparedIdentity {
    private final Identifier pipeline;
    private final String outputTarget;
    private final List<TextureRef> textures;

    PreparedIdentity(PreparedRenderType prepared) {
        this.pipeline = prepared.pipeline().getLocation();
        this.outputTarget = prepared.outputTarget().toString();
        List<TextureRef> refs = new ArrayList<>(prepared.textures().size());
        for (PreparedRenderType.Texture texture : prepared.textures()) {
            refs.add(new TextureRef(texture.name(), texture.textureView()));
        }
        refs.sort(Comparator.comparing(TextureRef::name)
                .thenComparingInt(ref -> System.identityHashCode(ref.view())));
        this.textures = List.copyOf(refs);
    }

    String display() {
        StringBuilder builder = new StringBuilder(64)
                .append(this.pipeline)
                .append('|')
                .append(this.outputTarget);
        for (TextureRef texture : this.textures) {
            builder.append('|')
                    .append(texture.name())
                    .append(':')
                    .append(System.identityHashCode(texture.view()));
        }
        return builder.toString();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PreparedIdentity identity)) {
            return false;
        }
        return this.pipeline.equals(identity.pipeline)
                && this.outputTarget.equals(identity.outputTarget)
                && this.textures.equals(identity.textures);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.pipeline, this.outputTarget, this.textures);
    }

    private record TextureRef(String name, GpuTextureView view) {
        @Override
        public boolean equals(Object other) {
            return other instanceof TextureRef ref && this.name.equals(ref.name) && this.view == ref.view;
        }

        @Override
        public int hashCode() {
            return 31 * this.name.hashCode() + System.identityHashCode(this.view);
        }
    }
}
