package com.polarikus.bcscanner.domain

/**
 * RPC client that talks to the FlipperZero application over the BLE transport.
 *
 * It runs the `bc_scanner` application in RPC mode, exchanges barcode values and waits for
 * the ack that confirms delivery.
 */
interface FlipperRpcClient {
    /**
     * Starts the Flipper application [name] in RPC mode (for bc_scanner: `args = "RPC"`).
     *
     * @throws FlipperNotReadyException when the application cannot be started.
     */
    suspend fun startApp(name: String, args: String? = null)

    /**
     * Sends [data] to the running application and waits for the ack.
     *
     * @return `true` when the ack with `CommandStatus OK` is received; `false` on a
     * application-level error.
     * @throws java.util.concurrent.TimeoutException when no ack arrives within the timeout.
     * @throws FlipperNotReadyException when the application is not running.
     */
    suspend fun sendData(data: ByteArray): Boolean

    /** Ends the RPC session. */
    suspend fun disconnect()
}
