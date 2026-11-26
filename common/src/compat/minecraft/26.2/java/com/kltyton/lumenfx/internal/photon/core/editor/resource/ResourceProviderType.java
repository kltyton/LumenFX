package com.kltyton.lumenfx.internal.photon.core.editor.resource;

public interface ResourceProviderType {
    String getTypeName();

    IResourcePath createFullPath(String path);
}
