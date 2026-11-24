package com.kltyton.lumenfx.client.audio.capture;

import com.kltyton.lumenfx.api.client.NiagaraAudioBuses.PcmWindow;
import com.kltyton.lumenfx.client.audio.analysis.MixedAudioHistory;
import com.kltyton.lumenfx.effect.niagara.audio.NiagaraAudioAnalysisDefinition;
import com.mojang.logging.LogUtils;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.Platform;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.SOFTOutputMode;

/** Shares the current process's real master output among native submix analyzers. */
public final class MinecraftMasterAudio {
    private static final Set<ClientLevel> USERS = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final long[] PUBLICATION = {-1, 0, 0};
    private static long discontinuities;
    private static Object timeline = new Object();
    private static long handle;
    private static long outputDevice;
    private record Format(int sampleRate, int channels, int channelMask) { }
    private static @Nullable Format outputFormat;
    private static int windowFrames;
    private static boolean failed;
    private static @Nullable PcmWindow published;

    private MinecraftMasterAudio() { }

    public static synchronized @Nullable PcmWindow window(ClientLevel world, NiagaraAudioAnalysisDefinition settings) {
        if (Platform.get() != Platform.WINDOWS || !settings.source().isEmpty()) return null;
        USERS.add(world);
        long device = Minecraft.getInstance().getSoundManager().soundEngine.library.currentDevice;
        if (device == 0) return null;
        if (outputDevice != device) {
            close();
            outputDevice = device;
            outputFormat = null;
            windowFrames = 0;
            resetTimeline();
            failed = false;
        }
        if (failed) return null;
        try {
            if (outputFormat == null) outputFormat = format(device);
            var format = outputFormat;
            int frames = Math.ceilDiv(16384, format.channels());
            if (!settings.spectrum())
                frames = Math.max(frames, MixedAudioHistory.scopeFrames(format.sampleRate(), settings.windowMilliseconds()));
            windowFrames = Math.max(windowFrames, frames);
            if (handle == 0)
                handle = WindowsProcessAudio.open(windowFrames, format.sampleRate(), format.channels(), format.channelMask());
            var samples = WindowsProcessAudio.read(handle, windowFrames, PUBLICATION);
            if (samples != null) {
                if (discontinuities != PUBLICATION[2]) {
                    discontinuities = PUBLICATION[2];
                    timeline = new Object();
                }
                published = new PcmWindow(format.sampleRate(), format.channels(), samples, timeline, PUBLICATION[1]);
            }
            return published;
        } catch (LinkageError | RuntimeException failure) {
            failed = true;
            close();
            LogUtils.getLogger().warn("Unable to capture Minecraft master audio for Niagara analysis", failure);
            return null;
        }
    }

    private static Format format(long device) {
        int rate = ALC10.alcGetInteger(device, ALC10.ALC_FREQUENCY);
        if (rate <= 0) throw new IllegalStateException("The active Minecraft audio device has no valid sample rate");
        if (!ALC10.alcIsExtensionPresent(device, "ALC_SOFT_output_mode"))
            throw new IllegalStateException("The active audio device does not expose its output channel layout");
        int mask = switch (ALC10.alcGetInteger(device, SOFTOutputMode.ALC_OUTPUT_MODE_SOFT)) {
            case SOFTOutputMode.ALC_MONO_SOFT -> 0x4;
            case SOFTOutputMode.ALC_STEREO_SOFT, SOFTOutputMode.ALC_STEREO_BASIC_SOFT,
                    SOFTOutputMode.ALC_STEREO_UHJ_SOFT, SOFTOutputMode.ALC_STEREO_HRTF_SOFT -> 0x3;
            case SOFTOutputMode.ALC_QUAD_SOFT -> 0x33;
            case SOFTOutputMode.ALC_SURROUND_5_1_SOFT -> 0x3f;
            case SOFTOutputMode.ALC_SURROUND_6_1_SOFT -> 0x70f;
            case SOFTOutputMode.ALC_SURROUND_7_1_SOFT -> 0x63f;
            default -> throw new IllegalStateException("The active Minecraft output has an unspecified channel layout");
        };
        return new Format(rate, Integer.bitCount(mask), mask);
    }

    public static synchronized void clear(ClientLevel world) {
        USERS.remove(world);
        if (USERS.isEmpty()) clear();
    }

    public static synchronized void clear() {
        close();
        USERS.clear();
        failed = false;
        windowFrames = 0;
        outputDevice = 0;
        outputFormat = null;
        resetTimeline();
    }

    private static void resetTimeline() {
        PUBLICATION[0] = -1;
        PUBLICATION[1] = PUBLICATION[2] = 0;
        discontinuities = 0;
        timeline = new Object();
    }

    private static void close() {
        long previous = handle;
        handle = 0;
        published = null;
        if (previous != 0) WindowsProcessAudio.close(previous);
    }
}
