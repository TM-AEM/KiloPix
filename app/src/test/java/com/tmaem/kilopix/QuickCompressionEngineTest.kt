package com.tmaem.kilopix

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Task 20: deterministic JVM regression tests for [QuickCompressionEngine.selectQuality]'s
 * observable band policy (pixel-count thresholds, PNG adjustment, 80..95 clamp and the
 * invalid-dimension fallback). Pure integer/float policy; no bitmap or encoder is invoked.
 */
class QuickCompressionEngineTest {

    private val jpeg = "image/jpeg"
    private val png = "image/png"

    @Test
    fun selectQuality_invalidDimensions_returnsDefault() {
        assertEquals(90, QuickCompressionEngine.selectQuality(0, 100, jpeg))
        assertEquals(90, QuickCompressionEngine.selectQuality(100, 0, jpeg))
        assertEquals(90, QuickCompressionEngine.selectQuality(-1, 100, jpeg))
        assertEquals(90, QuickCompressionEngine.selectQuality(100, -1, jpeg))
    }

    @Test
    fun selectQuality_oneMillionBoundary() {
        // exactly 1,000,000 px -> small band -> 95
        assertEquals(95, QuickCompressionEngine.selectQuality(1000, 1000, jpeg))
        // 1,001,000 px -> normal band -> 90
        assertEquals(90, QuickCompressionEngine.selectQuality(1000, 1001, jpeg))
    }

    @Test
    fun selectQuality_eightMillionBoundary() {
        // exactly 8,000,000 px -> normal band -> 90
        assertEquals(90, QuickCompressionEngine.selectQuality(8000, 1000, jpeg))
        // 8,001,000 px -> large band -> 85
        assertEquals(85, QuickCompressionEngine.selectQuality(8001, 1000, jpeg))
    }

    @Test
    fun selectQuality_twentyMillionBoundary() {
        // exactly 20,000,000 px -> large band -> 85
        assertEquals(85, QuickCompressionEngine.selectQuality(5000, 4000, jpeg))
        // 20,005,000 px -> extremely large band -> 80
        assertEquals(80, QuickCompressionEngine.selectQuality(5000, 4001, jpeg))
    }

    @Test
    fun selectQuality_extremelyLarge() {
        assertEquals(80, QuickCompressionEngine.selectQuality(10000, 10000, jpeg))
    }

    @Test
    fun selectQuality_pngAdjustmentRaisesBandByFive() {
        // 2,000,000 px -> base 90; PNG +5 -> 95
        assertEquals(90, QuickCompressionEngine.selectQuality(1000, 2000, jpeg))
        assertEquals(95, QuickCompressionEngine.selectQuality(1000, 2000, png))
        // 10,000,000 px -> base 85; PNG +5 -> 90
        assertEquals(85, QuickCompressionEngine.selectQuality(5000, 2000, jpeg))
        assertEquals(90, QuickCompressionEngine.selectQuality(5000, 2000, png))
    }

    @Test
    fun selectQuality_pngAdjustmentIsClampedAt95() {
        // 10,000 px -> base 95; PNG +5 would be 100 -> clamped to 95
        assertEquals(95, QuickCompressionEngine.selectQuality(100, 100, png))
    }

    @Test
    fun selectQuality_nullOrNonPngMimeGetsNoBonus() {
        assertEquals(90, QuickCompressionEngine.selectQuality(1000, 2000, null))
        assertEquals(90, QuickCompressionEngine.selectQuality(1000, 2000, jpeg))
    }
}
