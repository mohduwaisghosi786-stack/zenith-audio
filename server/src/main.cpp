#include "pipewire_capture.hpp"
#include "opus_encoder.hpp"
#include "network_server.hpp"
#include "audio_engine.hpp"
#include <iostream>
#include <csignal>
#include <atomic>
#include <string>

static std::atomic<bool> g_keep_running{true};

static void sig_handler(int signum) {
    (void)signum;
    g_keep_running = false;
}

int main(int argc, char *argv[]) {
    uint16_t port = zap::DEFAULT_PORT;
    int bitrate_kbps = 320;

    for (int i = 1; i < argc; ++i) {
        std::string arg = argv[i];
        if (arg == "--port" && i + 1 < argc) {
            port = static_cast<uint16_t>(std::stoi(argv[++i]));
        } else if (arg == "--bitrate" && i + 1 < argc) {
            bitrate_kbps = std::stoi(argv[++i]);
        } else if (arg == "--help" || arg == "-h") {
            std::cout << "Zenith Ultra-Low-Latency Audio Server\n"
                      << "Usage: " << argv[0] << " [options]\n"
                      << "  --port <port>       UDP listening port (default: 8088)\n"
                      << "  --bitrate <kbps>    Audio bitrate in kbps (default: 320)\n"
                      << "  --help, -h          Show this help message\n";
            return 0;
        }
    }

    signal(SIGINT, sig_handler);
    signal(SIGTERM, sig_handler);

    std::cout << "========================================================\n"
              << "       ZENITH ULTRA-LOW-LATENCY AUDIO SERVER (LINUX)    \n"
              << "========================================================\n"
              << "  Source:        PipeWire Default Monitor Capture\n"
              << "  Format:        48000 Hz, 2 Channels (Stereo)\n"
              << "  Encoder:       Opus Restricted Low-Delay (MDCT)\n"
              << "  Bitrate:       " << bitrate_kbps << " kbps\n"
              << "  Frame Size:    10 ms (480 samples)\n"
              << "  Transport:     Zenith UDP Protocol (Port " << port << ")\n"
              << "========================================================\n";

    auto source = std::make_unique<zenith::PipeWireCapture>();
    auto encoder = std::make_unique<zenith::OpusAudioEncoder>();
    auto transport = std::make_unique<zenith::NetworkServer>();

    zenith::AudioEngine engine(std::move(source), std::move(encoder), std::move(transport));

    if (!engine.start(port, bitrate_kbps * 1000)) {
        std::cerr << "[Main] Failed to start audio engine.\n";
        return 1;
    }

    std::cout << "[Main] Server is running. Press Ctrl+C to terminate.\n";

    while (g_keep_running.load()) {
        std::this_thread::sleep_for(std::chrono::milliseconds(200));
    }

    std::cout << "\n[Main] Shutting down Zenith Audio Server...\n";
    engine.stop();
    std::cout << "[Main] Shutdown complete.\n";
    return 0;
}
