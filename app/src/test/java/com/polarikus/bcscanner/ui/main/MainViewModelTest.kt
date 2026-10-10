package com.polarikus.bcscanner.ui.main

import com.polarikus.bcscanner.data.prefs.DeviceStore
import com.polarikus.bcscanner.data.session.LastSentCodeStore
import com.polarikus.bcscanner.domain.FlipperDeviceScanner
import com.polarikus.bcscanner.domain.FlipperTransport
import com.polarikus.bcscanner.domain.model.ConnectionState
import com.polarikus.bcscanner.domain.model.FlipperDevice
import com.polarikus.bcscanner.domain.usecase.ConnectToFlipper
import com.polarikus.bcscanner.domain.usecase.DisconnectFromFlipper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Unit tests for [MainViewModel].
 *
 * Verifies the status mapping that gates the "Scan" action (FR-003) and the auto-connect
 * to the last used device (FR-011).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `scan action is disabled until connected`() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeTransport()
        val viewModel = newViewModel(transport)

        assertFalse(viewModel.scanEnabled.value)

        transport.state.value = ConnectionState.Connecting
        assertFalse(viewModel.scanEnabled.value)

        transport.state.value = ConnectionState.Connected
        assertTrue(viewModel.scanEnabled.value)

        transport.state.value = ConnectionState.Error("boom")
        assertFalse(viewModel.scanEnabled.value)
    }

    @Test
    fun `auto connects to the last used device on init`() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeTransport()
        val store = FakeDeviceStore("AA:BB:CC:DD:EE:FF")

        newViewModel(transport, store)
        advanceUntilIdle()

        assertEquals("AA:BB:CC:DD:EE:FF", transport.lastConnected?.address)
    }

    @Test
    fun `scans for a device on first run when none was saved`() =
        runTest(UnconfinedTestDispatcher()) {
            val transport = FakeTransport()
            val store = FakeDeviceStore(null)
            val scanner = FakeDeviceScanner(FlipperDevice("CC:DD:EE:FF", "Flipper X"))

            newViewModel(transport, store, scanner)
            advanceUntilIdle()

            assertEquals(1, scanner.scans)
            assertEquals("CC:DD:EE:FF", transport.lastConnected?.address)
            assertEquals("CC:DD:EE:FF", store.current)
        }

    @Test
    fun `does not connect when no device was saved`() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeTransport()
        val store = FakeDeviceStore(null)

        newViewModel(transport, store)
        advanceUntilIdle()

        assertEquals(null, transport.lastConnected)
    }

    @Test
    fun `connect remembers the device and starts the transport`() =
        runTest(UnconfinedTestDispatcher()) {
            val transport = FakeTransport()
            val store = FakeDeviceStore(null)
            val viewModel = newViewModel(transport, store)

            viewModel.connect(FlipperDevice("11:22:33:44:55:66"))
            advanceUntilIdle()

            assertEquals("11:22:33:44:55:66", transport.lastConnected?.address)
            assertEquals("11:22:33:44:55:66", store.current)
        }

    @Test
    fun `disconnect delegates to the transport`() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeTransport()
        val viewModel = newViewModel(transport)
        transport.state.value = ConnectionState.Connected

        viewModel.disconnect()
        advanceUntilIdle()

        assertEquals(ConnectionState.Disconnected, transport.state.value)
    }

    private fun newViewModel(
        transport: FlipperTransport,
        store: DeviceStore = FakeDeviceStore(null),
        scanner: FlipperDeviceScanner = FakeDeviceScanner(null),
    ): MainViewModel = MainViewModel(
        transport = transport,
        connectToFlipper = ConnectToFlipper(transport, store, scanner),
        disconnectFromFlipper = DisconnectFromFlipper(transport),
        lastSentCodeStore = LastSentCodeStore(),
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
        val current: String? get() = state.value

        override suspend fun saveLastDeviceAddress(address: String) {
            state.value = address
        }

        override suspend fun clear() {
            state.value = null
        }
    }

    private class FakeDeviceScanner(private val result: FlipperDevice?) : FlipperDeviceScanner {
        var scans = 0
        override suspend fun scanForFlipper(): FlipperDevice? {
            scans++
            return result
        }
    }
}

/** Sets the Main dispatcher to a test dispatcher for `viewModelScope`. */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    private val dispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
