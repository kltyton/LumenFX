package com.kltyton.lumenfx.runtime.audio;

import com.kltyton.lumenfx.runtime.bridge.McResourceBridge;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nullable;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC10;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * Native 3D spatial audio playback engine using Minecraft's active OpenAL context ({@link AL10})
 * and LWJGL {@link STBVorbis} / PCM WAV decoding. Works identically across 1.20.1, 1.21.1, and 26.1.2.
 */
public final class DirectSpatialAudioEngine {
    public record SoundCueSpec(String soundId, float volume, float pitch,
                               float referenceDistance, float maxDistance, float rolloff,
                               float delaySeconds, boolean looping, boolean spatialized) { }

    private static final class ActiveVoice {
        final int sourceId;
        final boolean looping;
        boolean stopped;

        ActiveVoice(int sourceId, boolean looping) {
            this.sourceId = sourceId;
            this.looping = looping;
        }
    }

    private final Map<String, Integer> bufferCache = new HashMap<>();
    private final List<ActiveVoice> activeVoices = new ArrayList<>();
    private final int maxVoices = 64;

    public boolean isAudioContextReady() {
        try {
            return ALC10.alcGetCurrentContext() != 0L;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public int play(SoundCueSpec cue, double x, double y, double z,
                    double vx, double vy, double vz, @Nullable McResourceBridge bridge) {
        if (!isAudioContextReady() || cue.soundId() == null || cue.soundId().isBlank()) return 0;
        reapFinishedVoices();
        if (activeVoices.size() >= maxVoices) return 0;
        int buffer = resolveBuffer(cue.soundId(), bridge);
        if (buffer == 0) return 0;

        int source = AL10.alGenSources();
        if (source == 0) return 0;
        AL10.alSourcei(source, AL10.AL_BUFFER, buffer);
        AL10.alSourcef(source, AL10.AL_GAIN, Math.max(0f, cue.volume()));
        AL10.alSourcef(source, AL10.AL_PITCH, Math.max(0.1f, Math.min(4.0f, cue.pitch())));
        AL10.alSourcei(source, AL10.AL_LOOPING, cue.looping() ? AL10.AL_TRUE : AL10.AL_FALSE);
        if (cue.spatialized()) {
            AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_FALSE);
            AL10.alSource3f(source, AL10.AL_POSITION, (float) x, (float) y, (float) z);
            AL10.alSource3f(source, AL10.AL_VELOCITY, (float) vx, (float) vy, (float) vz);
            AL10.alSourcef(source, AL10.AL_REFERENCE_DISTANCE, Math.max(0.5f, cue.referenceDistance()));
            AL10.alSourcef(source, AL10.AL_MAX_DISTANCE, Math.max(4.0f, cue.maxDistance()));
            AL10.alSourcef(source, AL10.AL_ROLLOFF_FACTOR, Math.max(0f, cue.rolloff()));
        } else {
            AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
            AL10.alSource3f(source, AL10.AL_POSITION, 0f, 0f, 0f);
        }
        AL10.alSourcePlay(source);
        activeVoices.add(new ActiveVoice(source, cue.looping()));
        return source;
    }

    public void updateVoice(int sourceId, double x, double y, double z, double vx, double vy, double vz, float volume) {
        if (sourceId == 0 || !isAudioContextReady()) return;
        AL10.alSource3f(sourceId, AL10.AL_POSITION, (float) x, (float) y, (float) z);
        AL10.alSource3f(sourceId, AL10.AL_VELOCITY, (float) vx, (float) vy, (float) vz);
        AL10.alSourcef(sourceId, AL10.AL_GAIN, Math.max(0f, volume));
    }

    public void stopVoice(int sourceId) {
        if (sourceId == 0 || !isAudioContextReady()) return;
        for (Iterator<ActiveVoice> it = activeVoices.iterator(); it.hasNext(); ) {
            ActiveVoice v = it.next();
            if (v.sourceId == sourceId) {
                AL10.alSourceStop(sourceId);
                AL10.alDeleteSources(sourceId);
                v.stopped = true;
                it.remove();
                return;
            }
        }
    }

    public void reapFinishedVoices() {
        if (!isAudioContextReady()) return;
        for (Iterator<ActiveVoice> it = activeVoices.iterator(); it.hasNext(); ) {
            ActiveVoice v = it.next();
            int state = AL10.alGetSourcei(v.sourceId, AL10.AL_SOURCE_STATE);
            if (state == AL10.AL_STOPPED || v.stopped) {
                AL10.alDeleteSources(v.sourceId);
                it.remove();
            }
        }
    }

    public int activeVoiceCount() {
        return activeVoices.size();
    }

    private int resolveBuffer(String rawId, @Nullable McResourceBridge bridge) {
        Integer existing = bufferCache.get(rawId);
        if (existing != null) return existing;
        if (bridge == null) return 0;

        byte[] bytes = loadSoundBytes(rawId, bridge);
        if (bytes == null || bytes.length < 12) {
            bufferCache.put(rawId, 0);
            return 0;
        }
        int buf = decodeToOpenAlBuffer(bytes);
        bufferCache.put(rawId, buf);
        return buf;
    }

    private static @Nullable byte[] loadSoundBytes(String rawId, McResourceBridge bridge) {
        String id = rawId.contains(":") ? rawId : ("lumenfx:" + rawId);
        int colon = id.indexOf(':');
        String ns = id.substring(0, colon);
        String path = id.substring(colon + 1);
        byte[] direct = bridge.readResource(id);
        if (direct != null) return direct;
        String base = path.startsWith("sounds/") ? path : ("sounds/" + path);
        if (!base.endsWith(".ogg") && !base.endsWith(".wav")) {
            byte[] ogg = bridge.readResource(ns + ":" + base + ".ogg");
            if (ogg != null) return ogg;
            return bridge.readResource(ns + ":" + base + ".wav");
        }
        return bridge.readResource(ns + ":" + base);
    }

    /** Decodes OGG Vorbis (via STBVorbis) or PCM WAV bytes into an OpenAL buffer handle. */
    public static int decodeToOpenAlBuffer(byte[] bytes) {
        if (bytes.length >= 4 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F') {
            return decodeWav(bytes);
        }
        return decodeOgg(bytes);
    }

    /** Pure-Java WAV header validator usable in headless tests without an OpenAL context. */
    public static @Nullable DecodedPcm parseWavPcm(byte[] bytes) {
        if (bytes == null || bytes.length < 44) return null;
        ByteBuffer bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (bb.getInt(0) != 0x46464952 || bb.getInt(8) != 0x45564157) return null; // RIFF / WAVE
        int pos = 12;
        int channels = 0, sampleRate = 0, bitsPerSample = 0;
        int dataOffset = -1, dataLength = 0;
        while (pos + 8 <= bytes.length) {
            int chunkId = bb.getInt(pos);
            int chunkSize = bb.getInt(pos + 4);
            int payload = pos + 8;
            if (chunkSize < 0 || payload + chunkSize > bytes.length) break;
            if (chunkId == 0x20746d66 && chunkSize >= 16) { // "fmt "
                int audioFormat = bb.getShort(payload) & 0xFFFF;
                if (audioFormat != 1) return null; // PCM only
                channels = bb.getShort(payload + 2) & 0xFFFF;
                sampleRate = bb.getInt(payload + 4);
                bitsPerSample = bb.getShort(payload + 14) & 0xFFFF;
            } else if (chunkId == 0x61746164) { // "data"
                dataOffset = payload;
                dataLength = chunkSize;
                break;
            }
            pos = payload + chunkSize + (chunkSize & 1);
        }
        if (dataOffset < 0 || channels < 1 || channels > 2 || sampleRate <= 0
                || (bitsPerSample != 8 && bitsPerSample != 16)) {
            return null;
        }
        byte[] pcm = new byte[dataLength];
        System.arraycopy(bytes, dataOffset, pcm, 0, dataLength);
        return new DecodedPcm(channels, sampleRate, bitsPerSample, pcm);
    }

    public record DecodedPcm(int channels, int sampleRate, int bitsPerSample, byte[] pcm) { }

    private static int decodeWav(byte[] bytes) {
        DecodedPcm decoded = parseWavPcm(bytes);
        if (decoded == null) return 0;
        int alFormat = decoded.channels() == 1
                ? (decoded.bitsPerSample() == 8 ? AL10.AL_FORMAT_MONO8 : AL10.AL_FORMAT_MONO16)
                : (decoded.bitsPerSample() == 8 ? AL10.AL_FORMAT_STEREO8 : AL10.AL_FORMAT_STEREO16);
        int buf = AL10.alGenBuffers();
        ByteBuffer nativeData = MemoryUtil.memAlloc(decoded.pcm().length);
        try {
            nativeData.put(decoded.pcm()).flip();
            AL10.alBufferData(buf, alFormat, nativeData, decoded.sampleRate());
        } finally {
            MemoryUtil.memFree(nativeData);
        }
        return buf;
    }

    private static int decodeOgg(byte[] bytes) {
        ByteBuffer encoded = MemoryUtil.memAlloc(bytes.length);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            encoded.put(bytes).flip();
            IntBuffer chBuf = stack.mallocInt(1);
            IntBuffer rateBuf = stack.mallocInt(1);
            ShortBuffer pcm = STBVorbis.stb_vorbis_decode_memory(encoded, chBuf, rateBuf);
            if (pcm == null) return 0;
            try {
                int channels = chBuf.get(0);
                int rate = rateBuf.get(0);
                int alFormat = channels == 1 ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16;
                int buf = AL10.alGenBuffers();
                AL10.alBufferData(buf, alFormat, pcm, rate);
                return buf;
            } finally {
                MemoryUtil.memFree(pcm);
            }
        } catch (Throwable ignored) {
            return 0;
        } finally {
            MemoryUtil.memFree(encoded);
        }
    }

    public void stopAll() {
        if (isAudioContextReady()) {
            for (ActiveVoice v : activeVoices) {
                AL10.alSourceStop(v.sourceId);
                AL10.alDeleteSources(v.sourceId);
            }
        }
        activeVoices.clear();
    }

    public void dispose() {
        stopAll();
        if (isAudioContextReady()) {
            for (int buf : bufferCache.values()) {
                if (buf != 0) AL10.alDeleteBuffers(buf);
            }
        }
        bufferCache.clear();
    }

    public static String canonicalSoundId(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return s.contains(":") ? s : ("lumenfx:" + s);
    }
}
