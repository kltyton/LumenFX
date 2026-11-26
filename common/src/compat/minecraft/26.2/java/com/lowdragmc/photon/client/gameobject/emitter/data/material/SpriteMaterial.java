/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.gameobject.emitter.data.material;

import com.mojang.blaze3d.PrimitiveTopology;
import com.kltyton.lumenfx.internal.photon.core.math.HDRColor;
import com.kltyton.lumenfx.internal.photon.core.registry.annotation.LDLRegisterClient;
import com.kltyton.lumenfx.internal.photon.core.syncdata.annotation.Persisted;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.gameobject.emitter.data.MaterialSetting;
import com.lowdragmc.photon.client.render.PhotonMaterialUniforms;
import lombok.Getter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.resources.Identifier;

import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
@LDLRegisterClient(name = "sprite", registry = "photon:material")
public class SpriteMaterial extends ShaderInstanceMaterial {
    @Persisted
    public Identifier spriteLocation = Identifier.parse("");
    @Persisted
    protected float discardThreshold = 0.1f;
    @Persisted
    protected HDRColor hdr = HDRColor.black();
    @Persisted
    protected TextureMaterial.HDRMode hdrMode = TextureMaterial.HDRMode.ADDITIVE;
    @Persisted(subPersisted = true)
    @Getter
    protected final SoftParticles softParticles = new SoftParticles();
    @Persisted(subPersisted = true)
    @Getter
    protected final LitParticles litParticles = new LitParticles();

    /** 26.1: sprite sets live on {@code ParticleResources} (AT'd public in our accesstransformer.cfg). */
    @Nullable
    private SpriteSet getSpriteSet() {
        if (spriteLocation == null) return null;
        return Minecraft.getInstance().particleEngine.resourceManager.spriteSets.get(spriteLocation);
    }


    // 1.21 bound sprite_hdr_particle with U_SpriteUV from the FIRST frame (sprite.get(0, 1)) — the
    // same static remap now travels in the PhotonMaterial UBO's SpriteUV field.
    @Override
    public RenderType getRenderType(
            MaterialSetting setting,
            PrimitiveTopology mode) {
        var fragment = Photon.id("core/sprite_hdr_particle");
        var spriteSet = getSpriteSet();
        // premultiplied + opaque: the fsh applies HDR.rgb directly now, so the intensity has to be
        // folded in here and the alpha pinned (it carries no meaning for an emission offset)
        var emission = hdr.toVector4fOpaque();
        if (spriteSet == null) {
            // Values.of leaves U_SpriteUV at the identity window (0,0,1,1)
            return MaterialRenderTypes.hdrParticle(
                    MissingTextureAtlasSprite.getLocation(),
                    fragment, setting.pipelineKey(mode),
                    PhotonMaterialUniforms.Values.of(
                            emission, discardThreshold, hdrMode.mode, 0, softParticles.params(),
                            litParticles.blockIntensity()));
        }
        var sprite = spriteSet.get(0, 1);
        return MaterialRenderTypes.hdrParticle(
                sprite.atlasLocation(), fragment, setting.pipelineKey(mode),
                PhotonMaterialUniforms.Values.ofSprite(
                        emission, discardThreshold, hdrMode.mode,
                        sprite.getU0(), sprite.getV0(), sprite.getU1(), sprite.getV1(),
                        softParticles.params(), litParticles.blockIntensity()));
    }
}
