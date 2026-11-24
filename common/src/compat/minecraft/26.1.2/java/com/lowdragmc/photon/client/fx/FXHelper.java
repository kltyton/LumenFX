package com.lowdragmc.photon.client.fx;

import com.kltyton.lumenfx.runtime.DirectVfxEngine;
import com.kltyton.lumenfx.runtime.model.EffectModel;
import com.kltyton.lumenfx.runtime.model.PhotonFxModel;
import javax.annotation.Nullable;
import net.minecraft.resources.Identifier;

/** Canonical Photon {@code FXHelper} facade for Minecraft 26.1.2. */
public final class FXHelper {
    private FXHelper() { }

    public static @Nullable FX getFX(Identifier fxLocation) {
        return getFX(fxLocation, true);
    }

    public static @Nullable FX getFX(Identifier fxLocation, boolean useCache) {
        if (fxLocation == null) return null;
        String id = fxLocation.toString();
        PhotonFxModel model = DirectVfxEngine.INSTANCE.photonModel(id);
        if (model == null) {
            EffectModel loaded = DirectVfxEngine.INSTANCE.resolveOrLoad(id);
            if (loaded != null) model = loaded.photon();
        }
        return model != null ? new FX(model) : null;
    }

    public static boolean contains(Identifier id) {
        return id != null && DirectVfxEngine.INSTANCE.photonModel(id.toString()) != null;
    }

    public static int generation() {
        return DirectVfxEngine.INSTANCE.resourceGeneration();
    }
}
