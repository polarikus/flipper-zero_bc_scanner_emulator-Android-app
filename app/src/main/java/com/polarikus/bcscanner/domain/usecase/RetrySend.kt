package com.polarikus.bcscanner.domain.usecase

import com.polarikus.bcscanner.data.session.LastSentCodeStore
import com.polarikus.bcscanner.domain.model.DeliveryStatus

/**
 * Re-sends the last code whose delivery failed (FR-010, FR-014).
 *
 * A code is re-sent only when its previous delivery ended in [DeliveryStatus.Error]; an
 * already delivered code is never sent again. The new status is written back to the
 * [LastSentCodeStore] so the UI reflects the retry.
 *
 * @return the resulting [DeliveryStatus], or `null` when there is nothing to retry.
 */
class RetrySend(
    private val sendScannedCode: SendScannedCode,
    private val lastSentCodeStore: LastSentCodeStore,
) {

    suspend operator fun invoke(): DeliveryStatus? {
        val last = lastSentCodeStore.lastSentCode.value ?: return null
        if (last.status !is DeliveryStatus.Error) return last.status

        lastSentCodeStore.update(last.code, DeliveryStatus.Pending)
        val status = sendScannedCode(last.code)
        lastSentCodeStore.update(last.code, status)
        return status
    }
}
