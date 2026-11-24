package com.kltyton.lumenfx.client.render.motion;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.List;
import net.minecraft.client.renderer.StagedVertexBuffer;
import org.jspecify.annotations.Nullable;

/** Finalized metadata for one native StagedVertexBuffer.Draw in the published frame. */
public final class DrawMotion {
    private final StagedVertexBuffer.Draw draw;
    private final String preparedSignature;
    private final PrimitiveTopology primitiveTopology;
    private final VertexFormat format;
    private final int vertexCount;
    private final int capturedGpuCount;
    private final boolean validated;
    private final boolean countValid;
    private final StagedVertexBuffer.@Nullable ExecuteInfo execution;
    private final List<RunMotion> runs;

    DrawMotion(
            StagedVertexBuffer.Draw draw,
            String preparedSignature,
            PrimitiveTopology primitiveTopology,
            VertexFormat format,
            int vertexCount,
            int capturedGpuCount,
            boolean validated,
            boolean countValid,
            StagedVertexBuffer.@Nullable ExecuteInfo execution,
            List<RunMotion> runs) {
        this.draw = draw;
        this.preparedSignature = preparedSignature;
        this.primitiveTopology = primitiveTopology;
        this.format = format;
        this.vertexCount = vertexCount;
        this.capturedGpuCount = capturedGpuCount;
        this.validated = validated;
        this.countValid = countValid;
        this.execution = execution;
        this.runs = runs;
    }

    public StagedVertexBuffer.Draw draw() {
        return this.draw;
    }

    public String preparedSignature() {
        return this.preparedSignature;
    }

    public PrimitiveTopology primitiveTopology() {
        return this.primitiveTopology;
    }

    public VertexFormat format() {
        return this.format;
    }

    /** Actual Draw.vertexCount after upload. */
    public int vertexCount() {
        return this.vertexCount;
    }

    /** GPU vertices observed through the consumer during this frame. */
    public int capturedGpuCount() {
        return this.capturedGpuCount;
    }

    public boolean validated() {
        return this.validated;
    }

    public boolean countValid() {
        return this.countValid;
    }

    /** Captured at StagedVertexBuffer.getExecuteInfo(Draw) RETURN, when that draw executed. */
    public StagedVertexBuffer.@Nullable ExecuteInfo execution() {
        return this.execution;
    }

    public List<RunMotion> runs() {
        return this.runs;
    }
}
