package com.polarikus.bcscanner.domain

/**
 * Signals that the Flipper application is connected but not ready to accept data
 * (not running in RPC mode, or failed to start).
 */
class FlipperNotReadyException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
