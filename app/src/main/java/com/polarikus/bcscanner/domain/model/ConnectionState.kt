package com.polarikus.bcscanner.domain.model

/**
 * Connection state between the app and the FlipperZero, shown on the main screen.
 *
 * Allowed transitions (see data-model.md):
 * - [Disconnected] -> [Connecting] (start)
 * - [Connecting] -> [Connected] (success) / [Error] (failure) / [Disconnected] (cancel)
 * - [Connected] -> [Disconnected] (drop)
 * - [Error] -> [Connecting] (retry) / [Disconnected]
 */
sealed interface ConnectionState {
    /** No connection. */
    data object Disconnected : ConnectionState

    /** Connecting / establishing the session. */
    data object Connecting : ConnectionState

    /** GATT connected and the RPC session is available. */
    data object Connected : ConnectionState

    /** Connection/session error. [reason] is for diagnostics; UI shows a generic message. */
    data class Error(val reason: String) : ConnectionState
}
