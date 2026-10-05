#include <pipewire/pipewire.h>
#include <spa/param/audio/format-utils.h>
#include <spa/param/props.h>
#include <iostream>
#include <atomic>
#include <csignal>
#include <unistd.h>

struct AudioCaptureData {
    struct pw_main_loop *loop = nullptr;
    struct pw_stream *stream = nullptr;
    std::atomic<uint64_t> samples_captured{0};
    std::atomic<uint32_t> callbacks_received{0};
};

static void on_process(void *userdata) {
    auto *data = static_cast<AudioCaptureData*>(userdata);
    struct pw_buffer *b = pw_stream_dequeue_buffer(data->stream);
    if (!b) return;

    struct spa_buffer *buf = b->buffer;
    if (buf->datas[0].data != nullptr) {
        uint32_t n_bytes = buf->datas[0].chunk->size;
        uint32_t n_samples = n_bytes / (sizeof(float) * 2); // 2 channels float32
        data->samples_captured += n_samples;
        data->callbacks_received++;
    }
    pw_stream_queue_buffer(data->stream, b);
}

static void on_state_changed(void *userdata, enum pw_stream_state old_state,
                             enum pw_stream_state state, const char *error) {
    (void)userdata;
    (void)old_state;
    std::cout << "[PipeWire] Stream state: " << pw_stream_state_as_string(state)
              << (error ? " (error: " : "") << (error ? error : "")
              << (error ? ")" : "") << std::endl;
}

static const struct pw_stream_events stream_events = {
    .version = PW_VERSION_STREAM_EVENTS,
    .destroy = nullptr,
    .state_changed = on_state_changed,
    .control_info = nullptr,
    .io_changed = nullptr,
    .param_changed = nullptr,
    .add_buffer = nullptr,
    .remove_buffer = nullptr,
    .process = on_process,
    .drained = nullptr,
    .command = nullptr,
    .trigger_done = nullptr,
};

static std::atomic<bool> g_running{true};
static void sig_handler(int) {
    g_running = false;
}

int main() {
    signal(SIGINT, sig_handler);
    signal(SIGTERM, sig_handler);

    pw_init(nullptr, nullptr);

    AudioCaptureData data;
    data.loop = pw_main_loop_new(nullptr);
    if (!data.loop) {
        std::cerr << "Failed to create PipeWire main loop\n";
        return 1;
    }

    struct pw_properties *props = pw_properties_new(
        PW_KEY_MEDIA_TYPE, "Audio",
        PW_KEY_MEDIA_CATEGORY, "Capture",
        PW_KEY_MEDIA_ROLE, "Music",
        PW_KEY_APP_NAME, "ZenithAudioCaptureTest",
        "stream.capture.sink", "true",
        NULL
    );

    data.stream = pw_stream_new_simple(
        pw_main_loop_get_loop(data.loop),
        "zenith-capture",
        props,
        &stream_events,
        &data
    );

    if (!data.stream) {
        std::cerr << "Failed to create PipeWire stream\n";
        return 1;
    }

    uint8_t buffer[1024];
    struct spa_pod_builder b = SPA_POD_BUILDER_INIT(buffer, sizeof(buffer));

    struct spa_audio_info_raw info = {};
    info.format = SPA_AUDIO_FORMAT_F32;
    info.channels = 2;
    info.rate = 48000;
    info.position[0] = SPA_AUDIO_CHANNEL_FL;
    info.position[1] = SPA_AUDIO_CHANNEL_FR;

    const struct spa_pod *params[1];
    params[0] = spa_format_audio_raw_build(&b, SPA_PARAM_EnumFormat, &info);

    int res = pw_stream_connect(
        data.stream,
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
        std::cerr << "Failed to connect stream: " << res << "\n";
        return 1;
    }

    std::cout << "Starting loop for 2 seconds to test PipeWire monitor capture...\n";

    // Run loop in a background thread or iterate
    struct pw_loop *pwl = pw_main_loop_get_loop(data.loop);
    for (int i = 0; i < 200 && g_running; ++i) {
        pw_loop_iterate(pwl, 10);
    }

    std::cout << "Results: callbacks received: " << data.callbacks_received.load()
              << ", samples captured: " << data.samples_captured.load() << "\n";

    pw_stream_destroy(data.stream);
    pw_main_loop_destroy(data.loop);
    pw_deinit();
    std::cout << "PipeWire test successful!\n";
    return 0;
}
