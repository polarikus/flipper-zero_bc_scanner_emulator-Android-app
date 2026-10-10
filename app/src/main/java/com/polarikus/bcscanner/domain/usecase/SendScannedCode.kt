package com.polarikus.bcscanner.domain.usecase

import com.polarikus.bcscanner.domain.FlipperNotReadyException
import com.polarikus.bcscanner.domain.FlipperRpcClient
import com.polarikus.bcscanner.domain.model.DeliveryStatus
import com.polarikus.bcscanner.domain.model.ScannedCode
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CancellationException

/**
 * Sends a scanned [ScannedCode] to the FlipperZero and maps the outcome to a [DeliveryStatus].
 *
 * Delivery is successful only when the FlipperZero confirms the exchange with an ack (FR-015).
 * A timeout, a not-ready Flipper or a broken connection becomes [DeliveryStatus.Error] so the
 * user can retry (FR-010, FR-013, FR-014).
 */
class SendScannedCode(private val rpcClient: FlipperRpcClient) {

    /** Sends [code] and returns the resulting delivery status. */
    suspend operator fun invoke(code: ScannedCode): DeliveryStatus {
        return try {
            val delivered = rpcClient.sendData(code.value)
            if (delivered) {
                DeliveryStatus.Delivered
            } else {
                DeliveryStatus.Error("FlipperZero reported an application error")
            }
        } catch (e: CancellationException) {
            // Never swallow cancellation: it must propagate to cancel the calling coroutine.
            throw e
        } catch (e: TimeoutException) {
            DeliveryStatus.Error("No ack from FlipperZero (timeout)")
        } catch (e: FlipperNotReadyException) {
            DeliveryStatus.Error(e.message ?: "FlipperZero is not ready")
        } catch (e: Exception) {
            // A broken connection fails fast here so the UI never hangs (FR-009).
            DeliveryStatus.Error(e.message ?: "Failed to send the code")
        }
    }
}
