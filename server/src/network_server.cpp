#include "network_server.hpp"
#include <iostream>
#include <unistd.h>
#include <fcntl.h>
#include <sys/socket.h>
#include <arpa/inet.h>
#include <chrono>

namespace zenith {

static uint64_t get_now_us() {
    auto now = std::chrono::steady_clock::now();
    return std::chrono::duration_cast<std::chrono::microseconds>(now.time_since_epoch()).count();
}

NetworkServer::NetworkServer() = default;

NetworkServer::~NetworkServer() {
    stop();
}

bool NetworkServer::start(uint16_t port) {
    if (is_running_.load()) return false;

    port_ = port;
    sockfd_ = socket(AF_INET, SOCK_DGRAM, 0);
    if (sockfd_ < 0) {
        perror("[Network] Failed to create UDP socket");
        return false;
    }

    // Set reuseaddr
    int opt = 1;
    setsockopt(sockfd_, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

    // Increase socket send and receive buffers for low-latency bursts
    int sndbuf = 256 * 1024;
    setsockopt(sockfd_, SOL_SOCKET, SO_SNDBUF, &sndbuf, sizeof(sndbuf));
    int rcvbuf = 256 * 1024;
    setsockopt(sockfd_, SOL_SOCKET, SO_RCVBUF, &rcvbuf, sizeof(rcvbuf));

    struct sockaddr_in serv_addr = {};
    serv_addr.sin_family = AF_INET;
    serv_addr.sin_addr.s_addr = INADDR_ANY;
    serv_addr.sin_port = htons(port_);

    if (bind(sockfd_, (struct sockaddr *)&serv_addr, sizeof(serv_addr)) < 0) {
        perror("[Network] Socket bind failed");
        close(sockfd_);
        sockfd_ = -1;
        return false;
    }

    is_running_.store(true);
    rx_thread_ = std::thread(&NetworkServer::rx_thread_main, this);

    std::cout << "[Network] Zenith UDP server listening on port " << port_ << "\n";
    return true;
}

void NetworkServer::stop() {
    if (!is_running_.load()) return;

    is_running_.store(false);

    if (sockfd_ >= 0) {
        shutdown(sockfd_, SHUT_RDWR);
        close(sockfd_);
        sockfd_ = -1;
    }

    if (rx_thread_.joinable()) {
        rx_thread_.join();
    }

    std::cout << "[Network] Zenith UDP server stopped.\n";
}

void NetworkServer::broadcast_audio(const uint8_t *payload, size_t size, uint64_t timestamp_us, bool fec) {
    if (!is_running_.load() || sockfd_ < 0) return;

    std::vector<struct sockaddr_in> target_addrs;
    {
        std::lock_guard<std::mutex> lock(clients_mutex_);
        if (clients_.empty()) return;
        target_addrs.reserve(clients_.size());
        for (const auto &c : clients_) {
            target_addrs.push_back(c.addr);
        }
    }

    uint32_t seq = current_seq_.fetch_add(1, std::memory_order_relaxed);

    // Prepare packet buffer: header + payload
    uint8_t packet_buffer[2048];
    if (sizeof(zap::Header) + size > sizeof(packet_buffer)) {
        std::cerr << "[Network] Packet size exceeds buffer\n";
        return;
    }

    zap::Header hdr;
    hdr.magic = zap::MAGIC;
    hdr.version = zap::VERSION;
    hdr.type = zap::PKT_AUDIO_FRAME;
    hdr.seq_num = seq;
    hdr.timestamp_us = timestamp_us;
    hdr.payload_size = static_cast<uint16_t>(size);
    hdr.flags = fec ? zap::FLAG_FEC_PRESENT : zap::FLAG_NONE;

    zap::Header net_hdr = hdr;
    net_hdr.to_network();

    std::memcpy(packet_buffer, &net_hdr, sizeof(net_hdr));
    std::memcpy(packet_buffer + sizeof(net_hdr), payload, size);
    size_t total_packet_size = sizeof(net_hdr) + size;

    for (const auto &addr : target_addrs) {
        sendto(sockfd_, packet_buffer, total_packet_size, 0,
               (const struct sockaddr*)&addr, sizeof(addr));
    }

    packets_sent_.fetch_add(1, std::memory_order_relaxed);
    bytes_sent_.fetch_add(total_packet_size, std::memory_order_relaxed);
}

void NetworkServer::send_announce(const AudioFormat &format, int bitrate) {
    current_format_ = format;
    current_bitrate_ = bitrate;

    std::vector<struct sockaddr_in> target_addrs;
    {
        std::lock_guard<std::mutex> lock(clients_mutex_);
        if (clients_.empty()) return;
        for (const auto &c : clients_) {
            target_addrs.push_back(c.addr);
        }
    }

    zap::Header hdr;
    hdr.magic = zap::MAGIC;
    hdr.version = zap::VERSION;
    hdr.type = zap::PKT_SERVER_ANNOUNCE;
    hdr.seq_num = current_seq_.load(std::memory_order_relaxed);
    hdr.timestamp_us = get_now_us();
    hdr.payload_size = sizeof(zap::AnnouncePayload);
    hdr.flags = zap::FLAG_NONE;

    zap::AnnouncePayload payload;
    payload.sample_rate = format.sample_rate;
    payload.channels = format.channels;
    payload.frame_samples = format.frame_samples;
    payload.bitrate_bps = bitrate;
    payload.codec_type = zap::CODEC_OPUS;
    std::memset(payload.reserved, 0, sizeof(payload.reserved));

    uint8_t packet_buffer[sizeof(zap::Header) + sizeof(zap::AnnouncePayload)];
    zap::Header net_hdr = hdr;
    net_hdr.to_network();
    zap::AnnouncePayload net_payload = payload;
    net_payload.to_network();

    std::memcpy(packet_buffer, &net_hdr, sizeof(net_hdr));
    std::memcpy(packet_buffer + sizeof(net_hdr), &net_payload, sizeof(net_payload));

    for (const auto &addr : target_addrs) {
        sendto(sockfd_, packet_buffer, sizeof(packet_buffer), 0,
               (const struct sockaddr*)&addr, sizeof(addr));
    }
}

void NetworkServer::rx_thread_main() {
    uint8_t rx_buf[2048];
    struct sockaddr_in src_addr = {};
    socklen_t addr_len = sizeof(src_addr);

    while (is_running_.load()) {
        ssize_t n = recvfrom(sockfd_, rx_buf, sizeof(rx_buf), 0,
                             (struct sockaddr*)&src_addr, &addr_len);
        if (n < 0) {
            if (!is_running_.load()) break;
            continue;
        }

        if (n >= static_cast<ssize_t>(sizeof(zap::Header))) {
            handle_incoming_packet(rx_buf, n, src_addr);
        }
    }
}

void NetworkServer::update_or_add_client(const struct sockaddr_in &addr) {
    uint64_t now_us = get_now_us();
    std::lock_guard<std::mutex> lock(clients_mutex_);

    for (auto &c : clients_) {
        if (c.addr.sin_addr.s_addr == addr.sin_addr.s_addr &&
            c.addr.sin_port == addr.sin_port) {
            c.last_seen_us = now_us;
            return;
        }
    }

    ClientEndpoint new_client;
    new_client.addr = addr;
    new_client.last_seen_us = now_us;
    clients_.push_back(new_client);

    char ip_str[INET_ADDRSTRLEN];
    inet_ntop(AF_INET, &addr.sin_addr, ip_str, sizeof(ip_str));
    std::cout << "[Network] Registered new client: " << ip_str << ":" << ntohs(addr.sin_port)
              << " (Total clients: " << clients_.size() << ")\n";
}

void NetworkServer::cleanup_stale_clients() {
    uint64_t now_us = get_now_us();
    constexpr uint64_t TIMEOUT_US = 8'000'000; // 8 seconds timeout

    std::lock_guard<std::mutex> lock(clients_mutex_);
    for (auto it = clients_.begin(); it != clients_.end();) {
        if (now_us - it->last_seen_us > TIMEOUT_US) {
            char ip_str[INET_ADDRSTRLEN];
            inet_ntop(AF_INET, &it->addr.sin_addr, ip_str, sizeof(ip_str));
            std::cout << "[Network] Client timed out: " << ip_str << ":" << ntohs(it->addr.sin_port) << "\n";
            it = clients_.erase(it);
        } else {
            ++it;
        }
    }
}

void NetworkServer::handle_incoming_packet(const uint8_t *buffer, size_t size,
                                          const struct sockaddr_in &src_addr) {
    zap::Header hdr;
    std::memcpy(&hdr, buffer, sizeof(hdr));
    hdr.to_host();

    if (hdr.magic != zap::MAGIC || hdr.version != zap::VERSION) {
        return; // Ignore unknown or malformed packets
    }

    update_or_add_client(src_addr);
    cleanup_stale_clients();

    const uint8_t *payload = buffer + sizeof(zap::Header);
    size_t payload_len = size - sizeof(zap::Header);

    switch (hdr.type) {
        case zap::PKT_PING: {
            if (payload_len >= sizeof(zap::PingPayload)) {
                zap::PingPayload ping;
                std::memcpy(&ping, payload, sizeof(ping));
                ping.to_host();

                // Reply with PONG
                zap::Header pong_hdr;
                pong_hdr.magic = zap::MAGIC;
                pong_hdr.version = zap::VERSION;
                pong_hdr.type = zap::PKT_PONG;
                pong_hdr.seq_num = hdr.seq_num;
                pong_hdr.timestamp_us = get_now_us();
                pong_hdr.payload_size = sizeof(zap::PingPayload);
                pong_hdr.flags = zap::FLAG_NONE;

                zap::PingPayload pong_payload;
                pong_payload.client_timestamp_us = ping.client_timestamp_us;
                pong_payload.server_timestamp_us = pong_hdr.timestamp_us;

                uint8_t pong_buf[sizeof(zap::Header) + sizeof(zap::PingPayload)];
                pong_hdr.to_network();
                pong_payload.to_network();

                std::memcpy(pong_buf, &pong_hdr, sizeof(pong_hdr));
                std::memcpy(pong_buf + sizeof(pong_hdr), &pong_payload, sizeof(pong_payload));

                sendto(sockfd_, pong_buf, sizeof(pong_buf), 0,
                       (const struct sockaddr*)&src_addr, sizeof(src_addr));
            }
            break;
        }

        case zap::PKT_CLIENT_FEEDBACK: {
            if (payload_len >= sizeof(zap::FeedbackPayload)) {
                zap::FeedbackPayload fb;
                std::memcpy(&fb, payload, sizeof(fb));
                fb.to_host();

                {
                    std::lock_guard<std::mutex> lock(clients_mutex_);
                    for (auto &c : clients_) {
                        if (c.addr.sin_addr.s_addr == src_addr.sin_addr.s_addr &&
                            c.addr.sin_port == src_addr.sin_port) {
                            c.last_seq_received = fb.last_seq_received;
                            c.packets_lost = fb.total_packets_lost;
                            c.loss_pct_scaled = fb.loss_fraction_percent;
                            c.jitter_us = fb.jitter_us;
                            c.buffer_delay_ms = fb.buffer_delay_ms;
                            c.rtt_ms = fb.rtt_ms;
                            break;
                        }
                    }
                }

                if (feedback_cb_) {
                    feedback_cb_(fb.loss_fraction_percent, fb.jitter_us, fb.rtt_ms);
                }
            }
            break;
        }

        case zap::PKT_CONTROL_REQ: {
            if (payload_len >= sizeof(zap::ControlPayload)) {
                zap::ControlPayload ctrl;
                std::memcpy(&ctrl, payload, sizeof(ctrl));
                ctrl.to_host();

                if (control_cb_) {
                    control_cb_(ctrl.command, ctrl.target_bitrate);
                }
            }
            break;
        }

        default:
            break;
    }
}

TransportStats NetworkServer::get_stats() const {
    TransportStats stats;
    stats.packets_sent = packets_sent_.load(std::memory_order_relaxed);
    stats.bytes_sent = bytes_sent_.load(std::memory_order_relaxed);

    std::lock_guard<std::mutex> lock(const_cast<std::mutex&>(clients_mutex_));
    stats.active_clients = static_cast<uint32_t>(clients_.size());

    if (!clients_.empty()) {
        const auto &c = clients_.front();
        stats.client_reported_loss_pct = c.loss_pct_scaled;
        stats.client_reported_jitter_us = c.jitter_us;
        stats.client_reported_buffer_ms = c.buffer_delay_ms;
        stats.client_reported_rtt_ms = c.rtt_ms;
    }

    return stats;
}

} // namespace zenith
