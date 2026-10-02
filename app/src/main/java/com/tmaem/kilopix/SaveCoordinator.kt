package com.tmaem.kilopix

import android.content.ContentResolver
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Task 12 save coordinator.
 *
 * Drives the full single-item pipeline, one item at a time, synchronously, on whichever
 * thread the caller invokes it (the Activity runs it on a background thread):
 *
 * ```
 * source Uri
 *   -> ImageDecoder.decode
 *   -> compression engine (Quick / Target / Manual)
 *   -> ExifMetadataHandler.applyMetadata
 *   -> OutputStreamFactory.open
 *   -> persistent destination (SAF tree or MediaStore Downloads)
 * ```
 *
 * It reuses the exact public contracts defined by [BatchCompressionQueue]
 * ([BatchCompressionRequest], [BatchItemResult], [BatchFailureReason], [OutputStreamFactory],
 * cancellation) so the destination abstraction is identical and nothing in the queue is
 * redesigned. Metadata is applied between encoding and the destination stream, which the
 * queue's internal streaming loop cannot do; hence this dedicated per-item loop. No
 * compression logic is duplicated — the existing engines are called unchanged.
 */
class SaveCoordinator(
    private val outputStreamFactory: OutputStreamFactory,
) {

    private companion object {
        /** Bounded encoded-JPEG capture limit for the save path (matches preview bound). */
        const val MAX_ENCODED_JPEG_BYTES = 32L * 1024 * 1024
    }

    fun process(
        resolver: ContentResolver,
        requests: List<BatchCompressionRequest>,
        metadataPolicy: ExifMetadataHandler.MetadataPolicy = ExifMetadataHandler.MetadataPolicy.PRESERVE,
        gpsPolicy: ExifMetadataHandler.ExifGpsPolicy = ExifMetadataHandler.ExifGpsPolicy.PRESERVE,
        cancellation: BatchCancellationSignal? = null,
    ): BatchCompressionResult {
        if (requests.isEmpty()) {
            return BatchCompressionResult(0, 0, 0, 0, cancelled = false, itemResults = emptyList())
        }

        val itemResults = ArrayList<BatchItemResult>(requests.size)
        var successCount = 0
        var failedCount = 0
        var cancelled = false

        for (index in requests.indices) {
            if (cancellation?.isCancelled() == true) {
                cancelled = true
                for (remaining in index until requests.size) {
                    itemResults.add(
                        BatchItemResult.Failure(requests[remaining].sourceUri, BatchFailureReason.CANCELLATION),
                    )
                }
                break
            }

            val request = requests[index]
            val result = processItem(resolver, request, metadataPolicy, gpsPolicy)
            itemResults.add(result)
            when (result) {
                is BatchItemResult.Success -> successCount++
                is BatchItemResult.Failure -> failedCount++
            }
        }

        return BatchCompressionResult(
            totalCount = requests.size,
            completedCount = itemResults.size,
            successCount = successCount,
            failedCount = failedCount,
            cancelled = cancelled,
            itemResults = itemResults.toList(),
        )
    }

    private fun processItem(
        resolver: ContentResolver,
        request: BatchCompressionRequest,
        metadataPolicy: ExifMetadataHandler.MetadataPolicy,
        gpsPolicy: ExifMetadataHandler.ExifGpsPolicy,
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
            is DecodeResult.Failure -> return BatchItemResult.Failure(uri, mapDecode(decoded.reason))
            is DecodeResult.Success -> decoded
        }
        val bitmap = source.bitmap

        var output: OutputStream? = null
        return try {
            val encoded = encode(request, source)

            val finalBytes = applyMetadata(
                encoded.bytes,
                resolver,
                uri,
                metadataPolicy,
                gpsPolicy,
            )

            output = try {
                outputStreamFactory.open(uri)
            } catch (e: Exception) {
                return BatchItemResult.Failure(uri, BatchFailureReason.OUTPUT_STREAM_FAILURE)
            }

            try {
                output!!.write(finalBytes)
                output!!.flush()
            } catch (e: Exception) {
                return BatchItemResult.Failure(uri, BatchFailureReason.OUTPUT_STREAM_FAILURE)
            }

            // Success is reported only after the destination stream has been closed
            // successfully, so a partial/failed write is never misrepresented as saved.
            try {
                output!!.close()
            } catch (e: Exception) {
                return BatchItemResult.Failure(uri, BatchFailureReason.OUTPUT_STREAM_FAILURE)
            }
            output = null

            BatchItemResult.Success(
                sourceUri = uri,
                bytesWritten = finalBytes.size.toLong(),
                width = bitmap.width,
                height = bitmap.height,
                quality = encoded.quality,
            )
        } catch (e: MetadataSaveException) {
            BatchItemResult.Failure(uri, BatchFailureReason.UNEXPECTED_FAILURE)
        } catch (e: OutOfMemoryError) {
            BatchItemResult.Failure(uri, BatchFailureReason.MEMORY_FAILURE)
        } catch (e: Exception) {
            BatchItemResult.Failure(uri, BatchFailureReason.UNEXPECTED_FAILURE)
        } finally {
            if (output != null) {
                try {
                    output!!.close()
                } catch (_: Exception) {
                    // Best-effort close; the item result already reflects the outcome.
                }
            }
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    private class Encoded(val bytes: ByteArray, val quality: Int)

    /** Runs the selected engine into a bounded in-memory buffer and returns the bytes + quality. */
    private fun encode(request: BatchCompressionRequest, source: DecodeResult.Success): Encoded {
        val bitmap = source.bitmap
        val buffer = SaveBoundedByteArrayOutputStream(MAX_ENCODED_JPEG_BYTES.toInt())
        val quality: Int = when (request) {
            is BatchCompressionRequest.Quick -> {
                val r = QuickCompressionEngine.compress(
                    bitmap,
                    source.sourceWidth,
                    source.sourceHeight,
                    source.mimeType,
                    buffer,
                )
                when (r) {
                    is QuickCompressionResult.Success -> r.quality
                    is QuickCompressionResult.Failure -> throw SaveInternalException()
                }
            }
            is BatchCompressionRequest.Target -> {
                val r = TargetSizeCompressionEngine.compress(bitmap, request.targetBytes, buffer)
                when (r) {
                    is TargetSizeCompressionResult.Success -> r.quality
                    is TargetSizeCompressionResult.Failure -> throw SaveInternalException()
                }
            }
            is BatchCompressionRequest.Manual -> {
                val r = ManualCompressionEngine.compress(
                    bitmap,
                    request.quality,
                    request.outputWidth,
                    request.outputHeight,
                    buffer,
                )
                when (r) {
                    is ManualCompressionResult.Success -> r.quality
                    is ManualCompressionResult.Failure -> throw SaveInternalException()
                }
            }
        }
        if (buffer.exceeded || buffer.size() <= 0) {
            throw SaveInternalException()
        }
        val bytes = ByteArray(buffer.size())
        System.arraycopy(buffer.rawBytes(), 0, bytes, 0, buffer.size())
        return Encoded(bytes, quality)
    }

    /** Applies Task 11 metadata; a controlled metadata failure aborts the item. */
    private fun applyMetadata(
        encoded: ByteArray,
        resolver: ContentResolver,
        sourceUri: Uri,
        metadataPolicy: ExifMetadataHandler.MetadataPolicy,
        gpsPolicy: ExifMetadataHandler.ExifGpsPolicy,
    ): ByteArray {
        val result = ExifMetadataHandler.applyMetadata(resolver, sourceUri, encoded, metadataPolicy, gpsPolicy)
        return when (result) {
            is ExifMetadataHandler.ExifMetadataResult.Success -> result.jpegBytes
            is ExifMetadataHandler.ExifMetadataResult.MetadataUnavailable -> result.jpegBytes
            is ExifMetadataHandler.ExifMetadataResult.Failure -> throw MetadataSaveException()
        }
    }

    private fun mapDecode(reason: DecodeFailureReason): BatchFailureReason =
        when (reason) {
            DecodeFailureReason.INACCESSIBLE -> BatchFailureReason.DECODE_FAILURE
            DecodeFailureReason.INVALID_DIMENSIONS -> BatchFailureReason.DECODE_FAILURE
            DecodeFailureReason.DECODE_FAILED -> BatchFailureReason.DECODE_FAILURE
            DecodeFailureReason.OUT_OF_MEMORY -> BatchFailureReason.MEMORY_FAILURE
        }
}

/** Internal marker for a non-successful encode/metadata step; mapped to a failure reason. */
private class SaveInternalException : Exception()

/** Internal marker: metadata application failed and the item must not be reported as saved. */
private class MetadataSaveException : Exception()

/**
 * Bounded [ByteArrayOutputStream] used to capture the encoded JPEG in memory for the
 * metadata step. When the limit would be exceeded it records [exceeded] and aborts, so a
 * truncated JPEG is never treated as valid output.
 */
private class SaveBoundedByteArrayOutputStream(private val limit: Int) : ByteArrayOutputStream() {

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

    /** Direct access to the backing array (read [size] first) to avoid a spurious copy. */
    fun rawBytes(): ByteArray = buf

    private fun ensureWithinLimit(incoming: Int) {
        if (exceeded || count + incoming > limit) {
            exceeded = true
            throw SaveInternalException()
        }
    }
}
