#pragma once

#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <winsock2.h>
#include <windows.h>
#include <mmdeviceapi.h>
#include <audioclient.h>
#include <avrt.h>
#include <cstdint>
#include <vector>
#include <functional>
#include <atomic>
#include <thread>
#include <string>

namespace zenith {

class WasapiCapture {
public:
    using AudioCallback = std::function<void(const int16_t *pcm, size_t frames, uint64_t timestamp_us)>;

    WasapiCapture();
    ~WasapiCapture();

    bool start(AudioCallback callback);
    void stop();

    uint32_t get_sample_rate() const { return sample_rate_; }
    uint16_t get_channels() const { return channels_; }
    bool is_running() const { return is_running_.load(); }
    std::string get_device_name() const { return device_name_; }

private:
    void capture_thread_main();

    AudioCallback callback_;
    std::atomic<bool> is_running_{false};
    std::thread capture_thread_;

    IMMDeviceEnumerator *enumerator_ = nullptr;
    IMMDevice *device_ = nullptr;
    IAudioClient *audio_client_ = nullptr;
    IAudioCaptureClient *capture_client_ = nullptr;
    HANDLE audio_event_ = nullptr;

    WAVEFORMATEX *wave_format_ = nullptr;
    uint32_t sample_rate_ = 48000;
    uint16_t channels_ = 2;
    uint16_t bits_per_sample_ = 16;
    bool is_float_ = false;
    std::string device_name_ = "Default Output Device";

    std::vector<int16_t> conversion_buffer_;
};

} // namespace zenith
