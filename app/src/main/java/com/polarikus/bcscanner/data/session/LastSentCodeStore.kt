package com.polarikus.bcscanner.data.session

import com.polarikus.bcscanner.domain.model.DeliveryStatus
import com.polarikus.bcscanner.domain.model.LastSentCode
import com.polarikus.bcscanner.domain.model.ScannedCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory store of the last code sent to the FlipperZero (FR-012, FR-017).
 *
 * Only one value is kept for the lifetime of the process; no history is persisted.
 */
class LastSentCodeStore {

    private val _lastSentCode = MutableStateFlow<LastSentCode?>(null)

    /** The last sent code and its delivery status, or `null` when nothing was sent yet. */
    val lastSentCode: StateFlow<LastSentCode?> = _lastSentCode.asStateFlow()

    /** Records [code] and its delivery [status]. */
    fun update(code: ScannedCode, status: DeliveryStatus) {
        _lastSentCode.value = LastSentCode(code = code, status = status)
    }
}
