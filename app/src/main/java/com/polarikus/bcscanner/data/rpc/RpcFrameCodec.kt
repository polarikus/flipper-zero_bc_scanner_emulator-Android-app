package com.polarikus.bcscanner.data.rpc

import com.flipperdevices.protobuf.Flipper
import java.io.ByteArrayOutputStream

/**
 * Encodes and decodes the RPC framing used by the FlipperZero BLE serial profile.
 *
 * A frame is `varint(message length) || serialized PB_Main` (protobuf delimited), matching the
 * firmware's `PB_ENCODE_DELIMITED` / `PB_DECODE_DELIMITED`. BLE notifications may split a frame
 * across chunks or carry several frames at once, so [decode] is a streaming decoder that buffers
 * partial data between calls.
 *
 * Not thread-safe: feed it from a single collector.
 */
class RpcFrameCodec {

    private val buffer = ByteArrayOutputStream()

    /** Frames the given [message] into a single BLE-writable byte array. */
    fun encode(message: Flipper.Main): ByteArray {
        val payload = message.toByteArray()
        val out = ByteArrayOutputStream(payload.size + VARINT_MAX_BYTES)
        writeVarint(payload.size, out)
        out.write(payload, 0, payload.size)
        return out.toByteArray()
    }

    /**
     * Appends [chunk] to the internal buffer and returns every complete message that became
     * available. Incomplete trailing data stays buffered for the next call.
     */
    fun decode(chunk: ByteArray): List<Flipper.Main> {
        if (chunk.isNotEmpty()) {
            buffer.write(chunk, 0, chunk.size)
        }
        val result = mutableListOf<Flipper.Main>()
        while (true) {
            val bytes = buffer.toByteArray()
            val length = readVarintLength(bytes) ?: break
            val frameSize = length.headerSize + length.value
            if (bytes.size < frameSize) break

            val payload = bytes.copyOfRange(length.headerSize, frameSize)
            result += Flipper.Main.parseFrom(payload)

            buffer.reset()
            buffer.write(bytes, frameSize, bytes.size - frameSize)
        }
        return result
    }

    /** Drops any buffered partial frame (for example after a reconnect). */
    fun reset() {
        buffer.reset()
    }

    private fun writeVarint(value: Int, out: ByteArrayOutputStream) {
        var remaining = value
        while (true) {
            if (remaining and 0x7f.inv() == 0) {
                out.write(remaining)
                return
            }
            out.write((remaining and 0x7f) or 0x80)
            remaining = remaining ushr 7
        }
    }

    /**
     * Reads a varint length from the start of [bytes].
     *
     * @return the decoded length and the number of bytes it occupied, or `null` when the varint
     * is incomplete or malformed.
     */
    private fun readVarintLength(bytes: ByteArray): Varint? {
        var value = 0
        var shift = 0
        var index = 0
        while (index < bytes.size) {
            if (shift > MAX_VARINT_SHIFT) return null
            val current = bytes[index].toInt() and 0xff
            value = value or ((current and 0x7f) shl shift)
            index++
            if (current and 0x80 == 0) {
                return Varint(value, index)
            }
            shift += 7
        }
        return null
    }

    private data class Varint(val value: Int, val headerSize: Int)

    private companion object {
        const val MAX_VARINT_SHIFT = 28
        const val VARINT_MAX_BYTES = 5
    }
}
