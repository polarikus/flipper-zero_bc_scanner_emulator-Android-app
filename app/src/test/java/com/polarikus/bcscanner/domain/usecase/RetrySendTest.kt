package com.polarikus.bcscanner.domain.usecase

import com.polarikus.bcscanner.data.session.LastSentCodeStore
import com.polarikus.bcscanner.domain.FlipperNotReadyException
import com.polarikus.bcscanner.domain.FlipperRpcClient
import com.polarikus.bcscanner.domain.model.BarcodeFormat
import com.polarikus.bcscanner.domain.model.DeliveryStatus
import com.polarikus.bcscanner.domain.model.ScannedCode
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the retry path of a failed delivery (US3, FR-010, FR-014).
 *
 * A code whose delivery ended in [DeliveryStatus.Error] is sent again; a delivered code is
 * never re-sent. A connection drop or a not-ready Flipper must surface as [DeliveryStatus.Error]
 * without hanging (FR-009, FR-013).
 */
class RetrySendTest {

    @Test
    fun `returns null when nothing was sent yet`() = runTest {
        val store = LastSentCodeStore()
        val retry = RetrySend(SendScannedCode(FakeRpcClient()), store)

        assertEquals(null, retry())
    }

    @Test
    fun `does not resend an already delivered code`() = runTest {
        val store = LastSentCodeStore()
        val code = code()
        store.update(code, DeliveryStatus.Delivered)
        val client = FakeRpcClient(result = true)
        val retry = RetrySend(SendScannedCode(client), store)

        val status = retry()

        assertEquals(DeliveryStatus.Delivered, status)
        assertTrue("a delivered code must not be re-sent", client.sent.isEmpty())
    }

    @Test
    fun `resends a failed code and records the new status`() = runTest {
        val store = LastSentCodeStore()
        val code = code()
        store.update(code, DeliveryStatus.Error("timeout"))
        val client = FakeRpcClient(result = true)
        val retry = RetrySend(SendScannedCode(client), store)

        val status = retry()

        assertEquals(DeliveryStatus.Delivered, status)
        assertEquals(1, client.sent.size)
        assertEquals(DeliveryStatus.Delivered, store.lastSentCode.value?.status)
    }

    @Test
    fun `a connection drop during send maps to Error`() = runTest {
        val client = FakeRpcClient(error = IllegalStateException("connection lost"))

        val status = SendScannedCode(client)(code())

        assertTrue("expected Error but was $status", status is DeliveryStatus.Error)
    }

    @Test
    fun `a not ready flipper maps to Error`() = runTest {
        val client = FakeRpcClient(error = FlipperNotReadyException("not running"))

        val status = SendScannedCode(client)(code())

        assertTrue("expected Error but was $status", status is DeliveryStatus.Error)
    }

    @Test
    fun `a timeout maps to Error`() = runTest {
        val client = FakeRpcClient(error = TimeoutException("no ack"))

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
