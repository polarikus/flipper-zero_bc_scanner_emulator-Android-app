package com.polarikus.bcscanner.domain

import com.polarikus.bcscanner.domain.model.FlipperDevice

/**
 * Discovers a nearby FlipperZero (FR-001).
 *
 * Used to connect on first run (no saved device) and as a fallback when the saved MAC cannot
 * be reached.
 */
interface FlipperDeviceScanner {
    /** Returns the first FlipperZero found, or `null` when none is discovered in time. */
    suspend fun scanForFlipper(): FlipperDevice?
}
