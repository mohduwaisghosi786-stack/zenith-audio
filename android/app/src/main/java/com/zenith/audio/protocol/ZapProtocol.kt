package com.zenith.audio.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

object ZapProtocol {
    const val MAGIC: Short = 0x5A41.toShort() // 'Z', 'A'
    const val VERSION: Byte = 1
    const val DEFAULT_PORT = 59100

    const val PKT_AUDIO_FRAME: Byte = 0x01
    const val PKT_PING: Byte = 0x02
    const val PKT_PONG: Byte = 0x03
    const val PKT_CLIENT_FEEDBACK: Byte = 0x04
    const val PKT_SERVER_ANNOUNCE: Byte = 0x05
    const val PKT_CONTROL_REQ: Byte = 0x06

    const val CODEC_PCM16: Byte = 0x00
    const val CODEC_OPUS: Byte = 0x01

    const val FLAG_NONE: Short = 0x0000
    const val FLAG_FEC_PRESENT: Short = 0x0001
    const val FLAG_DISCONTINUITY: Short = 0x0002

    const val CMD_SET_BITRATE: Byte = 0x01
    const val CMD_RESYNC: Byte = 0x02
    const val CMD_PAUSE: Byte = 0x03
    const val CMD_RESUME: Byte = 0x04

    const val HEADER_SIZE = 20

    data class Header(
        val magic: Short,
        val version: Byte,
        val type: Byte,
        val seqNum: Long,       // uint32 represented as Long
        val timestampUs: Long,  // uint64
        val payloadSize: Int,   // uint16
        val flags: Short
    ) {
        val isFecPresent: Boolean get() = (flags.toInt() and FLAG_FEC_PRESENT.toInt()) != 0
        val isDiscontinuity: Boolean get() = (flags.toInt() and FLAG_DISCONTINUITY.toInt()) != 0
    }

    data class AudioPacket(
        val header: Header,
        val payload: ByteArray,
        val receiveTimeUs: Long
    )

    fun parseHeader(buffer: ByteBuffer): Header? {
        if (buffer.remaining() < HEADER_SIZE) return null
        buffer.order(ByteOrder.BIG_ENDIAN)

        val magic = buffer.short
        if (magic != MAGIC) return null

        val version = buffer.get()
        if (version != VERSION) return null

        val type = buffer.get()
        val seqNum = buffer.int.toLong() and 0xFFFFFFFFL
        val timestampUs = buffer.long
        val payloadSize = buffer.short.toInt() and 0xFFFF
        val flags = buffer.short

        return Header(magic, version, type, seqNum, timestampUs, payloadSize, flags)
    }

    fun buildPingPacket(clientTimestampUs: Long): ByteArray {
        val buf = ByteBuffer.allocate(HEADER_SIZE + 16).order(ByteOrder.BIG_ENDIAN)
        buf.putShort(MAGIC)
        buf.put(VERSION)
        buf.put(PKT_PING)
        buf.putInt(0)
        buf.putLong(clientTimestampUs)
        buf.putShort(16)
        buf.putShort(FLAG_NONE)

        buf.putLong(clientTimestampUs)
        buf.putLong(0L)
        return buf.array()
    }

    fun buildFeedbackPacket(
        lastSeqReceived: Long,
        totalPacketsReceived: Long,
        totalPacketsLost: Long,
        lossFractionPercent: Int, // e.g. 250 = 2.50%
        jitterUs: Int,
        bufferDelayMs: Int,
        rttMs: Int
    ): ByteArray {
        val buf = ByteBuffer.allocate(HEADER_SIZE + 20).order(ByteOrder.BIG_ENDIAN)
        buf.putShort(MAGIC)
        buf.put(VERSION)
        buf.put(PKT_CLIENT_FEEDBACK)
        buf.putInt(0)
        buf.putLong(System.nanoTime() / 1000L)
        buf.putShort(20)
        buf.putShort(FLAG_NONE)

        buf.putInt((lastSeqReceived and 0xFFFFFFFFL).toInt())
        buf.putInt((totalPacketsReceived and 0xFFFFFFFFL).toInt())
        buf.putInt((totalPacketsLost and 0xFFFFFFFFL).toInt())
        buf.putShort((lossFractionPercent and 0xFFFF).toShort())
        buf.putShort((jitterUs and 0xFFFF).toShort())
        buf.putShort((bufferDelayMs and 0xFFFF).toShort())
        buf.putShort((rttMs and 0xFFFF).toShort())
        return buf.array()
    }

    fun buildControlPacket(command: Byte, targetBitrate: Long): ByteArray {
        val buf = ByteBuffer.allocate(HEADER_SIZE + 8).order(ByteOrder.BIG_ENDIAN)
        buf.putShort(MAGIC)
        buf.put(VERSION)
        buf.put(PKT_CONTROL_REQ)
        buf.putInt(0)
        buf.putLong(System.nanoTime() / 1000L)
        buf.putShort(8)
        buf.putShort(FLAG_NONE)

        buf.put(command)
        buf.put(0.toByte())
        buf.putShort(0.toShort())
        buf.putInt(targetBitrate.toInt())
        return buf.array()
    }
}
