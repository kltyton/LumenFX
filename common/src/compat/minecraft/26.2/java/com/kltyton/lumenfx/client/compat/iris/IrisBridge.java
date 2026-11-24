package com.kltyton.lumenfx.client.compat.iris;

import com.kltyton.lumenfx.client.compat.shaderpack.FrameListener;
import com.kltyton.lumenfx.client.compat.shaderpack.FrameOwnership;
import com.kltyton.lumenfx.client.compat.shaderpack.HostFrameContextProvider;

public final class IrisBridge {
    private IrisBridge() {
    }

    public static boolean initialize() {
        return IrisCompat.initialize();
    }

    public static boolean isActive() {
        return IrisCompat.isActive();
    }

    public static boolean isShaderPackInUse() {
        return IrisCompat.isShaderPackInUse();
    }

    public static com.mojang.blaze3d.textures.GpuTexture activeSceneDepth() {
        return IrisCompat.activeSceneDepth();
    }

    public static String inactiveReason() {
        return IrisCompat.inactiveReason();
    }

    public static FrameOwnership prepareFrameOwnership() {
        return IrisCompat.prepareFrameOwnership();
    }

    public static boolean installFrameListener(final FrameListener listener) {
        return IrisCompat.installFrameListener(listener);
    }

    public static boolean installHostFrameContextProvider(final HostFrameContextProvider provider) {
        return IrisCompat.installHostFrameContextProvider(provider);
    }

    public static void clearFrameListener() {
        IrisCompat.clearFrameListener();
    }

    public static void clearHostFrameContextProvider() {
        IrisCompat.clearHostFrameContextProvider();
    }

    public static Throwable lastFailure() {
        return IrisCompat.lastFailure();
    }

    public static void shutdown() {
        IrisCompat.shutdown();
    }
}
