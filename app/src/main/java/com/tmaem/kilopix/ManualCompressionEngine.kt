package com.tmaem.kilopix

import android.graphics.Bitmap
import java.io.OutputStream

/**
 * Manual compression: encode a JPEG at an explicit caller-chosen quality and an
 * explicit caller-chosen output size.
 *
 * Task 08 scope: this is the first engine allowed to resize. Exactly one JPEG encode
 * occurs per invocation and all encoding is delegated to [JpegCompressionEngine].
 * This engine performs no quality search, no target-size logic, no EXIF work, no
 * storage and no UI work.
 *
 * ## Aspect ratio
 * Aspect ratio is deliberately **not** preserved. `outputWidth` and `outputHeight`
 * are authoritative, so non-proportional dimensions are allowed and there is no
 * cropping, letterboxing, or ratio correction.
 *
 * ## Quality
 * Quality is delegated to [JpegCompressionEngine], which clamps it into Android's
 * `0..100` range. Manual compression does not implement a second normalization and
 * does not interpret quality in any other way.
 *
 * ## Dimensions
 * `outputWidth`/`outputHeight` must be strictly positive. If they equal the source
 * dimensions, the source bitmap is encoded directly (no second bitmap is allocated).
 * Otherwise exactly one [Bitmap.createScaledBitmap] is performed.
 *
 * ## Memory
 * Before allocating a resized bitmap, the estimated ARGB_8888 footprint is computed
 * with [Long] and compared against [MAX_RESIZED_BITMAP_BYTES]. Oversized requests
 * fail with [ManualCompressionFailureReason.DIMENSIONS_TOO_LARGE] instead of
 * allocation.
 *
 * ## Ownership
 * The caller owns the source bitmap; it is never recycled, mutated, or retained. Any
 * resized bitmap created here is local to the call and is recycled before returning
 * (guarded so the caller bitmap can never be recycled).
 *
 * ## Alpha
 * No transparency policy is introduced. JPEG encoding (and therefore alpha handling)
 * remains delegated to [JpegCompressionEngine].
 */
object ManualCompressionEngine {

    private const val BYTES_PER_PIXEL = 4

    /**
     * Hard upper bound on the estimated ARGB_8888 memory of a resized bitmap: 64 MiB.
     * This equals the maximum pixel budget that Task 04's decoder may produce
     * (~16.7 M pixels), so a manual resize can never exceed the memory envelope already
     * validated for this app, and absurd requests are rejected before allocation.
     */
    const val MAX_RESIZED_BITMAP_BYTES = 64L * 1024 * 1024

    /**
     * Encodes [bitmap] as JPEG at [quality] scaled to [outputWidth] x [outputHeight].
     *
     * @return [ManualCompressionResult.Success] with the effective quality, the output
     *         dimensions and the byte count, or a controlled failure.
     */
    fun compress(
        bitmap: Bitmap,
        quality: Int,
        outputWidth: Int,
        outputHeight: Int,
        outputStream: OutputStream,
    ): ManualCompressionResult {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            return ManualCompressionResult.Failure(ManualCompressionFailureReason.INVALID_BITMAP)
        }
        if (outputWidth <= 0 || outputHeight <= 0) {
            return ManualCompressionResult.Failure(ManualCompressionFailureReason.INVALID_DIMENSIONS)
        }

        // Long pixel count (Int * Int always fits in Long). Compared against the budget
        // before any multiplication by bytes-per-pixel, so no overflow can occur.
        val pixels = outputWidth.toLong() * outputHeight.toLong()
        val maxPixels = MAX_RESIZED_BITMAP_BYTES / BYTES_PER_PIXEL
        if (pixels <= 0L || pixels > maxPixels) {
            return ManualCompressionResult.Failure(ManualCompressionFailureReason.DIMENSIONS_TOO_LARGE)
        }

        val dimensionsMatch = outputWidth == bitmap.width && outputHeight == bitmap.height

        var resized: Bitmap? = null
        try {
            val target: Bitmap
            if (dimensionsMatch) {
                // Same dimensions: encode the original directly, no allocation.
                target = bitmap
            } else {
                val created = try {
                    Bitmap.createScaledBitmap(bitmap, outputWidth, outputHeight, true)
                } catch (e: OutOfMemoryError) {
                    return ManualCompressionResult.Failure(ManualCompressionFailureReason.OUT_OF_MEMORY)
                }
                // Dimensions differ, so a distinct bitmap is expected; guard regardless.
                if (created === bitmap) {
                    target = bitmap
                } else {
                    resized = created
                    target = created
                }
            }

            // Exactly one JPEG encode per invocation, delegated to Task 05.
            return when (val result = JpegCompressionEngine.compress(target, quality, outputStream)) {
                is JpegCompressionResult.Success -> ManualCompressionResult.Success(
                    quality = result.quality,
                    width = outputWidth,
                    height = outputHeight,
                    bytesWritten = result.bytesWritten,
                )
                is JpegCompressionResult.Failure -> ManualCompressionResult.Failure(
                    mapFailure(result.reason),
                )
            }
        } finally {
            val temp = resized
            if (temp != null && temp !== bitmap && !temp.isRecycled) {
                temp.recycle()
            }
        }
    }

    private fun mapFailure(reason: JpegCompressionFailureReason): ManualCompressionFailureReason =
        when (reason) {
            JpegCompressionFailureReason.INVALID_BITMAP -> ManualCompressionFailureReason.INVALID_BITMAP
            JpegCompressionFailureReason.STREAM_FAILURE -> ManualCompressionFailureReason.STREAM_FAILURE
            JpegCompressionFailureReason.ENCODER_FAILED -> ManualCompressionFailureReason.ENCODER_FAILURE
            JpegCompressionFailureReason.ZERO_BYTES -> ManualCompressionFailureReason.ZERO_BYTES
            JpegCompressionFailureReason.COUNTER_OVERFLOW -> ManualCompressionFailureReason.COUNTER_OVERFLOW
            JpegCompressionFailureReason.OUT_OF_MEMORY -> ManualCompressionFailureReason.OUT_OF_MEMORY
        }
}

/** Outcome of a single manual compression attempt. */
sealed interface ManualCompressionResult {

    /**
     * [width]/[height] are the requested output dimensions (which the encoded JPEG
     * uses). [bytesWritten] is greater than zero.
     */
    data class Success(
        val quality: Int,
        val width: Int,
        val height: Int,
        val bytesWritten: Long,
    ) : ManualCompressionResult

    data class Failure(val reason: ManualCompressionFailureReason) : ManualCompressionResult
}

/**
 * Small, deterministic manual-compression failure taxonomy.
 *
 * There is no `INVALID_QUALITY`: quality validation is delegated to
 * [JpegCompressionEngine], which clamps out-of-range values instead of rejecting them.
 */
enum class ManualCompressionFailureReason {
    INVALID_BITMAP,
    INVALID_DIMENSIONS,
    DIMENSIONS_TOO_LARGE,
    OUT_OF_MEMORY,
    STREAM_FAILURE,
    ENCODER_FAILURE,
    ZERO_BYTES,
    COUNTER_OVERFLOW,
}
