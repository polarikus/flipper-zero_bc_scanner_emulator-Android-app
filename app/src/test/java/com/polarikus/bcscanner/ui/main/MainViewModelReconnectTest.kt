package com.polarikus.bcscanner.ui.main

import com.polarikus.bcscanner.data.prefs.DeviceStore
import com.polarikus.bcscanner.data.session.LastSentCodeStore
import com.polarikus.bcscanner.domain.FlipperDeviceScanner
import com.polarikus.bcscanner.domain.FlipperRpcClient
import com.polarikus.bcscanner.domain.FlipperTransport
import com.polarikus.bcscanner.domain.model.BarcodeFormat
import com.polarikus.bcscanner.domain.model.ConnectionState
import com.polarikus.bcscanner.domain.model.DeliveryStatus
import com.polarikus.bcscanner.domain.model.FlipperDevice
import com.polarikus.bcscanner.domain.model.ScannedCode
import com.polarikus.bcscanner.domain.usecase.ConnectToFlipper
import com.polarikus.bcscanner.domain.usecase.DisconnectFromFlipper
import com.polarikus.bcscanner.domain.usecase.RetrySend
import com.polarikus.bcscanner.domain.usecase.SendScannedCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Unit tests for the recovery path of [MainViewModel] after a connection drop or a failed
 * delivery (US3, FR-009, FR-010, SC-005).
 *
 * The "Scan" action is re-enabled automatically once the connection is restored, and a failed
 * delivery can be retried without restarting the app.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelReconnectTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `scan is re-enabled after the connection is restored`() =
        runTest(UnconfinedTestDispatcher()) {
            val transport = FakeTransport()
            val viewModel = newViewModel(transport)

            transport.state.value = ConnectionState.Connected
            assertTrue(viewModel.scanEnabled.value)

            transport.state.value = ConnectionState.Disconnected
            assertFalse(viewModel.scanEnabled.value)

            transport.state.value = ConnectionState.Connecting
            assertFalse(viewModel.scanEnabled.value)

            transport.state.value = ConnectionState.Connected
            assertTrue("the scan action must recover after a drop", viewModel.scanEnabled.value)
        }

    @Test
    fun `retryConnection reconnects to the last used device after an error`() =
        runTest(UnconfinedTestDispatcher()) {
            val transport = FakeTransport()
            val store = FakeDeviceStore("AA:BB:CC:DD:EE:FF")
            val viewModel = newViewModel(transport, store)
            advanceUntilIdle()
            transport.lastConnected = null
            transport.state.value = ConnectionState.Error("boom")

            viewModel.retryConnection()
            advanceUntilIdle()

            assertEquals("AA:BB:CC:DD:EE:FF", transport.lastConnected?.address)
        }

    @Test
    fun `retryLastSend resends a failed code from the main screen`() =
        runTest(UnconfinedTestDispatcher()) {
            val transport = FakeTransport()
            val store = LastSentCodeStore()
            store.update(code(), DeliveryStatus.Error("timeout"))
            val client = FakeRpcClient(result = true)
            val viewModel = newViewModel(transport, lastSentCodeStore = store, rpcClient = client)

            viewModel.retryLastSend()
            advanceUntilIdle()

            assertEquals(1, client.sent.size)
            assertEquals(DeliveryStatus.Delivered, store.lastSentCode.value?.status)
        }

    @Test
    fun `retryLastSend does nothing when the last code was delivered`() =
        runTest(UnconfinedTestDispatcher()) {
            val transport = FakeTransport()
            val store = LastSentCodeStore()
            store.update(code(), DeliveryStatus.Delivered)
            val client = FakeRpcClient(result = true)
            val viewModel = newViewModel(transport, lastSentCodeStore = store, rpcClient = client)

            viewModel.retryLastSend()
            advanceUntilIdle()

            assertTrue("a delivered code must not be re-sent", client.sent.isEmpty())
        }

    private fun code(): ScannedCode = ScannedCode(
        value = "value".toByteArray(),
        format = BarcodeFormat.QR_CODE,
    )

    private fun newViewModel(
        transport: FlipperTransport,
        store: DeviceStore = FakeDeviceStore(null),
        scanner: FlipperDeviceScanner = FakeDeviceScanner(null),
        lastSentCodeStore: LastSentCodeStore = LastSentCodeStore(),
        rpcClient: FlipperRpcClient = FakeRpcClient(),
    ): MainViewModel = MainViewModel(
        transport = transport,
        connectToFlipper = ConnectToFlipper(transport, store, scanner),
        disconnectFromFlipper = DisconnectFromFlipper(transport),
        lastSentCodeStore = lastSentCodeStore,
        retrySend = RetrySend(SendScannedCode(rpcClient), lastSentCodeStore),
    )

    private class FakeTransport : FlipperTransport {
        val state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
        override val connectionState: Flow<ConnectionState> = state
        private val incomingFlow = MutableSharedFlow<ByteArray>()
        override val incoming: Flow<ByteArray> = incomingFlow
        var lastConnected: FlipperDevice? = null

        override suspend fun connect(device: FlipperDevice) {
            lastConnected = device
            state.value = ConnectionState.Connecting
        }

        override suspend fun disconnect() {
            state.value = ConnectionState.Disconnected
        }

        override suspend fun write(data: ByteArray) = Unit
    }

    private class FakeDeviceStore(initial: String?) : DeviceStore {
        private val state = MutableStateFlow(initial)
        override val lastDeviceAddress: Flow<String?> = state

        override suspend fun saveLastDeviceAddress(address: String) {
            state.value = address
        }

        override suspend fun clear() {
            state.value = null
        }
    }

    private class FakeDeviceScanner(private val result: FlipperDevice?) : FlipperDeviceScanner {
        override suspend fun scanForFlipper(): FlipperDevice? = result
    }

    private class FakeRpcClient(private val result: Boolean = true) : FlipperRpcClient {
        val sent = mutableListOf<ByteArray>()

        override suspend fun startApp(name: String, args: String?) = Unit

        override suspend fun sendData(data: ByteArray): Boolean {
            sent += data
            return result
        }

        override suspend fun disconnect() = Unit
    }
}
