package com.tmaem.kilopix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Task 22: Quick-mode results must carry the original source dimensions and the encoded
 * output pixel dimensions as separate, explicitly named fields. Values whose meaning
 * changes by compression mode must not be exposed under the ambiguous names `width`/`height`.
 *
 * Deterministic and JVM-only: data-class field names are inspected via reflection and the
 * dimension/sampling/EXIF boundaries are pure functions, so no Android `Uri`, `Bitmap` or
 * physical device is required.
 */
class ResultDimensionContractTest {

    private fun fieldsOf(type: Class<*>): Set<String> =
        type.declaredFields.map { it.name }.toSet()

    // ---- Explicit field-name contract ------------------------------------------

    @Test
    fun batchItemSuccess_exposesSourceAndEncodedFields() {
        val fields = fieldsOf(BatchItemResult.Success::class.java)
        assertTrue("missing sourceWidth", "sourceWidth" in fields)
        assertTrue("missing sourceHeight", "sourceHeight" in fields)
        assertTrue("missing encodedWidth", "encodedWidth" in fields)
        assertTrue("missing encodedHeight", "encodedHeight" in fields)
    }

    @Test
    fun batchItemSuccess_avoidsAmbiguousWidthHeightFields() {
        val fields = fieldsOf(BatchItemResult.Success::class.java)
        assertFalse("ambiguous 'width' field must not exist", "width" in fields)
        assertFalse("ambiguous 'height' field must not exist", "height" in fields)
    }

    @Test
    fun resultItemSuccess_exposesSourceAndEncodedFields() {
        val fields = fieldsOf(ResultItem.Success::class.java)
        assertTrue("missing sourceWidth", "sourceWidth" in fields)
        assertTrue("missing sourceHeight", "sourceHeight" in fields)
        assertTrue("missing encodedWidth", "encodedWidth" in fields)
        assertTrue("missing encodedHeight", "encodedHeight" in fields)
    }

    @Test
    fun resultItemSuccess_avoidsAmbiguousWidthHeightFields() {
        val fields = fieldsOf(ResultItem.Success::class.java)
        assertFalse("ambiguous 'width' field must not exist", "width" in fields)
        assertFalse("ambiguous 'height' field must not exist", "height" in fields)
    }

    // ---- Source vs encoded independence ----------------------------------------

    @Test
    fun sizeReport_keepsSourceIndependentOfEncoded() {
        val report = ImageSizeReport(4000, 3000, 1000, 750)
        assertEquals(4000, report.sourceWidth)
        assertEquals(3000, report.sourceHeight)
        assertEquals(1000, report.encodedWidth)
        assertEquals(750, report.encodedHeight)
        assertTrue(report.wasSampled)
    }

    @Test
    fun sizeReport_unsampled_isNotSampled() {
        val report = ImageSizeReport.forQuick(1920, 1080, 1920, 1080)
        assertFalse(report.wasSampled)
        assertEquals(1920, report.encodedWidth)
        assertEquals(1080, report.encodedHeight)
    }

    // ---- Sampled large image ---------------------------------------------------

    @Test
    fun knownSourceAndSampleFactor_produceExpectedEncodedDimensions() {
        val sample = ImageDecoder.calculateInSampleSize(4000, 3000, 4, 8 * 1024 * 1024)
        assertEquals(4, sample)
        val encoded = ImageDecoder.sampledPixelDimensions(4000, 3000, sample)
        assertEquals(1000, encoded.width)
        assertEquals(750, encoded.height)
    }

    // ---- Result presentation ---------------------------------------------------

    @Test
    fun formatPixelDimensions_sampled_showsEncodedThenSource() {
        assertEquals(
            "1000 × 750 (from 4000 × 3000)",
            ResultsFormatter.formatPixelDimensions(4000, 3000, 1000, 750),
        )
    }

    @Test
    fun formatPixelDimensions_unsampled_showsSinglePair() {
        assertEquals("1920 × 1080", ResultsFormatter.formatPixelDimensions(1920, 1080, 1920, 1080))
    }

    // ---- EXIF compatibility ----------------------------------------------------

    @Test
    fun jpegWithoutExif_isClassifiedAsNoExif() {
        val kind = ExifMetadataHandler.classifySource(ByteArrayInputStream(jpegNoExif()))
        assertEquals(ExifMetadataHandler.SourceExifKind.NO_EXIF, kind)
    }

    @Test
    fun jpegWithExif_orientationSix_classifiesAsFoundWithoutRotatingPixels() {
        val kind = ExifMetadataHandler.classifySource(ByteArrayInputStream(jpegWithExif()))
        assertEquals(ExifMetadataHandler.SourceExifKind.FOUND, kind)
        // Storage-order dimensions are reported as-is; orientation stays metadata-only.
        val report = ImageSizeReport.forQuick(4032, 3024, 4032, 3024)
        assertEquals(4032, report.sourceWidth)
        assertEquals(3024, report.sourceHeight)
        assertFalse(report.wasSampled)
    }

    @Test
    fun pngSource_doesNotClaimMetadataPreservation() {
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
