package com.kltyton.lumenfx.client.graphics;

import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;

/**
 * Unwraps the active RenderPearl {@link GpuDeviceBackend} from MC 26.3's {@link FrontendGpuDevice}.
 */
public final class LumenGpuDeviceAccess {
    private LumenGpuDeviceAccess() {}

    public static GpuDeviceBackend backend(GpuDevice device) {
        if (device instanceof FrontendGpuDevice frontend) {
            return frontend.backend;
        }
        throw new IllegalStateException("Unsupported GpuDevice implementation: "
                + (device == null ? "null" : device.getClass().getName()));
    }
}
