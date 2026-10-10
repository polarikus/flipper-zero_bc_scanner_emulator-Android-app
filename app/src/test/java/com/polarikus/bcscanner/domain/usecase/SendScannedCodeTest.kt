package com.polarikus.bcscanner.domain.usecase

import com.polarikus.bcscanner.domain.FlipperNotReadyException
import com.polarikus.bcscanner.domain.FlipperRpcClient
import com.polarikus.bcscanner.domain.model.BarcodeFormat
import com.polarikus.bcscanner.domain.model.DeliveryStatus
import com.polarikus.bcscanner.domain.model.ScannedCode
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SendScannedCode].
 *
 * An ack (`CommandStatus OK`) maps to [DeliveryStatus.Delivered]; a timeout, an application
 * error or a not-ready Flipper maps to [DeliveryStatus.Error] (FR-013, FR-015).
 */
class SendScannedCodeTest {

    @Test
    fun `ack maps to Delivered`() = runTest {
        val client = FakeRpcClient(result = true)

        val status = SendScannedCode(client)(code())

        assertEquals(DeliveryStatus.Delivered, status)
        assertArrayEquals("value".toByteArray(), client.sent.single())
    }

    @Test
    fun `application error maps to Error`() = runTest {
        val client = FakeRpcClient(result = false)

        val status = SendScannedCode(client)(code())

        assertTrue("expected Error but was $status", status is DeliveryStatus.Error)
    }

    @Test
    fun `timeout maps to Error`() = runTest {
        val client = FakeRpcClient(error = TimeoutException("no ack"))

        val status = SendScannedCode(client)(code())

        assertTrue("expected Error but was $status", status is DeliveryStatus.Error)
    }

    @Test
    fun `not ready flipper maps to Error`() = runTest {
        val client = FakeRpcClient(error = FlipperNotReadyException("not running"))

        val status = SendScannedCode(client)(code())

        assertTrue("expected Error but was $status", status is DeliveryStatus.Error)
    }

    private fun code(): ScannedCode = ScannedCode(
        value = "value".toByteArray(),
        format = BarcodeFormat.QR_CODE,
    )

    private class FakeRpcClient(
        private val result: Boolean = true,
        private val error: Throwable? = null,
    ) : FlipperRpcClient {
        val sent = mutableListOf<ByteArray>()

        override suspend fun startApp(name: String, args: String?) = Unit

        override suspend fun sendData(data: ByteArray): Boolean {
            sent += data
            error?.let { throw it }
            return result
        }

        override suspend fun disconnect() = Unit
    }
}
