package com.polarikus.bcscanner.domain.model

/**
 * Barcode/QR formats supported by the recognition engine.
 *
 * A domain-level enum keeps the platform scanner (ML Kit) out of the domain layer; the
 * scanner implementation maps its own format constants onto these values.
 */
enum class BarcodeFormat {
    UNKNOWN,
    CODE_128,
    CODE_39,
    CODE_93,
    CODABAR,
    DATA_MATRIX,
    EAN_13,
    EAN_8,
    ITF,
    QR_CODE,
    UPC_A,
    UPC_E,
    PDF417,
    AZTEC,
}
