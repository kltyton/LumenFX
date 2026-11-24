/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.gameobject.emitter.data.material;

import com.mojang.blaze3d.PrimitiveTopology;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigHDR;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigNumber;
import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.math.HDRColor;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.gameobject.emitter.data.MaterialSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.ToggleGroup;
import com.lowdragmc.photon.client.render.PhotonMaterialUniforms;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
@LDLRegisterClient(name = "texture", registry = "photon:material")
@Setter
@Getter
public class TextureMaterial extends ShaderInstanceMaterial {
    public enum HDRMode {
        ADDITIVE(0),
        MULTIPLICATIVE(1);
        public final int mode;

        HDRMode(int mode) {
            this.mode = mode;
        }
    }

    public static class PixelArt extends ToggleGroup {
        @Configurable
        @ConfigNumber(range = {1, Integer.MAX_VALUE})
        public int bits = 8;
    }

    @Configurable(name = "TextureMaterial.texture")
    protected Identifier texture = Photon.id("textures/particle/circle.png");
    @Configurable(name = "TextureMaterial.discardThreshold")
    @ConfigNumber(range = {0, 1})
    protected float discardThreshold = 0.1f;
    @Configurable(name = "TextureMaterial.hdr")
    @ConfigHDR
    protected HDRColor hdr = HDRColor.black();
    @Configurable(name = "TextureMaterial.hdrMode")
    protected HDRMode hdrMode = HDRMode.ADDITIVE;
    @Configurable(name = "TextureMaterial.pixelArt", subConfigurable = true)
    protected final PixelArt pixelArt = new PixelArt();
    @Configurable(name = "TextureMaterial.softParticles", subConfigurable = true)
    @Getter
    protected final SoftParticles softParticles = new SoftParticles();
    @Configurable(name = "TextureMaterial.litParticles", subConfigurable = true)
    @Getter
    protected final LitParticles litParticles = new LitParticles();

    public TextureMaterial() {
    }

    public TextureMaterial(Identifier texture) {
        this.texture = texture;
    }

    @Override
    public IMaterial copy() {
        var mat = new TextureMaterial(texture);
        mat.discardThreshold = discardThreshold;
        mat.hdr = hdr.copy(); // deep: HDRColor is mutable, copies must not share it
        mat.hdrMode = hdrMode;
        mat.pixelArt.setEnable(pixelArt.isEnable());
        mat.pixelArt.bits = pixelArt.bits;
        mat.softParticles.copyFrom(softParticles);
        mat.litParticles.copyFrom(litParticles);
        return mat;
    }

    @Override
    public RenderType getRenderType(
            MaterialSetting setting,
            PrimitiveTopology mode) {
        // 1.21 selected the pixel program only when pixel-art was on; the plain program has no Bits use
        var fragment = pixelArt.isEnable() ? Photon.id("core/pixel_hdr_particle") : Photon.id("core/hdr_particle");
        return MaterialRenderTypes.hdrParticle(texture, fragment,
                setting.pipelineKey(mode),
                PhotonMaterialUniforms.Values.of(
                        // premultiplied + opaque: the fsh applies HDR.rgb directly now, so the intensity
                        // has to be folded in here and the alpha pinned (it means nothing for an offset)
                        hdr.toVector4fOpaque(), discardThreshold, hdrMode.mode,
                        pixelArt.isEnable() ? Math.max(pixelArt.bits, 1) : 0,
                        softParticles.params(), litParticles.blockIntensity()));
    }
}
