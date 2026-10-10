package com.polarikus.bcscanner.ui.scanner

import com.polarikus.bcscanner.data.session.LastSentCodeStore
import com.polarikus.bcscanner.domain.FlipperRpcClient
import com.polarikus.bcscanner.domain.model.BarcodeFormat
import com.polarikus.bcscanner.domain.model.DeliveryStatus
import com.polarikus.bcscanner.domain.model.ScannedCode
import com.polarikus.bcscanner.domain.usecase.SendScannedCode
import com.polarikus.bcscanner.ui.main.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Unit tests for the retry path of [ScannerViewModel] (US3, FR-010, FR-014).
 *
 * A failed delivery keeps the scanner screen open so the user can retry the same code; a
 * successful retry records the status and returns to the main screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScannerViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `a failed delivery stays on the scanner screen`() =
        runTest(UnconfinedTestDispatcher()) {
            val client = FakeRpcClient(failures = 1)
            val viewModel = newViewModel(client)
            val events = mutableListOf<ScannerEvent>()
            backgroundScope.launch { viewModel.events.collect { events += it } }

            viewModel.onCodeDetected(code())
            advanceUntilIdle()

            assertTrue(viewModel.state.value is ScannerUiState.Error)
            assertTrue("must not navigate away on error", events.isEmpty())
        }

    @Test
    fun `retry resends the failed code and returns to main on success`() =
        runTest(UnconfinedTestDispatcher()) {
            val client = FakeRpcClient(failures = 1)
            val store = LastSentCodeStore()
            val viewModel = newViewModel(client, store)
            val events = mutableListOf<ScannerEvent>()
            backgroundScope.launch { viewModel.events.collect { events += it } }

            viewModel.onCodeDetected(code())
            advanceUntilIdle()

            viewModel.retry()
            advanceUntilIdle()

            assertEquals(2, client.sent.size)
            assertTrue(viewModel.state.value is ScannerUiState.Delivered)
            assertEquals(listOf(ScannerEvent.ReturnToMain), events)
            assertEquals(DeliveryStatus.Delivered, store.lastSentCode.value?.status)
        }

    @Test
    fun `retry is ignored while no error is present`() =
        runTest(UnconfinedTestDispatcher()) {
            val client = FakeRpcClient(failures = 0)
            val viewModel = newViewModel(client)

            viewModel.retry()
            advanceUntilIdle()

            assertTrue(client.sent.isEmpty())
            assertTrue(viewModel.state.value is ScannerUiState.Scanning)
        }

    private fun newViewModel(
        client: FlipperRpcClient,
        store: LastSentCodeStore = LastSentCodeStore(),
    ): ScannerViewModel = ScannerViewModel(SendScannedCode(client), store)

    private fun code(): ScannedCode = ScannedCode(
        value = "value".toByteArray(),
        format = BarcodeFormat.QR_CODE,
    )

    private class FakeRpcClient(private val failures: Int) : FlipperRpcClient {
        val sent = mutableListOf<ByteArray>()
        private var attempts = 0

        override suspend fun startApp(name: String, args: String?) = Unit

        override suspend fun sendData(data: ByteArray): Boolean {
            sent += data
            attempts++
            if (attempts <= failures) throw IllegalStateException("connection lost")
            return true
        }

        override suspend fun disconnect() = Unit
    }
}
