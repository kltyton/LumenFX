package com.kltyton.lumenfx.client.render.scene.capture.mixin;

import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import java.util.List;
import java.util.Optional;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;

/** Internal bridge allowing {@link CommandEncoderSceneCaptureMixin} to expose expanded MRT attachments to RenderPearl validation. */
interface RenderPassScenePipelineAccess {
    void lumenfx$setExpandedColorAttachments(List<RenderPassDescriptor.@Nullable Attachment<Optional<Vector4fc>>> attachments);
}
