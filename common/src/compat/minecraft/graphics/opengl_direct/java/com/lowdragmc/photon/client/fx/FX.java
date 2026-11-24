package com.lowdragmc.photon.client.fx;

import com.kltyton.lumenfx.runtime.model.PhotonFxModel;
import java.util.List;

/** Photon FX handle exposing the decoded {@link PhotonFxModel} across direct-OpenGL targets. */
public final class FX {
    public static final String SUFFIX = ".fx";

    private final PhotonFxModel model;

    public FX(PhotonFxModel model) {
        this.model = model;
    }

    public String id() { return model.id(); }
    public PhotonFxModel model() { return model; }
    public List<PhotonFxModel.FxObjectSpec> objects() { return model.objects(); }
    public boolean hasAuthoredModel() { return model.hasAuthoredModel(); }
}
