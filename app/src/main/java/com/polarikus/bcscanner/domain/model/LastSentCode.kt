package com.polarikus.bcscanner.domain.model

/**
 * The last code sent, kept in session memory for display on the main screen.
 *
 * Only one value is kept; no history is maintained.
 */
data class LastSentCode(
    val code: ScannedCode,
    val status: DeliveryStatus,
)
