package com.tmaem.kilopix

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Platform-only target-size JPEG compressor.
 *
 * Task 07 scope: choose the highest JPEG quality in `0..100` whose encoded output
 * fits a caller-provided byte target, then write that single JPEG to the caller's
 * stream. Dimensions are never changed and the source bitmap is read-only.
 *
 * ## Search strategy
 * The quality space is finite (`0..100`, 101 values), and JPEG output size is assumed
 * monotonic (non-decreasing) in quality for a fixed bitmap and platform encoder. The
 * engine therefore performs a binary search for the highest fitting quality:
 *
 * - `low = 0`, `high = 100`
 * - test `mid`; if the candidate fits the target, remember it and search higher,
 *   otherwise search lower
 *
 * The loop is bounded by BOTH `low <= high` (strict range reduction) and
 * [MAX_ENCODING_ATTEMPTS], so it always terminates and can never spin.
 *
 * ## Maximum encoding attempts
 * A binary search over 101 discrete values performs at most
 * `ceil(log2(101)) = 7` probes. [MAX_ENCODING_ATTEMPTS] is fixed at that value and
 * enforced as a hard stop. Each probe performs exactly one
 * [JpegCompressionEngine.compress] call; there are no nested searches and no hidden
 * retries.
 *
 * ## Candidate buffering and limit
 * Candidate JPEGs are encoded into bounded in-memory buffers (never files). The
 * per-candidate buffer limit is `min(targetBytes, MAX_CANDIDATE_BYTES)`, so:
 * - when the target is small, no buffer may exceed the target (anything larger is
 *   immediately disqualified), keeping transient memory tiny;
 * - when the target is large, buffers are capped at [MAX_CANDIDATE_BYTES].
 *
 * A candidate that would exceed the limit is aborted by throwing from the buffer; the
 * engine detects this and treats the candidate as not fitting. JPEG data is never
 * truncated and never treated as valid.
 *
 * Only the single best fitting candidate is retained; every other candidate is
 * discarded immediately, so at most two buffers are live at once (current + best).
 *
 * ## Stream ownership
 * The caller owns [outputStream]. The engine never creates, closes, or owns it. The
 * selected JPEG is copied into it exactly once, after the search has completed.
 *
 * ## Alpha / EXIF / orientation
 * Identical boundary to Task 05: the bitmap is passed to the platform encoder
 * unchanged, so alpha is handled by the platform. No transparency policy, EXIF
 * read/write, or rotation is implemented here.
 */
object TargetSizeCompressionEngine {

    /** The quality sweep covers the full Android-supported JPEG range. */
    const val MIN_QUALITY = 0
    const val MAX_QUALITY = 100

    /**
     * Hard ceiling on `JpegCompressionEngine.compress` calls per invocation.
     * `ceil(log2(101)) = 7` suffices for a complete binary search over `0..100`.
     */
    const val MAX_ENCODING_ATTEMPTS = 7

    /**
     * Absolute per-candidate buffer cap. Task 04 bounds a decoded bitmap to at most
     * `64 MiB / 4` bytes-per-pixel, i.e. ~16.7 M pixels. At a conservative worst case
     * of 2 bytes per pixel for a quality-100 JPEG, the largest candidate is ~32 MiB.
     * Capping here keeps transient candidate memory predictable on low-memory devices
     * without ever truncating data.
     */
    const val MAX_CANDIDATE_BYTES = 32L * 1024 * 1024

    /**
     * Encodes [bitmap] at the highest quality that fits [targetBytes], writing the
     * single chosen JPEG to [outputStream].
     *
     * @param targetBytes explicit byte budget. Values `<= 0` are rejected with
     *        [TargetSizeCompressionFailureReason.INVALID_TARGET_SIZE] and are never
     *        silently converted to another target.
     */
    fun compress(
        bitmap: Bitmap,
        targetBytes: Long,
        outputStream: OutputStream,
    ): TargetSizeCompressionResult {
        if (targetBytes <= 0L) {
            return TargetSizeCompressionResult.Failure(
                reason = TargetSizeCompressionFailureReason.INVALID_TARGET_SIZE,
                targetBytes = targetBytes,
            )
        }
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            return TargetSizeCompressionResult.Failure(
                reason = TargetSizeCompressionFailureReason.INVALID_BITMAP,
                targetBytes = targetBytes,
            )
        }

        val candidateLimit = minOf(targetBytes, MAX_CANDIDATE_BYTES).toInt()
        val memoryCapInUse = targetBytes > MAX_CANDIDATE_BYTES

        var low = MIN_QUALITY
        var high = MAX_QUALITY
        var attempts = 0

        var bestQuality = -1
        var bestBuffer: BoundedByteArrayOutputStream? = null
        var bestSize = 0L
        var smallestEncodedBytes: Long? = null
        var hitMemoryCap = false

        // Bounded binary search. Both conditions must fail before it can stop early;
        // `attempts < MAX_ENCODING_ATTEMPTS` is a hard safety stop on top of `low > high`.
        while (low <= high && attempts < MAX_ENCODING_ATTEMPTS) {
            val mid = (low + high) / 2
            attempts += 1

            when (val outcome = encodeCandidate(bitmap, mid, candidateLimit)) {
                is CandidateOutcome.Encoded -> {
                    val size = outcome.size
                    val previousSmallest = smallestEncodedBytes
                    if (previousSmallest == null || size < previousSmallest) {
                        smallestEncodedBytes = size
                    }
                    if (size <= targetBytes) {
                        // Highest fitting quality so far; replacing releases the prior best.
                        bestQuality = mid
                        bestBuffer = outcome.buffer
                        bestSize = size
                        low = mid + 1
                    } else {
                        outcome.buffer.reset()
                        high = mid - 1
                    }
                }

                CandidateOutcome.LimitExceeded -> {
                    if (memoryCapInUse) hitMemoryCap = true
                    high = mid - 1
                }

                is CandidateOutcome.Failed -> {
                    return TargetSizeCompressionResult.Failure(
                        reason = outcome.reason,
                        targetBytes = targetBytes,
                    )
                }
            }
        }

        val buffer = bestBuffer
            ?: return TargetSizeCompressionResult.Failure(
                reason = if (hitMemoryCap) {
                    TargetSizeCompressionFailureReason.CANDIDATE_LIMIT_EXCEEDED
                } else {
                    TargetSizeCompressionFailureReason.TARGET_UNACHIEVABLE
                },
                targetBytes = targetBytes,
                smallestEncodedBytes = smallestEncodedBytes,
            )

        // Copy the chosen candidate into the caller-owned stream exactly once.
        val counter = CountingOutputStream(outputStream)
        try {
            buffer.writeTo(counter)
        } catch (e: OutOfMemoryError) {
            return TargetSizeCompressionResult.Failure(
                TargetSizeCompressionFailureReason.OUT_OF_MEMORY,
                targetBytes = targetBytes,
            )
        } catch (e: IOException) {
            return TargetSizeCompressionResult.Failure(
                TargetSizeCompressionFailureReason.STREAM_FAILURE,
                targetBytes = targetBytes,
            )
        } catch (e: Exception) {
            return TargetSizeCompressionResult.Failure(
                TargetSizeCompressionFailureReason.STREAM_FAILURE,
                targetBytes = targetBytes,
            )
        }

        if (counter.overflowed) {
            return TargetSizeCompressionResult.Failure(
                TargetSizeCompressionFailureReason.COUNTER_OVERFLOW,
                targetBytes = targetBytes,
            )
        }
        if (counter.bytesWritten != bestSize) {
            return TargetSizeCompressionResult.Failure(
                TargetSizeCompressionFailureReason.STREAM_FAILURE,
                targetBytes = targetBytes,
            )
        }

        return TargetSizeCompressionResult.Success(
            quality = bestQuality,
            bytesWritten = counter.bytesWritten,
        )
    }

    /**
     * Performs exactly one quality probe through the Task 05 engine. The candidate is
     * encoded into a bounded in-memory buffer; no output reaches the caller here.
     */
    private fun encodeCandidate(
        bitmap: Bitmap,
        quality: Int,
        limit: Int,
    ): CandidateOutcome {
        val buffer = BoundedByteArrayOutputStream(limit)
        val result = try {
            JpegCompressionEngine.compress(bitmap, quality, buffer)
        } catch (e: OutOfMemoryError) {
            return CandidateOutcome.Failed(TargetSizeCompressionFailureReason.OUT_OF_MEMORY)
        }

        return when (result) {
            is JpegCompressionResult.Success ->
                if (buffer.exceeded) {
                    CandidateOutcome.LimitExceeded
                } else {
                    CandidateOutcome.Encoded(buffer, result.bytesWritten)
                }

            is JpegCompressionResult.Failure -> when {
                // The buffer limit is the cause; Task 05 reports it as a stream failure.
                buffer.exceeded -> CandidateOutcome.LimitExceeded
                result.reason == JpegCompressionFailureReason.OUT_OF_MEMORY ->
                    CandidateOutcome.Failed(TargetSizeCompressionFailureReason.OUT_OF_MEMORY)
                result.reason == JpegCompressionFailureReason.STREAM_FAILURE ->
                    CandidateOutcome.Failed(TargetSizeCompressionFailureReason.STREAM_FAILURE)
                result.reason == JpegCompressionFailureReason.COUNTER_OVERFLOW ->
                    CandidateOutcome.Failed(TargetSizeCompressionFailureReason.COUNTER_OVERFLOW)
                else -> CandidateOutcome.Failed(TargetSizeCompressionFailureReason.ENCODER_FAILURE)
            }
        }
    }
}

/** Internal outcome of a single candidate quality probe. */
private sealed interface CandidateOutcome {

    class Encoded(val buffer: BoundedByteArrayOutputStream, val size: Long) : CandidateOutcome

    data object LimitExceeded : CandidateOutcome

    data class Failed(val reason: TargetSizeCompressionFailureReason) : CandidateOutcome
}

/** Raised by [BoundedByteArrayOutputStream] when a candidate would exceed its cap. */
private class CandidateLimitExceededException : IOException()

/**
 * In-memory [ByteArrayOutputStream] that refuses to grow beyond [limit] bytes. When
 * the limit would be exceeded it records [exceeded] and aborts the write, so the
 * platform encoder stops immediately and no oversized/truncated data is retained.
 *
 * [ByteArrayOutputStream.writeTo] is used for the final copy, so the encoded bytes
 * are never duplicated into a second array.
 */
private class BoundedByteArrayOutputStream(private val limit: Int) : ByteArrayOutputStream() {

    var exceeded: Boolean = false
        private set

    override fun write(b: Int) {
        ensureWithinLimit(1)
        super.write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        ensureWithinLimit(len)
        super.write(b, off, len)
    }

    private fun ensureWithinLimit(incoming: Int) {
        if (exceeded || count + incoming > limit) {
            exceeded = true
            throw CandidateLimitExceededException()
        }
    }
}

/** Outcome of a single target-size compression attempt. */
sealed interface TargetSizeCompressionResult {

    /** [quality] is the highest quality that fit; [bytesWritten] is `> 0` and `<= target`. */
    data class Success(val quality: Int, val bytesWritten: Long) : TargetSizeCompressionResult

    data class Failure(
        val reason: TargetSizeCompressionFailureReason,
        val targetBytes: Long? = null,
        val smallestEncodedBytes: Long? = null,
    ) : TargetSizeCompressionResult
}

/**
 * Small, deterministic target-size failure taxonomy.
 *
 * [TARGET_UNACHIEVABLE] means no tested quality in `0..100` produced a JPEG at or
 * below the target; the image is never resized to work around this.
 */
enum class TargetSizeCompressionFailureReason {
    INVALID_TARGET_SIZE,
    INVALID_BITMAP,
    TARGET_UNACHIEVABLE,
    STREAM_FAILURE,
    ENCODER_FAILURE,
    OUT_OF_MEMORY,
    CANDIDATE_LIMIT_EXCEEDED,
    COUNTER_OVERFLOW,
}
