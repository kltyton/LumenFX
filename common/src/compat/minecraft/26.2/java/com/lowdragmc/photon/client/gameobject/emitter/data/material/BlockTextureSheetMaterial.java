/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.gameobject.emitter.data.material;

import com.kltyton.lumenfx.internal.photon.core.registry.annotation.LDLRegisterClient;
import net.minecraft.client.renderer.texture.TextureAtlas;

@LDLRegisterClient(name = "block_atlas", registry = "photon:material")
public final class BlockTextureSheetMaterial extends TextureMaterial {
    public static final BlockTextureSheetMaterial INSTANCE = new BlockTextureSheetMaterial();

    private BlockTextureSheetMaterial() {
        super(TextureAtlas.LOCATION_BLOCKS);
    }
}
