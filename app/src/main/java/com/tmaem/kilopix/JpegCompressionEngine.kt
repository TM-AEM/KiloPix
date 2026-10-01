package com.tmaem.kilopix

import android.graphics.Bitmap
import java.io.IOException
import java.io.OutputStream

/**
 * Stateless, synchronous JPEG encoder built on Android's platform encoder
 * ([Bitmap.compress] with [Bitmap.CompressFormat.JPEG]).
 *
 * Task 05 scope: turn one already-decoded [Bitmap] into JPEG bytes written to a
 * caller-supplied [OutputStream]. Exactly one encoding operation is performed per
 * call. This engine performs no quality search, no resizing, no EXIF work, no file
 * creation and no UI work.
 *
 * ## Quality policy
 * Quality is an integer in Android's JPEG range `0..100`. Out-of-range values are
 * **clamped** deterministically into that range; in-range values pass through
 * unchanged. The effective value is always echoed in
 * [JpegCompressionResult.Success.quality]. This engine does not interpret quality
 * as "Quick"/"Target"/"Manual" - that belongs to callers.
 *
 * ## Alpha policy
 * JPEG has no alpha channel. The engine passes the bitmap directly to the platform
 * encoder and does **not** synthesize a background color or allocate a flattened
 * copy; how transparent pixels are represented is defined by the platform encoder.
 *
 * ## Bitmap ownership
 * The engine only reads the bitmap. It never recycles, mutates, replaces or retains
 * it; the caller keeps ownership.
 *
 * ## Stream ownership
 * The engine wraps the caller's stream in a [CountingOutputStream] to measure the
 * output, but it never closes or flushes that stream. The caller owns the
 * destination and decides when to close it.
 */
object JpegCompressionEngine {

    /** Android's JPEG encoder accepts qualities in this inclusive range. */
    const val MIN_QUALITY = 0
    const val MAX_QUALITY = 100

    /**
     * Encodes [bitmap] as JPEG at [quality] into [outputStream].
     *
     * @return [JpegCompressionResult.Success] with the effective quality and byte
     *         count, or a controlled [JpegCompressionResult.Failure].
     */
    fun compress(
        bitmap: Bitmap,
        quality: Int,
        outputStream: OutputStream,
    ): JpegCompressionResult {
        val effectiveQuality = normalizeQuality(quality)

        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            return JpegCompressionResult.Failure(JpegCompressionFailureReason.INVALID_BITMAP)
        }

        val counter = CountingOutputStream(outputStream)

        val encoded = try {
            // Single platform encoding operation. No retries and no quality search.
            bitmap.compress(Bitmap.CompressFormat.JPEG, effectiveQuality, counter)
        } catch (e: OutOfMemoryError) {
            return JpegCompressionResult.Failure(JpegCompressionFailureReason.OUT_OF_MEMORY)
        } catch (e: IllegalStateException) {
            return JpegCompressionResult.Failure(JpegCompressionFailureReason.INVALID_BITMAP)
        } catch (e: IllegalArgumentException) {
            return JpegCompressionResult.Failure(JpegCompressionFailureReason.INVALID_BITMAP)
        } catch (e: IOException) {
            return JpegCompressionResult.Failure(JpegCompressionFailureReason.STREAM_FAILURE)
        } catch (e: Exception) {
            return JpegCompressionResult.Failure(JpegCompressionFailureReason.ENCODER_FAILED)
        }

        if (!encoded) {
            return JpegCompressionResult.Failure(JpegCompressionFailureReason.ENCODER_FAILED)
        }
        if (counter.overflowed) {
            return JpegCompressionResult.Failure(JpegCompressionFailureReason.COUNTER_OVERFLOW)
        }
        if (counter.bytesWritten <= 0L) {
            return JpegCompressionResult.Failure(JpegCompressionFailureReason.ZERO_BYTES)
        }

        return JpegCompressionResult.Success(
            bytesWritten = counter.bytesWritten,
            quality = effectiveQuality,
        )
    }

    /** Clamps [quality] into `0..100`. Pure and side-effect free. */
    fun normalizeQuality(quality: Int): Int = quality.coerceIn(MIN_QUALITY, MAX_QUALITY)
}

/** Outcome of a single JPEG encoding attempt. */
sealed interface JpegCompressionResult {

    /** [bytesWritten] is always greater than zero. */
    data class Success(val bytesWritten: Long, val quality: Int) : JpegCompressionResult

    data class Failure(val reason: JpegCompressionFailureReason) : JpegCompressionResult
}

/** Small, deterministic JPEG encoding failure taxonomy. */
enum class JpegCompressionFailureReason {
    INVALID_BITMAP,
    STREAM_FAILURE,
    ENCODER_FAILED,
    ZERO_BYTES,
    COUNTER_OVERFLOW,
    OUT_OF_MEMORY,
}

/**
 * Forwarding [OutputStream] that counts bytes written to [delegate] without
 * buffering or retaining any of them. The counter is a [Long]; overflow is detected
 * defensively and surfaced through [overflowed] instead of wrapping silently.
 *
 * This is the mechanism future target-size work can build on. Closing this stream
 * closes the caller's stream, so ownership stays with the caller.
 */
class CountingOutputStream(private val delegate: OutputStream) : OutputStream() {

    var bytesWritten: Long = 0L
        private set

    var overflowed: Boolean = false
        private set

    override fun write(b: Int) {
        delegate.write(b)
        add(1L)
    }

    override fun write(b: ByteArray) {
        write(b, 0, b.size)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        delegate.write(b, off, len)
        add(len.toLong())
    }

    override fun flush() {
        delegate.flush()
    }

    override fun close() {
        delegate.close()
    }

    private fun add(count: Long) {
        if (overflowed || count <= 0L) return
        val next = bytesWritten + count
        if (next < bytesWritten) {
            overflowed = true
            bytesWritten = Long.MAX_VALUE
        } else {
            bytesWritten = next
        }
    }
}
