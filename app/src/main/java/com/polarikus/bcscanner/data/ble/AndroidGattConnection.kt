package com.polarikus.bcscanner.data.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.util.Log
import com.polarikus.bcscanner.domain.FlipperDeviceScanner
import com.polarikus.bcscanner.domain.model.FlipperDevice
import java.util.UUID
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * [GattConnectionFactory] backed by the Android `BluetoothGatt` stack.
 *
 * Requires the Bluetooth runtime permissions (requested by the UI layer, FR-004).
 */
@SuppressLint("MissingPermission")
class AndroidGattConnectionFactory(private val context: Context) : GattConnectionFactory {

    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter

    override fun create(address: String): GattConnection {
        val device = requireNotNull(adapter) { "Bluetooth is not available" }
            .getRemoteDevice(address)
        return AndroidGattConnection(context, device)
    }
}

/**
 * A single Android BLE GATT link. Translates `BluetoothGattCallback` callbacks into
 * [GattEvent]s and performs service discovery and notification subscription.
 */
@SuppressLint("MissingPermission")
class AndroidGattConnection(
    private val context: Context,
    private val device: BluetoothDevice,
) : GattConnection {

    private val eventFlow = MutableSharedFlow<GattEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val events: Flow<GattEvent> = eventFlow.asSharedFlow()

    private var gatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var servicesReady = false
    private val pendingDescriptors = ArrayDeque<Pair<BluetoothGattDescriptor, Boolean>>()

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.i(TAG, "onConnectionStateChange status=$status newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        this@AndroidGattConnection.gatt = gatt
                        eventFlow.tryEmit(GattEvent.Connected)
                        gatt.discoverServices()
                    } else {
                        eventFlow.tryEmit(GattEvent.Failure("connect status $status"))
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    // A drop after a successful session is a disconnect; a failed attempt is an error.
                    if (servicesReady) {
                        servicesReady = false
                        eventFlow.tryEmit(GattEvent.Disconnected)
                    } else {
                        eventFlow.tryEmit(GattEvent.Failure("disconnect status $status"))
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.i(TAG, "onServicesDiscovered status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                eventFlow.tryEmit(GattEvent.Failure("service discovery status $status"))
                return
            }
            val service = gatt.getService(UUID.fromString(BleFlipperTransport.SERIAL_SERVICE_UUID))
            if (service == null) {
                Log.w(
                    TAG,
                    "serial service ${BleFlipperTransport.SERIAL_SERVICE_UUID} not found; " +
                        "available: ${gatt.services.joinToString { it.uuid.toString() }}",
                )
                eventFlow.tryEmit(GattEvent.ServiceMissing(BleFlipperTransport.SERIAL_SERVICE_UUID))
                return
            }
            rxCharacteristic = service.getCharacteristic(
                UUID.fromString(BleFlipperTransport.RX_CHARACTERISTIC_UUID),
            )
            subscribe(
                gatt,
                service.getCharacteristic(UUID.fromString(BleFlipperTransport.TX_CHARACTERISTIC_UUID)),
                indicate = true,
            )
            subscribe(
                gatt,
                service.getCharacteristic(
                    UUID.fromString(BleFlipperTransport.FLOW_CONTROL_CHARACTERISTIC_UUID),
                ),
                indicate = false,
            )
            servicesReady = true
            eventFlow.tryEmit(GattEvent.ServicesDiscovered)
        }

        @Deprecated("Deprecated in Android 13")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            emitCharacteristic(characteristic, characteristic.value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            emitCharacteristic(characteristic, value)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (pendingDescriptors.isNotEmpty()) {
                pendingDescriptors.removeFirst()
            }
            writeNextDescriptor(gatt)
        }
    }

    override suspend fun connect(autoConnect: Boolean) {
        Log.i(TAG, "connect to ${device.address} autoConnect=$autoConnect")
        gatt = device.connectGatt(context, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
    }

    override suspend fun disconnect() {
        gatt?.disconnect()
    }

    override suspend fun close() {
        gatt?.close()
        gatt = null
        rxCharacteristic = null
        servicesReady = false
        pendingDescriptors.clear()
    }

    @Suppress("DEPRECATION")
    override suspend fun write(data: ByteArray) {
        val gatt = gatt ?: return
        val characteristic = rxCharacteristic ?: return
        characteristic.value = data
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        gatt.writeCharacteristic(characteristic)
    }

    private fun subscribe(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic?,
        indicate: Boolean,
    ) {
        if (characteristic == null) return
        gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(CCCD_UUID) ?: return
        pendingDescriptors.addLast(descriptor to indicate)
        if (pendingDescriptors.size == 1) {
            writeNextDescriptor(gatt)
        }
    }

    private fun writeNextDescriptor(gatt: BluetoothGatt) {
        val (descriptor, indicate) = pendingDescriptors.firstOrNull() ?: return
        descriptor.value = if (indicate) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
        @Suppress("DEPRECATION")
        gatt.writeDescriptor(descriptor)
    }

    private fun emitCharacteristic(characteristic: BluetoothGattCharacteristic, value: ByteArray?) {
        if (characteristic.uuid != UUID.fromString(BleFlipperTransport.TX_CHARACTERISTIC_UUID)) return
        if (value == null) return
        eventFlow.tryEmit(GattEvent.DataReceived(value))
    }

    private companion object {
        const val TAG = "AndroidGattConnection"
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}

/**
 * [FlipperDeviceScanner] backed by the Android Bluetooth stack (FR-001).
 *
 * Looks first at bonded (paired) devices — the FlipperZero stops advertising while it is
 * connected elsewhere or after the initial fast-advertising window — and only then performs a
 * live BLE scan. A device is recognized as a FlipperZero by its advertised name
 * ("Flipper …") or by the Flipper BLE service UUID (`0x3080..0x3087`).
 */
@SuppressLint("MissingPermission")
class AndroidFlipperDeviceScanner(context: Context) : FlipperDeviceScanner {

    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter

    override suspend fun scanForFlipper(): FlipperDevice? {
        bondedFlipper()?.let {
            Log.i(TAG, "found bonded Flipper: ${it.address} ${it.name}")
            return it
        }
        val scanner = try {
            adapter?.bluetoothLeScanner
        } catch (e: SecurityException) {
            Log.w(TAG, "BLE scan permission missing: ${e.message}")
            null
        } ?: return null

        Log.i(TAG, "starting BLE scan for a FlipperZero")
        return withTimeoutOrNull(SCAN_TIMEOUT_MILLIS) {
            suspendCancellableCoroutine { continuation ->
                val callback = object : ScanCallback() {
                    override fun onScanResult(callbackType: Int, result: ScanResult) {
                        if (!isFlipper(result)) return
                        Log.i(TAG, "found Flipper via scan: ${result.device.address}")
                        scanner.stopScan(this)
                        if (continuation.isActive) {
                            continuation.resume(
                                FlipperDevice(
                                    address = result.device.address,
                                    name = result.device.name ?: result.scanRecord?.deviceName,
                                ),
                            )
                        }
                    }

                    override fun onScanFailed(errorCode: Int) {
                        Log.w(TAG, "BLE scan failed: $errorCode")
                    }
                }
                scanner.startScan(callback)
                continuation.invokeOnCancellation { scanner.stopScan(callback) }
            }
        }
    }

    private fun bondedFlipper(): FlipperDevice? {
        val bonded = try {
            adapter?.bondedDevices
        } catch (e: SecurityException) {
            Log.w(TAG, "cannot read bonded devices: ${e.message}")
            null
        } ?: return null
        return bonded.firstOrNull { isFlipperName(it.name) }
            ?.let { FlipperDevice(address = it.address, name = it.name) }
    }

    private fun isFlipper(result: ScanResult): Boolean {
        val name = result.device?.name ?: result.scanRecord?.deviceName
        if (isFlipperName(name)) return true
        return result.scanRecord?.serviceUuids?.any { isFlipperServiceUuid(it.uuid) } == true
    }

    private fun isFlipperName(name: String?): Boolean =
        name?.contains(FLIPPER_NAME_MARKER, ignoreCase = true) == true

    private fun isFlipperServiceUuid(uuid: UUID): Boolean =
        (uuid.mostSignificantBits ushr 32) in FLIPPER_SERVICE_UUID_RANGE

    private companion object {
        const val TAG = "FlipperDeviceScanner"
        const val FLIPPER_NAME_MARKER = "Flipper"
        const val SCAN_TIMEOUT_MILLIS = 10_000L

        /** Flipper BLE advertising service UUIDs: 0x3080 | hardware color (see serial_profile.c). */
        val FLIPPER_SERVICE_UUID_RANGE = 0x3080L..0x3087L
    }
}
