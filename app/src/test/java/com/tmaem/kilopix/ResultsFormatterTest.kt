package com.tmaem.kilopix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/**
 * Task 20: deterministic JVM regression tests for [ResultsFormatter]'s byte formatting and
 * savings math, including null/zero/negative and growth edge cases. Pure functions only.
 */
class ResultsFormatterTest {

    private fun assertIntEquals(expected: Int, actual: Int?) {
        if (actual == null) fail("expected $expected but was null") else assertEquals(expected.toLong(), actual.toLong())
    }

    private fun assertLongEquals(expected: Long, actual: Long?) {
        if (actual == null) fail("expected $expected but was null") else assertEquals(expected, actual.toLong())
    }

    // ---- formatBytes ---------------------------------------------------------

    @Test
    fun formatBytes_byteRange() {
        assertEquals("0 B", ResultsFormatter.formatBytes(0L))
        assertEquals("1 B", ResultsFormatter.formatBytes(1L))
        assertEquals("1023 B", ResultsFormatter.formatBytes(1023L))
    }

    @Test
    fun formatBytes_kilobyteBoundary() {
        assertEquals("1.0 KB", ResultsFormatter.formatBytes(1024L))
        assertEquals("1.5 KB", ResultsFormatter.formatBytes(1536L))
        assertEquals("1024.0 KB", ResultsFormatter.formatBytes(1024L * 1024L - 1L))
    }

    @Test
    fun formatBytes_megabyteBoundary() {
        assertEquals("1.0 MB", ResultsFormatter.formatBytes(1024L * 1024L))
        assertEquals("1.5 MB", ResultsFormatter.formatBytes(1_572_864L))
    }

    @Test
    fun formatBytes_negativeIsClampedToZero() {
        assertEquals("0 B", ResultsFormatter.formatBytes(-5L))
    }

    // ---- percentageReduction -------------------------------------------------

    @Test
    fun percentageReduction_nullOrNonPositiveOriginalIsNull() {
        assertNull(ResultsFormatter.percentageReduction(null, 10L))
        assertNull(ResultsFormatter.percentageReduction(0L, 10L))
        assertNull(ResultsFormatter.percentageReduction(-1L, 10L))
    }

    @Test
    fun percentageReduction_negativeCompressedIsNull() {
        assertNull(ResultsFormatter.percentageReduction(100L, -1L))
    }

    @Test
    fun percentageReduction_equalSizesIsZero() {
        assertIntEquals(0, ResultsFormatter.percentageReduction(100L, 100L))
    }

    @Test
    fun percentageReduction_smallerOutput() {
        assertIntEquals(50, ResultsFormatter.percentageReduction(100L, 50L))
        assertIntEquals(67, ResultsFormatter.percentageReduction(100L, 33L))
    }

    @Test
    fun percentageReduction_fullReductionIsCappedAt100() {
        assertIntEquals(100, ResultsFormatter.percentageReduction(100L, 0L))
    }

    @Test
    fun percentageReduction_largerOutputIsClampedToZero() {
        assertIntEquals(0, ResultsFormatter.percentageReduction(100L, 200L))
    }

    // ---- bytesSaved ----------------------------------------------------------

    @Test
    fun bytesSaved_nullOriginalOrNegativeCompressedIsNull() {
        assertNull(ResultsFormatter.bytesSaved(null, 10L))
        assertNull(ResultsFormatter.bytesSaved(100L, -1L))
    }

    @Test
    fun bytesSaved_equalSizesIsNull() {
        assertNull(ResultsFormatter.bytesSaved(100L, 100L))
    }

    @Test
    fun bytesSaved_smallerOutput() {
        assertLongEquals(40L, ResultsFormatter.bytesSaved(100L, 60L))
    }

    @Test
    fun bytesSaved_largerOutputIsNull() {
        assertNull(ResultsFormatter.bytesSaved(100L, 150L))
    }

    // ---- bytesGrowth ---------------------------------------------------------

    @Test
    fun bytesGrowth_nullOrNonPositiveOriginalOrNegativeCompressedIsNull() {
        assertNull(ResultsFormatter.bytesGrowth(null, 10L))
        assertNull(ResultsFormatter.bytesGrowth(0L, 10L))
        assertNull(ResultsFormatter.bytesGrowth(-1L, 10L))
        assertNull(ResultsFormatter.bytesGrowth(100L, -1L))
    }

    @Test
    fun bytesGrowth_equalSizesIsNull() {
        assertNull(ResultsFormatter.bytesGrowth(100L, 100L))
    }

    @Test
    fun bytesGrowth_smallerOutputIsNull() {
        assertNull(ResultsFormatter.bytesGrowth(100L, 50L))
    }

    @Test
    fun bytesGrowth_largerOutput() {
        assertLongEquals(50L, ResultsFormatter.bytesGrowth(100L, 150L))
    }

    // ---- percentageGrowth ----------------------------------------------------

    @Test
    fun percentageGrowth_nullOrNonPositiveOriginalOrNegativeCompressedIsNull() {
        assertNull(ResultsFormatter.percentageGrowth(null, 150L))
        assertNull(ResultsFormatter.percentageGrowth(0L, 150L))
        assertNull(ResultsFormatter.percentageGrowth(-1L, 150L))
        assertNull(ResultsFormatter.percentageGrowth(100L, -1L))
    }

    @Test
    fun percentageGrowth_notLargerIsNull() {
        assertNull(ResultsFormatter.percentageGrowth(100L, 100L))
        assertNull(ResultsFormatter.percentageGrowth(100L, 50L))
    }

    @Test
    fun percentageGrowth_largerOutput() {
        assertIntEquals(50, ResultsFormatter.percentageGrowth(100L, 150L))
        assertIntEquals(100, ResultsFormatter.percentageGrowth(100L, 200L))
        assertIntEquals(900, ResultsFormatter.percentageGrowth(100L, 1000L))
    }
}
