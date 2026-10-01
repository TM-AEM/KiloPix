package com.tmaem.kilopix

import android.graphics.Bitmap
import java.io.OutputStream

/**
 * Adaptive "Quick" compression: choose one deterministic JPEG quality from source
 * metadata, then perform exactly **one** JPEG encoding via [JpegCompressionEngine].
 *
 * Task 06 scope: this layer contains no target-size logic, no quality search, no
 * repeated encoding and no resizing. The output is always JPEG at the full input
 * dimensions.
 *
 * ## Quality policy (application policy, not a mathematically optimal model)
 * Bands are based on the source pixel count, computed with [Long] to avoid overflow:
 *
 * - `<= 1,000,000` px (small)   -> 95
 * - `<= 8,000,000` px (normal)  -> 90
 * - `<= 20,000,000` px (large)  -> 85
 * - otherwise (extremely large) -> 80
 *
 * The band value is raised by [PNG_QUALITY_BONUS] for `image/png` sources
 * (screenshots/graphics), where JPEG artifacts are more visible. Results are then
 * clamped to [MIN_POLICY_QUALITY]..[MAX_POLICY_QUALITY]. The policy never selects
 * 0 or 1, and it only ever produces JPEG output regardless of the source MIME type.
 */
object QuickCompressionEngine {

    const val MIN_POLICY_QUALITY = 80
    const val MAX_POLICY_QUALITY = 95

    private const val DEFAULT_QUALITY = 90
    private const val PNG_QUALITY_BONUS = 5
    private const val MIME_PNG = "image/png"

    private const val SMALL_PIXELS = 1_000_000L
    private const val NORMAL_PIXELS = 8_000_000L
    private const val LARGE_PIXELS = 20_000_000L

    /**
     * Runs one adaptive Quick compression.
     *
     * @param sourceWidth original source width reported by the decoder (never the
     *        decoded bitmap width, and never resized).
     * @param sourceHeight original source height.
     * @param mimeType optional source MIME type; used only for quality selection.
     */
    fun compress(
        bitmap: Bitmap,
        sourceWidth: Int,
        sourceHeight: Int,
        mimeType: String?,
        outputStream: OutputStream,
    ): QuickCompressionResult {
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            return QuickCompressionResult.Failure(QuickCompressionFailureReason.INVALID_DIMENSIONS)
        }

        val quality = selectQuality(sourceWidth, sourceHeight, mimeType)

        // Exactly one encoding attempt, delegated to Task 05's engine.
        return when (val result = JpegCompressionEngine.compress(bitmap, quality, outputStream)) {
            is JpegCompressionResult.Success -> QuickCompressionResult.Success(
                quality = result.quality,
                bytesWritten = result.bytesWritten,
            )
            is JpegCompressionResult.Failure -> QuickCompressionResult.Failure(
                mapFailure(result.reason),
            )
        }
    }

    /**
     * Pure adaptive quality policy. No file, URI, bitmap or stream access and no side
     * effects, so it can be tested in isolation.
     */
    fun selectQuality(width: Int, height: Int, mimeType: String?): Int {
        if (width <= 0 || height <= 0) return DEFAULT_QUALITY

        val pixelCount = width.toLong() * height.toLong()
        val base = when {
            pixelCount <= SMALL_PIXELS -> 95
            pixelCount <= NORMAL_PIXELS -> 90
            pixelCount <= LARGE_PIXELS -> 85
            else -> 80
        }
        val adjusted = if (mimeType == MIME_PNG) base + PNG_QUALITY_BONUS else base
        return adjusted.coerceIn(MIN_POLICY_QUALITY, MAX_POLICY_QUALITY)
    }

    private fun mapFailure(reason: JpegCompressionFailureReason): QuickCompressionFailureReason =
        when (reason) {
            JpegCompressionFailureReason.INVALID_BITMAP -> QuickCompressionFailureReason.INVALID_BITMAP
            JpegCompressionFailureReason.STREAM_FAILURE -> QuickCompressionFailureReason.STREAM_FAILURE
            JpegCompressionFailureReason.ENCODER_FAILED -> QuickCompressionFailureReason.ENCODER_FAILED
            JpegCompressionFailureReason.ZERO_BYTES -> QuickCompressionFailureReason.ZERO_BYTES
            JpegCompressionFailureReason.COUNTER_OVERFLOW -> QuickCompressionFailureReason.COUNTER_OVERFLOW
            JpegCompressionFailureReason.OUT_OF_MEMORY -> QuickCompressionFailureReason.OUT_OF_MEMORY
        }
}

/** Outcome of a single adaptive Quick compression attempt. */
sealed interface QuickCompressionResult {

    /** [quality] is the quality that was actually used; [bytesWritten] is > 0. */
    data class Success(val quality: Int, val bytesWritten: Long) : QuickCompressionResult

    data class Failure(val reason: QuickCompressionFailureReason) : QuickCompressionResult
}

/** Small, deterministic Quick compression failure taxonomy. */
enum class QuickCompressionFailureReason {
    INVALID_DIMENSIONS,
    INVALID_BITMAP,
    STREAM_FAILURE,
    ENCODER_FAILED,
    ZERO_BYTES,
    COUNTER_OVERFLOW,
    OUT_OF_MEMORY,
}
