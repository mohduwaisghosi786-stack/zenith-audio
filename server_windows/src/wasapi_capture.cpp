#include "wasapi_capture.hpp"
#include <iostream>
#include <chrono>
#include <cmath>
#include <algorithm>

#pragma comment(lib, "ole32.lib")
#pragma comment(lib, "avrt.lib")

namespace zenith {

static uint64_t get_now_us() {
    auto now = std::chrono::steady_clock::now();
    return std::chrono::duration_cast<std::chrono::microseconds>(now.time_since_epoch()).count();
}

WasapiCapture::WasapiCapture() = default;

WasapiCapture::~WasapiCapture() {
    stop();
}

bool WasapiCapture::start(AudioCallback callback) {
    if (is_running_.load()) return false;
    callback_ = callback;

    HRESULT hr = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    if (FAILED(hr) && hr != RPC_E_CHANGED_MODE) {
        std::cerr << "[WASAPI] CoInitializeEx failed: " << hr << "\n";
        return false;
    }

    hr = CoCreateInstance(
        __uuidof(MMDeviceEnumerator),
        nullptr,
        CLSCTX_ALL,
        __uuidof(IMMDeviceEnumerator),
        reinterpret_cast<void**>(&enumerator_)
    );
    if (FAILED(hr) || !enumerator_) {
        std::cerr << "[WASAPI] CoCreateInstance(MMDeviceEnumerator) failed: " << hr << "\n";
        return false;
    }

    hr = enumerator_->GetDefaultAudioEndpoint(eRender, eConsole, &device_);
    if (FAILED(hr) || !device_) {
        std::cerr << "[WASAPI] GetDefaultAudioEndpoint failed: " << hr << "\n";
        return false;
    }

    hr = device_->Activate(
        __uuidof(IAudioClient),
        CLSCTX_ALL,
        nullptr,
        reinterpret_cast<void**>(&audio_client_)
    );
    if (FAILED(hr) || !audio_client_) {
        std::cerr << "[WASAPI] IAudioClient activation failed: " << hr << "\n";
        return false;
    }

    hr = audio_client_->GetMixFormat(&wave_format_);
    if (FAILED(hr) || !wave_format_) {
        std::cerr << "[WASAPI] GetMixFormat failed: " << hr << "\n";
        return false;
    }

    sample_rate_ = wave_format_->nSamplesPerSec;
    channels_ = wave_format_->nChannels;
    bits_per_sample_ = wave_format_->wBitsPerSample;

    if (wave_format_->wFormatTag == WAVE_FORMAT_IEEE_FLOAT) {
        is_float_ = true;
    } else if (wave_format_->wFormatTag == WAVE_FORMAT_EXTENSIBLE) {
        auto *ext = reinterpret_cast<WAVEFORMATEXTENSIBLE*>(wave_format_);
        if (ext->SubFormat == KSDATAFORMAT_SUBTYPE_IEEE_FLOAT) {
            is_float_ = true;
        }
    }

    std::cout << "[WASAPI] Loopback format: " << sample_rate_ << " Hz, "
              << channels_ << " channels, " << bits_per_sample_ << "-bit "
              << (is_float_ ? "(Float)" : "(PCM)") << "\n";

    // 10ms buffer size in 100-nanosecond units (REFERENCE_TIME)
    REFERENCE_TIME buffer_duration = 100000; // 10ms
    hr = audio_client_->Initialize(
        AUDCLNT_SHAREMODE_SHARED,
        AUDCLNT_STREAMFLAGS_LOOPBACK | AUDCLNT_STREAMFLAGS_EVENTCALLBACK,
        buffer_duration,
        0,
        wave_format_,
        nullptr
    );
    if (FAILED(hr)) {
        std::cerr << "[WASAPI] AudioClient Initialize failed: " << hr << "\n";
        return false;
    }

    audio_event_ = CreateEvent(nullptr, FALSE, FALSE, nullptr);
    if (!audio_event_) {
        std::cerr << "[WASAPI] Failed to create audio event handle\n";
        return false;
    }

    hr = audio_client_->SetEventHandle(audio_event_);
    if (FAILED(hr)) {
        std::cerr << "[WASAPI] SetEventHandle failed: " << hr << "\n";
        return false;
    }

    hr = audio_client_->GetService(
        __uuidof(IAudioCaptureClient),
        reinterpret_cast<void**>(&capture_client_)
    );
    if (FAILED(hr) || !capture_client_) {
        std::cerr << "[WASAPI] GetService(IAudioCaptureClient) failed: " << hr << "\n";
        return false;
    }

    hr = audio_client_->Start();
    if (FAILED(hr)) {
        std::cerr << "[WASAPI] AudioClient Start failed: " << hr << "\n";
        return false;
    }

    is_running_.store(true);
    capture_thread_ = std::thread(&WasapiCapture::capture_thread_main, this);
    std::cout << "[WASAPI] Windows 11 WASAPI Loopback Capture active.\n";
    return true;
}

void WasapiCapture::stop() {
    if (!is_running_.load()) return;
    is_running_.store(false);

    if (audio_event_) {
        SetEvent(audio_event_);
    }

    if (capture_thread_.joinable()) {
        capture_thread_.join();
    }

    if (audio_client_) {
        audio_client_->Stop();
        audio_client_->Release();
        audio_client_ = nullptr;
    }

    if (capture_client_) {
        capture_client_->Release();
        capture_client_ = nullptr;
    }

    if (device_) {
        device_->Release();
        device_ = nullptr;
    }

    if (enumerator_) {
        enumerator_->Release();
        enumerator_ = nullptr;
    }

    if (audio_event_) {
        CloseHandle(audio_event_);
        audio_event_ = nullptr;
    }

    if (wave_format_) {
        CoTaskMemFree(wave_format_);
        wave_format_ = nullptr;
    }

    CoUninitialize();
    std::cout << "[WASAPI] Windows 11 WASAPI Loopback Capture stopped.\n";
}

void WasapiCapture::capture_thread_main() {
    DWORD task_index = 0;
    HANDLE mm_handle = AvSetMmThreadCharacteristicsW(L"Pro Audio", &task_index);
    if (!mm_handle) {
        mm_handle = AvSetMmThreadCharacteristicsW(L"Audio", &task_index);
    }

    UINT32 packet_length = 0;
    BYTE *p_data = nullptr;
    UINT32 num_frames = 0;
    DWORD flags = 0;

    while (is_running_.load()) {
        DWORD wait_result = WaitForSingleObject(audio_event_, 100);
        if (wait_result != WAIT_OBJECT_0) {
            if (!is_running_.load()) break;
            continue;
        }

        while (is_running_.load()) {
            HRESULT hr = capture_client_->GetNextPacketSize(&packet_length);
            if (FAILED(hr) || packet_length == 0) break;

            hr = capture_client_->GetBuffer(&p_data, &num_frames, &flags, nullptr, nullptr);
            if (FAILED(hr)) break;

            uint64_t timestamp_us = get_now_us();
            size_t total_samples = num_frames * channels_;
            conversion_buffer_.resize(total_samples);

            if (flags & AUDCLNT_BUFFERFLAGS_SILENT) {
                std::fill(conversion_buffer_.begin(), conversion_buffer_.end(), 0);
            } else if (is_float_) {
                const float *f_samples = reinterpret_cast<const float*>(p_data);
                for (size_t i = 0; i < total_samples; ++i) {
                    float val = std::clamp(f_samples[i], -1.0f, 1.0f);
                    conversion_buffer_[i] = static_cast<int16_t>(val * 32767.0f);
                }
            } else if (bits_per_sample_ == 16) {
                const int16_t *s_samples = reinterpret_cast<const int16_t*>(p_data);
                std::copy(s_samples, s_samples + total_samples, conversion_buffer_.begin());
            }

            if (callback_ && num_frames > 0) {
                callback_(conversion_buffer_.data(), num_frames, timestamp_us);
            }

            capture_client_->ReleaseBuffer(num_frames);
        }
    }

    if (mm_handle) {
        AvRevertMmThreadCharacteristics(mm_handle);
    }
}

} // namespace zenith
