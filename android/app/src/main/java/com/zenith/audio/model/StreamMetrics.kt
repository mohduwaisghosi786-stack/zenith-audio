package com.zenith.audio.model

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING
}

data class StreamMetrics(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val serverIp: String = "",
    val bitrateKbps: Int = 320,
    val sampleRate: Int = 48000,
    val channels: String = "Stereo",
    val networkRttMs: Double = 0.0,
    val jitterMs: Double = 0.0,
    val jitterBufferMs: Double = 0.0,
    val playbackLatencyMs: Double = 0.0,
    val totalEstimatedLatencyMs: Double = 0.0,
    val outputDeviceName: String = "Phone Speaker",
    val isBluetooth: Boolean = false,
    val packetLossPercent: Double = 0.0,
    val packetsReceived: Long = 0,
    val packetsLost: Long = 0,
    val audioUnderruns: Int = 0
)
