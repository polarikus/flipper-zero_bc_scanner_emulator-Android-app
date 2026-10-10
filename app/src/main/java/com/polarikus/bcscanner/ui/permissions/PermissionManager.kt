package com.polarikus.bcscanner.ui.permissions

import android.Manifest
import android.os.Build

/**
 * Helpers describing which runtime permissions are required, by OS version.
 *
 * - API 26-30: `BLUETOOTH`, `BLUETOOTH_ADMIN` and `ACCESS_FINE_LOCATION` (needed for BLE scanning).
 * - API 31+: `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT`.
 * - Camera: `CAMERA`.
 *
 * The SDK version is a parameter (defaulting to the running device) so the mapping stays
 * unit-testable.
 */
object PermissionManager {

    const val CAMERA: String = Manifest.permission.CAMERA

    /**
     * Bluetooth permissions required on a device running [sdkInt].
     */
    fun bluetoothPermissions(sdkInt: Int = Build.VERSION.SDK_INT): List<String> =
        if (sdkInt >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            listOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        }

    /**
     * All permissions the app may request, optionally including the camera.
     */
    fun requiredPermissions(
        includeCamera: Boolean = false,
        sdkInt: Int = Build.VERSION.SDK_INT,
    ): List<String> = buildList {
        addAll(bluetoothPermissions(sdkInt))
        if (includeCamera) add(CAMERA)
    }
}
