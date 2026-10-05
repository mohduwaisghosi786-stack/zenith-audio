#pragma once

#include "audio_interfaces.hpp"
#include <pipewire/pipewire.h>
#include <spa/param/audio/format-utils.h>
#include <thread>
#include <atomic>

namespace zenith {

class PipeWireCapture : public IAudioSource {
public:
    PipeWireCapture(const AudioFormat &format = {48000, 2, 480}, const std::string &target_app = "");
    ~PipeWireCapture() override;

    bool start(AudioSampleCallback callback) override;
    void stop() override;
    AudioFormat get_format() const override { return format_; }
    bool is_active() const override { return is_streaming_.load(); }
    void process_audio();

private:
    static void on_process_callback(void *userdata);
    static void on_state_changed_callback(void *userdata, enum pw_stream_state old_state,
                                         enum pw_stream_state state, const char *error);

    void thread_main();

    AudioFormat format_;
    std::string target_app_;
    AudioSampleCallback callback_;

    struct pw_main_loop *loop_ = nullptr;
    struct pw_stream *stream_ = nullptr;
    struct spa_hook stream_listener_ = {};

    std::thread loop_thread_;
    std::atomic<bool> is_running_{false};
    std::atomic<bool> is_streaming_{false};
};

} // namespace zenith
