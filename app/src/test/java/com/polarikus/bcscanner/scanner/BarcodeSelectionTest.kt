package com.polarikus.bcscanner.scanner

import com.polarikus.bcscanner.domain.model.BarcodeFormat
import com.polarikus.bcscanner.domain.model.ScannedCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [BarcodeSelection].
 *
 * When several codes are recognized in one frame, the one with the highest confidence must be
 * chosen (FR-016); when confidence is unavailable, the first valid code is used.
 */
class BarcodeSelectionTest {

    @Test
    fun `returns null for an empty list`() {
        assertNull(BarcodeSelection.select(emptyList()))
    }

    @Test
    fun `selects the code with the highest confidence`() {
        val low = code("low", 0.4f)
        val high = code("high", 0.9f)
        val middle = code("middle", 0.6f)

        assertEquals(high, BarcodeSelection.select(listOf(low, high, middle)))
    }

    @Test
    fun `returns the first code when confidences are equal`() {
        val first = code("first", 0.5f)
        val second = code("second", 0.5f)

        assertEquals(first, BarcodeSelection.select(listOf(first, second)))
    }

    @Test
    fun `returns the first code when confidence is unavailable`() {
        val first = code("first", null)
        val second = code("second", null)

        assertEquals(first, BarcodeSelection.select(listOf(first, second)))
    }

    @Test
    fun `prefers a code with a known confidence over an unknown one`() {
        val unknown = code("unknown", null)
        val known = code("known", 0.1f)

        assertEquals(known, BarcodeSelection.select(listOf(unknown, known)))
    }

    private fun code(value: String, confidence: Float?): ScannedCode =
        ScannedCode(
            value = value.toByteArray(Charsets.UTF_8),
            format = BarcodeFormat.QR_CODE,
            confidence = confidence,
        )
}
