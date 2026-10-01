package com.tmaem.kilopix

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Preview / comparison engine.
 *
 * Task 09 scope: produce a **real** preview of the compressed output by
 *  1. delegating compression to the mode-specific engine (Quick / Target / Manual),
 *  2. capturing the actual encoded JPEG bytes in a bounded in-memory buffer,
 *  3. decoding those exact bytes into a preview [Bitmap].
 *
 * The preview therefore always represents the actual encoded JPEG. Sizes are the
 * actual number of bytes produced by the encoder - never estimated from quality,
 * source size, or dimensions.
 *
 * ## Mode delegation
 * The three existing engines already accept a caller-owned [java.io.OutputStream], so
 * no engine API change was required: this engine simply passes its own bounded buffer
 * as that stream and reads the bytes back.
 *
 * - Quick  -> [QuickCompressionEngine]
 * - Target -> [TargetSizeCompressionEngine]
 * - Manual -> [ManualCompressionEngine]
 *
 * No compression algorithm is duplicated and [Bitmap.compress] is never called here.
 *
 * ## Buffer and memory
 * The encoded JPEG is captured in a bounded [ByteArrayOutputStream]; if it would
 * exceed [MAX_PREVIEW_JPEG_BYTES] the write aborts and the result is
 * [CompressionPreviewFailureReason.PREVIEW_SIZE_LIMIT_EXCEEDED]. The JPEG is never
 * truncated.
 *
 * The preview bitmap is decoded with a bounds pass plus a memory-bounded sample size
 * ([MAX_PREVIEW_DECODE_BYTES]), reusing [ImageDecoder.calculateInSampleSize]. Only the
 * preview representation is downsampled; the encoded JPEG itself is unchanged.
 *
 * ## Ownership
 * The source bitmap is read-only: never recycled, mutated or retained. On success the
 * returned preview bitmap belongs to the caller. Encoded bytes stay internal to the
 * call (only the byte count is reported), so no extra copy is exposed.
 *
 * ## EXIF / orientation / alpha
 * No EXIF processing and no orientation changes: the preview reflects exactly the
 * JPEG produced by the current pipeline. Alpha behavior is whatever the underlying
 * JPEG encoder produced. Both remain delegated to the compression engines.
 */
object CompressionPreviewEngine {

    private const val BYTES_PER_PIXEL = 4

    /**
     * Maximum encoded JPEG size captured for preview: 32 MiB. Chosen to match the
     * largest candidate buffer the Task 07 search will ever hold and to keep the
     * simultaneous footprint (source bitmap + JPEG bytes + decoded preview) within a
     * lightweight, memory-constrained envelope.
     */
    const val MAX_PREVIEW_JPEG_BYTES = 32L * 1024 * 1024

    /**
     * Maximum memory for the decoded preview bitmap: 8 MiB (~2 M ARGB_8888 pixels,
     * e.g. 1600 x 1200). This is ample for an on-screen preview while bounding the
     * transient decode allocation.
     */
    const val MAX_PREVIEW_DECODE_BYTES = 8L * 1024 * 1024

    /**
     * Compresses [request] and returns a preview decoded from the actual JPEG bytes.
     */
    fun preview(request: CompressionPreviewRequest): CompressionPreviewResult {
        val bitmap = request.bitmap
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            return CompressionPreviewResult.Failure(CompressionPreviewFailureReason.INVALID_BITMAP)
        }

        val buffer = PreviewBoundedByteArrayOutputStream(MAX_PREVIEW_JPEG_BYTES.toInt())

        val outcome = try {
            when (request) {
                is CompressionPreviewRequest.Quick -> encodeQuick(request, buffer)
                is CompressionPreviewRequest.Target -> encodeTarget(request, buffer)
                is CompressionPreviewRequest.Manual -> encodeManual(request, buffer)
            }
        } catch (e: OutOfMemoryError) {
            return CompressionPreviewResult.Failure(CompressionPreviewFailureReason.OUT_OF_MEMORY)
        }

        // The buffer limit is checked first: an engine failure may simply be this
        // engine's own bounded buffer refusing to grow.
        if (buffer.exceeded) {
            return CompressionPreviewResult.Failure(
                CompressionPreviewFailureReason.PREVIEW_SIZE_LIMIT_EXCEEDED,
            )
        }
        if (outcome is ModeOutcome.Failed) {
            return CompressionPreviewResult.Failure(outcome.reason)
        }

        val size = buffer.size()
        if (size <= 0) {
            return CompressionPreviewResult.Failure(CompressionPreviewFailureReason.PREVIEW_DECODE_FAILED)
        }
        val bytes = buffer.rawBytes()

        // Bounds pass: read the encoded JPEG's real dimensions without allocating pixels.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, size, bounds)
        val encodedWidth = bounds.outWidth
        val encodedHeight = bounds.outHeight
        if (encodedWidth <= 0 || encodedHeight <= 0) {
            return CompressionPreviewResult.Failure(CompressionPreviewFailureReason.PREVIEW_DECODE_FAILED)
        }

        val sampleSize = ImageDecoder.calculateInSampleSize(
            encodedWidth,
            encodedHeight,
            BYTES_PER_PIXEL,
            MAX_PREVIEW_DECODE_BYTES.toInt(),
        )
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }

        val previewBitmap = try {
            BitmapFactory.decodeByteArray(bytes, 0, size, options)
        } catch (e: OutOfMemoryError) {
            return CompressionPreviewResult.Failure(CompressionPreviewFailureReason.OUT_OF_MEMORY)
        }
        if (previewBitmap == null) {
            // Never fall back to the source bitmap: it would misrepresent the JPEG.
            return CompressionPreviewResult.Failure(CompressionPreviewFailureReason.PREVIEW_DECODE_FAILED)
        }

        val quality = (outcome as ModeOutcome.Encoded).quality
        return CompressionPreviewResult.Success(
            bitmap = previewBitmap,
            encodedBytes = size.toLong(),
            width = encodedWidth,
            height = encodedHeight,
            quality = quality,
        )
    }

    private fun encodeQuick(
        request: CompressionPreviewRequest.Quick,
        buffer: PreviewBoundedByteArrayOutputStream,
    ): ModeOutcome = when (
        val result = QuickCompressionEngine.compress(
            request.bitmap,
            request.sourceWidth,
            request.sourceHeight,
            request.mimeType,
            buffer,
        )
    ) {
        is QuickCompressionResult.Success -> ModeOutcome.Encoded(result.quality)
        is QuickCompressionResult.Failure -> ModeOutcome.Failed(mapQuick(result.reason))
    }

    private fun encodeTarget(
        request: CompressionPreviewRequest.Target,
        buffer: PreviewBoundedByteArrayOutputStream,
    ): ModeOutcome = when (
        val result = TargetSizeCompressionEngine.compress(request.bitmap, request.targetBytes, buffer)
    ) {
        is TargetSizeCompressionResult.Success -> ModeOutcome.Encoded(result.quality)
        is TargetSizeCompressionResult.Failure -> ModeOutcome.Failed(mapTarget(result.reason))
    }

    private fun encodeManual(
        request: CompressionPreviewRequest.Manual,
        buffer: PreviewBoundedByteArrayOutputStream,
    ): ModeOutcome = when (
        val result = ManualCompressionEngine.compress(
            request.bitmap,
            request.quality,
            request.outputWidth,
            request.outputHeight,
            buffer,
        )
    ) {
        is ManualCompressionResult.Success -> ModeOutcome.Encoded(result.quality)
        is ManualCompressionResult.Failure -> ModeOutcome.Failed(mapManual(result.reason))
    }

    private fun mapQuick(reason: QuickCompressionFailureReason): CompressionPreviewFailureReason =
        when (reason) {
            QuickCompressionFailureReason.INVALID_DIMENSIONS -> CompressionPreviewFailureReason.INVALID_DIMENSIONS
            QuickCompressionFailureReason.INVALID_BITMAP -> CompressionPreviewFailureReason.INVALID_BITMAP
            QuickCompressionFailureReason.STREAM_FAILURE -> CompressionPreviewFailureReason.STREAM_FAILURE
            QuickCompressionFailureReason.ENCODER_FAILED -> CompressionPreviewFailureReason.ENCODER_FAILURE
            QuickCompressionFailureReason.ZERO_BYTES -> CompressionPreviewFailureReason.ZERO_BYTES
            QuickCompressionFailureReason.COUNTER_OVERFLOW -> CompressionPreviewFailureReason.COUNTER_OVERFLOW
            QuickCompressionFailureReason.OUT_OF_MEMORY -> CompressionPreviewFailureReason.OUT_OF_MEMORY
        }

    private fun mapTarget(reason: TargetSizeCompressionFailureReason): CompressionPreviewFailureReason =
        when (reason) {
            TargetSizeCompressionFailureReason.INVALID_TARGET_SIZE -> CompressionPreviewFailureReason.INVALID_TARGET_SIZE
            TargetSizeCompressionFailureReason.INVALID_BITMAP -> CompressionPreviewFailureReason.INVALID_BITMAP
            TargetSizeCompressionFailureReason.TARGET_UNACHIEVABLE -> CompressionPreviewFailureReason.TARGET_UNACHIEVABLE
            TargetSizeCompressionFailureReason.STREAM_FAILURE -> CompressionPreviewFailureReason.STREAM_FAILURE
            TargetSizeCompressionFailureReason.ENCODER_FAILURE -> CompressionPreviewFailureReason.ENCODER_FAILURE
            TargetSizeCompressionFailureReason.OUT_OF_MEMORY -> CompressionPreviewFailureReason.OUT_OF_MEMORY
            TargetSizeCompressionFailureReason.CANDIDATE_LIMIT_EXCEEDED -> CompressionPreviewFailureReason.DIMENSIONS_TOO_LARGE
            TargetSizeCompressionFailureReason.COUNTER_OVERFLOW -> CompressionPreviewFailureReason.COUNTER_OVERFLOW
        }

    private fun mapManual(reason: ManualCompressionFailureReason): CompressionPreviewFailureReason =
        when (reason) {
            ManualCompressionFailureReason.INVALID_BITMAP -> CompressionPreviewFailureReason.INVALID_BITMAP
            ManualCompressionFailureReason.INVALID_DIMENSIONS -> CompressionPreviewFailureReason.INVALID_DIMENSIONS
            ManualCompressionFailureReason.DIMENSIONS_TOO_LARGE -> CompressionPreviewFailureReason.DIMENSIONS_TOO_LARGE
            ManualCompressionFailureReason.OUT_OF_MEMORY -> CompressionPreviewFailureReason.OUT_OF_MEMORY
            ManualCompressionFailureReason.STREAM_FAILURE -> CompressionPreviewFailureReason.STREAM_FAILURE
            ManualCompressionFailureReason.ENCODER_FAILURE -> CompressionPreviewFailureReason.ENCODER_FAILURE
            ManualCompressionFailureReason.ZERO_BYTES -> CompressionPreviewFailureReason.ZERO_BYTES
            ManualCompressionFailureReason.COUNTER_OVERFLOW -> CompressionPreviewFailureReason.COUNTER_OVERFLOW
        }
}

/** Input to [CompressionPreviewEngine.preview], one variant per compression mode. */
sealed interface CompressionPreviewRequest {

    val bitmap: Bitmap

    data class Quick(
        override val bitmap: Bitmap,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val mimeType: String?,
    ) : CompressionPreviewRequest

    data class Target(
        override val bitmap: Bitmap,
        val targetBytes: Long,
    ) : CompressionPreviewRequest

    data class Manual(
        override val bitmap: Bitmap,
        val quality: Int,
        val outputWidth: Int,
        val outputHeight: Int,
    ) : CompressionPreviewRequest
}

/** Internal per-mode outcome: the effective quality or a mapped failure. */
private sealed interface ModeOutcome {

    class Encoded(val quality: Int) : ModeOutcome

    data class Failed(val reason: CompressionPreviewFailureReason) : ModeOutcome
}

/** Raised when a preview JPEG would exceed the bounded preview buffer. */
private class PreviewSizeLimitExceededException : IOException()

/**
 * Bounded [ByteArrayOutputStream] used to capture the encoded JPEG without files. When
 * the limit would be exceeded it records [exceeded] and aborts, so encoding stops
 * immediately and [rawBytes] is never a truncated JPEG treated as valid.
 *
 * [rawBytes] exposes the backing array directly (read with [size]) to avoid a second
 * byte-array copy when decoding the preview.
 */
private class PreviewBoundedByteArrayOutputStream(private val limit: Int) : ByteArrayOutputStream() {

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

    fun rawBytes(): ByteArray = buf

    private fun ensureWithinLimit(incoming: Int) {
        if (exceeded || count + incoming > limit) {
            exceeded = true
            throw PreviewSizeLimitExceededException()
        }
    }
}

/** Successful preview: a bitmap decoded from the actual JPEG bytes. */
sealed interface CompressionPreviewResult {

    /**
     * [bitmap] belongs to the caller after a successful result. [width]/[height] are
     * the actual encoded JPEG dimensions (which may be larger than [bitmap] if the
     * preview was downsampled). [encodedBytes] is the actual JPEG byte count.
     */
    data class Success(
        val bitmap: Bitmap,
        val encodedBytes: Long,
        val width: Int,
        val height: Int,
        val quality: Int,
    ) : CompressionPreviewResult

    data class Failure(val reason: CompressionPreviewFailureReason) : CompressionPreviewResult
}

/** Small, deterministic preview failure taxonomy. */
enum class CompressionPreviewFailureReason {
    INVALID_BITMAP,
    INVALID_DIMENSIONS,
    INVALID_TARGET_SIZE,
    DIMENSIONS_TOO_LARGE,
    TARGET_UNACHIEVABLE,
    PREVIEW_SIZE_LIMIT_EXCEEDED,
    PREVIEW_DECODE_FAILED,
    OUT_OF_MEMORY,
    STREAM_FAILURE,
    ENCODER_FAILURE,
    ZERO_BYTES,
    COUNTER_OVERFLOW,
}
