#pragma once

#include "audio_interfaces.hpp"
#include "ring_buffer.hpp"
#include <memory>
#include <thread>
#include <atomic>
#include <vector>

namespace zenith {

class AudioEngine {
public:
    AudioEngine(std::unique_ptr<IAudioSource> source,
                std::unique_ptr<IAudioEncoder> encoder,
                std::unique_ptr<IAudioTransport> transport);
    ~AudioEngine();

    bool start(uint16_t port, int initial_bitrate_bps = 320000);
    void stop();

    void set_bitrate(int bitrate_bps);
    int get_bitrate() const;

    TransportStats get_transport_stats() const;
    bool is_running() const { return is_running_.load(); }

private:
    void audio_worker_thread();
    void stats_worker_thread();

    std::unique_ptr<IAudioSource> source_;
    std::unique_ptr<IAudioEncoder> encoder_;
    std::unique_ptr<IAudioTransport> transport_;

    // Ring buffer holding 16384 float samples (~170ms maximum, normal occupancy < 10ms)
    static constexpr size_t RING_BUFFER_CAPACITY = 16384;
    SpscRingBuffer<float, RING_BUFFER_CAPACITY> ring_buffer_;

    std::atomic<bool> is_running_{false};
    std::thread worker_thread_;
    std::thread stats_thread_;

    AudioFormat format_;
    std::atomic<uint64_t> last_capture_time_us_{0};
    std::atomic<uint32_t> frames_encoded_{0};
};

} // namespace zenith
