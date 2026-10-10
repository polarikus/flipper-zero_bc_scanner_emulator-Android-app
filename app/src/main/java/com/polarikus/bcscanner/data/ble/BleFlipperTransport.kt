package com.polarikus.bcscanner.data.ble

import com.polarikus.bcscanner.domain.ConnectionStateMachine
import com.polarikus.bcscanner.domain.FlipperDeviceScanner
import com.polarikus.bcscanner.domain.FlipperTransport
import com.polarikus.bcscanner.domain.model.ConnectionState
import com.polarikus.bcscanner.domain.model.FlipperDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * [FlipperTransport] backed by the Android BLE GATT stack.
 *
 * The platform is hidden behind [GattConnectionFactory]; this class only maps [GattEvent]s to
 * the documented [ConnectionState] transitions (data-model.md) and forwards incoming bytes.
 *
 * Behaviour:
 * - When the saved MAC cannot be reached and a [FlipperDeviceScanner] is configured, the
 *   transport falls back to scanning for a nearby FlipperZero (FR-011).
 * - After an unexpected disconnect it keeps a background connection armed so the status
 *   returns to [ConnectionState.Connected] automatically when the FlipperZero comes back,
 *   without restarting the app (FR-009, SC-005).
 */
class BleFlipperTransport(
    private val connectionFactory: GattConnectionFactory,
    private val scope: CoroutineScope,
    private val deviceScanner: FlipperDeviceScanner? = null,
) : FlipperTransport {

    private val machine = ConnectionStateMachine()

    override val connectionState: StateFlow<ConnectionState> = machine.state

    private val incomingFlow = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val incoming: Flow<ByteArray> = incomingFlow.asSharedFlow()

    private var connection: GattConnection? = null
    private var collector: Job? = null
    private var reconnectJob: Job? = null
    private var scannedFallback = false

    /** Address of the device the app should stay connected to. */
    private var lastAddress: String? = null

    /** `true` while the user wants to stay connected (i.e. until an explicit disconnect). */
    private var keepConnected = false

    override suspend fun connect(device: FlipperDevice) {
        keepConnected = true
        scannedFallback = false
        lastAddress = device.address
        machine.onConnecting()
        startConnection(device.address, autoConnect = false)
    }

    override suspend fun disconnect() {
        keepConnected = false
        reconnectJob?.cancel()
        reconnectJob = null
        collector?.cancel()
        collector = null
        val current = connection
        connection = null
        current?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        machine.onDisconnected()
    }

    override suspend fun write(data: ByteArray) {
        connection?.write(data)
    }

    private fun startConnection(address: String, autoConnect: Boolean) {
        val previous = connection
        if (previous != null) {
            collector?.cancel()
            scope.launch { runCatching { previous.close() } }
        }
        val newConnection = runCatching { connectionFactory.create(address) }
            .getOrElse {
                machine.onError(it.message ?: "cannot create connection")
                return
            }
        connection = newConnection
        collector = scope.launch {
            newConnection.events.collect(::onEvent)
        }
        scope.launch {
            runCatching { newConnection.connect(autoConnect) }
                .onFailure { onEvent(GattEvent.Failure(it.message ?: "connect failed")) }
        }
    }

    private fun onEvent(event: GattEvent) {
        when (event) {
            // GATT is up; the state machine only needs to be in Connecting so that a
            // successful service discovery can move it to Connected (also on reconnect).
            GattEvent.Connected -> machine.onConnecting()
            GattEvent.ServicesDiscovered -> machine.onConnected()
            is GattEvent.ServiceMissing ->
                machine.onError("serial service ${event.serviceUuid} not found")
            is GattEvent.DataReceived -> incomingFlow.tryEmit(event.data)
            GattEvent.Disconnected -> {
                machine.onDisconnected()
                scheduleReconnect()
            }

            is GattEvent.Failure -> {
                machine.onError(event.reason)
                if (!scanFallbackIfNeeded()) {
                    scheduleReconnect()
                }
            }
        }
    }

    /**
     * Arms a background connection so the status returns to Connected automatically once the
     * FlipperZero is reachable again. The state stays [ConnectionState.Disconnected] while the
     * device is absent (FR-002) and only becomes Connecting once GATT actually connects.
     */
    private fun scheduleReconnect() {
        if (!keepConnected) return
        val address = lastAddress ?: return
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            delay(RECONNECT_DELAY_MILLIS)
            if (!keepConnected) return@launch
            startConnection(address, autoConnect = true)
        }
    }

    /** @return `true` when a fallback scan was launched (so a reconnect must not be scheduled). */
    private fun scanFallbackIfNeeded(): Boolean {
        val scanner = deviceScanner ?: return false
        if (scannedFallback) return false
        scannedFallback = true
        scope.launch {
            val found = runCatching { scanner.scanForFlipper() }.getOrNull() ?: return@launch
            lastAddress = found.address
            machine.onConnecting()
            startConnection(found.address, autoConnect = false)
        }
        return true
    }

    companion object {
        /** Delay before re-arming the background connection after a drop. */
        const val RECONNECT_DELAY_MILLIS = 2_000L

        /**
         * Flipper BLE serial service UUID (see contracts/ble-serial-profile.md).
         *
         * Note: the firmware stores the 128-bit UUID little-endian
         * (`targets/f7/ble_glue/services/serial_service_uuid.inc`), so the value seen
         * over the air is byte-reversed relative to the `.inc` literal.
         */
        const val SERIAL_SERVICE_UUID = "8fe5b3d5-2e7f-4a98-2a48-7acc60fe0000"

        /** TX characteristic: Flipper -> phone (indicate). */
        const val TX_CHARACTERISTIC_UUID = "19ed82ae-ed21-4c9d-4145-228e61fe0000"

        /** RX characteristic: phone -> Flipper (write). */
        const val RX_CHARACTERISTIC_UUID = "19ed82ae-ed21-4c9d-4145-228e62fe0000"

        /** Flow-control characteristic: Flipper -> phone (notify). */
        const val FLOW_CONTROL_CHARACTERISTIC_UUID = "19ed82ae-ed21-4c9d-4145-228e63fe0000"

        /** RPC status characteristic (read/write/notify). */
        const val RPC_STATUS_CHARACTERISTIC_UUID = "19ed82ae-ed21-4c9d-4145-228e64fe0000"
    }
}
