package com.polarikus.bcscanner.ui.scanner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.polarikus.bcscanner.data.session.LastSentCodeStore
import com.polarikus.bcscanner.domain.model.DeliveryStatus
import com.polarikus.bcscanner.domain.model.ScannedCode
import com.polarikus.bcscanner.domain.usecase.SendScannedCode
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** State of the scanner screen. */
sealed interface ScannerUiState {
    /** Waiting for a code to be recognized. */
    data object Scanning : ScannerUiState

    /** A code was recognized and is being sent to the FlipperZero. */
    data class Sending(val code: ScannedCode) : ScannerUiState

    /** The code was delivered and confirmed by the FlipperZero. */
    data class Delivered(val code: ScannedCode) : ScannerUiState

    /** Delivery failed; the user can scan again. */
    data class Error(val code: ScannedCode, val reason: String) : ScannerUiState
}

/** One-off scanner screen events (navigation). */
enum class ScannerEvent {
    /** Return to the main screen (single scan is complete). */
    ReturnToMain,
}

/**
 * Drives a single scan: recognize a code, send it to the FlipperZero, record the delivery status
 * and return to the main screen (FR-007, FR-012, FR-013).
 */
class ScannerViewModel(
    private val sendScannedCode: SendScannedCode,
    private val lastSentCodeStore: LastSentCodeStore,
) : ViewModel() {

    private val _state = MutableStateFlow<ScannerUiState>(ScannerUiState.Scanning)

    /** Current scanner state. */
    val state: StateFlow<ScannerUiState> = _state.asStateFlow()

    private val _events = Channel<ScannerEvent>(Channel.BUFFERED)

    /** One-off navigation events. */
    val events: Flow<ScannerEvent> = _events.receiveAsFlow()

    /** `true` once a code was captured, so further frames are ignored (single scan). */
    private var captured = false

    /** Handles a recognized [code]; subsequent codes are ignored until the scan completes. */
    fun onCodeDetected(code: ScannedCode) {
        if (captured) return
        captured = true
        _state.value = ScannerUiState.Sending(code)
        viewModelScope.launch {
            val status = sendScannedCode(code)
            lastSentCodeStore.update(code, status)
            _state.value = when (status) {
                DeliveryStatus.Delivered -> ScannerUiState.Delivered(code)
                DeliveryStatus.Pending -> ScannerUiState.Sending(code)
                is DeliveryStatus.Error -> ScannerUiState.Error(code, status.reason)
            }
            _events.send(ScannerEvent.ReturnToMain)
        }
    }
}
