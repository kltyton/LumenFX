package com.kltyton.lumenfx.client.compat.shaderpack;

import com.kltyton.lumenfx.client.compat.iris.IrisBridge;
import com.kltyton.lumenfx.platform.LumenPlatform;
import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;

public final class ShaderpackCompat {
    public static final String IRIS_MOD_ID = "iris";
    public static final String MINIMUM_IRIS_VERSION = "1.11.4";

    private ShaderpackCompat() {
    }

    public static boolean isIrisPresent() {
        return LumenPlatform.isModLoaded(IRIS_MOD_ID);
    }

    public static boolean isSupportedIrisPresent() {
        final String version = irisVersion();
        return version != null && isSupportedIrisVersion(version);
    }

    public static boolean isSupportedIrisVersion(final String version) {
        return isVersionAtLeast(baseVersion(version), 1, 11, 4);
    }

    /**
     * Iris 1.11.x on Minecraft 26.2 requires an OpenGL context (IrisShaders/Iris#3416). When present,
     * the Vulkan-preferred edition keeps OpenGL as the default backend rather than crashing during init.
     */
    public static boolean isIrisOpenGlOnly() {
        final String version = irisVersion();
        return version != null && !isVersionAtLeast(baseVersion(version), 1, 12, 0);
    }

    public static String irisVersion() {
        return LumenPlatform.modVersion(IRIS_MOD_ID);
    }

    public static boolean initializeIrisBridge() {
        if (!isSupportedIrisPresent() || !isOpenGlBackendActive()) {
            return false;
        }
        return IrisBridge.initialize();
    }

    public static boolean isIrisBridgeActive() {
        return isSupportedIrisPresent() && isOpenGlBackendActive() && IrisBridge.isActive();
    }

    public static boolean isShaderPackInUse() {
        return isSupportedIrisPresent() && isOpenGlBackendActive() && IrisBridge.isShaderPackInUse();
    }

    public static com.mojang.blaze3d.textures.GpuTexture activeSceneDepth() {
        return isIrisBridgeActive() ? IrisBridge.activeSceneDepth() : null;
    }

    public static String unavailableReason() {
        if (!isSupportedIrisPresent()) {
            return isIrisPresent()
                    ? "Unsupported Iris version: " + irisVersion()
                    : "Iris is not installed";
        }
        if (!isOpenGlBackendActive()) {
            return "Iris bridge requires the OpenGL graphics backend";
        }
        return IrisBridge.inactiveReason();
    }

    public static FrameOwnership prepareFrameOwnership() {
        if (!isIrisBridgeActive()) {
            return FrameOwnership.inactive();
        }
        return IrisBridge.prepareFrameOwnership();
    }

    public static boolean installFrameListener(final FrameListener listener) {
        if (!isIrisBridgeActive()) {
            return false;
        }
        return IrisBridge.installFrameListener(listener);
    }

    public static boolean installHostFrameContextProvider(final HostFrameContextProvider provider) {
        if (!isIrisBridgeActive()) {
            return false;
        }
        return IrisBridge.installHostFrameContextProvider(provider);
    }

    public static void clearFrameListener() {
        if (isSupportedIrisPresent()) {
            IrisBridge.clearFrameListener();
        }
    }

    public static void clearHostFrameContextProvider() {
        if (isSupportedIrisPresent()) {
            IrisBridge.clearHostFrameContextProvider();
        }
    }

    private static boolean isOpenGlBackendActive() {
        final GpuDevice device = RenderSystem.tryGetDevice();
        return device == null || device.backend instanceof GlDevice;
    }

    static boolean isVersionAtLeast(final String version, final int minMajor, final int minMinor, final int minPatch) {
        if (version == null || version.isBlank()) {
            return false;
        }
        final String[] parts = version.split("\\.");
        final int[] parsed = new int[3];
        for (int i = 0; i < Math.min(parts.length, 3); i++) {
            final String token = parts[i].trim();
            if (token.isEmpty()) {
                return false;
            }
            try {
                parsed[i] = Integer.parseInt(token);
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        if (parsed[0] != minMajor) {
            return parsed[0] > minMajor;
        }
        if (parsed[1] != minMinor) {
            return parsed[1] > minMinor;
        }
        return parsed[2] >= minPatch;
    }

    private static String baseVersion(final String version) {
        int end = version.length();
        final int plus = version.indexOf('+');
        if (plus >= 0) {
            end = plus;
        }
        final int dash = version.indexOf('-');
        if (dash >= 0 && dash < end) {
            end = dash;
        }
        return version.substring(0, end);
    }
}
