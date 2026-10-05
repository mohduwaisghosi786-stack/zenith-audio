package com.zenith.audio.codec

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

class OpusMediaCodecDecoder(
    private val sampleRate: Int = 48000,
    private val channels: Int = 2
) {
    companion object {
        private const val TAG = "OpusDecoder"
        private const val MIME_TYPE = "audio/opus"
    }

    private var codec: MediaCodec? = null
    private val bufferInfo = MediaCodec.BufferInfo()
    private var isConfigured = false

    // Pre-allocated reusable PCM buffer to eliminate GC allocations
    private val pcmScratchBuffer = ByteArray(8192)

    fun start(): Boolean {
        return try {
            val decoder = MediaCodec.createDecoderByType(MIME_TYPE)
            val format = MediaFormat.createAudioFormat(MIME_TYPE, sampleRate, channels)

            // CSD-0: 19 bytes OpusHead
            val csd0 = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
            csd0.put("OpusHead".toByteArray(Charsets.US_ASCII)) // 8 bytes
            csd0.put(1.toByte())                                // Version
            csd0.put(channels.toByte())                         // Channels
            csd0.putShort(0.toShort())                          // Pre-skip
            csd0.putInt(sampleRate)                             // Input Sample Rate
            csd0.putShort(0.toShort())                          // Output Gain
            csd0.put(0.toByte())                                // Channel mapping family
            csd0.flip()
            format.setByteBuffer("csd-0", csd0)

            // CSD-1: Pre-skip in nanoseconds
            val csd1 = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            csd1.putLong(0L)
            csd1.flip()
            format.setByteBuffer("csd-1", csd1)

            // CSD-2: Seek pre-roll in nanoseconds (80ms)
            val csd2 = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            csd2.putLong(80_000_000L)
            csd2.flip()
            format.setByteBuffer("csd-2", csd2)

            // Low-latency mode for Android 11+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }

            decoder.configure(format, null, null, 0)
            decoder.start()

            codec = decoder
            isConfigured = true
            Log.i(TAG, "Opus MediaCodec initialized successfully ($sampleRate Hz, $channels ch)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Opus MediaCodec decoder", e)
            false
        }
    }

    fun stop() {
        try {
            codec?.stop()
            codec?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing MediaCodec", e)
        } finally {
            codec = null
            isConfigured = false
        }
    }

    /**
     * Decodes an Opus packet and passes PCM 16-bit audio to onPcmDecoded callback.
     * Prevents dropped input frames by draining output buffers when pipeline is full.
     */
    fun decode(
        opusPacket: ByteArray,
        offset: Int,
        size: Int,
        timestampUs: Long,
        onPcmDecoded: (ByteArray, Int) -> Unit
    ) {
        val decoder = codec ?: return
        if (!isConfigured || size <= 0) return

        try {
            var inIndex = decoder.dequeueInputBuffer(2000)
            if (inIndex < 0) {
                // Relieve pipeline backpressure by draining output buffers
                drainOutput(decoder, onPcmDecoded)
                inIndex = decoder.dequeueInputBuffer(4000)
            }

            if (inIndex >= 0) {
                val inBuffer = decoder.getInputBuffer(inIndex)
                if (inBuffer != null) {
                    inBuffer.clear()
                    inBuffer.put(opusPacket, offset, size)
                    decoder.queueInputBuffer(inIndex, 0, size, timestampUs, 0)
                }
            } else {
                Log.w(TAG, "MediaCodec input buffer full; draining and retrying")
                drainOutput(decoder, onPcmDecoded)
            }

            // Drain decoded PCM buffers
            drainOutput(decoder, onPcmDecoded)
        } catch (e: Exception) {
            Log.e(TAG, "Decode error", e)
        }
    }

    private fun drainOutput(decoder: MediaCodec, onPcmDecoded: (ByteArray, Int) -> Unit) {
        var outIndex = decoder.dequeueOutputBuffer(bufferInfo, 0)
        while (outIndex >= 0) {
            val outBuffer = decoder.getOutputBuffer(outIndex)
            val pcmSize = bufferInfo.size
            if (outBuffer != null && pcmSize > 0) {
                outBuffer.position(bufferInfo.offset)
                if (pcmSize <= pcmScratchBuffer.size) {
                    outBuffer.get(pcmScratchBuffer, 0, pcmSize)
                    decoder.releaseOutputBuffer(outIndex, false)
                    onPcmDecoded(pcmScratchBuffer, pcmSize)
                } else {
                    val temp = ByteArray(pcmSize)
                    outBuffer.get(temp)
                    decoder.releaseOutputBuffer(outIndex, false)
                    onPcmDecoded(temp, pcmSize)
                }
            } else {
                decoder.releaseOutputBuffer(outIndex, false)
            }
            outIndex = decoder.dequeueOutputBuffer(bufferInfo, 0)
        }
    }
}
