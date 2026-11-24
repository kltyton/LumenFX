package com.kltyton.lumenfx.api.client;

import com.kltyton.lumenfx.api.client.parameter.TextureTarget;
import com.kltyton.lumenfx.client.VfxEngine;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.resources.Identifier;

/** Producer access resolves the current resource generation without transferring ownership. */
public final class VfxTextures {
    private VfxTextures() { }

    public static TextureTarget renderTarget(Identifier id) {
        RenderSystem.assertOnRenderThread();
        var target = VfxEngine.INSTANCE.textureTarget(id);
        if (target == null) throw new IllegalArgumentException("Render target texture has not been prepared: " + id);
        return target;
    }
}
