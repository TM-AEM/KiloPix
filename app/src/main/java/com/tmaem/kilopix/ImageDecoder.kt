package com.tmaem.kilopix

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri

/**
 * Memory-safe, single-image decoder built entirely on Android platform APIs.
 *
 * Task 04 scope: decode one already-selected [Uri] into a bounded [Bitmap] for
 * future compression work. This class performs no compression, no preview and no
 * EXIF processing, and writes nothing anywhere.
 *
 * Decode-time sampling ([BitmapFactory.Options.inSampleSize]) is a memory-safety
 * measure, not a user-facing resize feature.
 *
 * Orientation: Android's [BitmapFactory] does not apply EXIF orientation. The
 * returned bitmap is therefore in the decoder's natural pixel representation;
 * orientation/EXIF handling is deliberately deferred (Task 11).
 */
object ImageDecoder {

    /** ARGB_8888 stores four bytes per pixel. */
    private const val BYTES_PER_PIXEL = 4

    private const val MIN_BUDGET_BYTES = 8L * 1024 * 1024
    private const val MAX_BUDGET_BYTES = 64L * 1024 * 1024

    /** Hard ceiling for the power-of-two sampling loop, to avoid overflow. */
    private const val MAX_SAMPLE_SIZE = 1 shl 20

    /**
     * Decodes [uri] into a bitmap that fits within [maxDecodeBytes] of pixel data.
     *
     * @param maxDecodeBytes upper bound for the decoded bitmap's pixel allocation.
     *        Defaults to a conservative share of the process heap (see
     *        [defaultMaxDecodeBytes]). The caller owns the returned bitmap and is
     *        free to recycle/discard it.
     */
    fun decode(
        resolver: ContentResolver,
        uri: Uri,
        maxDecodeBytes: Int = defaultMaxDecodeBytes(),
    ): DecodeResult {
        val bounds = readBounds(resolver, uri)
            ?: return DecodeResult.Failure(DecodeFailureReason.INACCESSIBLE)

        val sourceWidth = bounds.outWidth
        val sourceHeight = bounds.outHeight
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            return DecodeResult.Failure(DecodeFailureReason.INVALID_DIMENSIONS)
        }

        val sampleSize = calculateInSampleSize(sourceWidth, sourceHeight, BYTES_PER_PIXEL, maxDecodeBytes)

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
            // Keep dimensions deterministic; do not let density scaling alter them.
            inScaled = false
        }

        val bitmap = try {
            resolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, options)
            }
        } catch (e: OutOfMemoryError) {
            return DecodeResult.Failure(DecodeFailureReason.OUT_OF_MEMORY)
        } catch (e: SecurityException) {
            return DecodeResult.Failure(DecodeFailureReason.INACCESSIBLE)
        } catch (e: Exception) {
            return DecodeResult.Failure(DecodeFailureReason.DECODE_FAILED)
        }

        if (bitmap == null) {
            return DecodeResult.Failure(DecodeFailureReason.DECODE_FAILED)
        }

        return DecodeResult.Success(
            bitmap = bitmap,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            mimeType = bounds.outMimeType,
        )
    }

    /**
     * Bounds-only pass: reads dimensions and MIME type without allocating pixels.
     * Returns null when the document cannot be opened or the provider fails.
     */
    private fun readBounds(resolver: ContentResolver, uri: Uri): BitmapFactory.Options? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        return try {
            resolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, options)
                options
            }
        } catch (e: SecurityException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Smallest power-of-two sample size whose decoded pixel allocation fits the
     * budget. Returns 1 when the source already fits (no downscaling). All pixel
     * arithmetic uses Long to avoid integer overflow, and the loop is bounded by
     * [MAX_SAMPLE_SIZE] to avoid an unbounded loop on pathological input.
     */
    internal fun calculateInSampleSize(
        sourceWidth: Int,
        sourceHeight: Int,
        bytesPerPixel: Int,
        maxDecodeBytes: Int,
    ): Int {
        if (sourceWidth <= 0 || sourceHeight <= 0) return 1
        if (bytesPerPixel <= 0 || maxDecodeBytes <= 0) return 1

        val maxPixels = maxDecodeBytes.toLong() / bytesPerPixel.toLong()
        if (maxPixels <= 0L) return 1

        val sourcePixels = sourceWidth.toLong() * sourceHeight.toLong()
        if (sourcePixels <= maxPixels) return 1

        var sample = 1
        while (sample < MAX_SAMPLE_SIZE) {
            val width = sourceWidth / sample
            val height = sourceHeight / sample
            val pixels = width.toLong() * height.toLong()
            if (width >= 1 && height >= 1 && pixels <= maxPixels) return sample
            sample = sample shl 1
        }
        return MAX_SAMPLE_SIZE
    }

    /** Keeps the decoded bitmap within roughly a quarter of the process heap. */
    private fun defaultMaxDecodeBytes(): Int {
        val budget = Runtime.getRuntime().maxMemory() / 4
        return budget.coerceIn(MIN_BUDGET_BYTES, MAX_BUDGET_BYTES).toInt()
    }
}

/** Outcome of a single-image decode attempt. */
sealed interface DecodeResult {

    /** The caller owns [bitmap] and may discard it when done. */
    data class Success(
        val bitmap: Bitmap,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val mimeType: String?,
    ) : DecodeResult

    data class Failure(val reason: DecodeFailureReason) : DecodeResult
}

/**
 * Small, deterministic failure taxonomy. Corrupt and unsupported images are both
 * reported as [DECODE_FAILED], because the platform decoder does not expose a
 * reliable distinction without an external codec.
 */
enum class DecodeFailureReason {
    INACCESSIBLE,
    INVALID_DIMENSIONS,
    DECODE_FAILED,
    OUT_OF_MEMORY,
}
