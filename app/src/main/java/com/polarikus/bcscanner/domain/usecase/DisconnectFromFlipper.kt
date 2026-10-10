package com.polarikus.bcscanner.domain.usecase

import com.polarikus.bcscanner.domain.FlipperTransport

/**
 * Closes the connection to the FlipperZero and releases the transport resources.
 */
class DisconnectFromFlipper(private val transport: FlipperTransport) {
    /** Disconnects from the current device. */
    suspend operator fun invoke() {
        transport.disconnect()
    }
}
