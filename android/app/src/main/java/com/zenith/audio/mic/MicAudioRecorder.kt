package com.zenith.audio.mic

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import com.zenith.audio.protocol.ZapProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

class MicAudioRecorder(
    private val context: Context,
    private val sendRawPacket: (ByteArray) -> Unit
) {
    companion object {
        private const val TAG = "MicAudioRecorder"
        private const val SAMPLE_RATE = 48000
        private const val CHANNELS = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val FRAME_SAMPLES = 960 // 20ms at 48kHz
        private const val BYTES_PER_SAMPLE = 2
        private const val FRAME_BYTES = FRAME_SAMPLES * BYTES_PER_SAMPLE // 1920 bytes
    }

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _micLevel = MutableStateFlow(0f)
    val micLevel: StateFlow<Float> = _micLevel.asStateFlow()

    private val running = AtomicBoolean(false)
    private var recordThread: Thread? = null
    private var audioRecord: AudioRecord? = null

    fun start(): Boolean {
        if (running.get()) return true

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "RECORD_AUDIO permission not granted")
            return false
        }

        val minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNELS, ENCODING)
        val bufSize = maxOf(minBufSize, FRAME_BYTES * 4)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                CHANNELS,
                ENCODING,
                bufSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                audioRecord?.release()
                audioRecord = null
                return false
            }

            audioRecord?.startRecording()
            running.set(true)
            _isRecording.value = true

            // Send mic state ON control packet
            sendRawPacket(ZapProtocol.buildMicStatePacket(true))

            recordThread = Thread({ recordLoop() }, "ZenithMicRecordThread").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }

            Log.i(TAG, "Wireless microphone recording started (48kHz Mono 20ms)")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error starting mic recorder", e)
            stop()
            return false
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return

        _isRecording.value = false
        _micLevel.value = 0f

        // Send mic state OFF control packet
        try {
            sendRawPacket(ZapProtocol.buildMicStatePacket(false))
        } catch (_: Exception) {}

        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioRecord: ${e.message}")
        }

        try {
            recordThread?.join(500)
        } catch (_: Exception) {}
        recordThread = null

        audioRecord?.release()
        audioRecord = null

        Log.i(TAG, "Wireless microphone recording stopped")
    }

    private fun recordLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

        val record = audioRecord ?: return
        val buffer = ByteArray(FRAME_BYTES)
        var seq = 0

        var sumSquares = 0.0
        var samplesCount = 0

        while (running.get()) {
            val bytesRead = record.read(buffer, 0, FRAME_BYTES)
            if (bytesRead > 0) {
                // Calculate RMS audio level for UI indicator
                var i = 0
                while (i < bytesRead - 1) {
                    val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
                    val s = sample.toShort().toDouble()
                    sumSquares += s * s
                    samplesCount++
                    i += 2
                }

                if (samplesCount >= 4800) { // Update level roughly every 100ms
                    val rms = sqrt(sumSquares / samplesCount)
                    val normalized = (rms / 32768.0).toFloat().coerceIn(0f, 1f)
                    _micLevel.value = normalized
                    sumSquares = 0.0
                    samplesCount = 0
                }

                // Send PCM frame to server
                val packet = ZapProtocol.buildMicAudioPacket(seq++, buffer, bytesRead, isPcm = true)
                sendRawPacket(packet)
            } else if (bytesRead < 0) {
                Log.w(TAG, "AudioRecord read error: $bytesRead")
                break
            }
        }
    }
}
