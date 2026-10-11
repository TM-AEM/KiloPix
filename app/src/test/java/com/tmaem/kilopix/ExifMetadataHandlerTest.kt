package com.tmaem.kilopix

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class ExifMetadataHandlerTest {

    @Test
    fun jpegWithExif_isClassifiedAsFound() {
        val kind = ExifMetadataHandler.classifySource(ByteArrayInputStream(jpegWithExif()))
        assertEquals(ExifMetadataHandler.SourceExifKind.FOUND, kind)
    }

    @Test
    fun jpegWithoutExif_isClassifiedAsNoExif() {
        val kind = ExifMetadataHandler.classifySource(ByteArrayInputStream(jpegNoExif()))
        assertEquals(ExifMetadataHandler.SourceExifKind.NO_EXIF, kind)
    }

    @Test
    fun jpegWithoutExif_preserveDoesNotClaimMetadata() {
        val encoded = jpegNoExif()
        val result = ExifMetadataHandler.preserveFallback(
            ExifMetadataHandler.SourceExifKind.NO_EXIF,
            encoded,
        )
        assertTrue(result is ExifMetadataHandler.ExifMetadataResult.MetadataUnavailable)
        val unavailable = result as ExifMetadataHandler.ExifMetadataResult.MetadataUnavailable
        assertEquals(ExifMetadataHandler.ExifMetadataFailureReason.EXIF_UNAVAILABLE, unavailable.reason)
        assertArrayEquals(encoded, unavailable.jpegBytes)
    }

    @Test
    fun pngSource_isClassifiedAsNotJpeg() {
        val kind = ExifMetadataHandler.classifySource(ByteArrayInputStream(pngSignature()))
        assertEquals(ExifMetadataHandler.SourceExifKind.NOT_JPEG, kind)
    }

    @Test
    fun pngSource_preserveDisabledStrip_doesNotFailAndDoesNotClaimPreservation() {
        val encoded = jpegNoExif()
        val result = ExifMetadataHandler.preserveFallback(
            ExifMetadataHandler.SourceExifKind.NOT_JPEG,
            encoded,
        )
        assertTrue(result is ExifMetadataHandler.ExifMetadataResult.MetadataUnavailable)
        val unavailable = result as ExifMetadataHandler.ExifMetadataResult.MetadataUnavailable
        assertEquals(ExifMetadataHandler.ExifMetadataFailureReason.UNSUPPORTED_FORMAT, unavailable.reason)
        assertArrayEquals(encoded, unavailable.jpegBytes)
    }

    @Test
    fun pngSource_stripEnabled_removePolicySucceeds() {
        val result = ExifMetadataHandler.removeExifFromEncoded(jpegNoExif())
        assertTrue(result is ExifMetadataHandler.ExifMetadataResult.Success)
    }

    @Test
    fun malformedJpegSource_isClassifiedAsInvalidJpeg() {
        val kind = ExifMetadataHandler.classifySource(ByteArrayInputStream(malformedJpeg()))
        assertEquals(ExifMetadataHandler.SourceExifKind.INVALID_JPEG, kind)
    }

    @Test
    fun malformedJpegSource_preserveIsControlledFailure() {
        val result = ExifMetadataHandler.preserveFallback(
            ExifMetadataHandler.SourceExifKind.INVALID_JPEG,
            jpegNoExif(),
        )
        assertTrue(result is ExifMetadataHandler.ExifMetadataResult.Failure)
        val failure = result as ExifMetadataHandler.ExifMetadataResult.Failure
        assertEquals(ExifMetadataHandler.ExifMetadataFailureReason.INVALID_JPEG, failure.reason)
    }

    @Test
    fun inaccessibleSource_preserveIsControlledFailure() {
        val result = ExifMetadataHandler.preserveFallback(null, jpegNoExif())
        assertTrue(result is ExifMetadataHandler.ExifMetadataResult.Failure)
        val failure = result as ExifMetadataHandler.ExifMetadataResult.Failure
        assertEquals(ExifMetadataHandler.ExifMetadataFailureReason.SOURCE_INACCESSIBLE, failure.reason)
    }

    private fun pngSignature(): ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

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

    private fun malformedJpeg(): ByteArray = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(),
        0x00, 0x01,
    )
}
