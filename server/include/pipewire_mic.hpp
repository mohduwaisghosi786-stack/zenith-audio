#pragma once

#include <pipewire/pipewire.h>
#include <spa/param/audio/format-utils.h>
#include <opus/opus.h>
#include <thread>
#include <atomic>
#include <vector>
#include <cstdint>
#include "ring_buffer.hpp"

namespace zenith {

class PipeWireMicSink {
public:
    PipeWireMicSink();
    ~PipeWireMicSink();

    bool start();
    void stop();

    void push_audio_frame(const uint8_t *data, size_t size, bool is_pcm);
    void process_audio();

private:
    void thread_main();

    struct pw_main_loop *loop_ = nullptr;
    struct pw_stream *stream_ = nullptr;
    std::thread loop_thread_;
    std::atomic<bool> is_running_{false};

    OpusDecoder *opus_decoder_ = nullptr;
    SpscRingBuffer<float, 65536> ring_buffer_;
};

} // namespace zenith
