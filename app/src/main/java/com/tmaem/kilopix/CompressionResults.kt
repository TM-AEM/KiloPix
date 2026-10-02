package com.tmaem.kilopix

import android.net.Uri
import java.util.Locale

/**
 * Task 15: UI-layer result model for one processed image, derived from the ordered
 * [BatchCompressionResult.itemResults] plus the pre-captured source sizes. This is purely
 * additive at the UI layer: no compression model or save-layer class is changed.
 *
 * [sourceName] and [outputName] are display (not filesystem) names, resolved once so the
 * results list renders without repeated provider queries.
 */
internal sealed interface ResultItem {

    val sourceUri: Uri
    val sourceName: String

    val isSuccess: Boolean
        get() = this is Success

    data class Success(
        override val sourceUri: Uri,
        override val sourceName: String,
        /** Output content Uri, or null when the save could not report one (not shareable). */
        val outputUri: Uri?,
        val outputName: String?,
        /** Source byte size when the provider exposed it; null when unavailable. */
        val originalSize: Long?,
        /** Compressed/output byte size ([BatchItemResult.Success.bytesWritten]). */
        val compressedSize: Long,
        val width: Int,
        val height: Int,
        val quality: Int,
    ) : ResultItem

    data class Failure(
        override val sourceUri: Uri,
        override val sourceName: String,
        val reason: BatchFailureReason,
    ) : ResultItem
}

/**
 * Task 15: framework-only helpers for turning a byte size into human text and computing
 * compression savings. Kept dependency-free and deterministic so it can be reasoned about
 * in isolation from Android UI.
 */
internal object ResultsFormatter {

    private const val KIB = 1024L
    private const val MIB = 1024L * 1024L

    /**
     * Formats a non-negative byte count compactly: "150 B", "12.4 KB", "3.2 MB".
     * Anything below zero is clamped to "0 B".
     */
    fun formatBytes(bytes: Long): String {
        val value = bytes.coerceAtLeast(0L)
        return when {
            value >= MIB -> "%.1f MB".format(Locale.US, value / MIB.toDouble())
            value >= KIB -> "%.1f KB".format(Locale.US, value / KIB.toDouble())
            else -> "$value B"
        }
    }

    /**
     * Percentage size reduction from [original] to [compressed], in [0..100] inclusive,
     * or null when unknown or the original size is not positive. A negative reduction
     * (i.e. the output grew) is reported as 0 so the caller can fall back to helper text.
     */
    fun percentageReduction(original: Long?, compressed: Long): Int? {
        if (original == null || original <= 0 || compressed < 0) return null
        val ratio = 1.0 - compressed.toDouble() / original.toDouble()
        val percent = (ratio * 100.0).coerceIn(0.0, 100.0)
        return percent.toInt()
    }

    /**
     * Number of bytes freed, or null when the original size is unknown or the output grew
     * (in which case there is no saving to claim).
     */
    fun bytesSaved(original: Long?, compressed: Long): Long? {
        if (original == null || compressed < 0) return null
        val saved = original - compressed
        return saved.takeIf { it > 0 }
    }

    /**
     * Number of extra bytes when the output is larger than the original, or null when the
     * original is unknown, the sizes are equal, or the output is smaller/equal.
     */
    fun bytesGrowth(original: Long?, compressed: Long): Long? {
        if (original == null || original <= 0 || compressed < 0) return null
        val growth = compressed - original
        return growth.takeIf { it > 0 }
    }

    /**
     * Percentage by which the output is larger than the original, or null when unknown or
     * not larger. Computed in floating point to avoid integer overflow.
     */
    fun percentageGrowth(original: Long?, compressed: Long): Int? {
        if (original == null || original <= 0 || compressed < 0) return null
        if (compressed <= original) return null
        val growth = (compressed - original).toDouble() / original.toDouble()
        return (growth * 100.0).toInt()
    }
}
