#include "opus_encoder.hpp"
#include <iostream>

namespace zenith {

OpusAudioEncoder::OpusAudioEncoder() = default;

OpusAudioEncoder::~OpusAudioEncoder() {
    std::lock_guard<std::mutex> lock(encoder_mutex_);
    if (encoder_) {
        opus_encoder_destroy(encoder_);
        encoder_ = nullptr;
    }
}

bool OpusAudioEncoder::init(const AudioFormat &format, int target_bitrate_bps) {
    std::lock_guard<std::mutex> lock(encoder_mutex_);
    if (encoder_) {
        opus_encoder_destroy(encoder_);
        encoder_ = nullptr;
    }

    format_ = format;
    frame_samples_ = format.frame_samples;
    current_bitrate_.store(target_bitrate_bps);

    int error = OPUS_OK;
    encoder_ = opus_encoder_create(
        format.sample_rate,
        format.channels,
        OPUS_APPLICATION_RESTRICTED_LOWDELAY,
        &error
    );

    if (error != OPUS_OK || !encoder_) {
        std::cerr << "[Opus] Failed to create encoder: " << opus_strerror(error) << "\n";
        return false;
    }

    opus_encoder_ctl(encoder_, OPUS_SET_BITRATE(target_bitrate_bps));
    opus_encoder_ctl(encoder_, OPUS_SET_COMPLEXITY(7));
    opus_encoder_ctl(encoder_, OPUS_SET_SIGNAL(OPUS_SIGNAL_MUSIC));
    opus_encoder_ctl(encoder_, OPUS_SET_INBAND_FEC(1));
    opus_encoder_ctl(encoder_, OPUS_SET_PACKET_LOSS_PERC(0));
    opus_encoder_ctl(encoder_, OPUS_SET_PREDICTION_DISABLED(0));

    std::cout << "[Opus] Windows 11 Opus encoder initialized: " << format.sample_rate << "Hz, "
              << format.channels << "ch, " << (target_bitrate_bps / 1000) << " kbps\n";

    return true;
}

int OpusAudioEncoder::encode(const float *pcm_interleaved, int frame_samples,
                             uint8_t *out_buffer, int max_bytes) {
    std::lock_guard<std::mutex> lock(encoder_mutex_);
    if (!encoder_) return -1;

    int res = opus_encode_float(encoder_, pcm_interleaved, frame_samples, out_buffer, max_bytes);
    if (res < 0) {
        std::cerr << "[Opus] Encode float error: " << opus_strerror(res) << "\n";
    }
    return res;
}

int OpusAudioEncoder::encode16(const int16_t *pcm_interleaved, int frame_samples,
                               uint8_t *out_buffer, int max_bytes) {
    std::lock_guard<std::mutex> lock(encoder_mutex_);
    if (!encoder_) return -1;

    int res = opus_encode(encoder_, pcm_interleaved, frame_samples, out_buffer, max_bytes);
    if (res < 0) {
        std::cerr << "[Opus] Encode int16 error: " << opus_strerror(res) << "\n";
    }
    return res;
}

void OpusAudioEncoder::set_bitrate(int bitrate_bps) {
    std::lock_guard<std::mutex> lock(encoder_mutex_);
    if (!encoder_) return;

    current_bitrate_.store(bitrate_bps);
    opus_encoder_ctl(encoder_, OPUS_SET_BITRATE(bitrate_bps));
    std::cout << "[Opus] Dynamic bitrate updated to: " << (bitrate_bps / 1000) << " kbps\n";
}

void OpusAudioEncoder::set_packet_loss_percent(int loss_perc) {
    std::lock_guard<std::mutex> lock(encoder_mutex_);
    if (!encoder_) return;

    if (loss_perc < 0) loss_perc = 0;
    if (loss_perc > 100) loss_perc = 100;

    packet_loss_perc_.store(loss_perc);
    opus_encoder_ctl(encoder_, OPUS_SET_PACKET_LOSS_PERC(loss_perc));
}

} // namespace zenith
