#include <opus/opus.h>
#include <iostream>
#include <vector>
#include <cmath>
#include <chrono>

int main() {
    int error = 0;
    OpusEncoder *enc = opus_encoder_create(48000, 2, OPUS_APPLICATION_RESTRICTED_LOWDELAY, &error);
    if (error != OPUS_OK || !enc) {
        std::cerr << "Failed to create opus encoder: " << opus_strerror(error) << "\n";
        return 1;
    }

    opus_encoder_ctl(enc, OPUS_SET_BITRATE(320000));
    opus_encoder_ctl(enc, OPUS_SET_COMPLEXITY(7));
    opus_encoder_ctl(enc, OPUS_SET_SIGNAL(OPUS_SIGNAL_MUSIC));
    opus_encoder_ctl(enc, OPUS_SET_INBAND_FEC(1));
    opus_encoder_ctl(enc, OPUS_SET_PACKET_LOSS_PERC(5));

    // Test 10ms frame = 480 samples @ 48kHz * 2 channels = 960 floats
    const int frame_size = 480;
    std::vector<float> pcm(frame_size * 2);
    for (size_t i = 0; i < pcm.size(); ++i) {
        pcm[i] = std::sin(2.0 * M_PI * 440.0 * (i / 2) / 48000.0);
    }

    std::vector<unsigned char> out(1000);

    auto t0 = std::chrono::high_resolution_clock::now();
    int encoded_bytes = opus_encode_float(enc, pcm.data(), frame_size, out.data(), out.size());
    auto t1 = std::chrono::high_resolution_clock::now();

    auto dur_us = std::chrono::duration_cast<std::chrono::microseconds>(t1 - t0).count();

    if (encoded_bytes < 0) {
        std::cerr << "Encode failed: " << opus_strerror(encoded_bytes) << "\n";
        opus_encoder_destroy(enc);
        return 1;
    }

    std::cout << "Opus encode success: 10ms frame (480 samples, stereo) -> "
              << encoded_bytes << " bytes in " << dur_us << " microseconds!\n";

    // Test decoding
    OpusDecoder *dec = opus_decoder_create(48000, 2, &error);
    std::vector<float> decoded(frame_size * 2);
    int dec_samples = opus_decode_float(dec, out.data(), encoded_bytes, decoded.data(), frame_size, 0);
    std::cout << "Opus decode success: decoded " << dec_samples << " samples!\n";

    opus_decoder_destroy(dec);
    opus_encoder_destroy(enc);
    return 0;
}
