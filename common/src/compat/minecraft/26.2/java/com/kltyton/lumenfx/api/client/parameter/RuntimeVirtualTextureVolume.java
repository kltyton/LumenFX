package com.kltyton.lumenfx.api.client.parameter;

import com.kltyton.lumenfx.api.client.parameter.NiagaraRuntimeVirtualTexture.Feedback;

/** Owns the placement of a runtime VT asset in one client scene. */
public interface RuntimeVirtualTextureVolume extends NiagaraRuntimeVirtualTexture, AutoCloseable {
    /** Marks intersecting resident and in-flight pages dirty after a scene change. */
    void invalidate(Feedback region);

    @Override void close();
}
