#include "pipewire_capture.hpp"
#include <iostream>
#include <chrono>
#include <spa/param/props.h>

namespace zenith {

static const struct pw_stream_events stream_events = {
    .version = PW_VERSION_STREAM_EVENTS,
    .destroy = nullptr,
    .state_changed = [](void *userdata, enum pw_stream_state old_state,
                        enum pw_stream_state state, const char *error) {
        (void)userdata;
        (void)old_state;
        if (state == PW_STREAM_STATE_STREAMING) {
            std::cout << "[PipeWire] Audio capture is active and streaming system audio.\n";
        } else if (state == PW_STREAM_STATE_ERROR) {
            std::cerr << "[PipeWire] Stream error: " << (error ? error : "unknown") << "\n";
        }
    },
    .control_info = nullptr,
    .io_changed = nullptr,
    .param_changed = nullptr,
    .add_buffer = nullptr,
    .remove_buffer = nullptr,
    .process = [](void *userdata) {
        auto *self = static_cast<PipeWireCapture*>(userdata);
        self->process_audio();
    },
    .drained = nullptr,
    .command = nullptr,
    .trigger_done = nullptr,
};

PipeWireCapture::PipeWireCapture(const AudioFormat &format, const std::string &target_app)
    : format_(format), target_app_(target_app) {
}

PipeWireCapture::~PipeWireCapture() {
    stop();
}

bool PipeWireCapture::start(AudioSampleCallback callback) {
    if (is_running_.load()) {
        return false;
    }

    callback_ = std::move(callback);
    is_running_.store(true);

    loop_thread_ = std::thread(&PipeWireCapture::thread_main, this);
    return true;
}

void PipeWireCapture::stop() {
    if (!is_running_.load()) {
        return;
    }

    is_running_.store(false);

    if (loop_) {
        pw_main_loop_quit(loop_);
    }

    if (loop_thread_.joinable()) {
        loop_thread_.join();
    }
}

void PipeWireCapture::thread_main() {
    pw_init(nullptr, nullptr);

    loop_ = pw_main_loop_new(nullptr);
    if (!loop_) {
        std::cerr << "[PipeWire] Failed to create main loop\n";
        is_running_.store(false);
        return;
    }

    // Capture monitor of default audio output sink or specific application
    struct pw_properties *props = pw_properties_new(
        PW_KEY_MEDIA_TYPE, "Audio",
        PW_KEY_MEDIA_CATEGORY, "Capture",
        PW_KEY_MEDIA_ROLE, "Music",
        PW_KEY_APP_NAME, "ZenithAudioServer",
        PW_KEY_NODE_LATENCY, "480/48000",
        PW_KEY_NODE_RATE, "1/48000",
        nullptr
    );

    if (!target_app_.empty()) {
        pw_properties_set(props, PW_KEY_TARGET_OBJECT, target_app_.c_str());
        std::cout << "[PipeWire] Capturing target application node: " << target_app_ << "\n";
    } else {
        pw_properties_set(props, "stream.capture.sink", "true");
        std::cout << "[PipeWire] Capturing entire Linux system mixed audio output.\n";
    }

    stream_ = pw_stream_new_simple(
        pw_main_loop_get_loop(loop_),
        "zenith-system-capture",
        props,
        &stream_events,
        this
    );

    if (!stream_) {
        std::cerr << "[PipeWire] Failed to create stream\n";
        pw_main_loop_destroy(loop_);
        loop_ = nullptr;
        is_running_.store(false);
        return;
    }

    uint8_t buffer[1024];
    struct spa_pod_builder b = SPA_POD_BUILDER_INIT(buffer, sizeof(buffer));

    struct spa_audio_info_raw info = {};
    info.format = SPA_AUDIO_FORMAT_F32;
    info.channels = format_.channels;
    info.rate = format_.sample_rate;
    if (format_.channels == 2) {
        info.position[0] = SPA_AUDIO_CHANNEL_FL;
        info.position[1] = SPA_AUDIO_CHANNEL_FR;
    }

    const struct spa_pod *params[1];
    params[0] = spa_format_audio_raw_build(&b, SPA_PARAM_EnumFormat, &info);

    int res = pw_stream_connect(
        stream_,
        PW_DIRECTION_INPUT,
        PW_ID_ANY,
        static_cast<pw_stream_flags>(
            PW_STREAM_FLAG_AUTOCONNECT |
            PW_STREAM_FLAG_MAP_BUFFERS |
            PW_STREAM_FLAG_RT_PROCESS
        ),
        params, 1
    );

    if (res < 0) {
        std::cerr << "[PipeWire] Failed to connect stream: " << res << "\n";
        pw_stream_destroy(stream_);
        pw_main_loop_destroy(loop_);
        stream_ = nullptr;
        loop_ = nullptr;
        is_running_.store(false);
        return;
    }

    is_streaming_.store(true);
    pw_main_loop_run(loop_);

    is_streaming_.store(false);
    if (stream_) {
        pw_stream_destroy(stream_);
        stream_ = nullptr;
    }
    if (loop_) {
        pw_main_loop_destroy(loop_);
        loop_ = nullptr;
    }
    pw_deinit();
}

void PipeWireCapture::process_audio() {
    if (!stream_) return;

    struct pw_buffer *b = pw_stream_dequeue_buffer(stream_);
    if (!b) return;

    struct spa_buffer *buf = b->buffer;
    if (buf->datas[0].data != nullptr) {
        auto *samples = static_cast<const float*>(buf->datas[0].data);
        uint32_t chunk_size = buf->datas[0].chunk->size;
        uint32_t total_samples = chunk_size / sizeof(float);
        uint32_t frame_count = total_samples / format_.channels;

        if (frame_count > 0 && callback_) {
            auto now = std::chrono::steady_clock::now();
            uint64_t timestamp_us = std::chrono::duration_cast<std::chrono::microseconds>(
                now.time_since_epoch()
            ).count();

            callback_(samples, frame_count, timestamp_us);
        }
    }

    pw_stream_queue_buffer(stream_, b);
}

} // namespace zenith
