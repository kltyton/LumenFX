package com.kltyton.lumenfx.client.render.texture.layer;

/** Subresource selected by the native image view, independent of its parent allocation. */
public interface TextureLayerView {
    int selectedImageLayer();
    boolean samplesImageArray();
}
