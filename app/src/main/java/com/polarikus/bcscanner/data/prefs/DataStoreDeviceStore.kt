package com.polarikus.bcscanner.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Single DataStore instance for the whole process. */
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "bc_scanner_settings",
)

/**
 * [DeviceStore] backed by Jetpack DataStore Preferences.
 *
 * Stores only the last used FlipperZero MAC address; the UI language is derived from the OS
 * locale and is not persisted.
 */
class DataStoreDeviceStore(private val context: Context) : DeviceStore {

    override val lastDeviceAddress: Flow<String?> =
        context.settingsDataStore.data.map { preferences -> preferences[LAST_DEVICE_ADDRESS] }

    override suspend fun saveLastDeviceAddress(address: String) {
        context.settingsDataStore.edit { preferences ->
            preferences[LAST_DEVICE_ADDRESS] = address
        }
    }

    override suspend fun clear() {
        context.settingsDataStore.edit { preferences ->
            preferences.remove(LAST_DEVICE_ADDRESS)
        }
    }

    private companion object {
        val LAST_DEVICE_ADDRESS = stringPreferencesKey("last_device_address")
    }
}
