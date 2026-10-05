package com.zenith.audio.network

import android.content.Context
import android.os.Process
import android.util.Log
import com.zenith.audio.audio.LowLatencyAudioPlayer
import com.zenith.audio.codec.OpusMediaCodecDecoder
import com.zenith.audio.jitter.AdaptiveJitterBuffer
import com.zenith.audio.model.ConnectionState
import com.zenith.audio.model.StreamMetrics
import com.zenith.audio.protocol.ZapProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class UdpAudioReceiver(private val context: Context) {
    companion object {
        private const val TAG = "UdpReceiver"
        private const val SOCKET_TIMEOUT_MS = 2000
    }

    private val netIoExecutor = Executors.newSingleThreadExecutor()

    private val _metrics = MutableStateFlow(StreamMetrics())
    val metrics: StateFlow<StreamMetrics> = _metrics.asStateFlow()

    private val isRunning = AtomicBoolean(false)
    private var rxThread: Thread? = null
    private var playThread: Thread? = null
    private var telemetryThread: Thread? = null

    private var socket: DatagramSocket? = null
    private var serverAddress: InetAddress? = null
    private var serverPort: Int = ZapProtocol.DEFAULT_PORT

    private val jitterBuffer = AdaptiveJitterBuffer()
    private val opusDecoder = OpusMediaCodecDecoder()
    private val audioPlayer = LowLatencyAudioPlayer(context)

    private var measuredRttMs: Double = 0.0
    private var currentBitrateKbps: Int = 320
    private var lastPacketTimeNs: Long = 0L

    fun start(serverIp: String, port: Int = ZapProtocol.DEFAULT_PORT, initialBitrateKbps: Int = 320): Boolean {
        if (isRunning.get()) stop()

        currentBitrateKbps = initialBitrateKbps
        serverPort = port

        try {
            serverAddress = InetAddress.getByName(serverIp)
            socket = DatagramSocket().apply {
                soTimeout = SOCKET_TIMEOUT_MS
                receiveBufferSize = 256 * 1024
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create socket or resolve host $serverIp", e)
            return false
        }

        jitterBuffer.reset()
        if (!opusDecoder.start()) {
            socket?.close()
            return false
        }
        if (!audioPlayer.start()) {
            opusDecoder.stop()
            socket?.close()
            return false
        }

        isRunning.set(true)
        lastPacketTimeNs = System.nanoTime()

        _metrics.value = StreamMetrics(
            connectionState = ConnectionState.CONNECTING,
            serverIp = serverIp,
            bitrateKbps = initialBitrateKbps
        )

        rxThread = Thread({ rxLoop() }, "Zenith-UDP-Rx").apply { start() }
        playThread = Thread({ playLoop() }, "Zenith-Audio-Play").apply { start() }
        telemetryThread = Thread({ telemetryLoop() }, "Zenith-Telemetry").apply { start() }

        return true
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return

        try {
            socket?.close()
        } catch (_: Exception) {}

        rxThread?.join(500)
        playThread?.join(500)
        telemetryThread?.join(500)

        opusDecoder.stop()
        audioPlayer.stop()

        _metrics.value = _metrics.value.copy(
            connectionState = ConnectionState.DISCONNECTED
        )
    }

    fun setBitrate(bitrateKbps: Int) {
        currentBitrateKbps = bitrateKbps
        val targetBps = bitrateKbps * 1000L
        val packet = ZapProtocol.buildControlPacket(ZapProtocol.CMD_SET_BITRATE, targetBps)
        sendRaw(packet)
    }

    private fun sendPing() {
        val nowUs = System.nanoTime() / 1000L
        val pingBytes = ZapProtocol.buildPingPacket(nowUs)
        sendRaw(pingBytes)
    }

    private fun sendFeedback() {
        val totalRcv = jitterBuffer.getPacketsReceived()
        val totalLost = jitterBuffer.getPacketsLost()
        val lossFraction = if (totalRcv + totalLost > 0) {
            ((totalLost * 10000) / (totalRcv + totalLost)).toInt()
        } else 0

        val fbBytes = ZapProtocol.buildFeedbackPacket(
            lastSeqReceived = jitterBuffer.getNextExpectedSeq(),
            totalPacketsReceived = totalRcv,
            totalPacketsLost = totalLost,
            lossFractionPercent = lossFraction,
            jitterUs = (jitterBuffer.getJitterMs() * 1000.0).toInt(),
            bufferDelayMs = jitterBuffer.getCurrentBufferDelayMs().toInt(),
            rttMs = measuredRttMs.toInt()
        )
        sendRaw(fbBytes)
    }

    private fun sendRaw(data: ByteArray) {
        val s = socket ?: return
        val addr = serverAddress ?: return
        netIoExecutor.execute {
            try {
                val dgram = DatagramPacket(data, data.size, addr, serverPort)
                s.send(dgram)
            } catch (e: Exception) {
                Log.e(TAG, "sendRaw failed to $addr:$serverPort", e)
            }
        }
    }

    private fun rxLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val rxBuffer = ByteArray(2048)
        val packet = DatagramPacket(rxBuffer, rxBuffer.size)

        // Send initial ping from background thread
        sendPing()
        Log.i(TAG, "Sent initial PING to $serverAddress:$serverPort")

        while (isRunning.get()) {
            try {
                val s = socket ?: break
                packet.length = rxBuffer.size // Ensure buffer length is not truncated by previous small packet
                s.receive(packet)

                lastPacketTimeNs = System.nanoTime()
                val receiveTimeUs = lastPacketTimeNs / 1000L

                val byteBuffer = ByteBuffer.wrap(packet.data, packet.offset, packet.length)
                val header = ZapProtocol.parseHeader(byteBuffer) ?: continue

                when (header.type) {
                    ZapProtocol.PKT_AUDIO_FRAME -> {
                        val payloadOffset = packet.offset + ZapProtocol.HEADER_SIZE
                        val payloadSize = header.payloadSize
                        if (payloadSize > 0 && payloadOffset + payloadSize <= packet.offset + packet.length) {
                            val pooledPacket = jitterBuffer.obtainPacket(
                                header,
                                packet.data,
                                payloadOffset,
                                payloadSize,
                                receiveTimeUs
                            )
                            jitterBuffer.pushPacket(pooledPacket)
                        }
                    }

                    ZapProtocol.PKT_PONG -> {
                        if (packet.length >= ZapProtocol.HEADER_SIZE + 16) {
                            val pldBuf = ByteBuffer.wrap(packet.data, packet.offset + ZapProtocol.HEADER_SIZE, 16)
                                .order(ByteOrder.BIG_ENDIAN)
                            val clientSendTimeUs = pldBuf.long
                            val roundTripUs = receiveTimeUs - clientSendTimeUs
                            if (roundTripUs in 1..2_000_000) {
                                measuredRttMs = measuredRttMs * 0.8 + (roundTripUs / 1000.0) * 0.2
                            }
                        }
                    }

                    ZapProtocol.PKT_SERVER_ANNOUNCE -> {
                        if (packet.length >= ZapProtocol.HEADER_SIZE + 20) {
                            val pldBuf = ByteBuffer.wrap(packet.data, packet.offset + ZapProtocol.HEADER_SIZE, 20)
                                .order(ByteOrder.BIG_ENDIAN)
                            pldBuf.int // sample rate
                            pldBuf.short // channels
                            pldBuf.short // frame samples
                            val bitrate = pldBuf.int
                            currentBitrateKbps = bitrate / 1000
                        }
                    }
                }
            } catch (e: Exception) {
                // Socket timeout or read error
                if (!isRunning.get()) break
            }
        }
    }

    private fun playLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

        while (isRunning.get()) {
            val popResult = jitterBuffer.popNextPacket()

            when (popResult) {
                is AdaptiveJitterBuffer.PopResult.Packet -> {
                    val pkt = popResult.audioPacket
                    opusDecoder.decode(
                        pkt.payload,
                        0,
                        pkt.payloadSize,
                        pkt.header.timestampUs
                    ) { pcmBuffer, pcmSize ->
                        audioPlayer.write(pcmBuffer, pcmSize)
                    }
                    jitterBuffer.recyclePacket(pkt)
                }

                is AdaptiveJitterBuffer.PopResult.LostPacket -> {
                    // Conceal lost packet with 10ms of silence in AudioTrack to prevent underruns
                    audioPlayer.writeSilence(10)
                }

                is AdaptiveJitterBuffer.PopResult.WaitingForReorder -> {
                    try {
                        Thread.sleep(1)
                    } catch (_: Exception) {}
                }

                is AdaptiveJitterBuffer.PopResult.Empty -> {
                    try {
                        Thread.sleep(2)
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private fun telemetryLoop() {
        var pingCounter = 0

        while (isRunning.get()) {
            try {
                Thread.sleep(500) // 2Hz stats update & feedback
            } catch (_: Exception) {
                break
            }

            pingCounter++
            if (pingCounter % 4 == 0) { // Every 1 second
                sendPing()
            }
            sendFeedback()

            val nowNs = System.nanoTime()
            val timeSinceLastPacketMs = (nowNs - lastPacketTimeNs) / 1_000_000.0

            val connectionState = when {
                timeSinceLastPacketMs > 3000.0 -> {
                    // Watchdog: attempt auto-reconnect ping
                    sendPing()
                    ConnectionState.RECONNECTING
                }
                jitterBuffer.getPacketsReceived() > 0 -> ConnectionState.CONNECTED
                else -> ConnectionState.CONNECTING
            }

            val rtt = measuredRttMs
            val jitter = jitterBuffer.getJitterMs()
            val jbufDelay = jitterBuffer.getCurrentBufferDelayMs()
            val playDelay = audioPlayer.getPlaybackLatencyMs()
            val totalEstLatency = (rtt / 2.0) + jbufDelay + playDelay

            val totalRcv = jitterBuffer.getPacketsReceived()
            val totalLost = jitterBuffer.getPacketsLost()
            val lossPct = if (totalRcv + totalLost > 0) {
                (totalLost.toDouble() / (totalRcv + totalLost).toDouble()) * 100.0
            } else 0.0

            val (devName, isBt) = audioPlayer.getOutputDeviceInfo()

            _metrics.value = StreamMetrics(
                connectionState = connectionState,
                serverIp = serverAddress?.hostAddress ?: "",
                bitrateKbps = currentBitrateKbps,
                sampleRate = 48000,
                channels = "Stereo",
                networkRttMs = rtt,
                jitterMs = jitter,
                jitterBufferMs = jbufDelay,
                playbackLatencyMs = playDelay,
                totalEstimatedLatencyMs = totalEstLatency,
                outputDeviceName = devName,
                isBluetooth = isBt,
                packetLossPercent = lossPct,
                packetsReceived = totalRcv,
                packetsLost = totalLost,
                audioUnderruns = audioPlayer.getUnderruns()
            )
        }
    }
}
