package com.zenith.audio.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import java.nio.ByteBuffer

class LowLatencyAudioPlayer(private val context: Context) {
    companion object {
        private const val TAG = "AudioPlayer"
        const val SAMPLE_RATE = 48000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_STEREO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioTrack: AudioTrack? = null
    private val audioTimestamp = AudioTimestamp()
    private var totalFramesWritten: Long = 0

    private val silenceScratch = ByteArray(1920) // 10ms at 48kHz stereo 16-bit

    fun start(): Boolean {
        return try {
            val minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)

            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            val format = AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL_CONFIG)
                .setEncoding(AUDIO_FORMAT)
                .build()

            val track = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()

            // Pre-seed with 30ms of silence so hardware DAC starts smoothly without underruns
            val primeSilence = ByteArray(1920 * 3)
            track.write(primeSilence, 0, primeSilence.size, AudioTrack.WRITE_BLOCKING)

            track.play()
            audioTrack = track
            totalFramesWritten = (primeSilence.size / 4).toLong()
            Log.i(TAG, "LowLatencyAudioPlayer started. Buffer size: $minBufferSize bytes")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AudioTrack", e)
            false
        }
    }

    fun writeSilence(durationMs: Int = 10) {
        val frames = (SAMPLE_RATE * durationMs) / 1000
        val bytes = frames * 4
        if (bytes <= silenceScratch.size) {
            write(silenceScratch, bytes)
        } else {
            val buf = ByteArray(bytes)
            write(buf, bytes)
        }
    }

    fun stop() {
        try {
            audioTrack?.let {
                if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    it.stop()
                }
                it.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioTrack", e)
        } finally {
            audioTrack = null
        }
    }

    fun write(data: ByteArray, size: Int): Int {
        val track = audioTrack ?: return 0
        val bytesWritten = track.write(data, 0, size, AudioTrack.WRITE_BLOCKING)
        if (bytesWritten > 0) {
            // 4 bytes per stereo 16-bit frame (2 bytes left + 2 bytes right)
            totalFramesWritten += (bytesWritten / 4)
        }
        return bytesWritten
    }

    fun write(byteBuffer: ByteBuffer, size: Int): Int {
        val track = audioTrack ?: return 0
        val bytesWritten = track.write(byteBuffer, size, AudioTrack.WRITE_BLOCKING)
        if (bytesWritten > 0) {
            totalFramesWritten += (bytesWritten / 4)
        }
        return bytesWritten
    }

    fun setPlaybackSpeed(speed: Float) {
        val track = audioTrack ?: return
        try {
            val params = track.playbackParams
            if (Math.abs(params.speed - speed) > 0.005f) {
                track.playbackParams = params.setSpeed(speed)
            }
        } catch (_: Exception) {}
    }

    fun getUnderruns(): Int {
        return audioTrack?.underrunCount ?: 0
    }

    fun getPlaybackLatencyMs(): Double {
        val track = audioTrack ?: return 0.0
        return try {
            if (track.getTimestamp(audioTimestamp)) {
                val framesInHardwareQueue = totalFramesWritten - audioTimestamp.framePosition
                if (framesInHardwareQueue > 0) {
                    (framesInHardwareQueue.toDouble() / SAMPLE_RATE.toDouble()) * 1000.0
                } else {
                    // Fallback estimate based on buffer size
                    (track.bufferSizeInFrames.toDouble() / SAMPLE_RATE.toDouble()) * 1000.0
                }
            } else {
                (track.bufferSizeInFrames.toDouble() / SAMPLE_RATE.toDouble()) * 1000.0
            }
        } catch (e: Exception) {
            10.0
        }
    }

    fun getOutputDeviceInfo(): Pair<String, Boolean> {
        val track = audioTrack
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        var deviceName = "Phone Speaker"
        var isBluetooth = false

        if (track != null) {
            val routedDevice = track.routedDevice
            if (routedDevice != null) {
                return parseDeviceInfo(routedDevice)
            }
        }

        // Fallback to active output devices
        val devices = audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS) ?: emptyArray()
        for (device in devices) {
            if (device.isSink) {
                val (name, bt) = parseDeviceInfo(device)
                if (bt) return Pair(name, bt) // Prioritize reporting Bluetooth if connected
                deviceName = name
            }
        }

        return Pair(deviceName, isBluetooth)
    }

    private fun parseDeviceInfo(device: AudioDeviceInfo): Pair<String, Boolean> {
        val isBt = when (device.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER -> true
            else -> false
        }

        val name = when (device.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP (${device.productName})"
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE Audio (${device.productName})"
            AudioDeviceInfo.TYPE_BLE_SPEAKER -> "Bluetooth LE Speaker (${device.productName})"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headphones"
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Audio (${device.productName})"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone Speaker"
            else -> device.productName.toString().ifEmpty { "Default Output" }
        }

        return Pair(name, isBt)
    }
}
