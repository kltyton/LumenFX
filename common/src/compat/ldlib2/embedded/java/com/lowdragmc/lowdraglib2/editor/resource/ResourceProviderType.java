package com.lowdragmc.lowdraglib2.editor.resource;

public interface ResourceProviderType {
    String getTypeName();

    IResourcePath createFullPath(String path);
}
