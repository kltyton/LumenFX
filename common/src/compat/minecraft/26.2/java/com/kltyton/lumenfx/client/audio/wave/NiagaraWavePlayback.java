package com.kltyton.lumenfx.client.audio.wave;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.Consumer;
import javax.sound.sampled.AudioFormat;
import net.minecraft.client.sounds.AudioStream;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.EXTFloat32;
import org.lwjgl.openal.EXTMCFormats;
import org.lwjgl.openal.SOFTCallbackBuffer;

/** Streams filtered float PCM from the real device callback or from the host's queued audio buffers. */
public final class NiagaraWavePlayback implements AudioStream {
    public static final class Format extends AudioFormat {
        Format(int rate, int channels) {
            super(Encoding.PCM_FLOAT, rate, Float.SIZE, channels, channels * Float.BYTES, rate,
                    ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN);
        }
    }

    private final ByteBuffer pcm;
    private final int inputRate, startFrame;
    private final boolean looping;
    private final Format format;
    private final Consumer<Throwable> failureHandler;
    private float pitch, cutoff;
    private long state;
    private int callbackBuffer;
    private boolean closed;

    NiagaraWavePlayback(ByteBuffer pcm, int inputRate, int outputRate, int channels, int startFrame, boolean looping,
                        float pitch, float cutoff, Consumer<Throwable> failureHandler) {
        this.pcm = pcm;
        this.inputRate = inputRate;
        this.startFrame = startFrame;
        this.looping = looping;
        this.pitch = pitch;
        this.cutoff = cutoff;
        this.failureHandler = failureHandler;
        format = new Format(outputRate, channels);
    }

    public synchronized void parameters(float pitch, float cutoff) {
        if (this.pitch == pitch && this.cutoff == cutoff) return;
        this.pitch = pitch;
        this.cutoff = cutoff;
        if (state != 0) NativeWavePlayback.parameters(state, pitch, cutoff);
    }

    public synchronized boolean attachCallback(int source) throws IOException {
        if (!AL.getCapabilities().AL_SOFT_callback_buffer) return false;
        int audioFormat = openAlFormat(format);
        long handle = prepare();
        int buffer = AL10.alGenBuffers();
        requireNoError("allocate callback buffer");
        try {
            SOFTCallbackBuffer.nalBufferCallbackSOFT(buffer, audioFormat, (int)format.getSampleRate(),
                    NativeWavePlayback.callback(), handle);
            requireNoError("register callback buffer");
            AL10.alSourcei(source, AL10.AL_BUFFER, buffer);
            requireNoError("attach callback buffer");
        } catch (IOException | RuntimeException | LinkageError failure) {
            AL10.alDeleteBuffers(buffer);
            int error = AL10.alGetError();
            if (error != AL10.AL_NO_ERROR) failure.addSuppressed(error("delete unattached callback buffer", error));
            throw failure;
        }
        callbackBuffer = buffer;
        return true;
    }

    @Override public Format getFormat() { return format; }

    @Override public synchronized ByteBuffer read(int requested) throws IOException {
        if (closed) return null;
        int frameBytes = format.getFrameSize();
        // Channel pumps four buffers. Keep enough queued audio for a client tick without queuing seconds of old parameters.
        int frames = Math.min(Math.max(1, requested / frameBytes), Math.max(1, (int)format.getSampleRate() / 20));
        int bytes = frames * frameBytes;
        var output = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
        try {
            int written = NativeWavePlayback.render(prepare(), output, bytes);
            return written == 0 ? null : output.limit(written);
        } catch (IOException | RuntimeException | LinkageError failure) {
            failed(failure);
            throw new IOException("Could not render Niagara source audio", failure);
        }
    }

    public void failed(Throwable failure) { failureHandler.accept(failure); }

    /** Called after the owning OpenAL channel stops and detaches the callback. */
    @Override public synchronized void close() throws IOException {
        if (closed) return;
        if (callbackBuffer != 0) {
            AL10.alDeleteBuffers(callbackBuffer);
            requireNoError("delete detached callback buffer");
            callbackBuffer = 0;
        }
        if (state != 0) NativeWavePlayback.destroy(state);
        state = 0;
        closed = true;
    }

    private long prepare() throws IOException {
        if (closed) throw new IOException("Niagara source stream was closed");
        if (state == 0) {
            openAlFormat(format);
            state = NativeWavePlayback.create(pcm, inputRate, (int)format.getSampleRate(), format.getChannels(),
                    startFrame, looping, pitch, cutoff);
            if (state == 0) throw new IOException("Could not initialize Niagara source PCM");
        }
        return state;
    }

    public static int openAlFormat(Format format) {
        var capabilities = AL.getCapabilities();
        int channels = format.getChannels();
        if (channels <= 2) {
            if (!capabilities.AL_EXT_FLOAT32)
                throw new IllegalStateException("The active audio device lacks float PCM support for Niagara source mixing");
            return channels == 1 ? EXTFloat32.AL_FORMAT_MONO_FLOAT32 : EXTFloat32.AL_FORMAT_STEREO_FLOAT32;
        }
        if (!capabilities.AL_EXT_MCFORMATS)
            throw new IllegalStateException("The active audio device lacks surround PCM support for Niagara source mixing");
        return switch (channels) {
            case 4 -> EXTMCFormats.AL_FORMAT_QUAD32;
            case 6 -> EXTMCFormats.AL_FORMAT_51CHN32;
            case 8 -> EXTMCFormats.AL_FORMAT_71CHN32;
            default -> throw new IllegalArgumentException("Unsupported Niagara source channel count " + channels);
        };
    }

    private static void requireNoError(String action) throws IOException {
        int error = AL10.alGetError();
        if (error != AL10.AL_NO_ERROR) throw error(action, error);
    }

    private static IOException error(String action, int error) {
        return new IOException("Could not " + action + " for Niagara audio (OpenAL " + error + ")");
    }
}
