package com.tmaem.kilopix

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns

/**
 * Deterministic output-file naming policy, isolated from [BatchCompressionQueue].
 *
 * The policy preserves the source filename base where practical and always produces a
 * `.jpg` extension (KiloPix output is always a JPEG, regardless of the possibly-varied
 * source format). Names are de-conflicted with a deterministic numeric suffix so an
 * existing output is never silently overwritten:
 *
 * ```
 * image.jpg  ->  image (1).jpg  ->  image (2).jpg ...
 * ```
 *
 * Collision resolution is delegated to a caller-supplied probe so it works identically
 * for a SAF tree (via [android.provider.DocumentsContract.createDocument]) and for
 * MediaStore Downloads (via a `DISPLAY_NAME` query).
 */
object OutputFileName {

    private const val DEFAULT_BASE = "compressed"
    private const val JPEG_EXTENSION = ".jpg"

    /** Base name (no extension) derived from the source display name, else [DEFAULT_BASE]. */
    fun baseName(resolver: ContentResolver, sourceUri: Uri): String {
        val display = queryDisplayName(resolver, sourceUri) ?: return DEFAULT_BASE
        val base = display.substringBeforeLast('.').trim()
        return base.ifEmpty { DEFAULT_BASE }
    }

    /** Fully qualified JPEG output name for [base]. */
    fun jpegName(base: String): String = base + JPEG_EXTENSION

    /**
     * Returns the first non-occupied name starting from [name], appending
     * ` (1)`, ` (2)`, ... until [occupied] reports the candidate is free.
     */
    fun collisionSafeName(name: String, occupied: (String) -> Boolean): String {
        if (!occupied(name)) return name
        val dot = name.lastIndexOf('.')
        val prefix = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var count = 1
        while (true) {
            val candidate = "$prefix ($count)$ext"
            if (!occupied(candidate)) return candidate
            count++
        }
    }

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? {
        return try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor: Cursor ->
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
                }
        } catch (e: Exception) {
            null
        }
    }
}
