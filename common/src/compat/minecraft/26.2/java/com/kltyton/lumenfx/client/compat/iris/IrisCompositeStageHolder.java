package com.kltyton.lumenfx.client.compat.iris;

import com.kltyton.lumenfx.client.compat.shaderpack.ColourEncoding;

/** Exposes precomputed composite stage cutpoints on an Iris CompositeRenderer instance. */
public interface IrisCompositeStageHolder {
    IrisCompositeStagePlanner.StagePlan lumenfx$stagePlan(ColourEncoding initialEncoding);

    void lumenfx$setActiveFrameContext(Object frameBase);
}
