package com.kltyton.lumenfx.client.audio.scene;

import net.minecraft.util.Util;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.SOFTDeviceClock;

/** One audio-device clock snapshot per client sound update. */
public final class MinecraftAudioClock {
    private long device;
    private boolean physicalClock;
    private boolean sampled;
    private long previousTime;
    private double seconds;

    public double current(long device) { return sampled && this.device == device ? seconds : advance(device); }

    public double advance(long currentDevice) {
        boolean changed = device != currentDevice;
        if (changed) {
            device = currentDevice;
            physicalClock = device != 0 && ALC10.alcIsExtensionPresent(device, "ALC_SOFT_device_clock");
        }
        long time = physicalClock ? SOFTDeviceClock.alcGetInteger64vSOFT(device, SOFTDeviceClock.ALC_DEVICE_CLOCK_SOFT)
                : Util.getNanos();
        if (sampled && !changed && time > previousTime) seconds += (time - previousTime) * 1.e-9;
        previousTime = time;
        sampled = true;
        return seconds;
    }

    public void clear() {
        sampled = false;
        device = 0;
        physicalClock = false;
        seconds = 0;
    }
}
