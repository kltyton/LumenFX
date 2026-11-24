package com.lowdragmc.photon.client.fx;

import com.kltyton.lumenfx.runtime.DirectVfxEngine;
import com.kltyton.lumenfx.runtime.model.EffectModel;
import com.kltyton.lumenfx.runtime.model.PhotonFxModel;
import javax.annotation.Nullable;
import net.minecraft.resources.ResourceLocation;

/** Canonical Photon {@code FXHelper} facade for Minecraft 1.20.1 and 1.21.1. */
public final class FXHelper {
    private FXHelper() { }

    public static @Nullable FX getFX(ResourceLocation fxLocation) {
        return getFX(fxLocation, true);
    }

    public static @Nullable FX getFX(ResourceLocation fxLocation, boolean useCache) {
        if (fxLocation == null) return null;
        String id = fxLocation.toString();
        PhotonFxModel model = DirectVfxEngine.INSTANCE.photonModel(id);
        if (model == null) {
            EffectModel loaded = DirectVfxEngine.INSTANCE.resolveOrLoad(id);
            if (loaded != null) model = loaded.photon();
        }
        return model != null ? new FX(model) : null;
    }

    public static boolean contains(ResourceLocation id) {
        return id != null && DirectVfxEngine.INSTANCE.photonModel(id.toString()) != null;
    }

    public static int generation() {
        return DirectVfxEngine.INSTANCE.resourceGeneration();
    }
}
