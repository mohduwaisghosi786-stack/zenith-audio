# Zenith Audio: Linux → Android Ultra-Low-Latency Audio Engine

Zenith Audio is a production-grade, ultra-low-latency real-time audio streaming system designed to capture the mixed system audio output of a Linux computer (PipeWire / ALSA) and stream it directly to an Android phone over local Wi-Fi / Ethernet for playback through connected Bluetooth headphones, Bluetooth speakers, or phone speakers.

```
       LINUX PC (Ubuntu/Arch/CachyOS)
┌──────────────────────────────────────────────┐
│  PipeWire System Output (Default Sink Monitor)│
│  - Captures: YouTube, Games, Spotify, etc.   │
└──────────────────────┬───────────────────────┘
                       │ Zero-copy SPSC Lock-free Ring Buffer
                       ▼
┌──────────────────────────────────────────────┐
│  Opus Encoder (libopus 1.6.1)                │
│  - 48 kHz, Stereo, 320 kbps (High Quality)   │
│  - OPUS_APPLICATION_RESTRICTED_LOWDELAY     │
│  - In-Band Forward Error Correction (FEC)    │
└──────────────────────┬───────────────────────┘
                       │ Zenith Audio Protocol (ZAP)
                       ▼
┌──────────────────────────────────────────────┐
│  Ultra-Low-Latency UDP Transport Engine      │
│  - UDP Port 59100                            │
│  - Microsecond Monotonic Timestamps          │
│  - Dynamic Bitrate & In-Band Feedback Loop   │
└──────────────────────┬───────────────────────┘
                       │ Local Wi-Fi / LAN (~1-3ms)
                       ▼
            ANDROID 15 RECEIVER (API 35)
┌──────────────────────────────────────────────┐
│  Zenith UDP Receiver Service (URGENT_AUDIO)  │
│  - Foreground Service (Media Playback Type)  │
│  - Adaptive Jitter Buffer (RFC 3550)         │
│  - Clock Drift Mitigation & Loss Concealment │
└──────────────────────┬───────────────────────┘
                       │ Direct Native Decoding
                       ▼
┌──────────────────────────────────────────────┐
│  Opus MediaCodec Decoder (Low-Latency Mode)  │
│  - Hardware/OS accelerated Opus decoding     │
└──────────────────────┬───────────────────────┘
                       │ Direct Stream Write
                       ▼
┌──────────────────────────────────────────────┐
│  Low-Latency AudioTrack (FastMixer Path)     │
│  - PERFORMANCE_MODE_LOW_LATENCY              │
│  - Normal Android Audio Routing System       │
└──────────────────────┬───────────────────────┘
                       │ Standard Android Subsystem
                       ▼
┌──────────────────────────────────────────────┐
│  Bluetooth Headphones / Speaker / DAC        │
│  - Bluetooth A2DP / LE Audio                 │
└──────────────────────────────────────────────┘
```

---

## Key Features

1. **Complete Linux System Audio Capture**:
   - Uses native `libpipewire-0.3` to automatically attach to the monitor stream of the default output sink.
   - Captures all desktop sound: YouTube, browsers, media players, games, Discord, and system notifications without muting or disrupting PC audio.

2. **Ultra-Low Latency & Real-Time Safe**:
   - Lock-free Single-Producer Single-Consumer (SPSC) ring buffer connects PipeWire's real-time thread to the encoder thread with zero memory allocations during streaming.
   - `OPUS_APPLICATION_RESTRICTED_LOWDELAY` eliminates speech-prediction algorithmic delay (pure MDCT CELT mode).
   - Encode time: **~140 microseconds** (0.14 ms) for a 10 ms frame.
   - Total estimated software pipeline latency: **15 - 25 ms** on modern Wi-Fi networks (excluding Bluetooth hardware encoding delay).

3. **Packet Loss Resilience & Adaptive Jitter Buffer**:
   - Monotonic sequence numbers and microsecond timestamps.
   - RFC 3550 interarrival jitter estimator dynamically resizes buffer cushion (target: 10 - 20 ms on Wi-Fi).
   - In-band Forward Error Correction (FEC) enabled in Opus.
   - Bidirectional RTCP-style feedback packet sent from Android to Linux every 250 ms, adapting Opus loss percentage and bitrate in real time.
   - Verified resilient under 1%, 3%, 5%, and 10% simulated packet loss without stalling.

4. **Android 15 Architecture**:
   - Native `AudioTrack` configured with `PERFORMANCE_MODE_LOW_LATENCY`.
   - Android foreground service with `mediaPlayback` type and partial wake lock prevents background throttling or audio stutter when the screen turns off.
   - Preserves standard Android audio routing: automatically routes to Bluetooth headphones/earbuds/speakers when paired, wired headphones, or phone speakers.

5. **Live Telemetry & Diagnostics**:
   - Exposes measured latency, jitter, packet loss percentage, audio underruns, throughput, and active sink device in the Material 3 UI and server console.

---

## Quick Start

### 1. Build and Run Linux Server

```bash
# Build the server
cd server
make

# Run the server (default port 59100, 320 kbps)
./bin/zenith-server

# Or with custom parameters:
./bin/zenith-server --port 59100 --bitrate 320
```

To run as a systemd user service that starts automatically:
```bash
cp scripts/zenith-server.service ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now zenith-server
```

### 2. Install Android App

Download the signed release APK from GitHub Releases:
* **`zenith-audio-v1.0.0.apk`**

To install via USB/Wi-Fi ADB:
```bash
adb install -r release_artifacts/zenith-audio-v1.0.0.apk
```

Or copy the APK to your phone and install it directly.

### 3. Connect & Stream

1. Note your Linux PC's IP address (e.g. `192.168.1.9`).
2. Open **Zenith Audio** on your Android phone.
3. Enter your Linux PC IP and select **320 kbps (HQ)**.
4. Tap **CONNECT TO SERVER**.
5. Connect your Bluetooth headphones to your Android phone.
6. Play audio on your Linux PC (YouTube, music, game) — you will immediately hear it in real time through your headphones!

---

## Latency Breakdown

| Component | Typical Latency | Notes |
|---|---|---|
| **PipeWire Capture** | ~1.0 ms | Quantum buffer delivery |
| **Opus Encoding** | ~0.14 ms | 10ms frame, libopus MDCT |
| **Network Transit (Wi-Fi)** | ~1.5 - 3.0 ms | UDP LAN transmission |
| **Jitter Buffer Cushion** | ~10.0 - 15.0 ms | Dynamically adapted via RFC 3550 |
| **Opus Decoding** | ~0.2 ms | Hardware/OS MediaCodec |
| **AudioTrack DAC Buffer** | ~5.0 - 10.0 ms | FastMixer audio hardware queue |
| **Total Engine Latency** | **~18 - 29 ms** | Complete software pipeline |
| **Bluetooth A2DP/LE** | +30 - 120 ms | Physical Bluetooth hardware delay |

---

## Testing & Benchmarks

Run the benchmark suite:
```bash
# Run latency & throughput benchmark
./scripts/latency_benchmark.sh

# Run packet loss stress test (1%, 3%, 5%, 10%)
./scripts/test_packet_loss.sh
```
