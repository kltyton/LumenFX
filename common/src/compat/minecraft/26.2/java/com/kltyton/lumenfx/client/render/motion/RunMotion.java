package com.kltyton.lumenfx.client.render.motion;

import com.mojang.blaze3d.PrimitiveTopology;
import net.minecraft.client.renderer.StagedVertexBuffer;

/**
 * One emitted draw run inside a submission.
 *
 * <p>{@code logicalOffset}/{@code logicalVertexCount} are emission order inside the submit.
 * {@code gpuVertexOffset}/{@code gpuVertexCount} are relative to the draw and include the
 * BufferBuilder LINES duplication. Quad sorting only changes indices; vertex order is preserved.</p>
 */
public record RunMotion(
        SubmitKey key,
        StagedVertexBuffer.Draw draw,
        int logicalOffset,
        int logicalVertexCount,
        int gpuVertexOffset,
        int gpuVertexCount,
        PrimitiveTopology primitiveTopology,
        String preparedSignature,
        boolean linesExpanded) {
}
