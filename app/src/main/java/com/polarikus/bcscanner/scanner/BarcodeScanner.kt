package com.polarikus.bcscanner.scanner

import androidx.camera.core.ImageProxy
import com.polarikus.bcscanner.domain.model.ScannedCode

/**
 * Recognizes barcode/QR codes from camera frames.
 *
 * The platform implementation (CameraX + ML Kit) lives behind this interface.
 */
interface BarcodeScanner {
    /**
     * Recognizes the best code in [image] and returns it, or `null` when no valid code is
     * found. When several codes are present, the one with the highest confidence is chosen.
     */
    fun detect(image: ImageProxy): ScannedCode?
}
