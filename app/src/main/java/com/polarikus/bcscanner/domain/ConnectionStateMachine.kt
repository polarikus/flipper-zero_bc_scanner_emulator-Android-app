package com.polarikus.bcscanner.domain

import com.polarikus.bcscanner.domain.model.ConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Guarded state machine for the FlipperZero connection.
 *
 * Only the transitions documented in data-model.md are accepted; invalid transitions are
 * ignored so that out-of-order platform callbacks cannot corrupt the state.
 *
 * ```
 * Disconnected --onConnecting--> Connecting --onConnected--> Connected
 *                                    |  \--onError--> Error        |
 *                                    |                  |          |
 *                                    \----onDisconnected-+---onDisconnected
 *                                                       |
 *                                          Error --onConnecting--> Connecting
 * ```
 */
class ConnectionStateMachine {

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)

    /** Current connection state. */
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    /** Starts connecting. Valid from [ConnectionState.Disconnected] or [ConnectionState.Error] (retry). */
    fun onConnecting() {
        if (_state.value is ConnectionState.Disconnected || _state.value is ConnectionState.Error) {
            _state.value = ConnectionState.Connecting
        }
    }

    /** Marks the connection as established. Valid only from [ConnectionState.Connecting]. */
    fun onConnected() {
        if (_state.value is ConnectionState.Connecting) {
            _state.value = ConnectionState.Connected
        }
    }

    /** Records a failure. Valid only from [ConnectionState.Connecting]. */
    fun onError(reason: String) {
        if (_state.value is ConnectionState.Connecting) {
            _state.value = ConnectionState.Error(reason)
        }
    }

    /** Moves to [ConnectionState.Disconnected] from any state (cancel, drop or dismissal). */
    fun onDisconnected() {
        _state.value = ConnectionState.Disconnected
    }
}
