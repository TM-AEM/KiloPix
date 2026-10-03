package com.tmaem.kilopix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 20: deterministic JVM regression tests for [ImageDecoder.calculateInSampleSize]'s
 * memory-bounded, overflow-safe power-of-two sampling policy. Pure integer/long math; the
 * bitmap decoder is never invoked. Same-package access to the `internal` method is used
 * without changing production visibility.
 */
class ImageDecoderSampleSizeTest {

    private val bytesPerPixel = 4
    private val eightMiB = 8 * 1024 * 1024

    private fun isPowerOfTwo(value: Int): Boolean = value > 0 && (value and (value - 1)) == 0

    @Test
    fun withinBudget_returnsOne() {
        assertEquals(1, ImageDecoder.calculateInSampleSize(100, 100, bytesPerPixel, eightMiB))
    }

    @Test
    fun invalidDimensions_returnsOne() {
        assertEquals(1, ImageDecoder.calculateInSampleSize(0, 100, bytesPerPixel, eightMiB))
        assertEquals(1, ImageDecoder.calculateInSampleSize(100, 0, bytesPerPixel, eightMiB))
        assertEquals(1, ImageDecoder.calculateInSampleSize(-5, 100, bytesPerPixel, eightMiB))
        assertEquals(1, ImageDecoder.calculateInSampleSize(100, -5, bytesPerPixel, eightMiB))
    }

    @Test
    fun invalidBudgetOrBytesPerPixel_returnsOne() {
        assertEquals(1, ImageDecoder.calculateInSampleSize(1000, 1000, 0, eightMiB))
        assertEquals(1, ImageDecoder.calculateInSampleSize(1000, 1000, -1, eightMiB))
        assertEquals(1, ImageDecoder.calculateInSampleSize(1000, 1000, bytesPerPixel, 0))
        assertEquals(1, ImageDecoder.calculateInSampleSize(1000, 1000, bytesPerPixel, -1))
    }

    @Test
    fun oversizedImage_usesPowerOfTwoDownsample() {
        // 4000x4000 = 16,000,000 px; budget = 8 MiB / 4 = 2,097,152 px
        val sample = ImageDecoder.calculateInSampleSize(4000, 4000, bytesPerPixel, eightMiB)
        assertEquals(4, sample)
        assertTrue(isPowerOfTwo(sample))
    }

    @Test
    fun veryLargeDimensions_stayWithinBudget() {
        val maxPixels = eightMiB / bytesPerPixel
        val sample = ImageDecoder.calculateInSampleSize(100_000, 100_000, bytesPerPixel, eightMiB)
        val width = 100_000 / sample
        val height = 100_000 / sample
        assertTrue(width >= 1 && height >= 1)
        assertTrue(width.toLong() * height.toLong() <= maxPixels.toLong())
        assertTrue(isPowerOfTwo(sample))
    }

    @Test
    fun impossibleBudget_returnsMaximumSampleSize() {
        // With a 1-pixel budget, no allowed sample can fit the loop, so the cap is returned.
        val sample = ImageDecoder.calculateInSampleSize(2_000_000, 2_000_000, 1, 1)
        assertEquals(1 shl 20, sample)
    }

    @Test
    fun maximumIntDimensions_areOverflowSafe() {
        val sample = ImageDecoder.calculateInSampleSize(Int.MAX_VALUE, Int.MAX_VALUE, bytesPerPixel, eightMiB)
        assertTrue(sample >= 1)
        assertTrue(isPowerOfTwo(sample))
    }
}
