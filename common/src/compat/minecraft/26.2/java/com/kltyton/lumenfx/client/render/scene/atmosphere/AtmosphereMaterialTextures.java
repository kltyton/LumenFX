package com.kltyton.lumenfx.client.render.scene.atmosphere;

import com.kltyton.lumenfx.client.VfxEngine;
import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraSceneInputs.Atmosphere;
import com.kltyton.lumenfx.effect.MaterialDefinition;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import org.jspecify.annotations.Nullable;

/** Binds the current host view's atmosphere textures to particle materials. */
public final class AtmosphereMaterialTextures {
    private static final int TRANSMITTANCE_INPUTS = MaterialDefinition.ATMOSPHERE_ILLUMINANCE_VIEW_INPUT
            | MaterialDefinition.ATMOSPHERE_DISK_VIEW_INPUT;
    public static final int MASK = TRANSMITTANCE_INPUTS | MaterialDefinition.ATMOSPHERE_SKY_VIEW_INPUT
            | MaterialDefinition.ATMOSPHERE_AERIAL_VIEW_INPUT;
    public static final String TRANSMITTANCE = "MaterialAtmosphereTransmittance";
    public static final String SKY_VIEW = "MaterialAtmosphereSkyView";
    public static final String AERIAL = "MaterialAtmosphereAerialVolume";

    private AtmosphereMaterialTextures() { }

    public static void layout(BindGroupLayout.Builder layout, int inputs) {
        if ((inputs & TRANSMITTANCE_INPUTS) != 0) layout.withSampler(TRANSMITTANCE);
        if ((inputs & MaterialDefinition.ATMOSPHERE_SKY_VIEW_INPUT) != 0) layout.withSampler(SKY_VIEW);
        if ((inputs & MaterialDefinition.ATMOSPHERE_AERIAL_VIEW_INPUT) != 0) layout.withSampler(AERIAL);
    }

    public static void bind(RenderPass pass, int inputs, @Nullable Atmosphere atmosphere) {
        if ((inputs & MASK) == 0) return;
        var sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        if ((inputs & TRANSMITTANCE_INPUTS) != 0)
            pass.bindTexture(TRANSMITTANCE, view(atmosphere, false), sampler);
        if ((inputs & MaterialDefinition.ATMOSPHERE_SKY_VIEW_INPUT) != 0)
            pass.bindTexture(SKY_VIEW, view(atmosphere, true), sampler);
        if ((inputs & MaterialDefinition.ATMOSPHERE_AERIAL_VIEW_INPUT) != 0) {
            var aerial = atmosphere == null ? null : atmosphere.aerialPerspective();
            if (aerial != null) pass.bindTexture(AERIAL, aerial.volume(), aerial.sampler());
            else {
                var neutral = VfxEngine.INSTANCE.neutralAerialPerspective();
                pass.bindTexture(AERIAL, neutral.rasterView(), neutral.rasterSampler());
            }
        }
    }

    public static boolean bind(ComputeBindings.Builder builder, String name, @Nullable Atmosphere atmosphere) {
        if (name.equals(AERIAL)) {
            var aerial = atmosphere == null ? null : atmosphere.aerialPerspective();
            if (aerial != null) builder.texture(name, aerial.volume(), aerial.sampler());
            else builder.volume(name, VfxEngine.INSTANCE.neutralAerialPerspective(), 0);
            return true;
        }
        if (!name.equals(TRANSMITTANCE) && !name.equals(SKY_VIEW)) return false;
        builder.texture(name, view(atmosphere, name.equals(SKY_VIEW)), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
        return true;
    }

    private static GpuTextureView view(@Nullable Atmosphere atmosphere, boolean skyView) {
        if (atmosphere != null) {
            if (!skyView) return atmosphere.transmittance();
            var sky = atmosphere.skyView();
            if (sky != null) return sky.texture();
        }
        // Inactive expressions return before sampling. Keep a valid descriptor on raster-only hosts.
        return Minecraft.getInstance().getTextureManager().getTexture(MissingTextureAtlasSprite.getLocation()).getTextureView();
    }
}
