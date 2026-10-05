#pragma once

#include "audio_interfaces.hpp"
#include <opus/opus.h>
#include <atomic>
#include <mutex>

namespace zenith {

class OpusAudioEncoder : public IAudioEncoder {
public:
    OpusAudioEncoder();
    ~OpusAudioEncoder() override;

    bool init(const AudioFormat &format, int target_bitrate_bps) override;
    int encode(const float *pcm_interleaved, int frame_samples, uint8_t *out_buffer, int max_bytes) override;
    void set_bitrate(int bitrate_bps) override;
    int get_bitrate() const override { return current_bitrate_.load(); }
    void set_packet_loss_percent(int loss_perc) override;
    int get_frame_samples() const override { return frame_samples_; }
    const char* get_name() const override { return "Opus Low-Delay"; }

private:
    OpusEncoder *encoder_ = nullptr;
    AudioFormat format_;
    int frame_samples_ = 480;
    std::atomic<int> current_bitrate_{320000};
    std::atomic<int> packet_loss_perc_{0};
    std::mutex encoder_mutex_;
};

} // namespace zenith
