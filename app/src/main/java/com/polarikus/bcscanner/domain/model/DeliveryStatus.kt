package com.polarikus.bcscanner.domain.model

/**
 * Delivery status of a value sent to the FlipperZero.
 *
 * Delivery counts as successful only when an ack (`CommandStatus OK`) is received.
 */
sealed interface DeliveryStatus {
    /** Sent over BLE, waiting for the ack. */
    data object Pending : DeliveryStatus

    /** Ack received. */
    data object Delivered : DeliveryStatus

    /**
     * Ack timeout, write error, Flipper error or a broken connection.
     * [reason] is for diagnostics; UI shows a generic message.
     */
    data class Error(val reason: String) : DeliveryStatus
}
