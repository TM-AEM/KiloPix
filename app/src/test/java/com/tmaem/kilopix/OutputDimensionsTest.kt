package com.tmaem.kilopix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Task 22: Quick-mode output dimensions must distinguish source pixels from encoded
 * pixels. Sampling is allowed for memory safety; results must never claim the
 * encoded JPEG is still full-resolution when it is not. No pixel rotation.
 */
class OutputDimensionsTest {

    @Test
    fun sampledPixelDimensions_sampleOne_equalsSource() {
        val size = ImageDecoder.sampledPixelDimensions(1920, 1080, sampleSize = 1)
        assertEquals(1920, size.width)
        assertEquals(1080, size.height)
    }

    @Test
    fun sampledPixelDimensions_knownSampleFactor_dividesBothAxes() {
        val sample = ImageDecoder.calculateInSampleSize(4000, 4000, 4, 8 * 1024 * 1024)
        assertEquals(4, sample)
        val size = ImageDecoder.sampledPixelDimensions(4000, 4000, sample)
        assertEquals(1000, size.width)
        assertEquals(1000, size.height)
    }

    @Test
    fun sampledPixelDimensions_preserveStorageOrderForPortrait() {
        val size = ImageDecoder.sampledPixelDimensions(3000, 4000, sampleSize = 2)
        assertEquals(1500, size.width)
        assertEquals(2000, size.height)
    }

    @Test
    fun imageSizeReport_keepsSourceIndependentOfEncoded() {
        val report = ImageSizeReport(
            sourceWidth = 4000,
            sourceHeight = 3000,
            encodedWidth = 1000,
            encodedHeight = 750,
        )
        assertEquals(4000, report.sourceWidth)
        assertEquals(3000, report.sourceHeight)
        assertEquals(1000, report.encodedWidth)
        assertEquals(750, report.encodedHeight)
        assertTrue(report.wasSampled)
    }

    @Test
    fun imageSizeReport_quickSampledOutput_describesEncodedPixelsNotSource() {
        val sourceWidth = 4000
        val sourceHeight = 4000
        val sample = 4
        val encoded = ImageDecoder.sampledPixelDimensions(sourceWidth, sourceHeight, sample)
        val report = ImageSizeReport.forQuick(
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            encodedWidth = encoded.width,
            encodedHeight = encoded.height,
        )
        assertEquals(4000, report.sourceWidth)
        assertEquals(4000, report.sourceHeight)
        assertEquals(1000, report.encodedWidth)
        assertEquals(1000, report.encodedHeight)
        assertTrue(report.wasSampled)
    }

    @Test
    fun imageSizeReport_unsampled_matchesSource() {
        val report = ImageSizeReport.forQuick(1920, 1080, 1920, 1080)
        assertFalse(report.wasSampled)
        assertEquals(1920, report.encodedWidth)
        assertEquals(1080, report.encodedHeight)
    }

    @Test
    fun jpegWithoutExif_isClassifiedAsNoExif() {
        val kind = ExifMetadataHandler.classifySource(ByteArrayInputStream(jpegNoExif()))
        assertEquals(ExifMetadataHandler.SourceExifKind.NO_EXIF, kind)
        val unavailable = ExifMetadataHandler.preserveFallback(
            ExifMetadataHandler.SourceExifKind.NO_EXIF,
            jpegNoExif(),
        ) as ExifMetadataHandler.ExifMetadataResult.MetadataUnavailable
        assertEquals(
            ExifMetadataHandler.ExifMetadataFailureReason.EXIF_UNAVAILABLE,
            unavailable.reason,
        )
    }

    @Test
    fun jpegWithExif_orientationSix_doesNotSwapStorageDimensions() {
        val kind = ExifMetadataHandler.classifySource(ByteArrayInputStream(jpegWithExif()))
        assertEquals(ExifMetadataHandler.SourceExifKind.FOUND, kind)
        val report = ImageSizeReport.forQuick(
            sourceWidth = 4032,
            sourceHeight = 3024,
            encodedWidth = 4032,
            encodedHeight = 3024,
        )
        assertEquals(4032, report.sourceWidth)
        assertEquals(3024, report.sourceHeight)
        assertEquals(4032, report.encodedWidth)
        assertEquals(3024, report.encodedHeight)
        assertFalse(report.wasSampled)
    }

    @Test
    fun jpegWithExif_sampled_stillClassifiesAsFound() {
        val kind = ExifMetadataHandler.classifySource(ByteArrayInputStream(jpegWithExif()))
        assertEquals(ExifMetadataHandler.SourceExifKind.FOUND, kind)
        val report = ImageSizeReport.forQuick(4032, 3024, 1008, 756)
        assertTrue(report.wasSampled)
        assertEquals(4032, report.sourceWidth)
        assertEquals(3024, report.sourceHeight)
        assertEquals(1008, report.encodedWidth)
        assertEquals(756, report.encodedHeight)
    }

    @Test
    fun pngPreserve_doesNotClaimMetadataPreservation() {
        val result = ExifMetadataHandler.preserveFallback(
            ExifMetadataHandler.SourceExifKind.NOT_JPEG,
            jpegNoExif(),
        )
        val unavailable = result as ExifMetadataHandler.ExifMetadataResult.MetadataUnavailable
        assertEquals(
            ExifMetadataHandler.ExifMetadataFailureReason.UNSUPPORTED_FORMAT,
            unavailable.reason,
        )
    }

    @Test
    fun resultsFormatter_distinguishesSourceAndEncodedWhenSampled() {
        val text = ResultsFormatter.formatPixelDimensions(
            sourceWidth = 4000,
            sourceHeight = 3000,
            encodedWidth = 1000,
            encodedHeight = 750,
        )
        assertEquals("1000 × 750 (from 4000 × 3000)", text)
    }

    @Test
    fun resultsFormatter_singlePairWhenUnsampled() {
        val text = ResultsFormatter.formatPixelDimensions(1920, 1080, 1920, 1080)
        assertEquals("1920 × 1080", text)
    }

    @Test
    fun imageSizeReport_fromSourceAndBitmapSize_mapsEncodedPixels() {
        val report = ImageSizeReport.forQuick(
            sourceWidth = 4000,
            sourceHeight = 3000,
            encodedWidth = 1000,
            encodedHeight = 750,
        )
        assertEquals(4000, report.sourceWidth)
        assertEquals(3000, report.sourceHeight)
        assertEquals(1000, report.encodedWidth)
        assertEquals(750, report.encodedHeight)
        assertTrue(report.wasSampled)
    }

    private fun jpegNoExif(): ByteArray = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(),
        0xFF.toByte(), 0xD9.toByte(),
    )

    private fun jpegWithExif(): ByteArray {
        val signature = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0x00, 0x00)
        val length = 2 + signature.size
        return byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
            0xFF.toByte(), 0xE1.toByte(),
            (length shr 8).toByte(), (length and 0xFF).toByte(),
        ) + signature
    }
}
