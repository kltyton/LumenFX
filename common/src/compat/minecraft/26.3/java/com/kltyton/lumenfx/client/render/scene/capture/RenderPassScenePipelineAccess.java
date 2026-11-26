package com.kltyton.lumenfx.client.render.scene.capture;

import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import java.util.List;
import java.util.Optional;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;

/** Scene capture state and post-pass work for the RenderPearl pass that owns them. */
public interface RenderPassScenePipelineAccess {
    void lumenfx$setExpandedColorAttachments(List<RenderPassDescriptor.@Nullable Attachment<Optional<Vector4fc>>> attachments);
    void lumenfx$afterClose(Runnable task);
}
