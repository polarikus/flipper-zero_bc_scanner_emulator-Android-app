package com.polarikus.bcscanner.data.ble

import kotlinx.coroutines.flow.Flow

/**
 * Events emitted by a single BLE GATT link to a FlipperZero.
 *
 * The transport consumes these platform events and maps them to [com.polarikus.bcscanner.domain.model.ConnectionState].
 */
sealed interface GattEvent {
    /** The GATT link is established (before service discovery). */
    data object Connected : GattEvent

    /** Services were discovered and the Flipper serial service is present. */
    data object ServicesDiscovered : GattEvent

    /** Services were discovered but the Flipper serial service is missing. */
    data class ServiceMissing(val serviceUuid: String) : GattEvent

    /** Bytes arrived on the TX (indicate) characteristic. */
    data class DataReceived(val data: ByteArray) : GattEvent

    /** The GATT link was closed by the remote side or the OS. */
    data object Disconnected : GattEvent

    /** A GATT/GAP error occurred. [reason] is for diagnostics. */
    data class Failure(val reason: String) : GattEvent
}

/**
 * One BLE GATT connection to a device, hidden behind an interface so the transport can be
 * unit-tested with a fake connection instead of the Android `BluetoothGatt` stack.
 */
interface GattConnection {
    /** Platform events for this link. */
    val events: Flow<GattEvent>

    /**
     * Opens the GATT link and starts service discovery.
     *
     * @param autoConnect when `true`, keeps a background connection that completes as soon as
     * the device becomes reachable (used for reconnection).
     */
    suspend fun connect(autoConnect: Boolean = false)

    /** Closes the GATT link but keeps the connection reusable. */
    suspend fun disconnect()

    /** Releases all resources associated with the connection. */
    suspend fun close()

    /** Writes [data] to the RX characteristic. */
    suspend fun write(data: ByteArray)
}

/** Creates [GattConnection] instances bound to a MAC [address]. */
interface GattConnectionFactory {
    /** Creates (but does not connect) a connection to [address]. */
    fun create(address: String): GattConnection
}
