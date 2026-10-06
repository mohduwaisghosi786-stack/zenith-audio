#include "wasapi_capture.hpp"
#include "opus_encoder.hpp"
#include "network_server.hpp"
#include <iostream>
#include <csignal>
#include <atomic>
#include <chrono>
#include <thread>
#include <vector>

static std::atomic<bool> g_running{true};

static void signal_handler(int) {
    std::cout << "\n[Main] Shutdown signal received. Stopping Zenith Windows 11 Server...\n";
    g_running.store(false);
}

int main(int argc, char *argv[]) {
    std::signal(SIGINT, signal_handler);
    std::signal(SIGTERM, signal_handler);

    uint16_t port = 59100;
    int bitrate_kbps = 320;

    for (int i = 1; i < argc; ++i) {
        std::string arg = argv[i];
        if (arg == "--port" && i + 1 < argc) {
            port = static_cast<uint16_t>(std::stoi(argv[++i]));
        } else if (arg == "--bitrate" && i + 1 < argc) {
            bitrate_kbps = std::stoi(argv[++i]);
        }
    }

    std::cout << "================================================================\n";
    std::cout << "   ⚡ ZENITH AUDIO ENGINE — WINDOWS 11 NATIVE SERVER           \n";
    std::cout << "        WASAPI Loopback Capture → Opus Low-Latency UDP           \n";
    std::cout << "================================================================\n";
    std::cout << "[Config] Port: " << port << " | Target Bitrate: " << bitrate_kbps << " kbps\n";

    zenith::NetworkServer network_server;
    if (!network_server.start(port)) {
        std::cerr << "[Error] Failed to initialize Windows UDP network server on port " << port << "\n";
        return 1;
    }

    zenith::AudioFormat format;
    format.sample_rate = 48000;
    format.channels = 2;
    format.frame_samples = 480; // 10ms frame at 48kHz for sub-millisecond responsiveness

    zenith::OpusAudioEncoder encoder;
    if (!encoder.init(format, bitrate_kbps * 1000)) {
        std::cerr << "[Error] Failed to initialize Opus encoder\n";
        network_server.stop();
        return 1;
    }

    network_server.send_announce(format, bitrate_kbps * 1000);

    // Adaptive dynamic bitrate feedback handler
    network_server.set_feedback_callback([&](uint32_t loss_pct_scaled, uint32_t jitter_us, uint32_t rtt_ms) {
        int loss_pct = static_cast<int>(loss_pct_scaled / 100);
        encoder.set_packet_loss_percent(loss_pct);
    });

    // Control commands handler (e.g. bitrate change from client)
    network_server.set_control_callback([&](uint8_t cmd, uint32_t param) {
        if (cmd == zap::CMD_SET_BITRATE && param > 0) {
            encoder.set_bitrate(static_cast<int>(param));
        }
    });

    zenith::WasapiCapture wasapi_capture;
    std::vector<int16_t> pcm_accumulator;
    std::mutex accum_mutex;

    bool wasapi_ok = wasapi_capture.start([&](const int16_t *pcm, size_t frames, uint64_t timestamp_us) {
        std::lock_guard<std::mutex> lock(accum_mutex);

        // Accumulate 16-bit interleaved stereo samples
        size_t samples = frames * format.channels;
        pcm_accumulator.insert(pcm_accumulator.end(), pcm, pcm + samples);

        size_t required_samples = format.frame_samples * format.channels;
        uint8_t opus_buf[1500];

        while (pcm_accumulator.size() >= required_samples) {
            int encoded_bytes = encoder.encode16(pcm_accumulator.data(), format.frame_samples, opus_buf, sizeof(opus_buf));
            if (encoded_bytes > 0) {
                network_server.broadcast_audio(opus_buf, encoded_bytes, timestamp_us, false);
            }
            pcm_accumulator.erase(pcm_accumulator.begin(), pcm_accumulator.begin() + required_samples);
        }
    });

    if (!wasapi_ok) {
        std::cerr << "[Error] Failed to initialize WASAPI Loopback Capture\n";
        network_server.stop();
        return 1;
    }

    std::cout << "[Main] Windows 11 Audio Pipeline ACTIVE and STREAMING!\n";
    std::cout << "[Main] Press Ctrl+C to terminate.\n";
    std::cout << "----------------------------------------------------------------\n";

    uint64_t last_packets = 0;
    while (g_running.load()) {
        std::this_thread::sleep_for(std::chrono::seconds(2));
        auto stats = network_server.get_stats();
        uint64_t pps = (stats.packets_sent - last_packets) / 2;
        last_packets = stats.packets_sent;

        std::cout << "[Telemetry] Clients: " << stats.active_clients
                  << " | Pkts/s: " << pps
                  << " | Loss: " << (stats.client_reported_loss_pct / 100.0) << "%"
                  << " | Jitter: " << (stats.client_reported_jitter_us / 1000.0) << "ms"
                  << " | RTT: " << stats.client_reported_rtt_ms << "ms\n";
    }

    wasapi_capture.stop();
    network_server.stop();
    std::cout << "[Main] Zenith Windows 11 Server terminated cleanly.\n";
    return 0;
}
