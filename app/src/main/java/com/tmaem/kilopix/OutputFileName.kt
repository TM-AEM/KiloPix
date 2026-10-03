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

    /**
     * Conservative cap on the sanitized base name (Task 18). Prevents pathologically long
     * source filenames from producing a provider-rejected output document name. The cap is
     * applied to the base only (before the `.jpg` extension and any ` (N)` collision suffix),
     * so it cannot strip the extension or produce an empty name.
     */
    private const val MAX_BASE_LENGTH = 80

    /** Base name (no extension) derived from the source display name, else [DEFAULT_BASE]. */
    fun baseName(resolver: ContentResolver, sourceUri: Uri): String {
        val display = queryDisplayName(resolver, sourceUri) ?: return DEFAULT_BASE
        val base = display.substringBeforeLast('.').trim()
        return sanitizeBase(base.ifEmpty { DEFAULT_BASE })
    }

    /**
     * Task 13: sanitizes a derived base name so it is safe to use as a content-provider
     * document / MediaStore display name.
     *
     * Removes characters that are unsafe as a provider filename — specifically `/`, `\`,
     * NUL, all ASCII control characters, and legacy-filesystem-forbidden characters
     * (`:`, `*`, `?`, `"`, `<`, `>`, `|`). This guarantees, for example, that a source
     * named `a/b.jpg` never produces an output filename containing `/`.
     *
     * Preserved: spaces, dots, parentheses, underscores, hyphens, and normal Unicode
     * characters (non-ASCII text is not touched).
     *
     * Case policy: this performs NO locale-dependent case conversion and the policy is
     * exactly case-sensitive (`IMAGE.jpg` and `image.jpg` remain distinct output names),
     * which matches the exact-name replacement matching used elsewhere in Task 13.
     *
     * @return the sanitized name, or [DEFAULT_BASE] ("compressed") when nothing remains.
     */
    fun sanitizeBase(raw: String): String {
        val sanitized = buildString {
            for (ch in raw) {
                if (!isUnsafeForFilename(ch)) append(ch)
            }
            if (isEmpty()) append(DEFAULT_BASE)
        }
        return if (sanitized.length > MAX_BASE_LENGTH) {
            sanitized.take(MAX_BASE_LENGTH)
        } else {
            sanitized
        }
    }

    private fun isUnsafeForFilename(ch: Char): Boolean = when {
        ch == '/' || ch == '\\' || ch.code == 0 -> true
        ch.code < 0x20 -> true                      // ASCII control characters
        ch == ':' || ch == '*' || ch == '?' || ch == '"' || ch == '<' || ch == '>' || ch == '|' -> true
        else -> false
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
