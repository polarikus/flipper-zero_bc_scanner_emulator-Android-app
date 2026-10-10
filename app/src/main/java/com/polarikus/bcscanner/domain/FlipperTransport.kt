package com.polarikus.bcscanner.domain

import com.polarikus.bcscanner.domain.model.ConnectionState
import com.polarikus.bcscanner.domain.model.FlipperDevice
import kotlinx.coroutines.flow.Flow

/**
 * Low-level transport to a FlipperZero over BLE.
 *
 * The platform implementation (BluetoothGatt) lives behind this interface so the rest of
 * the app can be tested with a fake transport.
 */
interface FlipperTransport {
    /** Current connection state, updated automatically on GAP events. */
    val connectionState: Flow<ConnectionState>

    /** Raw bytes received from the FlipperZero (RPC frames). */
    val incoming: Flow<ByteArray>

    /** Connects to [device]. */
    suspend fun connect(device: FlipperDevice)

    /** Closes the connection and releases resources. */
    suspend fun disconnect()

    /** Writes [data] to the transport (RX characteristic). */
    suspend fun write(data: ByteArray)
}
