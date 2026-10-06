#pragma once

#include "audio_interfaces.hpp"
#include "../../protocol/protocol.hpp"
#include <winsock2.h>
#include <ws2tcpip.h>
#include <iphlpapi.h>
#include <cstdint>
#include <vector>
#include <mutex>
#include <thread>
#include <atomic>

#pragma comment(lib, "ws2_32.lib")
#pragma comment(lib, "iphlpapi.lib")

namespace zenith {

struct ClientEndpoint {
    struct sockaddr_in addr;
    uint64_t last_seen_us = 0;
    uint32_t last_seq_received = 0;
    uint32_t packets_lost = 0;
    uint16_t loss_pct_scaled = 0;
    uint16_t jitter_us = 0;
    uint16_t buffer_delay_ms = 0;
    uint16_t rtt_ms = 0;
};

class NetworkServer {
public:
    NetworkServer();
    ~NetworkServer();

    bool start(uint16_t port = zap::DEFAULT_PORT);
    void stop();

    void broadcast_audio(const uint8_t *payload, size_t size, uint64_t timestamp_us, bool fec = false);
    void send_announce(const AudioFormat &format, int bitrate);

    void set_feedback_callback(FeedbackCallback cb) { feedback_cb_ = cb; }
    void set_control_callback(ControlCallback cb) { control_cb_ = cb; }
    void set_mic_frame_callback(MicFrameCallback cb) { mic_cb_ = cb; }

    TransportStats get_stats() const;
    bool is_running() const { return is_running_.load(); }
    size_t get_active_client_count() const;

private:
    void rx_thread_main();
    void handle_incoming_packet(const uint8_t *buffer, size_t size, const struct sockaddr_in &src_addr);
    void update_or_add_client(const struct sockaddr_in &addr);
    void cleanup_stale_clients();
    void broadcast_discovery_beacon();
    void send_discovery_beacon(const struct sockaddr_in &dest_addr);

    SOCKET sockfd_ = INVALID_SOCKET;
    uint16_t port_ = zap::DEFAULT_PORT;
    std::atomic<bool> is_running_{false};
    std::thread rx_thread_;

    std::vector<ClientEndpoint> clients_;
    mutable std::mutex clients_mutex_;

    std::atomic<uint32_t> current_seq_{0};
    std::atomic<uint64_t> packets_sent_{0};
    std::atomic<uint64_t> bytes_sent_{0};

    FeedbackCallback feedback_cb_;
    ControlCallback control_cb_;
    MicFrameCallback mic_cb_;

    AudioFormat current_format_;
    int current_bitrate_ = 320000;
};

} // namespace zenith
