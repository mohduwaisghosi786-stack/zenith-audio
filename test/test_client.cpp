#include "protocol.hpp"
#include <opus/opus.h>
#include <iostream>
#include <vector>
#include <cmath>
#include <cstring>
#include <unistd.h>
#include <arpa/inet.h>
#include <chrono>

static uint64_t get_now_us() {
    auto now = std::chrono::steady_clock::now();
    return std::chrono::duration_cast<std::chrono::microseconds>(now.time_since_epoch()).count();
}

int main(int argc, char *argv[]) {
    const char *host = "127.0.0.1";
    uint16_t port = zap::DEFAULT_PORT;
    if (argc > 1) host = argv[1];
    if (argc > 2) port = static_cast<uint16_t>(std::stoi(argv[2]));

    int sockfd = socket(AF_INET, SOCK_DGRAM, 0);
    if (sockfd < 0) {
        perror("Socket creation failed");
        return 1;
    }

    struct timeval tv;
    tv.tv_sec = 0;
    tv.tv_usec = 500000; // 500ms timeout
    setsockopt(sockfd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

    struct sockaddr_in serv_addr = {};
    serv_addr.sin_family = AF_INET;
    serv_addr.sin_port = htons(port);
    inet_pton(AF_INET, host, &serv_addr.sin_addr);

    // Create Opus decoder
    int err = OPUS_OK;
    OpusDecoder *dec = opus_decoder_create(48000, 2, &err);
    if (err != OPUS_OK) {
        std::cerr << "Failed to create opus decoder\n";
        return 1;
    }

    std::cout << "[TestClient] Sending initial PING to " << host << ":" << port << "...\n";

    zap::Header ping_hdr;
    ping_hdr.magic = zap::MAGIC;
    ping_hdr.version = zap::VERSION;
    ping_hdr.type = zap::PKT_PING;
    ping_hdr.seq_num = 0;
    ping_hdr.timestamp_us = get_now_us();
    ping_hdr.payload_size = sizeof(zap::PingPayload);
    ping_hdr.flags = zap::FLAG_NONE;

    zap::PingPayload ping_pld;
    ping_pld.client_timestamp_us = ping_hdr.timestamp_us;
    ping_pld.server_timestamp_us = 0;

    uint8_t send_buf[sizeof(zap::Header) + sizeof(zap::PingPayload)];
    zap::Header net_hdr = ping_hdr;
    net_hdr.to_network();
    zap::PingPayload net_pld = ping_pld;
    net_pld.to_network();

    std::memcpy(send_buf, &net_hdr, sizeof(net_hdr));
    std::memcpy(send_buf + sizeof(net_hdr), &net_pld, sizeof(net_pld));

    sendto(sockfd, send_buf, sizeof(send_buf), 0, (struct sockaddr*)&serv_addr, sizeof(serv_addr));

    uint32_t last_seq = 0;
    uint32_t packets_received = 0;
    uint32_t packets_lost = 0;
    double jitter_estimate_us = 0.0;
    int64_t prev_transit_us = 0;
    bool has_prev = false;

    std::vector<float> pcm_out(480 * 2);
    uint8_t rx_buf[2048];

    std::cout << "[TestClient] Listening for audio packets for 3 seconds...\n";
    auto start_time = std::chrono::steady_clock::now();

    while (true) {
        auto now = std::chrono::steady_clock::now();
        if (std::chrono::duration_cast<std::chrono::seconds>(now - start_time).count() >= 3) {
            break;
        }

        struct sockaddr_in from_addr;
        socklen_t from_len = sizeof(from_addr);
        ssize_t n = recvfrom(sockfd, rx_buf, sizeof(rx_buf), 0, (struct sockaddr*)&from_addr, &from_len);
        if (n < static_cast<ssize_t>(sizeof(zap::Header))) continue;

        zap::Header hdr;
        std::memcpy(&hdr, rx_buf, sizeof(hdr));
        hdr.to_host();

        if (hdr.magic != zap::MAGIC || hdr.version != zap::VERSION) continue;

        uint64_t rx_time_us = get_now_us();

        if (hdr.type == zap::PKT_AUDIO_FRAME) {
            packets_received++;
            if (packets_received > 1) {
                if (hdr.seq_num > last_seq + 1) {
                    packets_lost += (hdr.seq_num - last_seq - 1);
                }
            }
            last_seq = hdr.seq_num;

            // RFC 3550 jitter calculation
            int64_t transit_us = static_cast<int64_t>(rx_time_us) - static_cast<int64_t>(hdr.timestamp_us);
            if (has_prev) {
                int64_t d = std::abs(transit_us - prev_transit_us);
                jitter_estimate_us += (static_cast<double>(d) - jitter_estimate_us) / 16.0;
            } else {
                has_prev = true;
            }
            prev_transit_us = transit_us;

            // Decode Opus payload
            const uint8_t *payload = rx_buf + sizeof(zap::Header);
            int samples = opus_decode_float(dec, payload, hdr.payload_size, pcm_out.data(), 480, 0);

            if (packets_received % 100 == 0) {
                std::cout << "[TestClient] Received " << packets_received << " frames | Seq: " << hdr.seq_num
                          << " | Decoded " << samples << " samples | Jitter: "
                          << (jitter_estimate_us / 1000.0) << " ms\n";
            }
        } else if (hdr.type == zap::PKT_PONG) {
            std::cout << "[TestClient] Received PONG from server!\n";
        } else if (hdr.type == zap::PKT_SERVER_ANNOUNCE) {
            std::cout << "[TestClient] Received SERVER_ANNOUNCE from server!\n";
        }
    }

    // Send final feedback to server
    zap::Header fb_hdr;
    fb_hdr.magic = zap::MAGIC;
    fb_hdr.version = zap::VERSION;
    fb_hdr.type = zap::PKT_CLIENT_FEEDBACK;
    fb_hdr.seq_num = 0;
    fb_hdr.timestamp_us = get_now_us();
    fb_hdr.payload_size = sizeof(zap::FeedbackPayload);
    fb_hdr.flags = zap::FLAG_NONE;

    zap::FeedbackPayload fb_pld;
    fb_pld.last_seq_received = last_seq;
    fb_pld.total_packets_received = packets_received;
    fb_pld.total_packets_lost = packets_lost;
    fb_pld.loss_fraction_percent = (packets_received + packets_lost > 0)
        ? static_cast<uint16_t>((packets_lost * 10000) / (packets_received + packets_lost))
        : 0;
    fb_pld.jitter_us = static_cast<uint16_t>(jitter_estimate_us);
    fb_pld.buffer_delay_ms = 10;
    fb_pld.rtt_ms = 2;

    uint8_t fb_buf[sizeof(zap::Header) + sizeof(zap::FeedbackPayload)];
    fb_hdr.to_network();
    fb_pld.to_network();
    std::memcpy(fb_buf, &fb_hdr, sizeof(fb_hdr));
    std::memcpy(fb_buf + sizeof(fb_hdr), &fb_pld, sizeof(fb_pld));
    sendto(sockfd, fb_buf, sizeof(fb_buf), 0, (struct sockaddr*)&serv_addr, sizeof(serv_addr));

    std::cout << "\n[TestClient Summary] Total packets received: " << packets_received
              << " | Total lost: " << packets_lost
              << " | Final Jitter: " << (jitter_estimate_us / 1000.0) << " ms\n";

    opus_decoder_destroy(dec);
    close(sockfd);
    return 0;
}
