package com.polarikus.bcscanner.scanner

import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanner as MlKitScanner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.polarikus.bcscanner.domain.model.BarcodeFormat
import com.polarikus.bcscanner.domain.model.ScannedCode

/**
 * [BarcodeScanner] backed by CameraX image analysis frames and ML Kit Barcode Scanning.
 *
 * The frame is processed synchronously (the analyzer runs off the main thread); when several
 * codes are present, [BarcodeSelection] picks the best one (FR-016).
 */
class MlKitBarcodeScanner : BarcodeScanner {

    private val scanner: MlKitScanner = BarcodeScanning.getClient()

    override fun detect(image: ImageProxy): ScannedCode? {
        val mediaImage = image.image ?: return null
        val input = InputImage.fromMediaImage(mediaImage, image.imageInfo.rotationDegrees)
        return try {
            val barcodes = Tasks.await(scanner.process(input))
            BarcodeSelection.select(barcodes.mapNotNull(::toScannedCode))
        } catch (e: Exception) {
            null
        }
    }

    private fun toScannedCode(barcode: Barcode): ScannedCode? {
        val bytes = barcode.rawValue?.toByteArray(Charsets.UTF_8)
            ?: barcode.rawBytes
            ?: return null
        if (bytes.isEmpty()) return null
        return ScannedCode(
            value = bytes,
            format = mapFormat(barcode.format),
            confidence = null,
        )
    }

    private fun mapFormat(format: Int): BarcodeFormat = when (format) {
        Barcode.FORMAT_CODE_128 -> BarcodeFormat.CODE_128
        Barcode.FORMAT_CODE_39 -> BarcodeFormat.CODE_39
        Barcode.FORMAT_CODE_93 -> BarcodeFormat.CODE_93
        Barcode.FORMAT_CODABAR -> BarcodeFormat.CODABAR
        Barcode.FORMAT_DATA_MATRIX -> BarcodeFormat.DATA_MATRIX
        Barcode.FORMAT_EAN_13 -> BarcodeFormat.EAN_13
        Barcode.FORMAT_EAN_8 -> BarcodeFormat.EAN_8
        Barcode.FORMAT_ITF -> BarcodeFormat.ITF
        Barcode.FORMAT_QR_CODE -> BarcodeFormat.QR_CODE
        Barcode.FORMAT_UPC_A -> BarcodeFormat.UPC_A
        Barcode.FORMAT_UPC_E -> BarcodeFormat.UPC_E
        Barcode.FORMAT_PDF417 -> BarcodeFormat.PDF417
        Barcode.FORMAT_AZTEC -> BarcodeFormat.AZTEC
        else -> BarcodeFormat.UNKNOWN
    }
}
