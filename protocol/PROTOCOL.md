# Zenith Audio Protocol (ZAP) Specification

## Overview
Zenith Audio Protocol (ZAP) is an ultra-low-latency, connectionless, UDP-based binary protocol designed specifically for streaming high-fidelity audio from Linux to Android devices over local Wi-Fi and Ethernet networks.

## Design Goals
1. **Ultra-Low Latency**: No connection handshakes, no head-of-line blocking (HOL), compact 20-byte fixed header.
2. **Packet-Loss Resilience**: In-band sequence tracking, support for Opus Forward Error Correction (FEC), and packet loss feedback loops.
3. **Adaptive Jitter Tracking**: RFC 3550 standard interarrival jitter measurement via microsecond precision timestamps.
4. **Dynamic Bitrate & Codec Negotiation**: Allows the client to adjust streaming bitrate on the fly (64 kbps to 320 kbps).

## Packet Framing
Every datagram begins with the 20-byte `ZapHeader`:

| Field | Size (bytes) | Type | Description |
|---|---|---|---|
| `magic` | 2 | `uint16_t` | Constant `0x5A41` ('Z', 'A') |
| `version` | 1 | `uint8_t` | Protocol version (`0x01`) |
| `type` | 1 | `uint8_t` | Packet type enum |
| `seq_num` | 4 | `uint32_t` | Monotonically increasing sequence number |
| `timestamp_us` | 8 | `uint64_t` | Monotonic timestamp in microseconds |
| `payload_size` | 2 | `uint16_t` | Length of following payload |
| `flags` | 2 | `uint16_t` | Bitmask: Bit 0 = FEC present, Bit 1 = Discontinuity |

## Packet Types
- `0x01` (`PKT_AUDIO_FRAME`): Opus encoded audio frame (or PCM16 fallback).
- `0x02` (`PKT_PING`): Probing packet for RTT and clock drift calculation.
- `0x03` (`PKT_PONG`): Reply to Ping packet containing client and server timestamps.
- `0x04` (`PKT_CLIENT_FEEDBACK`): Statistics feedback (loss rate, jitter, buffer delay, RTT).
- `0x05` (`PKT_SERVER_ANNOUNCE`): Stream metadata (sample rate, channels, frame size, bitrate).
- `0x06` (`PKT_CONTROL_REQ`): Client control requests (bitrate adjustment, pause, resume, resync).

## Default Audio Parameters
- **Sample Rate**: 48,000 Hz
- **Channels**: 2 (Stereo)
- **Frame Size**: 480 samples (10 milliseconds)
- **Opus Application**: `OPUS_APPLICATION_RESTRICTED_LOWDELAY`
- **Bitrate**: 320,000 bps (320 kbps) default
- **Port**: UDP 8088
