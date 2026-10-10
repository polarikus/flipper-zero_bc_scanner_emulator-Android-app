package com.polarikus.bcscanner.scanner

import com.polarikus.bcscanner.domain.model.ScannedCode

/**
 * Chooses one code when several are recognized in the same frame (FR-016).
 *
 * The code with the highest [ScannedCode.confidence] wins. When confidence is unavailable for
 * all candidates (ML Kit does not expose one), the first valid code is used so the UI never
 * stalls.
 */
object BarcodeSelection {

    /** Returns the best candidate from [candidates], or `null` when there is none. */
    fun select(candidates: List<ScannedCode>): ScannedCode? {
        if (candidates.isEmpty()) return null
        return candidates.maxByOrNull { it.confidence ?: Float.NEGATIVE_INFINITY }
    }
}
