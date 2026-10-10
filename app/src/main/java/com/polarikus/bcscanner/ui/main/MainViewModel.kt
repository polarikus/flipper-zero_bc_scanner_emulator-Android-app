package com.polarikus.bcscanner.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.polarikus.bcscanner.domain.FlipperTransport
import com.polarikus.bcscanner.domain.model.ConnectionState
import com.polarikus.bcscanner.domain.model.FlipperDevice
import com.polarikus.bcscanner.domain.usecase.ConnectToFlipper
import com.polarikus.bcscanner.domain.usecase.DisconnectFromFlipper
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Presents the connection status on the main screen and gates the "Scan" action.
 *
 * The status mirrors [FlipperTransport.connectionState]; the "Scan" action is enabled only
 * while connected (FR-003). On creation the view model auto-connects to the last used device
 * (FR-011).
 */
class MainViewModel(
    private val transport: FlipperTransport,
    private val connectToFlipper: ConnectToFlipper,
    private val disconnectFromFlipper: DisconnectFromFlipper,
) : ViewModel() {

    /** Current connection state, shown on the main screen. */
    val connectionState: StateFlow<ConnectionState> = transport.connectionState
        .stateIn(viewModelScope, SharingStarted.Eagerly, ConnectionState.Disconnected)

    /** Whether the "Scan" action is available (only when [ConnectionState.Connected]). */
    val scanEnabled: StateFlow<Boolean> = connectionState
        .map { it is ConnectionState.Connected }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        viewModelScope.launch { connectToFlipper.connectToLastDevice() }
    }

    /** Connects to [device] and remembers it for the next launch. */
    fun connect(device: FlipperDevice) {
        viewModelScope.launch { connectToFlipper(device) }
    }

    /** Retries the connection to the last used device (after an error or permission grant). */
    fun retryConnection() {
        viewModelScope.launch { connectToFlipper.connectToLastDevice() }
    }

    /** Closes the connection. */
    fun disconnect() {
        viewModelScope.launch { disconnectFromFlipper() }
    }
}
