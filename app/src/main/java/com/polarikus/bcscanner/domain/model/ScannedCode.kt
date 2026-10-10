package com.polarikus.bcscanner.domain.model

import java.time.Instant

/**
 * A code recognized by the camera.
 *
 * [value] holds the raw bytes of the code and must be non-empty. When several codes are
 * present in a frame, the one with the highest [confidence] is selected.
 */
class ScannedCode(
    val value: ByteArray,
    val format: BarcodeFormat,
    val confidence: Float? = null,
    val scannedAt: Instant = Instant.now(),
) {
    init {
        require(value.isNotEmpty()) { "ScannedCode value must not be empty" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ScannedCode) return false
        return value.contentEquals(other.value) &&
            format == other.format &&
            confidence == other.confidence &&
            scannedAt == other.scannedAt
    }

    override fun hashCode(): Int {
        var result = value.contentHashCode()
        result = 31 * result + format.hashCode()
        result = 31 * result + (confidence?.hashCode() ?: 0)
        result = 31 * result + scannedAt.hashCode()
        return result
    }

    override fun toString(): String =
        "ScannedCode(format=$format, confidence=$confidence, scannedAt=$scannedAt, " +
            "value=${value.size} bytes)"
}
