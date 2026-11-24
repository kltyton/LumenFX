package com.kltyton.lumenfx.client.compat.shaderpack;

@FunctionalInterface
public interface HostFrameContextProvider {
    HostFrameContext capture();
}
