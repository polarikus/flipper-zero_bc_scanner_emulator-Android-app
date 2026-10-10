package com.polarikus.bcscanner.data.ble

import com.polarikus.bcscanner.domain.FlipperDeviceScanner
import com.polarikus.bcscanner.domain.model.ConnectionState
import com.polarikus.bcscanner.domain.model.FlipperDevice
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [BleFlipperTransport] using a fake GATT connection.
 *
 * Verifies that GATT/GAP events are mapped to the documented [ConnectionState]
 * transitions (data-model.md) and that incoming bytes are forwarded.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BleFlipperTransportTest {

    @Test
    fun `initial state is Disconnected`() = runTest(UnconfinedTestDispatcher()) {
        val transport = newTransport(FakeGattConnection())

        assertEquals(ConnectionState.Disconnected, transport.connectionState.value)
    }

    @Test
    fun `connect requests the saved address and moves to Connecting`() =
        runTest(UnconfinedTestDispatcher()) {
            val connection = FakeGattConnection()
            val factory = FakeConnectionFactory(connection)
            val transport = BleFlipperTransport(factory, backgroundScope)

            transport.connect(FlipperDevice("AA:BB:CC:DD:EE:FF"))

            assertEquals(ConnectionState.Connecting, transport.connectionState.value)
            assertEquals(listOf("AA:BB:CC:DD:EE:FF"), factory.addresses)
            assertTrue(connection.connectCalled)
        }

    @Test
    fun `service discovery moves Connecting to Connected`() =
        runTest(UnconfinedTestDispatcher()) {
            val connection = FakeGattConnection()
            val transport = newTransport(connection)

            transport.connect(FlipperDevice("AA:BB"))
            connection.emit(GattEvent.Connected)
            connection.emit(GattEvent.ServicesDiscovered)

            assertEquals(ConnectionState.Connected, transport.connectionState.value)
        }

    @Test
    fun `missing serial service moves to Error`() = runTest(UnconfinedTestDispatcher()) {
        val connection = FakeGattConnection()
        val transport = newTransport(connection)

        transport.connect(FlipperDevice("AA:BB"))
        connection.emit(GattEvent.Connected)
        connection.emit(GattEvent.ServiceMissing(BleFlipperTransport.SERIAL_SERVICE_UUID))

        val state = transport.connectionState.value
        assertTrue("expected Error but was $state", state is ConnectionState.Error)
    }

    @Test
    fun `gatt failure moves Connecting to Error`() = runTest(UnconfinedTestDispatcher()) {
        val connection = FakeGattConnection()
        val transport = newTransport(connection)

        transport.connect(FlipperDevice("AA:BB"))
        connection.emit(GattEvent.Failure("gatt 133"))

        val state = transport.connectionState.value
        assertTrue("expected Error but was $state", state is ConnectionState.Error)
        assertEquals("gatt 133", (state as ConnectionState.Error).reason)
    }

    @Test
    fun `gatt disconnect moves Connected to Disconnected`() =
        runTest(UnconfinedTestDispatcher()) {
            val connection = FakeGattConnection()
            val transport = newTransport(connection)

            transport.connect(FlipperDevice("AA:BB"))
            connection.emit(GattEvent.Connected)
            connection.emit(GattEvent.ServicesDiscovered)
            connection.emit(GattEvent.Disconnected)

            assertEquals(ConnectionState.Disconnected, transport.connectionState.value)
        }

    @Test
    fun `incoming bytes are forwarded to the incoming flow`() =
        runTest(UnconfinedTestDispatcher()) {
            val connection = FakeGattConnection()
            val transport = newTransport(connection)
            val received = mutableListOf<ByteArray>()
            backgroundScope.launch { transport.incoming.collect { received += it } }

            transport.connect(FlipperDevice("AA:BB"))
            connection.emit(GattEvent.DataReceived(byteArrayOf(1, 2, 3)))
            advanceUntilIdle()

            assertEquals(1, received.size)
            assertArrayEquals(byteArrayOf(1, 2, 3), received.first())
        }

    @Test
    fun `disconnect releases the gatt connection`() = runTest(UnconfinedTestDispatcher()) {
        val connection = FakeGattConnection()
        val transport = newTransport(connection)
        transport.connect(FlipperDevice("AA:BB"))
        connection.emit(GattEvent.Connected)
        connection.emit(GattEvent.ServicesDiscovered)

        transport.disconnect()

        assertTrue(connection.disconnectCalled)
        assertTrue(connection.closeCalled)
        assertEquals(ConnectionState.Disconnected, transport.connectionState.value)
    }

    @Test
    fun `reconnects automatically after an unexpected disconnect`() =
        runTest(UnconfinedTestDispatcher()) {
            val first = FakeGattConnection()
            val second = FakeGattConnection()
            val factory = FakeConnectionFactory(first, second)
            val transport = BleFlipperTransport(factory, backgroundScope)

            transport.connect(FlipperDevice("AA:BB"))
            first.emit(GattEvent.Connected)
            first.emit(GattEvent.ServicesDiscovered)
            assertEquals(ConnectionState.Connected, transport.connectionState.value)

            first.emit(GattEvent.Disconnected)
            assertEquals(ConnectionState.Disconnected, transport.connectionState.value)

            advanceTimeBy(BleFlipperTransport.RECONNECT_DELAY_MILLIS + 1)
            advanceUntilIdle()

            assertEquals(2, factory.addresses.size)
            assertEquals("AA:BB", factory.addresses.last())
            assertEquals(true, second.lastAutoConnect)

            second.emit(GattEvent.Connected)
            second.emit(GattEvent.ServicesDiscovered)
            assertEquals(ConnectionState.Connected, transport.connectionState.value)
        }

    @Test
    fun `does not reconnect after an explicit disconnect`() =
        runTest(UnconfinedTestDispatcher()) {
            val connection = FakeGattConnection()
            val factory = FakeConnectionFactory(connection)
            val transport = BleFlipperTransport(factory, backgroundScope)

            transport.connect(FlipperDevice("AA:BB"))
            connection.emit(GattEvent.Connected)
            connection.emit(GattEvent.ServicesDiscovered)

            transport.disconnect()
            advanceTimeBy(BleFlipperTransport.RECONNECT_DELAY_MILLIS + 1)
            advanceUntilIdle()

            assertEquals(1, factory.addresses.size)
            assertEquals(ConnectionState.Disconnected, transport.connectionState.value)
        }

    @Test
    fun `write forwards bytes to the gatt connection`() = runTest(UnconfinedTestDispatcher()) {
        val connection = FakeGattConnection()
        val transport = newTransport(connection)
        transport.connect(FlipperDevice("AA:BB"))

        transport.write(byteArrayOf(9, 8, 7))

        assertEquals(1, connection.written.size)
        assertArrayEquals(byteArrayOf(9, 8, 7), connection.written.first())
    }

    @Test
    fun `connect falls back to scanning when the direct connection fails`() =
        runTest(UnconfinedTestDispatcher()) {
            val failing = FakeGattConnection()
            val succeeding = FakeGattConnection()
            val factory = FakeConnectionFactory(failing, succeeding)
            val scanner = FakeDeviceScanner(FlipperDevice("CC:DD:EE:FF", "Flipper X"))
            val transport = BleFlipperTransport(factory, backgroundScope, scanner)

            transport.connect(FlipperDevice("AA:BB"))
            failing.emit(GattEvent.Failure("device not found"))
            advanceUntilIdle()

            assertEquals(1, scanner.scans)
            assertEquals("CC:DD:EE:FF", factory.addresses.last())
            succeeding.emit(GattEvent.Connected)
            succeeding.emit(GattEvent.ServicesDiscovered)
            assertEquals(ConnectionState.Connected, transport.connectionState.value)
        }

    @Test
    fun `no fallback scanning when no scanner is configured`() =
        runTest(UnconfinedTestDispatcher()) {
            val connection = FakeGattConnection()
            val transport = newTransport(connection)

            transport.connect(FlipperDevice("AA:BB"))
            connection.emit(GattEvent.Failure("device not found"))
            advanceUntilIdle()

            assertTrue(transport.connectionState.value is ConnectionState.Error)
        }

    private fun TestScope.newTransport(connection: FakeGattConnection): BleFlipperTransport =
        BleFlipperTransport(FakeConnectionFactory(connection), backgroundScope)

    private class FakeConnectionFactory(vararg connections: GattConnection) :
        GattConnectionFactory {
        private val queue = ArrayDeque(connections.toList())
        val addresses = mutableListOf<String>()

        override fun create(address: String): GattConnection {
            addresses += address
            return if (queue.size > 1) queue.removeFirst() else queue.first()
        }
    }

    private class FakeGattConnection : GattConnection {
        private val flow = MutableSharedFlow<GattEvent>(extraBufferCapacity = 32)
        override val events: Flow<GattEvent> = flow.asSharedFlow()
        var connectCalled = false
        var disconnectCalled = false
        var closeCalled = false
        var lastAutoConnect: Boolean? = null
        val written = mutableListOf<ByteArray>()

        fun emit(event: GattEvent) {
            flow.tryEmit(event)
        }

        override suspend fun connect(autoConnect: Boolean) {
            connectCalled = true
            lastAutoConnect = autoConnect
        }

        override suspend fun disconnect() {
            disconnectCalled = true
        }

        override suspend fun close() {
            closeCalled = true
        }

        override suspend fun write(data: ByteArray) {
            written += data
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
