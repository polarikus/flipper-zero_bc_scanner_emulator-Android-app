package com.polarikus.bcscanner.data.prefs

import kotlinx.coroutines.flow.Flow

/**
 * Persists the last used FlipperZero address so the app can auto-connect to it.
 */
interface DeviceStore {
    /** The last saved device MAC address, or `null` when none is stored. */
    val lastDeviceAddress: Flow<String?>

    /** Saves [address] as the last used device. */
    suspend fun saveLastDeviceAddress(address: String)

    /** Clears the stored address. */
    suspend fun clear()
}
