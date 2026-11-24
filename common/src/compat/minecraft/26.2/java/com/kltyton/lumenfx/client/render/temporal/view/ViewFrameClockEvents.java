package com.kltyton.lumenfx.client.render.temporal.view;

import com.kltyton.lumenfx.client.render.hdr.WorldHdrCapture;

/** Supplies real frame time independently of paused simulation time and UI delta clamping. */
public final class ViewFrameClockEvents {
    private ViewFrameClockEvents() { }

    public static void begin() {
        WorldHdrCapture.INSTANCE.beginRenderFrame();
    }
}
