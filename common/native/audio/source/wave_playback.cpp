#include <jni.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <bit>
#include <cmath>
#include <cstdint>
#include <new>

namespace {
constexpr float pi = 3.1415926535897932f;
constexpr float maximumCutoff = 20000.0f;
constexpr float minimumPitchRatio = 0.0625f;
constexpr float maximumPitchRatio = 16.0f;
constexpr float pcmToFloat = 1.0f / 32767.0f;
constexpr float nearlyEqual = 0.0001f;

uint64_t pack(float pitch, float cutoff) noexcept {
    return uint64_t{std::bit_cast<uint32_t>(cutoff)} << 32 | std::bit_cast<uint32_t>(pitch);
}

struct WavePlayback {
    jobject retainedPcm;
    const int16_t* samples;
    int channels;
    int frames;
    int outputRate;
    float sampleRateRatio;
    bool looping;
    int position;
    float fraction = 0;
    float currentRatio = 1;
    float currentCutoff = -1;
    float coefficient = 0;
    std::array<float, 8> history{};
    std::atomic<uint64_t> parameters;
    bool first = true;

    WavePlayback(jobject storage, const int16_t* pcm, int inputRate, int outputRate, int channels, int frames, int start,
                 bool looping, float pitch, float cutoff) noexcept
        : retainedPcm(storage), samples(pcm), channels(channels), frames(frames), outputRate(outputRate),
          sampleRateRatio(static_cast<float>(inputRate) / static_cast<float>(outputRate)),
          looping(looping), position(start), parameters(pack(pitch, cutoff)) {}

    int render(float* destination, int requested) noexcept {
        uint64_t settings = parameters.load(std::memory_order_relaxed);
        float pitch = std::bit_cast<float>(static_cast<uint32_t>(settings));
        float cutoff = std::bit_cast<float>(static_cast<uint32_t>(settings >> 32));
        float targetRatio = std::clamp(pitch * sampleRateRatio, minimumPitchRatio, maximumPitchRatio);
        if (first) currentRatio = targetRatio;
        int ratioRamp = requested;
        float ratioDelta = (targetRatio - currentRatio) / static_cast<float>(requested);
        if (std::abs(targetRatio - currentRatio) < nearlyEqual) ratioRamp = 0;
        while (ratioRamp > 1 && std::abs(ratioDelta) < 0.000001f) {
            ratioRamp /= 2;
            ratioDelta = (targetRatio - currentRatio) / static_cast<float>(ratioRamp);
        }
        if (ratioRamp <= 1) {
            ratioRamp = 0;
            currentRatio = targetRatio;
        }

        float targetCoefficient = coefficient;
        if (std::abs(cutoff - currentCutoff) >= nearlyEqual) {
            currentCutoff = cutoff;
            const float normalized = std::clamp(2.0f * cutoff / static_cast<float>(outputRate), 0.0f, 1.0f);
            targetCoefficient = std::exp(-pi * normalized);
        }
        if (first || requested == 1) coefficient = targetCoefficient;
        const float filterDelta = (targetCoefficient - coefficient) / static_cast<float>(requested);
        const bool filter = currentCutoff < maximumCutoff - nearlyEqual;
        first = false;

        int written = 0;
        for (; written < requested && position < frames; ++written) {
            const int after = position + 1 < frames ? position + 1 : looping ? 0 : position;
            const int base = position * channels;
            const int next = after * channels;
            coefficient += filterDelta;
            for (int channel = 0; channel < channels; ++channel) {
                float a = samples[base + channel] * pcmToFloat;
                float b = samples[next + channel] * pcmToFloat;
                float value = a + (b - a) * fraction;
                if (filter) {
                    value += coefficient * (history[channel] - value);
                    history[channel] = value;
                }
                *destination++ = value;
            }
            if (written < ratioRamp) currentRatio += ratioDelta;
            else currentRatio = targetRatio;
            fraction += currentRatio;
            const int advanced = static_cast<int>(fraction);
            position += advanced;
            if (looping && position >= frames) position %= frames;
            fraction -= static_cast<float>(advanced);
        }
        currentRatio = targetRatio;
        coefficient = targetCoefficient;
        return written;
    }
};

static_assert(std::atomic<uint64_t>::is_always_lock_free);

// OpenAL Soft ALBUFFERCALLBACKTYPESOFT. All storage is retained by the attached voice.
int __cdecl renderCallback(void* user, void* buffer, int bytes) noexcept {
    auto& wave = *static_cast<WavePlayback*>(user);
    const int frameBytes = wave.channels * static_cast<int>(sizeof(float));
    if (bytes < frameBytes) return 0;
    return wave.render(static_cast<float*>(buffer), bytes / frameBytes) * frameBytes;
}

WavePlayback* voice(jlong handle) noexcept { return reinterpret_cast<WavePlayback*>(handle); }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_kltyton_lumenfx_client_audio_wave_NativeWavePlayback_create(
        JNIEnv* env, jclass, jobject pcm, jint inputRate, jint outputRate, jint channels, jint start, jboolean looping,
        jfloat pitch, jfloat cutoff) {
    if (channels < 1 || channels > 8 || inputRate <= 0 || outputRate <= 0 || !std::isfinite(pitch)
        || !std::isfinite(cutoff)) return 0;
    auto* samples = static_cast<int16_t*>(env->GetDirectBufferAddress(pcm));
    const jlong bytes = env->GetDirectBufferCapacity(pcm);
    const int frameBytes = channels * static_cast<int>(sizeof(int16_t));
    if (!samples || bytes < frameBytes || bytes > INT32_MAX || bytes % frameBytes != 0) return 0;
    const int frames = static_cast<int>(bytes / frameBytes);
    if (start < 0 || start > frames || (looping && start == frames)) return 0;
    jobject storage = env->NewGlobalRef(pcm);
    if (!storage) return 0;
    auto* playback = new (std::nothrow)
        WavePlayback(storage, samples, inputRate, outputRate, channels, frames, start, looping, pitch, cutoff);
    if (!playback) env->DeleteGlobalRef(storage);
    return reinterpret_cast<jlong>(playback);
}

extern "C" JNIEXPORT void JNICALL
Java_com_kltyton_lumenfx_client_audio_wave_NativeWavePlayback_parameters(
        JNIEnv*, jclass, jlong handle, jfloat pitch, jfloat cutoff) {
    if (std::isfinite(pitch) && std::isfinite(cutoff))
        voice(handle)->parameters.store(pack(pitch, cutoff), std::memory_order_relaxed);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_kltyton_lumenfx_client_audio_wave_NativeWavePlayback_callback(JNIEnv*, jclass) {
    return reinterpret_cast<jlong>(&renderCallback);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_kltyton_lumenfx_client_audio_wave_NativeWavePlayback_render(
        JNIEnv* env, jclass, jlong handle, jobject output, jint bytes) {
    auto* destination = env->GetDirectBufferAddress(output);
    const auto capacity = env->GetDirectBufferCapacity(output);
    if (!destination || bytes < 0 || bytes > capacity) return 0;
    return renderCallback(voice(handle), destination, bytes);
}

extern "C" JNIEXPORT void JNICALL
Java_com_kltyton_lumenfx_client_audio_wave_NativeWavePlayback_destroy(JNIEnv* env, jclass, jlong handle) {
    auto* playback = voice(handle);
    env->DeleteGlobalRef(playback->retainedPcm);
    delete playback;
}
