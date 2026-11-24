#define NOMINMAX
#define _WIN32_WINNT 0x0A00
#define NTDDI_VERSION 0x0A00000A
#include <windows.h>
#include <initguid.h>
#include <audioclient.h>
#include <audioclientactivationparams.h>
#include <mmdeviceapi.h>
#include <mmreg.h>
#include <ks.h>
#include <ksmedia.h>
#include <wrl.h>
#include <jni.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <exception>
#include <memory>
#include <mutex>
#include <thread>
#include <unordered_map>
#include <vector>

namespace lumenfx::audio {
using Microsoft::WRL::ComPtr;
struct Capture {
    HANDLE stop = nullptr;
    HANDLE ready = nullptr;
    HANDLE activated = nullptr;
    const UINT32 sample_rate;
    const UINT16 channels;
    const DWORD channel_mask;
    ComPtr<IAudioClient> client;
    std::atomic<HRESULT> activation_result{E_PENDING};
    std::atomic<HRESULT> result{E_PENDING};
    std::mutex samples_mutex;
    std::vector<float> samples;
    size_t write_index = 0;
    size_t available = 0;
    uint64_t sequence = 0;
    uint64_t end_frame = 0;
    uint64_t discontinuities = 0;
    std::chrono::steady_clock::time_point last_packet{};

    Capture(size_t frames, UINT32 rate, UINT16 count, DWORD mask)
            : sample_rate(rate), channels(count), channel_mask(mask), samples(frames * count) {
        stop = CreateEventW(nullptr, TRUE, FALSE, nullptr);
        ready = CreateEventW(nullptr, FALSE, FALSE, nullptr);
        activated = CreateEventW(nullptr, TRUE, FALSE, nullptr);
    }
    ~Capture() {
        if (stop) CloseHandle(stop);
        if (ready) CloseHandle(ready);
        if (activated) CloseHandle(activated);
    }

    void reserve(size_t frames) {
        std::lock_guard lock(samples_mutex);
        size_t count = frames * channels;
        if (count <= samples.size()) return;
        std::vector<float> replacement(count);
        size_t first = (write_index + samples.size() - available) % samples.size();
        for (size_t index = 0; index < available; ++index)
            replacement[index] = samples[(first + index) % samples.size()];
        samples.swap(replacement);
        write_index = available;
    }

    void append(const float* source, size_t frames, bool silent, bool discontinuity) {
        std::lock_guard lock(samples_mutex);
        if (discontinuity) {
            available = 0;
            ++discontinuities;
        }
        end_frame += frames;
        size_t count = frames * channels;
        size_t skip = count > samples.size() ? count - samples.size() : 0;
        for (size_t index = skip; index < count; ++index) {
            samples[write_index] = silent ? 0.0f : source[index];
            write_index = (write_index + 1) % samples.size();
        }
        available = std::min(samples.size(), available + count - skip);
        last_packet = std::chrono::steady_clock::now();
        ++sequence;
    }
};

// The completion object outlives a canceled capture until Windows returns its activation.
class Activation final : public Microsoft::WRL::RuntimeClass<
        Microsoft::WRL::RuntimeClassFlags<Microsoft::WRL::ClassicCom>,
        Microsoft::WRL::FtmBase, IActivateAudioInterfaceCompletionHandler> {
    std::shared_ptr<Capture> capture;
public:
    explicit Activation(std::shared_ptr<Capture> value) : capture(std::move(value)) {}
    HRESULT STDMETHODCALLTYPE ActivateCompleted(IActivateAudioInterfaceAsyncOperation* operation) override {
        HRESULT activated_result = E_UNEXPECTED;
        ComPtr<IUnknown> activated_client;
        HRESULT status = operation->GetActivateResult(&activated_result, &activated_client);
        if (SUCCEEDED(status)) status = activated_result;
        if (SUCCEEDED(status)) status = activated_client.As(&capture->client);
        capture->activation_result.store(status, std::memory_order_release);
        SetEvent(capture->activated);
        return S_OK;
    }
};

HRESULT drain(Capture& capture, IAudioCaptureClient& input) {
    UINT32 pending = 0;
    HRESULT status = input.GetNextPacketSize(&pending);
    while (SUCCEEDED(status) && pending != 0) {
        BYTE* data = nullptr;
        UINT32 frames = 0;
        DWORD flags = 0;
        status = input.GetBuffer(&data, &frames, &flags, nullptr, nullptr);
        if (FAILED(status)) return status;
        try {
            if (frames != 0) capture.append(reinterpret_cast<const float*>(data), frames,
                    (flags & AUDCLNT_BUFFERFLAGS_SILENT) != 0,
                    (flags & AUDCLNT_BUFFERFLAGS_DATA_DISCONTINUITY) != 0);
        } catch (...) {
            input.ReleaseBuffer(frames);
            throw;
        }
        status = input.ReleaseBuffer(frames);
        if (FAILED(status)) return status;
        status = input.GetNextPacketSize(&pending);
    }
    return status;
}

HRESULT run(const std::shared_ptr<Capture>& capture) {
    auto callback = Microsoft::WRL::Make<Activation>(capture);
    if (!callback) return E_OUTOFMEMORY;
    AUDIOCLIENT_ACTIVATION_PARAMS parameters{};
    parameters.ActivationType = AUDIOCLIENT_ACTIVATION_TYPE_PROCESS_LOOPBACK;
    parameters.ProcessLoopbackParams.TargetProcessId = GetCurrentProcessId();
    parameters.ProcessLoopbackParams.ProcessLoopbackMode = PROCESS_LOOPBACK_MODE_INCLUDE_TARGET_PROCESS_TREE;
    PROPVARIANT activation{};
    activation.vt = VT_BLOB;
    activation.blob.cbSize = sizeof(parameters);
    activation.blob.pBlobData = reinterpret_cast<BYTE*>(&parameters);
    ComPtr<IActivateAudioInterfaceAsyncOperation> operation;
    HRESULT status = ActivateAudioInterfaceAsync(VIRTUAL_AUDIO_DEVICE_PROCESS_LOOPBACK, __uuidof(IAudioClient),
            &activation, callback.Get(), &operation);
    if (FAILED(status)) return status;
    HANDLE activation_events[] = {capture->stop, capture->activated};
    DWORD event = WaitForMultipleObjects(2, activation_events, FALSE, INFINITE);
    if (event == WAIT_OBJECT_0) return S_FALSE;
    if (event != WAIT_OBJECT_0 + 1) return HRESULT_FROM_WIN32(GetLastError());
    status = capture->activation_result.load(std::memory_order_acquire);
    if (FAILED(status)) return status;
    auto client = std::move(capture->client);
    WAVEFORMATEXTENSIBLE format{};
    format.Format.wFormatTag = WAVE_FORMAT_EXTENSIBLE;
    format.Format.nChannels = capture->channels;
    format.Format.nSamplesPerSec = capture->sample_rate;
    format.Format.wBitsPerSample = sizeof(float) * 8;
    format.Format.nBlockAlign = capture->channels * sizeof(float);
    format.Format.nAvgBytesPerSec = capture->sample_rate * format.Format.nBlockAlign;
    format.Format.cbSize = sizeof(WAVEFORMATEXTENSIBLE) - sizeof(WAVEFORMATEX);
    format.Samples.wValidBitsPerSample = sizeof(float) * 8;
    format.dwChannelMask = capture->channel_mask;
    format.SubFormat = KSDATAFORMAT_SUBTYPE_IEEE_FLOAT;
    status = client->Initialize(AUDCLNT_SHAREMODE_SHARED,
            AUDCLNT_STREAMFLAGS_LOOPBACK | AUDCLNT_STREAMFLAGS_EVENTCALLBACK |
            AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM | AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY,
            0, 0, &format.Format, nullptr);
    if (FAILED(status)) return status;
    ComPtr<IAudioCaptureClient> input;
    status = client->GetService(IID_PPV_ARGS(&input));
    if (FAILED(status)) return status;
    status = client->SetEventHandle(capture->ready);
    if (FAILED(status)) return status;
    status = client->Start();
    if (FAILED(status)) return status;
    capture->result.store(S_OK, std::memory_order_release);
    HANDLE events[] = {capture->stop, capture->ready};
    for (;;) {
        event = WaitForMultipleObjects(2, events, FALSE, INFINITE);
        if (event == WAIT_OBJECT_0) break;
        if (event != WAIT_OBJECT_0 + 1) {
            status = HRESULT_FROM_WIN32(GetLastError());
            break;
        }
        status = drain(*capture, *input.Get());
        if (FAILED(status)) break;
    }
    HRESULT stopped = client->Stop();
    return FAILED(status) ? status : stopped;
}

void worker(std::shared_ptr<Capture> capture) {
    HRESULT initialized = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    if (FAILED(initialized)) {
        capture->result.store(initialized, std::memory_order_release);
        return;
    }
    try { capture->result.store(run(capture), std::memory_order_release); }
    catch (const std::bad_alloc&) { capture->result.store(E_OUTOFMEMORY, std::memory_order_release); }
    catch (...) { capture->result.store(E_UNEXPECTED, std::memory_order_release); }
    CoUninitialize();
}

std::mutex captures_mutex;
std::unordered_map<jlong, std::shared_ptr<Capture>> captures;
jlong next_capture = 0;

std::shared_ptr<Capture> find(jlong handle) {
    std::lock_guard lock(captures_mutex);
    auto found = captures.find(handle);
    return found == captures.end() ? nullptr : found->second;
}

void fail(JNIEnv* env, const char* message) {
    auto type = env->FindClass("java/lang/IllegalStateException");
    if (type) env->ThrowNew(type, message);
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_kltyton_lumenfx_client_audio_capture_WindowsProcessAudio_open(JNIEnv* env, jclass, jint frames,
        jint sample_rate, jint channels, jint channel_mask) {
    using namespace lumenfx::audio;
    if (sample_rate < 1 || channels < 1 || channels > 8 || frames < 1 || frames > INT32_MAX / channels) {
        fail(env, "Invalid LumenFX master audio window size");
        return 0;
    }
    try {
        // Process activation can complete after its caller has canceled the capture.
        static int module_anchor;
        HMODULE module = nullptr;
        if (!GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_PIN,
                reinterpret_cast<LPCWSTR>(&module_anchor), &module)) {
            fail(env, "Unable to retain the LumenFX asynchronous audio callback library");
            return 0;
        }
        auto capture = std::make_shared<Capture>(static_cast<size_t>(frames), static_cast<UINT32>(sample_rate),
                static_cast<UINT16>(channels), static_cast<DWORD>(channel_mask));
        if (!capture->stop || !capture->ready || !capture->activated) {
            fail(env, "Unable to create LumenFX master audio events");
            return 0;
        }
        jlong handle;
        {
            std::lock_guard lock(captures_mutex);
            if (next_capture == INT64_MAX) {
                fail(env, "LumenFX master audio handle range is exhausted");
                return 0;
            }
            handle = ++next_capture;
            captures.emplace(handle, capture);
        }
        try {
            std::thread thread(worker, capture);
            try { thread.detach(); }
            catch (...) {
                SetEvent(capture->stop);
                if (thread.joinable()) thread.join();
                throw;
            }
        }
        catch (...) {
            std::lock_guard lock(captures_mutex);
            captures.erase(handle);
            throw;
        }
        return handle;
    } catch (const std::exception& error) { fail(env, error.what()); }
    return 0;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_kltyton_lumenfx_client_audio_capture_WindowsProcessAudio_read(JNIEnv* env, jclass, jlong handle,
        jint frames, jlongArray metadata) {
    using namespace lumenfx::audio;
    auto capture = find(handle);
    if (!capture || !metadata || env->GetArrayLength(metadata) != 3 || frames < 1 || frames > INT32_MAX / capture->channels) {
        fail(env, "Invalid LumenFX master audio snapshot request");
        return nullptr;
    }
    HRESULT status = capture->result.load(std::memory_order_acquire);
    if (status == E_PENDING) return nullptr;
    if (FAILED(status)) {
        char message[96];
        std::snprintf(message, sizeof(message), "LumenFX process audio capture failed with HRESULT 0x%08lX",
                static_cast<unsigned long>(status));
        fail(env, message);
        return nullptr;
    }
    try {
        capture->reserve(static_cast<size_t>(frames));
        jlong previous = 0;
        env->GetLongArrayRegion(metadata, 0, 1, &previous);
        if (env->ExceptionCheck()) return nullptr;
        std::vector<float> snapshot;
        jlong publication[3];
        {
            std::lock_guard lock(capture->samples_mutex);
            bool stale = capture->available != 0 && std::chrono::steady_clock::now() - capture->last_packet
                    > std::chrono::duration<double>(static_cast<double>(frames) / capture->sample_rate);
            publication[0] = static_cast<jlong>(capture->sequence);
            if (stale) publication[0] = -publication[0] - 1;
            if (publication[0] == previous) return nullptr;
            publication[1] = static_cast<jlong>(capture->end_frame);
            publication[2] = static_cast<jlong>(capture->discontinuities);
            if (!stale) {
                size_t count = std::min(capture->available, static_cast<size_t>(frames) * capture->channels);
                snapshot.resize(count);
                size_t first = (capture->write_index + capture->samples.size() - count) % capture->samples.size();
                for (size_t index = 0; index < count; ++index)
                    snapshot[index] = capture->samples[(first + index) % capture->samples.size()];
            }
        }
        auto output = env->NewFloatArray(static_cast<jsize>(snapshot.size()));
        if (!output) return nullptr;
        if (!snapshot.empty()) env->SetFloatArrayRegion(output, 0, static_cast<jsize>(snapshot.size()), snapshot.data());
        if (!env->ExceptionCheck()) env->SetLongArrayRegion(metadata, 0, 3, publication);
        return output;
    } catch (const std::exception& error) { fail(env, error.what()); }
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_kltyton_lumenfx_client_audio_capture_WindowsProcessAudio_close(JNIEnv*, jclass, jlong handle) {
    using namespace lumenfx::audio;
    std::shared_ptr<Capture> capture;
    {
        std::lock_guard lock(captures_mutex);
        auto found = captures.find(handle);
        if (found == captures.end()) return;
        capture = std::move(found->second);
        captures.erase(found);
    }
    SetEvent(capture->stop);
}
