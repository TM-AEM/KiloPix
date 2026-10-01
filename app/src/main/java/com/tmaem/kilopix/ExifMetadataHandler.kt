package com.tmaem.kilopix

import android.content.ContentResolver
import android.media.ExifInterface
import android.net.Uri
import java.io.IOException
import java.io.InputStream

/**
 * Lightweight, dependency-free EXIF metadata handler (Task 11).
 *
 * This component operates **only on already-encoded JPEG bytes** and never
 * recompresses, decodes, rotates, resizes or re-encodes pixels. It modifies the JPEG
 * container by splicing/filtering the EXIF APP1 segment:
 *
 * - [MetadataPolicy.PRESERVE] copies the source image's EXIF APP1 segment
 *   byte-for-byte into the encoded JPEG output (removing any EXIF APP1 already
 *   present in the output first), applying a length-preserving GPS scrub only when
 *   [ExifGpsPolicy.REMOVE] is requested.
 * - [MetadataPolicy.REMOVE] removes all EXIF APP1 segments from the encoded JPEG.
 *
 * ## Platform usage
 * [android.media.ExifInterface] is used **only for reading/reporting** the source
 * Orientation value. There is no AndroidX ExifInterface and no third-party or native
 * EXIF library. No filesystem path is required: the source is always opened through
 * [ContentResolver.openInputStream].
 *
 * ## No recompression
 * This class contains **zero** direct `Bitmap.compress()` calls. The single project
 * encode site remains `JpegCompressionEngine`. The entropy-coded image data after the
 * SOS marker is preserved byte-for-byte and is never parsed as JPEG segments.
 *
 * ## Result contract
 * [ExifMetadataResult.Success] means metadata was applied. [ExifMetadataResult.MetadataUnavailable]
 * means the encoded JPEG is returned unchanged because there was nothing to preserve
 * (or the source format did not support raw EXIF preservation); metadata was **not**
 * claimed to be preserved. [ExifMetadataResult.Failure] is a controlled failure that
 * never silently falls back to a success state.
 *
 * ## Ownership / memory
 * The returned `ByteArray` belongs to the caller. A [MetadataPolicy.PRESERVE] source is
 * inspected via a streaming pass so the full original JPEG is not loaded into a second
 * in-memory array. Streams are closed with `use {}`. The extracted EXIF APP1 is bounded
 * by [MAX_EXIF_APP1_BYTES]; larger EXIF is never truncated and fails safely. No
 * [System.gc] call and no global buffers are used.
 */
object ExifMetadataHandler {

    /** Whether to preserve or remove EXIF metadata on the encoded JPEG. */
    enum class MetadataPolicy {

        /** Copy supported source EXIF metadata into the encoded JPEG. */
        PRESERVE,

        /** Remove EXIF metadata from the encoded JPEG. */
        REMOVE,
    }

    /** Whether GPS metadata is preserved or scrubbed when EXIF is preserved. */
    enum class ExifGpsPolicy {

        /** Keep GPS metadata when preserving EXIF. */
        PRESERVE,

        /** Scrub GPS metadata so it can never remain preserved accidentally. */
        REMOVE,
    }

    /**
     * Outcome of a metadata operation. Never falls back to a misleading success on
     * failure.
     */
    sealed interface ExifMetadataResult {

        /**
         * Metadata was applied to [jpegBytes] (or, for [MetadataPolicy.REMOVE],
         * EXIF was removed from it). [orientation] is the source Orientation value
         * (1..8) read via [ExifInterface] when it was available, otherwise null.
         */
        data class Success(
            val jpegBytes: ByteArray,
            val orientation: Int?,
        ) : ExifMetadataResult

        /**
         * The encoded [jpegBytes] is returned safely unchanged because there was no
         * source EXIF to preserve. Metadata preservation is **not** claimed.
         */
        data class MetadataUnavailable(
            val jpegBytes: ByteArray,
            val reason: ExifMetadataFailureReason,
        ) : ExifMetadataResult

        /** A controlled failure. The encoded JPEG is not returned as a success. */
        data class Failure(val reason: ExifMetadataFailureReason) : ExifMetadataResult
    }

    /** Controlled, explicit failure taxonomy. Implementation exceptions are mapped. */
    enum class ExifMetadataFailureReason {
        INVALID_INPUT,
        SOURCE_INACCESSIBLE,
        UNSUPPORTED_FORMAT,
        EXIF_UNAVAILABLE,
        INVALID_JPEG,
        MALFORMED_EXIF,
        GPS_SCRUB_FAILED,
        METADATA_WRITE_FAILED,
        OUT_OF_MEMORY,
        UNEXPECTED,
    }

    /** JPEG marker bytes (unsigned). */
    private const val MARKER = 0xFF
    private const val SOI_MARKER = 0xD8
    private const val EOI_MARKER = 0xD9
    private const val SOS_MARKER = 0xDA
    private const val APP1_MARKER = 0xE1
    private const val TEM_MARKER = 0x01

    /** Six-byte EXIF APP1 payload signature: `Exif\0\0`. */
    private val EXIF_SIGNATURE = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0x00, 0x00)

    /** GPSInfoIFD pointer tag located in IFD0. */
    private const val GPS_INFO_TAG = 0x8825

    /** TIFF magic value (ASCII "42" encoded per byte order). */
    private const val TIFF_MAGIC = 42

    /**
     * Bounded upper size for a preserved EXIF APP1 segment (~1 MiB). EXIF APP1 segments
     * larger than this are never truncated: the operation fails safely instead of
     * claiming successful preservation.
     */
    private const val MAX_EXIF_APP1_BYTES = 1 * 1024 * 1024

    private const val EXIF_HEADER_LENGTH = 6
    private const val TIFF_HEADER_LENGTH = 8
    private const val IFD_ENTRY_BYTES = 12

    /** ExifIFDPointer tag located in IFD0. */
    private const val EXIF_IFD_POINTER_TAG = 0x8769

    /** JPEGInterchangeFormat (thumbnail data offset) tag located in the thumbnail IFD. */
    private const val JPEG_INTERCHANGE_TAG = 0x0201

    /**
     * Applies [metadataPolicy] (and [gpsPolicy]) to the already-encoded [encodedJpeg].
     *
     * @param resolver used only to open [sourceUri]; it is not retained.
     * @param sourceUri the original image URI (used only for [MetadataPolicy.PRESERVE]).
     * @param encodedJpeg the compressed JPEG bytes produced by an earlier stage.
     */
    fun applyMetadata(
        resolver: ContentResolver,
        sourceUri: Uri,
        encodedJpeg: ByteArray,
        metadataPolicy: MetadataPolicy,
        gpsPolicy: ExifGpsPolicy,
    ): ExifMetadataResult {
        return try {
            applyMetadataInternal(resolver, sourceUri, encodedJpeg, metadataPolicy, gpsPolicy)
        } catch (e: OutOfMemoryError) {
            ExifMetadataResult.Failure(ExifMetadataFailureReason.OUT_OF_MEMORY)
        } catch (e: Exception) {
            ExifMetadataResult.Failure(ExifMetadataFailureReason.UNEXPECTED)
        }
    }

    private fun applyMetadataInternal(
        resolver: ContentResolver,
        sourceUri: Uri,
        encodedJpeg: ByteArray,
        metadataPolicy: MetadataPolicy,
        gpsPolicy: ExifGpsPolicy,
    ): ExifMetadataResult {
        if (encodedJpeg.isEmpty()) {
            return ExifMetadataResult.Failure(ExifMetadataFailureReason.INVALID_INPUT)
        }
        if (encodedJpeg.size < 2 || u(encodedJpeg[0]) != MARKER || u(encodedJpeg[1]) != SOI_MARKER) {
            return ExifMetadataResult.Failure(ExifMetadataFailureReason.INVALID_JPEG)
        }

        val scan = scanJpeg(encodedJpeg)
            ?: return ExifMetadataResult.Failure(ExifMetadataFailureReason.INVALID_JPEG)

        return when (metadataPolicy) {
            MetadataPolicy.REMOVE -> {
                val rebuilt = rebuildWithoutExif(encodedJpeg, scan)
                    ?: return ExifMetadataResult.Failure(ExifMetadataFailureReason.METADATA_WRITE_FAILED)
                ExifMetadataResult.Success(rebuilt, orientation = null)
            }

            MetadataPolicy.PRESERVE -> preserve(resolver, sourceUri, encodedJpeg, scan, gpsPolicy)
        }
    }

    /** PRESERVE path: read source EXIF APP1 and splice it into the output JPEG. */
    private fun preserve(
        resolver: ContentResolver,
        sourceUri: Uri,
        encodedJpeg: ByteArray,
        scan: JpegScan,
        gpsPolicy: ExifGpsPolicy,
    ): ExifMetadataResult {
        val sourceExif = readSourceExifApp1(resolver, sourceUri)
            ?: return ExifMetadataResult.Failure(ExifMetadataFailureReason.SOURCE_INACCESSIBLE)

        val app1 = when (sourceExif) {
            is SourceExif.Found -> sourceExif.segment
            SourceExif.NotJpeg ->
                return ExifMetadataResult.Failure(ExifMetadataFailureReason.UNSUPPORTED_FORMAT)
            SourceExif.NoExif, SourceExif.TooLarge ->
                return ExifMetadataResult.MetadataUnavailable(
                    jpegBytes = encodedJpeg,
                    reason = ExifMetadataFailureReason.EXIF_UNAVAILABLE,
                )
            SourceExif.InvalidJpeg ->
                return ExifMetadataResult.Failure(ExifMetadataFailureReason.INVALID_JPEG)
        }

        val preserved = if (gpsPolicy == ExifGpsPolicy.PRESERVE) {
            app1
        } else {
            when (val removed = removeGps(app1)) {
                GpsRemoveResult.NoGps -> app1
                is GpsRemoveResult.Removed -> removed.rewritten
                GpsRemoveResult.Malformed ->
                    return ExifMetadataResult.Failure(ExifMetadataFailureReason.GPS_SCRUB_FAILED)
            }
        }

        val rebuilt = spliceWithExif(encodedJpeg, scan, preserved)
            ?: return ExifMetadataResult.Failure(ExifMetadataFailureReason.METADATA_WRITE_FAILED)

        val orientation = readSourceOrientation(resolver, sourceUri)
        return ExifMetadataResult.Success(rebuilt, orientation)
    }

    /**
     * Rebuilds [data] dropping every pre-SOS EXIF APP1 segment, preserving all other
     * pre-SOS segments and the SOS/entropy/EOI portion byte-for-byte.
     */
    private fun rebuildWithoutExif(data: ByteArray, scan: JpegScan): ByteArray? {
        var size = 2 // SOI
        for (seg in scan.segments) {
            if (!seg.isExifApp1) size += seg.length
        }
        size += data.size - scan.sosOffset
        return try {
            val out = ByteArray(size)
            var pos = copy(out, data, 0, 2)
            for (seg in scan.segments) {
                if (!seg.isExifApp1) pos = copy(out, data, seg.start, seg.length)
            }
            copy(out, data, scan.sosOffset, data.size - scan.sosOffset)
            out
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    /** Rebuilds [data] inserting the preserved EXIF APP1 right after SOI. */
    private fun spliceWithExif(data: ByteArray, scan: JpegScan, app1: ByteArray): ByteArray? {
        if (app1.size > MAX_EXIF_APP1_BYTES) return null
        var size = 2 + app1.size
        for (seg in scan.segments) {
            if (!seg.isExifApp1) size += seg.length
        }
        size += data.size - scan.sosOffset
        return try {
            val out = ByteArray(size)
            var pos = copy(out, data, 0, 2)
            pos = copy(out, app1, 0, app1.size)
            for (seg in scan.segments) {
                if (!seg.isExifApp1) pos = copy(out, data, seg.start, seg.length)
            }
            copy(out, data, scan.sosOffset, data.size - scan.sosOffset)
            out
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    // ---------------------------------------------------------------------------
    // JPEG segment scanning (byte array: the encoded output JPEG)
    // ---------------------------------------------------------------------------

    private class Segment(val start: Int, val length: Int, val isExifApp1: Boolean)

    private class JpegScan(val segments: List<Segment>, val sosOffset: Int)

    /**
     * Scans the pre-SOS portion of a JPEG byte array. Returns null on malformed JPEG.
     * Parsing stops at SOS so entropy-coded data is never interpreted as segments.
     */
    private fun scanJpeg(data: ByteArray): JpegScan? {
        if (data.size < 2) return null
        if (u(data[0]) != MARKER || u(data[1]) != SOI_MARKER) return null

        val segments = ArrayList<Segment>()
        var i = 2
        while (i < data.size) {
            if (u(data[i]) != MARKER) return null

            var codeIndex = i + 1
            while (codeIndex < data.size && u(data[codeIndex]) == MARKER) codeIndex++
            if (codeIndex >= data.size) return null
            val code = u(data[codeIndex])

            when {
                code == SOS_MARKER -> {
                    // Stop before reading the entropy-coded data that follows.
                    return JpegScan(segments, i)
                }
                code == EOI_MARKER -> {
                    // No entropy data; EOI marks the remainder.
                    return JpegScan(segments, i)
                }
                code == SOI_MARKER || code == TEM_MARKER -> {
                    // Standalone, body-less markers.
                    segments.add(Segment(i, codeIndex + 1 - i, isExifApp1 = false))
                    i = codeIndex + 1
                }
                code in (0xD0..0xD7) -> {
                    // RST markers only appear inside entropy data, never pre-SOS.
                    return null
                }
                else -> {
                    // Length-bearing segment.
                    if (codeIndex + 3 > data.size) return null
                    val length = readU16(data, codeIndex + 1)
                    if (length < 2) return null
                    val payloadLength = length - 2
                    val payloadStart = codeIndex + 3
                    val segmentEnd = payloadStart + payloadLength
                    if (segmentEnd > data.size) return null
                    val isExif = code == APP1_MARKER && isExifSignature(data, payloadStart, payloadLength)
                    segments.add(Segment(i, segmentEnd - i, isExif))
                    i = segmentEnd
                }
            }
        }
        return null
    }

    private fun isExifSignature(data: ByteArray, payloadStart: Int, payloadLength: Int): Boolean {
        if (payloadLength < EXIF_SIGNATURE.size) return false
        for (k in EXIF_SIGNATURE.indices) {
            if (data[payloadStart + k] != EXIF_SIGNATURE[k]) return false
        }
        return true
    }

    // ---------------------------------------------------------------------------
    // Source EXIF APP1 extraction (streaming; bounded, no full-image load)
    // ---------------------------------------------------------------------------

    private sealed interface SourceExif {
        class Found(val segment: ByteArray) : SourceExif
        object NotJpeg : SourceExif
        object NoExif : SourceExif
        object TooLarge : SourceExif
        object InvalidJpeg : SourceExif
    }

    /**
     * Streams [resolver]'s source, validating SOI and extracting the first EXIF APP1
     * segment (bounded by [MAX_EXIF_APP1_BYTES]). Does not load the whole image and
     * stops before SOS.
     */
    private fun readSourceExifApp1(resolver: ContentResolver, sourceUri: Uri): SourceExif? {
        return try {
            resolver.openInputStream(sourceUri)?.use { input -> scanSourceForExif(input) }
                ?: SourceExif.NotJpeg
        } catch (e: SecurityException) {
            null
        } catch (e: IOException) {
            null
        }
    }

    private fun scanSourceForExif(input: InputStream): SourceExif {
        val soi = ByteArray(2)
        if (!readExact(input, soi)) return SourceExif.NotJpeg
        if (u(soi[0]) != MARKER || u(soi[1]) != SOI_MARKER) return SourceExif.NotJpeg

        while (true) {
            val first = readByte(input) ?: return SourceExif.InvalidJpeg
            if (first != MARKER) return SourceExif.InvalidJpeg

            var code = readByte(input) ?: return SourceExif.InvalidJpeg
            while (code == MARKER) {
                code = readByte(input) ?: return SourceExif.InvalidJpeg
            }

            when {
                code == SOS_MARKER || code == EOI_MARKER -> return SourceExif.NoExif
                code == SOI_MARKER || code == TEM_MARKER -> Unit
                code in (0xD0..0xD7) -> return SourceExif.InvalidJpeg
                else -> {
                    val hi = readByte(input) ?: return SourceExif.InvalidJpeg
                    val lo = readByte(input) ?: return SourceExif.InvalidJpeg
                    val length = (hi shl 8) or lo
                    if (length < 2) return SourceExif.InvalidJpeg
                    val payloadLength = length - 2

                    if (code == APP1_MARKER && payloadLength >= EXIF_SIGNATURE.size) {
                        val signature = ByteArray(EXIF_SIGNATURE.size)
                        if (!readExact(input, signature)) return SourceExif.InvalidJpeg
                        if (signature.contentEquals(EXIF_SIGNATURE)) {
                            // Reserved bytes: FF E1 LL LL + first 6 payload bytes read.
                            if (payloadLength + 4 > MAX_EXIF_APP1_BYTES) {
                                // Oversized EXIF: never truncated, fail safely.
                                return SourceExif.TooLarge
                            }
                            val segment = ByteArray(2 + 2 + payloadLength)
                            segment[0] = MARKER.toByte()
                            segment[1] = APP1_MARKER.toByte()
                            segment[2] = hi.toByte()
                            segment[3] = lo.toByte()
                            signature.copyInto(segment, 4)
                            val remaining = payloadLength - EXIF_SIGNATURE.size
                            if (remaining > 0 && !readExact(input, segment, 4 + EXIF_SIGNATURE.size, remaining)) {
                                return SourceExif.InvalidJpeg
                            }
                            return SourceExif.Found(segment)
                        }
                        // Non-EXIF APP1: skip its remaining payload.
                        if (!skipExact(input, payloadLength - EXIF_SIGNATURE.size)) return SourceExif.InvalidJpeg
                    } else {
                        if (!skipExact(input, payloadLength)) return SourceExif.InvalidJpeg
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------------------
    // GPS removal (conservative TIFF/EXIF rewrite, fail-closed)
    // ---------------------------------------------------------------------------
    //
    // The REMOVE contract requires that GPS metadata is NOT retained in the EXIF
    // APP1 segment, not merely that it becomes unreachable through the pointer graph.
    // Therefore this rewriter physically removes:
    //   - the GPSInfoIFD (0x8825) entry from IFD0,
    //   - the complete GPS IFD structure it references,
    //   - every GPS out-of-line value region referenced by that GPS IFD,
    // and then compacts the remaining EXIF bytes while remapping every TIFF-relative
    // offset that belongs to preserved structures (the TIFF header IFD0 offset, every
    // kept IFD's next-IFD pointer, every kept entry's out-of-line data offset, the
    // ExifIFD pointer and the thumbnail JPEG offset). Non-GPS metadata is preserved.
    //
    // The rewriter is deliberately conservative: any layout it cannot parse, bounds
    // check and remap with certainty causes [GpsRemoveResult.Malformed], which the
    // caller maps to `Failure(GPS_SCRUB_FAILED)`. It never silently truncates or
    // guesses, and it never claims GPS was removed from an unparseable structure.

    private sealed interface GpsRemoveResult {
        /** No GPS pointer present in IFD0; the segment already carries no GPS. */
        object NoGps : GpsRemoveResult

        /** GPS entry, GPS IFD and GPS data were physically removed and remapped. */
        data class Removed(val rewritten: ByteArray) : GpsRemoveResult

        /** The structure could not be safely parsed and remapped. */
        object Malformed : GpsRemoveResult
    }

    /** A 4-byte TIFF-relative offset field that must be remapped after compaction. */
    private class OffsetField(val fieldIndex: Int, val targetTiffOffset: Long)

    /** Accumulated parse state for a single EXIF APP1 segment. */
    private class TiffContext(
        val segment: ByteArray,
        val tiffStart: Int,
        val littleEndian: Boolean,
    ) {
        val removed = ArrayList<LongRange>()
        val keptRanges = ArrayList<LongRange>()
        val structuralRanges = ArrayList<LongRange>()
        val gpsEntryRanges = ArrayList<LongRange>()
        val offsetFields = ArrayList<OffsetField>()
        val visitedIfds = HashSet<Int>()
        var ifd0CountField = -1
        var ifd0NewCount = 0
        var gpsFound = false
    }

    /** Byte width of a TIFF field type; -1 for unsupported types. */
    private fun tiffTypeSize(type: Int): Int = when (type) {
        1, 2, 6, 7 -> 1
        3, 8 -> 2
        4, 9, 11 -> 4
        5, 10, 12 -> 8
        else -> -1
    }

    /**
     * Removes GPS metadata from an EXIF APP1 [segment], physically dropping the GPS
     * IFD and its data and remapping preserved offsets, or fails closed.
     */
    private fun removeGps(segment: ByteArray): GpsRemoveResult {
        if (segment.size < 4 + EXIF_HEADER_LENGTH + TIFF_HEADER_LENGTH) return GpsRemoveResult.Malformed

        var p = 4
        for (k in EXIF_SIGNATURE.indices) {
            if (p + k >= segment.size || segment[p + k] != EXIF_SIGNATURE[k]) return GpsRemoveResult.Malformed
        }
        p += EXIF_HEADER_LENGTH

        val byteOrder = readU16(segment, p)
        val littleEndian = when (byteOrder) {
            0x4949 -> true
            0x4D4D -> false
            else -> return GpsRemoveResult.Malformed
        }
        if (p + TIFF_HEADER_LENGTH > segment.size) return GpsRemoveResult.Malformed
        if (readU16(segment, p + 2, littleEndian) != TIFF_MAGIC) return GpsRemoveResult.Malformed

        val ifd0Raw = readU32(segment, p + 4, littleEndian).toLong() and 0xFFFFFFFFL
        if (ifd0Raw > Int.MAX_VALUE.toLong()) return GpsRemoveResult.Malformed
        if (p.toLong() + ifd0Raw + 2 > segment.size.toLong()) return GpsRemoveResult.Malformed

        val ctx = TiffContext(segment, p, littleEndian)
        ctx.offsetFields.add(OffsetField(p + 4, ifd0Raw))
        val ifd0 = ifd0Raw.toInt()
        if (!parseIfd(ctx, ifd0, isIfd0 = true)) return GpsRemoveResult.Malformed

        if (!ctx.gpsFound) return GpsRemoveResult.NoGps

        // Validate regions for ambiguity/overlap before remapping.
        ctx.removed.sortBy { it.first }
        for (i in ctx.removed.indices) {
            val a = ctx.removed[i]
            if (a.first < 0L || a.last >= segment.size.toLong()) return GpsRemoveResult.Malformed
            for (j in i + 1 until ctx.removed.size) {
                val b = ctx.removed[j]
                if (rangesOverlap(a, b)) return GpsRemoveResult.Malformed
            }
            for (k in ctx.keptRanges) {
                if (rangesOverlap(a, k)) return GpsRemoveResult.Malformed
            }
            for (st in ctx.structuralRanges) {
                // The GPS entry removed from *inside* IFD0's entry table is expected to
                // fall within IFD0's structural span; every other removed region (the GPS
                // IFD structure and GPS out-of-line data) must never overlap a preserved
                // IFD table, otherwise compaction would silently corrupt it.
                if (rangesOverlap(a, st) && ctx.gpsEntryRanges.all { g -> !rangesOverlap(g, a) }) {
                    return GpsRemoveResult.Malformed
                }
            }
        }
        for (i in ctx.keptRanges.indices) {
            val a = ctx.keptRanges[i]
            if (a.first < 0L || a.last >= segment.size.toLong()) return GpsRemoveResult.Malformed
            for (j in i + 1 until ctx.keptRanges.size) {
                val b = ctx.keptRanges[j]
                if (rangesOverlap(a, b)) return GpsRemoveResult.Malformed
            }
        }

        val removedRanges = ctx.removed
        val remaining = ctx.offsetFields.filter { field ->
            // A preserved offset field must not reference within a removed region.
            val targetAbs = ctx.tiffStart.toLong() + field.targetTiffOffset
            removedRanges.none { rangeContains(it, targetAbs) }
        }
        if (remaining.size != ctx.offsetFields.size) return GpsRemoveResult.Malformed

        var totalRemoved = 0L
        for (r in removedRanges) totalRemoved += rangeLength(r)
        if (totalRemoved <= 0L) return GpsRemoveResult.Malformed
        // Respect the caller-side 1 MiB bound and ensure a sane non-empty result.
        if (segment.size.toLong() - totalRemoved < 2L) return GpsRemoveResult.Malformed

        val out = ByteArray((segment.size.toLong() - totalRemoved).toInt())
        var w = 0
        for (i in segment.indices) {
            if (removedRanges.any { rangeContains(it, i.toLong()) }) continue
            out[w++] = segment[i]
        }

        // Remap preserved offset fields, writing into their (shifted) new positions.
        for (field in ctx.offsetFields) {
            val newFieldAbs = field.fieldIndex.toLong() - deltaBefore(removedRanges, field.fieldIndex.toLong())
            val oldTargetAbs = ctx.tiffStart.toLong() + field.targetTiffOffset
            val delta = deltaBefore(removedRanges, oldTargetAbs)
            val newTarget = field.targetTiffOffset - delta
            if (newTarget < 0L || newTarget > Int.MAX_VALUE.toLong()) return GpsRemoveResult.Malformed
            if (newFieldAbs < 0L || newFieldAbs + 4 > out.size.toLong()) return GpsRemoveResult.Malformed
            writeU32(out, newFieldAbs.toInt(), newTarget, littleEndian)
        }

        // IFD0 entry count (GPS entry removed => count - 1).
        if (ctx.ifd0CountField < 0) return GpsRemoveResult.Malformed
        val newCountAbs = ctx.ifd0CountField.toLong() - deltaBefore(removedRanges, ctx.ifd0CountField.toLong())
        if (newCountAbs < 0L || newCountAbs + 2 > out.size.toLong()) return GpsRemoveResult.Malformed
        if (ctx.ifd0NewCount < 0 || ctx.ifd0NewCount > 0xFFFF) return GpsRemoveResult.Malformed
        writeU16(out, newCountAbs.toInt(), ctx.ifd0NewCount, littleEndian)

        return GpsRemoveResult.Removed(out)
    }

    /**
     * Parses one kept IFD (IFD0 or a preserved ExifIFD/thumbnail) or the GPS IFD,
     * recording removed/kept value regions and the offset fields that need remapping.
     * Returns false (=> fail closed) if the structure cannot be parsed safely.
     */
    private fun parseIfd(ctx: TiffContext, tiffOffset: Int, isIfd0: Boolean): Boolean {
        val s = ctx.segment
        val t0 = ctx.tiffStart
        val little = ctx.littleEndian

        val countAbsL = t0.toLong() + tiffOffset.toLong()
        if (countAbsL < 0L || countAbsL + 2 > s.size.toLong()) return false
        val countAbs = countAbsL.toInt()

        val entryCount = readU16(s, countAbs, little)
        val entriesL = countAbsL + 2
        val nextFieldL = entriesL + entryCount.toLong() * IFD_ENTRY_BYTES
        if (nextFieldL + 4 > s.size.toLong()) return false
        if (entryCount * IFD_ENTRY_BYTES > s.size) return false

        // The full structural span of a kept IFD (count + entries + next pointer) is
        // protected: a removed GPS data range must never overlap it, otherwise remap
        // would silently corrupt the structure. Fail closed on that ambiguity.
        ctx.structuralRanges.add(LongRange(countAbsL, nextFieldL + 3))

        if (isIfd0) {
            ctx.ifd0CountField = countAbs
            ctx.ifd0NewCount = entryCount
        }

        var gpsIfdOffset: Long? = null
        var gpsEntryRemoved = false

        for (index in 0 until entryCount) {
            val entryL = entriesL + index.toLong() * IFD_ENTRY_BYTES
            val entry = entryL.toInt()
            val tag = readU16(s, entry, little)
            val type = readU16(s, entry + 2, little)
            val countRaw = readU32(s, entry + 4, little).toLong() and 0xFFFFFFFFL
            val typeSize = tiffTypeSize(type)
            if (typeSize < 0) return false
            if (countRaw > Int.MAX_VALUE.toLong()) return false
            val dataSizeL = typeSize.toLong() * countRaw
            if (dataSizeL > Int.MAX_VALUE.toLong()) return false
            val dataSize = dataSizeL.toInt()
            val valueField = entry + 8
            if (valueField + 4 > s.size) return false
            val valueRaw = readU32(s, valueField, little).toLong() and 0xFFFFFFFFL

            if (tag == GPS_INFO_TAG) {
                // GPS pointer (0x8825) is only valid in IFD0; elsewhere it is ambiguous.
                if (!isIfd0) return false
                if (ctx.gpsFound) return false // duplicate GPS pointer => ambiguous
                ctx.gpsFound = true
                ctx.ifd0NewCount = entryCount - 1
                val gpsEntry = LongRange(entryL, entryL + IFD_ENTRY_BYTES - 1)
                ctx.removed.add(gpsEntry)
                ctx.gpsEntryRanges.add(gpsEntry)
                gpsEntryRemoved = true
                if (dataSize != 4) return false // GPS pointer must be a single LONG offset
                gpsIfdOffset = valueRaw
                continue
            }

            if (tag == EXIF_IFD_POINTER_TAG) {
                if (dataSize != 4) return false
                if (valueRaw > Int.MAX_VALUE.toLong()) return false
                if (ctx.visitedIfds.contains(valueRaw.toInt())) return false
                ctx.visitedIfds.add(valueRaw.toInt())
                ctx.offsetFields.add(OffsetField(valueField, valueRaw))
                if (!parseIfd(ctx, valueRaw.toInt(), isIfd0 = false)) return false
                continue
            }

            if (tag == JPEG_INTERCHANGE_TAG) {
                // 0x0201 (thumbnail JPEG data offset) lives in the thumbnail IFD (or IFD0).
                if (dataSize != 4) return false
                ctx.offsetFields.add(OffsetField(valueField, valueRaw))
                continue
            }

            if (dataSize > 4) {
                val targetL = t0.toLong() + valueRaw
                if (targetL < 0L || targetL + dataSize.toLong() > s.size.toLong()) return false
                ctx.offsetFields.add(OffsetField(valueField, valueRaw))
                ctx.keptRanges.add(LongRange(targetL, targetL + dataSize.toLong() - 1))
            }
        }

        // IFD0 may point to a thumbnail IFD via its next-IFD pointer.
        val nextValue = readU32(s, nextFieldL.toInt(), little).toLong() and 0xFFFFFFFFL
        ctx.offsetFields.add(OffsetField(nextFieldL.toInt(), nextValue))
        if (nextValue != 0L) {
            if (nextValue > Int.MAX_VALUE.toLong()) return false
            if (ctx.visitedIfds.contains(nextValue.toInt())) return false
            ctx.visitedIfds.add(nextValue.toInt())
            if (!parseIfd(ctx, nextValue.toInt(), isIfd0 = false)) return false
        }

        // Parse the GPS IFD (removed) after IFD0's entries/next are handled.
        val gps = gpsIfdOffset
        if (!gpsEntryRemoved && gps != null) {
            return false
        }
        if (gps != null) {
            if (!removeGpsIfd(ctx, gps)) return false
        }
        return true
    }

    /**
     * Records the GPS IFD structure and all GPS out-of-line value regions as removed.
     * Returns false (fail closed) if the GPS IFD cannot be parsed safely.
     */
    private fun removeGpsIfd(ctx: TiffContext, gpsTiffOffset: Long): Boolean {
        val s = ctx.segment
        val t0 = ctx.tiffStart
        val little = ctx.littleEndian
        if (gpsTiffOffset > Int.MAX_VALUE.toLong()) return false
        val gpsAbsL = t0.toLong() + gpsTiffOffset
        if (gpsAbsL < 0L || gpsAbsL + 2 > s.size.toLong()) return false

        val entryCount = readU16(s, gpsAbsL.toInt(), little)
        val entriesL = gpsAbsL + 2
        val nextFieldL = entriesL + entryCount.toLong() * IFD_ENTRY_BYTES
        if (nextFieldL + 4 > s.size.toLong()) return false
        if (entryCount * IFD_ENTRY_BYTES > s.size) return false

        ctx.removed.add(LongRange(gpsAbsL, nextFieldL + 3))

        for (index in 0 until entryCount) {
            val entryL = entriesL + index.toLong() * IFD_ENTRY_BYTES
            val entry = entryL.toInt()
            val type = readU16(s, entry + 2, little)
            val countRaw = readU32(s, entry + 4, little).toLong() and 0xFFFFFFFFL
            val typeSize = tiffTypeSize(type)
            if (typeSize < 0) return false
            if (countRaw > Int.MAX_VALUE.toLong()) return false
            val dataSizeL = typeSize.toLong() * countRaw
            if (dataSizeL > Int.MAX_VALUE.toLong()) return false
            val dataSize = dataSizeL.toInt()
            if (dataSize > 4) {
                val valueRaw = readU32(s, entry + 8, little).toLong() and 0xFFFFFFFFL
                val targetL = t0.toLong() + valueRaw
                if (targetL < 0L || targetL + dataSize.toLong() > s.size.toLong()) return false
                ctx.removed.add(LongRange(targetL, targetL + dataSize.toLong() - 1))
            }
        }
        return true
    }

    private fun rangeLength(r: LongRange): Long = r.last - r.first + 1

    private fun rangesOverlap(a: LongRange, b: LongRange): Boolean =
        a.first <= b.last && b.first <= a.last

    private fun rangeContains(r: LongRange, i: Long): Boolean = i >= r.first && i <= r.last

    /** Total length of removed ranges that start before [absIndex]. */
    private fun deltaBefore(removed: List<LongRange>, absIndex: Long): Long {
        var d = 0L
        for (r in removed) {
            if (r.first < absIndex) d += rangeLength(r)
        }
        return d
    }


    // ---------------------------------------------------------------------------
    // Orientation reporting via platform ExifInterface (read only)
    // ---------------------------------------------------------------------------

    private fun readSourceOrientation(resolver: ContentResolver, sourceUri: Uri): Int? {
        return try {
            resolver.openInputStream(sourceUri)?.use { input ->
                val exif = ExifInterface(input)
                if (exif.hasAttribute(ExifInterface.TAG_ORIENTATION)) {
                    exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
                        .takeIf { it in 1..8 }
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    // ---------------------------------------------------------------------------
    // Stream / array helpers
    // ---------------------------------------------------------------------------

    private fun readExact(input: InputStream, buffer: ByteArray): Boolean =
        readExact(input, buffer, 0, buffer.size)

    private fun readExact(input: InputStream, buffer: ByteArray, offset: Int, length: Int): Boolean {
        var total = 0
        while (total < length) {
            val read = input.read(buffer, offset + total, length - total)
            if (read < 0) return false
            total += read
        }
        return true
    }

    private fun skipExact(input: InputStream, count: Int): Boolean {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skip(remaining.toLong())
            if (skipped <= 0L) {
                // skip() may return 0 on non-seekable streams; fall back to a single read.
                if (input.read() < 0) return false
                remaining -= 1
            } else {
                remaining -= skipped.toInt()
            }
        }
        return true
    }

    private fun readByte(input: InputStream): Int? = runCatching { input.read() }.getOrNull()
        ?.let { if (it < 0) null else it }

    private fun copy(dst: ByteArray, src: ByteArray, srcPos: Int, length: Int): Int {
        System.arraycopy(src, srcPos, dst, 0, length)
        return length
    }

    private fun u(b: Byte): Int = b.toInt() and 0xFF

    private fun readU16(data: ByteArray, index: Int): Int {
        if (index + 1 >= data.size) return -1
        return (u(data[index]) shl 8) or u(data[index + 1])
    }

    private fun readU16(data: ByteArray, index: Int, littleEndian: Boolean): Int {
        val a = u(data[index])
        val b = u(data[index + 1])
        return if (littleEndian) a or (b shl 8) else (a shl 8) or b
    }

    private fun readU32(data: ByteArray, index: Int, littleEndian: Boolean): Int {
        val b0 = u(data[index])
        val b1 = u(data[index + 1])
        val b2 = u(data[index + 2])
        val b3 = u(data[index + 3])
        return if (littleEndian) {
            b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
        } else {
            (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
        }
    }

    private fun writeU32(data: ByteArray, index: Int, value: Long, littleEndian: Boolean) {
        val v = value and 0xFFFFFFFFL
        if (littleEndian) {
            data[index] = (v and 0xFF).toByte()
            data[index + 1] = ((v shr 8) and 0xFF).toByte()
            data[index + 2] = ((v shr 16) and 0xFF).toByte()
            data[index + 3] = ((v shr 24) and 0xFF).toByte()
        } else {
            data[index] = ((v shr 24) and 0xFF).toByte()
            data[index + 1] = ((v shr 16) and 0xFF).toByte()
            data[index + 2] = ((v shr 8) and 0xFF).toByte()
            data[index + 3] = (v and 0xFF).toByte()
        }
    }

    private fun writeU16(data: ByteArray, index: Int, value: Int, littleEndian: Boolean) {
        val v = value and 0xFFFF
        if (littleEndian) {
            data[index] = (v and 0xFF).toByte()
            data[index + 1] = ((v shr 8) and 0xFF).toByte()
        } else {
            data[index] = ((v shr 8) and 0xFF).toByte()
            data[index + 1] = (v and 0xFF).toByte()
        }
    }
}
