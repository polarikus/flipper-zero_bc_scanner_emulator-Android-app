package com.polarikus.bcscanner.domain.model

/**
 * Application settings.
 *
 * The UI language follows the OS locale (Russian -> Russian, otherwise English) and is
 * therefore not stored here.
 */
data class AppSettings(
    val lastDeviceAddress: String? = null,
)
