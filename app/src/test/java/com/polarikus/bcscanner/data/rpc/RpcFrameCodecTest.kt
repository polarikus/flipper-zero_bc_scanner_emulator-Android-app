package com.polarikus.bcscanner.data.rpc

import com.flipperdevices.protobuf.Flipper
import com.flipperdevices.protobuf.app.Application
import com.google.protobuf.ByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [RpcFrameCodec].
 *
 * The RPC framing is `varint(message length) || PB_Main` (protobuf delimited), matching the
 * firmware (`PB_ENCODE_DELIMITED` / `PB_DECODE_DELIMITED`). The codec must also buffer
 * partial frames split across BLE notifications and emit several frames from one chunk.
 */
class RpcFrameCodecTest {

    @Test
    fun `encode prefixes the payload with a single-byte varint length`() {
        val message = Flipper.Main.newBuilder().setCommandId(1).build()
        val frame = RpcFrameCodec().encode(message)
        val payload = message.toByteArray()

        assertTrue("payload should fit in one varint byte", payload.size < 0x80)
        assertEquals(payload.size, frame[0].toInt() and 0x7f)
        assertArrayEquals(payload, frame.copyOfRange(1, frame.size))
    }

    @Test
    fun `encode uses a multi-byte varint for large payloads`() {
        val data = ByteArray(200) { it.toByte() }
        val message = Flipper.Main.newBuilder()
            .setCommandId(2)
            .setAppDataExchangeRequest(
                Application.DataExchangeRequest.newBuilder().setData(ByteString.copyFrom(data)),
            )
            .build()
        val payload = message.toByteArray()
        val frame = RpcFrameCodec().encode(message)

        // First byte has the continuation bit set; length is encoded over several bytes.
        assertTrue(frame[0].toInt() and 0x80 != 0)
        assertTrue(frame.size > payload.size)
    }

    @Test
    fun `decode returns a complete frame`() {
        val codec = RpcFrameCodec()
        val message = Flipper.Main.newBuilder()
            .setCommandId(7)
            .setAppStartRequest(
                Application.StartRequest.newBuilder().setName("bc_scanner").setArgs("RPC"),
            )
            .build()

        val decoded = codec.decode(codec.encode(message))

        assertEquals(1, decoded.size)
        assertEquals(7, decoded.first().commandId)
        assertEquals("bc_scanner", decoded.first().appStartRequest.name)
        assertEquals("RPC", decoded.first().appStartRequest.args)
    }

    @Test
    fun `decode buffers partial frames across chunks`() {
        val codec = RpcFrameCodec()
        val message = Flipper.Main.newBuilder().setCommandId(9).build()
        val frame = codec.encode(message)

        assertTrue(codec.decode(frame.copyOfRange(0, 1)).isEmpty())
        assertTrue(codec.decode(frame.copyOfRange(1, 2)).isEmpty())

        val decoded = codec.decode(frame.copyOfRange(2, frame.size))

        assertEquals(1, decoded.size)
        assertEquals(9, decoded.first().commandId)
    }

    @Test
    fun `decode returns several frames from one chunk`() {
        val codec = RpcFrameCodec()
        val first = codec.encode(Flipper.Main.newBuilder().setCommandId(1).build())
        val second = codec.encode(Flipper.Main.newBuilder().setCommandId(2).build())
        val third = codec.encode(Flipper.Main.newBuilder().setCommandId(3).build())

        val decoded = codec.decode(first + second + third)

        assertEquals(listOf(1, 2, 3), decoded.map { it.commandId })
    }

    @Test
    fun `decode keeps the trailing partial frame buffered`() {
        val codec = RpcFrameCodec()
        val first = codec.encode(Flipper.Main.newBuilder().setCommandId(1).build())
        val second = codec.encode(Flipper.Main.newBuilder().setCommandId(2).build())

        val decoded = codec.decode(first + second.copyOfRange(0, 1))

        assertEquals(1, decoded.size)
        assertEquals(1, decoded.first().commandId)

        val rest = codec.decode(second.copyOfRange(1, second.size))
        assertEquals(2, rest.first().commandId)
    }

    @Test
    fun `reset clears the buffered partial frame`() {
        val codec = RpcFrameCodec()
        val frame = codec.encode(Flipper.Main.newBuilder().setCommandId(1).build())
        codec.decode(frame.copyOfRange(0, 1))

        codec.reset()

        assertTrue(codec.decode(byteArrayOf()).isEmpty())
    }
}
