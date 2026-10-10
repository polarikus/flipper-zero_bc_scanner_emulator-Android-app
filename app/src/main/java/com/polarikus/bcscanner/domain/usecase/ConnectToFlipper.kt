package com.polarikus.bcscanner.domain.usecase

import com.polarikus.bcscanner.data.prefs.DeviceStore
import com.polarikus.bcscanner.domain.FlipperDeviceScanner
import com.polarikus.bcscanner.domain.FlipperTransport
import com.polarikus.bcscanner.domain.model.FlipperDevice
import kotlinx.coroutines.flow.first

/**
 * Connects to a FlipperZero and remembers it for auto-connect (FR-011).
 *
 * On first run (no saved device) the FlipperZero is discovered by scanning (FR-001).
 */
class ConnectToFlipper(
    private val transport: FlipperTransport,
    private val deviceStore: DeviceStore,
    private val deviceScanner: FlipperDeviceScanner,
) {

    /**
     * Connects to [device] and persists its address as the last used device.
     */
    suspend operator fun invoke(device: FlipperDevice) {
        transport.connect(device)
        deviceStore.saveLastDeviceAddress(device.address)
    }

    /**
     * Connects to the last used device, or discovers a FlipperZero when none was saved.
     *
     * @return `true` when a connection attempt was started, `false` when no device was found.
     */
    suspend fun connectToLastDevice(): Boolean {
        val savedAddress = deviceStore.lastDeviceAddress.first()
        val device = if (savedAddress != null) {
            FlipperDevice(address = savedAddress)
        } else {
            deviceScanner.scanForFlipper() ?: return false
        }
        transport.connect(device)
        deviceStore.saveLastDeviceAddress(device.address)
        return true
    }
}
