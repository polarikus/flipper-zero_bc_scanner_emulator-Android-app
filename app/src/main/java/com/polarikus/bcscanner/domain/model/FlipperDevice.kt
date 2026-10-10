package com.polarikus.bcscanner.domain.model

/**
 * A FlipperZero device the app connects to.
 *
 * The [address] (MAC) is the unique key and is required; it is persisted to auto-connect
 * to the last used device. The [name] is optional and used for display only.
 */
data class FlipperDevice(
    val address: String,
    val name: String? = null,
)
