package com.kltyton.lumenfx.internal.photon.core.editor.resource;

public record BuiltinPath(String name) implements IResourcePath {
    @Override
    public ResourceProviderType getType() {
        return BUILTIN_TYPE;
    }

    @Override
    public String getPath() {
        return name;
    }

    @Override
    public String getResourceName() {
        return name;
    }
}
