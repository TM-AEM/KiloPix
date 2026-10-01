package com.tmaem.kilopix

import android.content.ContentResolver
import android.net.Uri
import java.io.OutputStream

/**
 * Lightweight, memory-safe batch compression queue (Task 10).
 *
 * The queue processes an ordered list of already-imported image [Uri]s **one item at
 * a time** using the existing compression engines. It contains no compression
 * algorithm of its own: decoding is delegated to [ImageDecoder] and encoding is
 * delegated to [QuickCompressionEngine], [TargetSizeCompressionEngine] or
 * [ManualCompressionEngine] depending on the request mode.
 *
 * ## What this class deliberately does NOT do
 * This layer owns no output policy. It never creates files, never chooses a folder,
 * never uses SAF destinations, never overwrites or renames originals and never shares
 * anything. A caller-supplied [OutputStreamFactory] decides where each item's bytes
 * go. It also performs no UI work, no persistence, no networking, no EXIF handling and
 * no replacement/naming logic; those belong to later tasks.
 *
 * ## Threading
 * [process] is fully synchronous. The caller must invoke it off the main thread if it
 * is called from a UI context. No thread, executor, coroutine or third-party
 * concurrency primitive is created here; sequential execution is the whole point,
 * because it guarantees that only one full-resolution bitmap is alive at a time.
 *
 * ## Cancellation
 * Cancellation is cooperative and is observed **between items**. A cancellation
 * request that arrives while an item is being processed lets that item finish safely
 * (its stream is closed and its bitmap released as usual) and prevents the next item
 * from starting. [Thread.stop] and forced thread termination are never used.
 *
 * ## Output-stream ownership contract
 * For each item the queue calls [OutputStreamFactory.open] and then **owns** the
 * returned stream for the duration of that item only. The stream is closed by the
 * queue on every exit path: success, engine failure, unexpected exception and
 * cancellation. A stream is never retained after its item completes, and streams that
 * belong to earlier items are never closed again. The factory must therefore return a
 * fresh, unshared stream for every call.
 *
 * ## Memory
 * The queue never retains bitmaps, encoded byte arrays, input streams, output streams,
 * a [ContentResolver] or any Android UI object in a field. The decoded bitmap for an
 * item is owned by the queue and recycled in a `finally` block once the item is done,
 * before the next item is decoded. Engines remain responsible for any temporary
 * bitmaps they create. [System.gc] is never called.
 */
class BatchCompressionQueue(
    private val outputStreamFactory: OutputStreamFactory,
) {

    /**
     * Processes [requests] sequentially and returns the ordered batch outcome.
     *
     * This method is synchronous and reusable: the same instance may be invoked any
     * number of times, and no state is carried over between invocations.
     *
     * @param resolver used only for decoding; it is not retained after the call.
     * @param requests the exact ordered request list to process. It is not modified
     *        and duplicates are intentionally not removed (Task 03 owns selection
     *        deduplication).
     * @param listener optional progress sink; no Android UI type is referenced here.
     * @param cancellation optional cooperative cancellation signal, checked before
     *        each item.
     */
    fun process(
        resolver: ContentResolver,
        requests: List<BatchCompressionRequest>,
        listener: BatchProgressListener? = null,
        cancellation: BatchCancellationSignal? = null,
    ): BatchCompressionResult {
        if (requests.isEmpty()) {
            return BatchCompressionResult(
                totalCount = 0,
                completedCount = 0,
                successCount = 0,
                failedCount = 0,
                cancelled = false,
                itemResults = emptyList(),
            )
        }

        val total = requests.size
        val itemResults = ArrayList<BatchItemResult>(total)
        var successCount = 0
        var failedCount = 0
        var cancelled = false

        for (index in 0 until total) {
            // Cooperative cancellation: checked only between items so the current
            // synchronous operation is never interrupted unsafely. The remaining
            // items are recorded as CANCELLATION so the result stays ordered and
            // covers every supplied URI.
            if (cancellation?.isCancelled() == true) {
                cancelled = true
                for (remaining in index until total) {
                    itemResults.add(
                        BatchItemResult.Failure(
                            sourceUri = requests[remaining].sourceUri,
                            reason = BatchFailureReason.CANCELLATION,
                        ),
                    )
                }
                break
            }

            val request = requests[index]
            listener?.onItemStarted(
                currentIndex = index,
                totalCount = total,
                sourceUri = request.sourceUri,
            )

            val result = processItem(resolver, request)
            itemResults.add(result)
            when (result) {
                is BatchItemResult.Success -> successCount += 1
                is BatchItemResult.Failure -> failedCount += 1
            }

            listener?.onItemCompleted(
                currentIndex = index,
                totalCount = total,
                completedCount = itemResults.size,
                result = result,
            )
        }

        return BatchCompressionResult(
            totalCount = total,
            completedCount = itemResults.size,
            successCount = successCount,
            failedCount = failedCount,
            cancelled = cancelled,
            itemResults = itemResults.toList(),
        )
    }

    /**
     * Decodes, compresses and releases exactly one item. Every failure path is mapped
     * to a controlled [BatchItemResult.Failure] so one bad image can never abort the
     * batch.
     */
    private fun processItem(
        resolver: ContentResolver,
        request: BatchCompressionRequest,
    ): BatchItemResult {
        val uri = request.sourceUri

        val decoded = try {
            ImageDecoder.decode(resolver, uri)
        } catch (e: OutOfMemoryError) {
            return BatchItemResult.Failure(uri, BatchFailureReason.MEMORY_FAILURE)
        } catch (e: Exception) {
            return BatchItemResult.Failure(uri, BatchFailureReason.DECODE_FAILURE)
        }

        val source = when (decoded) {
            is DecodeResult.Failure ->
                return BatchItemResult.Failure(uri, mapDecodeFailure(decoded.reason))
            is DecodeResult.Success -> decoded
        }

        val bitmap = source.bitmap
        var output: OutputStream? = null
        try {
            output = try {
                outputStreamFactory.open(uri)
            } catch (e: OutOfMemoryError) {
                return BatchItemResult.Failure(uri, BatchFailureReason.MEMORY_FAILURE)
            } catch (e: Exception) {
                return BatchItemResult.Failure(uri, BatchFailureReason.OUTPUT_STREAM_FAILURE)
            }

            return compress(request, source, output)
        } catch (e: OutOfMemoryError) {
            return BatchItemResult.Failure(uri, BatchFailureReason.MEMORY_FAILURE)
        } catch (e: Exception) {
            return BatchItemResult.Failure(uri, BatchFailureReason.UNEXPECTED_FAILURE)
        } finally {
            // The queue owns the current item's stream: close it on every path.
            if (output != null) {
                try {
                    output.close()
                } catch (e: Exception) {
                    // Best-effort close; the item result already reflects the encoding.
                }
            }
            // Release the decoded bitmap the queue owns before moving on.
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    /** Routes a single decoded item to the matching existing engine. */
    private fun compress(
        request: BatchCompressionRequest,
        source: DecodeResult.Success,
        output: OutputStream,
    ): BatchItemResult {
        val uri = request.sourceUri
        val bitmap = source.bitmap

        return when (request) {
            is BatchCompressionRequest.Quick -> {
                val result = QuickCompressionEngine.compress(
                    bitmap = bitmap,
                    sourceWidth = source.sourceWidth,
                    sourceHeight = source.sourceHeight,
                    mimeType = source.mimeType,
                    outputStream = output,
                )
                when (result) {
                    is QuickCompressionResult.Success -> BatchItemResult.Success(
                        sourceUri = uri,
                        bytesWritten = result.bytesWritten,
                        width = bitmap.width,
                        height = bitmap.height,
                        quality = result.quality,
                    )
                    is QuickCompressionResult.Failure ->
                        BatchItemResult.Failure(uri, mapQuickFailure(result.reason))
                }
            }

            is BatchCompressionRequest.Target -> {
                val result = TargetSizeCompressionEngine.compress(
                    bitmap = bitmap,
                    targetBytes = request.targetBytes,
                    outputStream = output,
                )
                when (result) {
                    is TargetSizeCompressionResult.Success -> BatchItemResult.Success(
                        sourceUri = uri,
                        bytesWritten = result.bytesWritten,
                        width = bitmap.width,
                        height = bitmap.height,
                        quality = result.quality,
                    )
                    is TargetSizeCompressionResult.Failure ->
                        BatchItemResult.Failure(uri, mapTargetFailure(result.reason))
                }
            }

            is BatchCompressionRequest.Manual -> {
                val result = ManualCompressionEngine.compress(
                    bitmap = bitmap,
                    quality = request.quality,
                    outputWidth = request.outputWidth,
                    outputHeight = request.outputHeight,
                    outputStream = output,
                )
                when (result) {
                    is ManualCompressionResult.Success -> BatchItemResult.Success(
                        sourceUri = uri,
                        bytesWritten = result.bytesWritten,
                        width = result.width,
                        height = result.height,
                        quality = result.quality,
                    )
                    is ManualCompressionResult.Failure ->
                        BatchItemResult.Failure(uri, mapManualFailure(result.reason))
                }
            }
        }
    }

    private fun mapDecodeFailure(reason: DecodeFailureReason): BatchFailureReason =
        when (reason) {
            DecodeFailureReason.INACCESSIBLE -> BatchFailureReason.DECODE_FAILURE
            DecodeFailureReason.INVALID_DIMENSIONS -> BatchFailureReason.DECODE_FAILURE
            DecodeFailureReason.DECODE_FAILED -> BatchFailureReason.DECODE_FAILURE
            DecodeFailureReason.OUT_OF_MEMORY -> BatchFailureReason.MEMORY_FAILURE
        }

    private fun mapQuickFailure(reason: QuickCompressionFailureReason): BatchFailureReason =
        when (reason) {
            QuickCompressionFailureReason.INVALID_DIMENSIONS -> BatchFailureReason.INVALID_REQUEST
            QuickCompressionFailureReason.INVALID_BITMAP -> BatchFailureReason.COMPRESSION_FAILURE
            QuickCompressionFailureReason.STREAM_FAILURE -> BatchFailureReason.OUTPUT_STREAM_FAILURE
            QuickCompressionFailureReason.ENCODER_FAILED -> BatchFailureReason.COMPRESSION_FAILURE
            QuickCompressionFailureReason.ZERO_BYTES -> BatchFailureReason.COMPRESSION_FAILURE
            QuickCompressionFailureReason.COUNTER_OVERFLOW -> BatchFailureReason.COMPRESSION_FAILURE
            QuickCompressionFailureReason.OUT_OF_MEMORY -> BatchFailureReason.MEMORY_FAILURE
        }

    private fun mapTargetFailure(reason: TargetSizeCompressionFailureReason): BatchFailureReason =
        when (reason) {
            TargetSizeCompressionFailureReason.INVALID_TARGET_SIZE -> BatchFailureReason.INVALID_REQUEST
            TargetSizeCompressionFailureReason.INVALID_BITMAP -> BatchFailureReason.COMPRESSION_FAILURE
            TargetSizeCompressionFailureReason.TARGET_UNACHIEVABLE -> BatchFailureReason.COMPRESSION_FAILURE
            TargetSizeCompressionFailureReason.STREAM_FAILURE -> BatchFailureReason.OUTPUT_STREAM_FAILURE
            TargetSizeCompressionFailureReason.ENCODER_FAILURE -> BatchFailureReason.COMPRESSION_FAILURE
            TargetSizeCompressionFailureReason.OUT_OF_MEMORY -> BatchFailureReason.MEMORY_FAILURE
            TargetSizeCompressionFailureReason.CANDIDATE_LIMIT_EXCEEDED -> BatchFailureReason.MEMORY_FAILURE
            TargetSizeCompressionFailureReason.COUNTER_OVERFLOW -> BatchFailureReason.COMPRESSION_FAILURE
        }

    private fun mapManualFailure(reason: ManualCompressionFailureReason): BatchFailureReason =
        when (reason) {
            ManualCompressionFailureReason.INVALID_BITMAP -> BatchFailureReason.COMPRESSION_FAILURE
            ManualCompressionFailureReason.INVALID_DIMENSIONS -> BatchFailureReason.INVALID_REQUEST
            ManualCompressionFailureReason.DIMENSIONS_TOO_LARGE -> BatchFailureReason.INVALID_REQUEST
            ManualCompressionFailureReason.OUT_OF_MEMORY -> BatchFailureReason.MEMORY_FAILURE
            ManualCompressionFailureReason.STREAM_FAILURE -> BatchFailureReason.OUTPUT_STREAM_FAILURE
            ManualCompressionFailureReason.ENCODER_FAILURE -> BatchFailureReason.COMPRESSION_FAILURE
            ManualCompressionFailureReason.ZERO_BYTES -> BatchFailureReason.COMPRESSION_FAILURE
            ManualCompressionFailureReason.COUNTER_OVERFLOW -> BatchFailureReason.COMPRESSION_FAILURE
        }
}

/**
 * Caller-supplied sink for one item's encoded bytes.
 *
 * `open` is invoked once per item and must return a fresh stream that the queue may
 * close. Returning an [OutputStream] that is also used elsewhere violates the queue's
 * ownership contract.
 */
fun interface OutputStreamFactory {

    /** Opens the destination stream for [sourceUri]. May throw to signal failure. */
    fun open(sourceUri: Uri): OutputStream
}

/** Cooperative cancellation signal polled by the queue between items. */
fun interface BatchCancellationSignal {

    /** Must return `true` once cancellation has been requested. */
    fun isCancelled(): Boolean
}

/** Minimal progress sink. No Android UI type is referenced. */
interface BatchProgressListener {

    /** Called immediately before an item starts. [currentIndex] is zero-based. */
    fun onItemStarted(currentIndex: Int, totalCount: Int, sourceUri: Uri)

    /** Called after an item finishes, with its per-item result. */
    fun onItemCompleted(
        currentIndex: Int,
        totalCount: Int,
        completedCount: Int,
        result: BatchItemResult,
    )
}

/**
 * Immutable request for one batch item. [sourceUri] is the only field shared by all
 * modes; mode-specific parameters live on the concrete variant.
 */
sealed interface BatchCompressionRequest {

    val sourceUri: Uri

    /** Adaptive Quick compression; dimensions come from the decode result. */
    data class Quick(override val sourceUri: Uri) : BatchCompressionRequest

    /** Target-size compression with an explicit positive byte budget. */
    data class Target(
        override val sourceUri: Uri,
        val targetBytes: Long,
    ) : BatchCompressionRequest

    /** Manual compression with an explicit quality and explicit output size. */
    data class Manual(
        override val sourceUri: Uri,
        val quality: Int,
        val outputWidth: Int,
        val outputHeight: Int,
    ) : BatchCompressionRequest
}

/**
 * Immutable per-item outcome.
 *
 * [Success.width] and [Success.height] are the dimensions of the encoded output
 * (the decoded bitmap for Quick/Target, the requested size for Manual).
 */
sealed interface BatchItemResult {

    val sourceUri: Uri

    data class Success(
        override val sourceUri: Uri,
        val bytesWritten: Long,
        val width: Int,
        val height: Int,
        val quality: Int,
    ) : BatchItemResult

    data class Failure(
        override val sourceUri: Uri,
        val reason: BatchFailureReason,
    ) : BatchItemResult
}

/**
 * Small, controlled failure taxonomy. Internal engine exceptions are never exposed.
 */
enum class BatchFailureReason {
    DECODE_FAILURE,
    INVALID_REQUEST,
    COMPRESSION_FAILURE,
    OUTPUT_STREAM_FAILURE,
    CANCELLATION,
    MEMORY_FAILURE,
    UNEXPECTED_FAILURE,
}

/**
 * Immutable batch outcome.
 *
 * [itemResults] always contains exactly one entry per input request, in original
 * order (`itemResults.size == totalCount`), so [completedCount] equals
 * `totalCount` and `successCount + failedCount == totalCount`. Items skipped because
 * of cancellation are recorded with [BatchFailureReason.CANCELLATION] and counted
 * toward [failedCount]; [cancelled] is `true` when the run was stopped early.
 */
data class BatchCompressionResult(
    val totalCount: Int,
    val completedCount: Int,
    val successCount: Int,
    val failedCount: Int,
    val cancelled: Boolean,
    val itemResults: List<BatchItemResult>,
)
