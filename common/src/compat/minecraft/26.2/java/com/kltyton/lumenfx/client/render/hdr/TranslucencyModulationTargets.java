package com.kltyton.lumenfx.client.render.hdr;

import com.kltyton.lumenfx.effect.render.TranslucencyPass;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.EnumMap;
import java.util.EnumSet;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/** RGB transmission multiplies the background independently of the separate translucency color. */
final class TranslucencyModulationTargets implements AutoCloseable {
    private static final Vector4f IDENTITY = new Vector4f(1, 1, 1, 1);
    private final EnumMap<TranslucencyPass, TextureTarget> targets = new EnumMap<>(TranslucencyPass.class);
    private final EnumSet<TranslucencyPass> active = EnumSet.noneOf(TranslucencyPass.class);

    RenderTarget begin(TranslucencyPass stage, RenderTarget colorLayer) {
        TextureTarget target = targets.get(stage);
        var format = colorLayer.getColorTexture().getFormat();
        if (target != null && target.getColorTexture().getFormat() != format) {
            targets.remove(stage);
            active.remove(stage);
            target.destroyBuffers();
            target = null;
        }
        if (target == null) {
            target = new TextureTarget("LumenFX " + stage + " modulation", colorLayer.width, colorLayer.height, true, format);
            targets.put(stage, target);
        } else if (target.width != colorLayer.width || target.height != colorLayer.height) {
            target.resize(colorLayer.width, colorLayer.height);
            active.remove(stage);
        }
        if (active.add(stage)) RenderSystem.getDevice().createCommandEncoder().clearColorTexture(target.getColorTexture(), IDENTITY);
        target.copyDepthFrom(colorLayer);
        return target;
    }

    @Nullable GpuTextureView color(TranslucencyPass stage) {
        return active.contains(stage) ? targets.get(stage).getColorTextureView() : null;
    }

    void reset() { active.clear(); }

    @Override public void close() {
        targets.values().forEach(TextureTarget::destroyBuffers);
        targets.clear();
        reset();
    }
}
