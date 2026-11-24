package com.kltyton.lumenfx.client.resource.texture;

import com.kltyton.lumenfx.api.client.parameter.TextureParameter;
import com.kltyton.lumenfx.client.resource.texture.target.RenderTargetTextureArray;
import com.kltyton.lumenfx.client.resource.texture.target.RenderTargetTextureVolume;
import com.kltyton.lumenfx.client.resource.texture.target.RenderTargetTextureCube;
import com.mojang.blaze3d.systems.RenderSystem;
import com.kltyton.lumenfx.client.VfxEngine;
import java.util.Map;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Immutable render-thread snapshot. EffectResources owns the lifecycle; the
 * GPU renderer only looks up and binds the prepared texture and sampler.
 */
public final class EffectTextures {
    private EffectTextures() { }

    public static EffectTexture get(Identifier id) {
        return VfxEngine.INSTANCE.texture(id);
    }

    public static com.kltyton.lumenfx.client.resource.texture.target.RenderTargetTexture target(Identifier id) {
        RenderSystem.assertOnRenderThread();
        return VfxEngine.INSTANCE.textureTarget(id);
    }

    public static com.kltyton.lumenfx.api.client.parameter.TextureParameter.Snapshot snapshot(Identifier id) {
        RenderSystem.assertOnRenderThread();
        EffectTexture image = get(id);
        if (image != null) return image.snapshot();
        var target = VfxEngine.INSTANCE.textureTarget(id);
        if (target != null) return target.snapshot();
        throw new IllegalArgumentException("Niagara texture has not been prepared: " + id);
    }

    public static TextureParameter.@Nullable Retention retain(Identifier id, TextureParameter.Snapshot snapshot) {
        RenderSystem.assertOnRenderThread();
        EffectTexture image = get(id);
        if (image != null) return image.retain(snapshot);
        var target = target(id);
        return target == null ? null : target.retain(snapshot);
    }

    public static @Nullable RenderTargetTextureArray targetArray(Identifier id) {
        RenderSystem.assertOnRenderThread();
        var texture = get(id);
        return texture == null ? null : texture.targetArray();
    }

    public static @Nullable RenderTargetTextureVolume targetVolume(Identifier id) {
        RenderSystem.assertOnRenderThread();
        var texture = get(id);
        return texture == null ? null : texture.targetVolume();
    }
    public static @Nullable RenderTargetTextureCube targetCube(Identifier id) {
        RenderSystem.assertOnRenderThread();
        var texture = get(id);
        return texture == null ? null : texture.targetCube();
    }

    public static void releaseReplaced(VfxEngine.ReplacedTextures previous) {
        try { releaseReplaced(previous.images()); }
        finally {
            try { previous.targets().forEach((id, target) -> { if (VfxEngine.INSTANCE.textureTarget(id) != target) target.close(); }); }
            finally {
                if (previous.mipPrograms() != null && VfxEngine.INSTANCE.textureMipPrograms() != previous.mipPrograms()) previous.mipPrograms().close();
            }
        }
    }

    public static void install(Map<Identifier, EffectTexture> next) {
        RenderSystem.assertOnRenderThread();
        releaseReplaced(VfxEngine.INSTANCE.replaceTextures(next));
    }

    public static void releaseReplaced(Map<Identifier, EffectTexture> previous) {
        RenderSystem.assertOnRenderThread();
        for (var entry : previous.entrySet())
            if (get(entry.getKey()) != entry.getValue()) entry.getValue().close();
    }

    public static void clear() {
        var images = VfxEngine.INSTANCE.replaceTextures(Map.of());
        var targets = VfxEngine.INSTANCE.replaceTextureTargets(Map.of());
        var mipPrograms = VfxEngine.INSTANCE.replaceTextureMipPrograms(null);
        releaseReplaced(new VfxEngine.ReplacedTextures(images, targets, mipPrograms));
    }
}
