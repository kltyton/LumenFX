package com.kltyton.lumenfx.client.render.motion;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import org.jspecify.annotations.Nullable;

/**
 * Recording pass-through bound to one draw run. LINES append each emitted logical vertex twice,
 * matching BufferBuilder's endLastVertex expansion; every other topology appends once.
 */
final class MotionVertexConsumer implements VertexConsumer, MotionIntrinsicTarget {
    private final VertexConsumer delegate;
    private final @Nullable FloatHistoryBuffer target;
    private final @Nullable SubmissionHistory submit;
    private @Nullable RunRecord run;
    private final DrawCapture draw;
    private final boolean lines;

    MotionVertexConsumer(
            VertexConsumer delegate,
            @Nullable FloatHistoryBuffer target,
            @Nullable SubmissionHistory submit,
            @Nullable RunRecord run,
            DrawCapture draw,
            boolean lines) {
        this.delegate = delegate;
        this.target = target;
        this.submit = submit;
        this.run = run;
        this.draw = draw;
        this.lines = lines;
    }

    @Override
    public VertexConsumer lumenfx$delegate() {
        return this.delegate;
    }

    @Override
    public void lumenfx$recordIntrinsicVertex(float x, float y, float z) {
        this.record(x, y, z);
    }

    private void record(float x, float y, float z) {
        if (this.run != null && this.submit != null && this.target != null)
            prepareRun(this.run, this.submit, this.target);
        if (this.submit != null) this.submit.logicalCount++;
        if (this.run != null) {
            this.run.logicalVertexCount++;
            this.run.gpuVertexCount += this.lines ? 2 : 1;
        }
        this.draw.capturedGpuCount += this.lines ? 2 : 1;
        if (this.target != null) {
            int sourceVertex = SourceVertexIdentity.nextVertexId();
            this.target.add(x, y, z, this.submit.nextVertex(sourceVertex));
            if (this.lines) this.target.add(x, y, z, this.submit.nextVertex(sourceVertex));
        }
    }

    private void prepareRun(RunRecord current, SubmissionHistory submit, FloatHistoryBuffer target) {
        if (current.gpuVertexCount == 0) {
            current.logicalOffset = submit.logicalCount;
            current.historyVertexOffset = target.count / 3;
            current.gpuVertexOffset = this.draw.capturedGpuCount;
        } else if (current.gpuVertexOffset + current.gpuVertexCount != this.draw.capturedGpuCount) {
            RunRecord next = new RunRecord(current.key, current.draw, current.prepared, submit.logicalCount,
                    target.count / 3, this.draw.capturedGpuCount, current.primitiveTopology);
            submit.currentRuns.add(next);
            this.draw.runs.add(next);
            this.run = next;
        }
    }

    @Override public void putBakedQuad(PoseStack.Pose pose, BakedQuad quad, QuadInstance instance) {
        SourceVertexIdentity.pushQuad(quad);
        try { VertexConsumer.super.putBakedQuad(pose, quad, instance); }
        finally { SourceVertexIdentity.pop(); }
    }

    @Override public void putBlockBakedQuad(float x, float y, float z, BakedQuad quad, QuadInstance instance) {
        SourceVertexIdentity.pushQuad(quad);
        try { VertexConsumer.super.putBlockBakedQuad(x, y, z, quad, instance); }
        finally { SourceVertexIdentity.pop(); }
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        this.record(x, y, z);
        this.delegate.addVertex(x, y, z);
        return this;
    }

    @Override
    public void addVertex(
            float x,
            float y,
            float z,
            int color,
            float u,
            float v,
            int overlayCoords,
            int lightCoords,
            float nx,
            float ny,
            float nz) {
        this.record(x, y, z);
        this.delegate.addVertex(x, y, z, color, u, v, overlayCoords, lightCoords, nx, ny, nz);
    }

    @Override
    public VertexConsumer setColor(int r, int g, int b, int a) {
        this.delegate.setColor(r, g, b, a);
        return this;
    }

    @Override
    public VertexConsumer setColor(int color) {
        this.delegate.setColor(color);
        return this;
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
        this.delegate.setUv(u, v);
        return this;
    }

    @Override
    public VertexConsumer setUv1(int u, int v) {
        this.delegate.setUv1(u, v);
        return this;
    }

    @Override
    public VertexConsumer setUv2(int u, int v) {
        this.delegate.setUv2(u, v);
        return this;
    }

    @Override
    public VertexConsumer setNormal(float x, float y, float z) {
        this.delegate.setNormal(x, y, z);
        return this;
    }

    @Override
    public VertexConsumer setLineWidth(float width) {
        this.delegate.setLineWidth(width);
        return this;
    }
}
