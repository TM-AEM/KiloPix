package com.tmaem.kilopix

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.IOException
import java.io.OutputStream

/**
 * Destination-aware [OutputStreamFactory] — the exact integration point [BatchCompressionQueue]
 * consumes (see [BatchCompressionQueue.outputStreamFactory]; the queue type is not modified).
 *
 * For each source Uri it:
 *  1. Decides the active destination: a validated user-selected SAF tree, or the default
 *     MediaStore Downloads destination.
 *  2. Reads the persisted Task 13 output policy ([OutputPolicyStore]) — [UNIQUE] (default)
 *     or [REPLACE].
 *  3. Derives a sanitized, collision-safe output name from the source (UNIQUE), or reuses a
 *     deterministic sanitized target name and overwrites an existing output in place (REPLACE).
 *  4. Creates (or, in REPLACE, opens for writing) the output document and returns a writable
 *     [OutputStream] via [ContentResolver.openOutputStream].
 *
 * It never overwrites the source, never targets arbitrary files outside the configured
 * KiloPix destination, never silently swallows a replacement, and never uses [java.io.File]
 * against a SAF tree or MediaStore destination. All destination I/O is delegated to the
 * framework providers.
 */
class KiloPixOutputStreamFactory(
    private val context: Context,
) : OutputStreamFactory {

    private val resolver: ContentResolver get() = context.contentResolver

    /** KiloPix always encodes JPEG. */
    private val outputMimeType: String = "image/jpeg"

    override fun open(sourceUri: Uri): OutputStream {
        val tree = OutputDestination.validateCustomTree(context)
        val policy = OutputPolicyStore.current(context)
        return if (tree != null) openInTree(tree, sourceUri, policy) else openInMediaStore(sourceUri, policy)
    }

    /**
     * Opens (SAF tree) the destination stream for [sourceUri] under the selected policy.
     *
     * [OutputPolicy.UNIQUE]: exactly the Task 12 behavior — deterministic name with a
     * numeric de-conflict suffix, never overwriting.
     *
     * [OutputPolicy.REPLACE]: deterministic sanitized target name; if a child document in
     * this tree has the exact same display name, write to it in place (never `(1)`), else
     * create the document. The existing document is opened for writing first; it is never
     * deleted before a direct overwrite is attempted.
     */
    private fun openInTree(tree: Uri, sourceUri: Uri, policy: OutputPolicy): OutputStream {
        val base = OutputFileName.baseName(resolver, sourceUri)
        val jpeg = OutputFileName.jpegName(base)
        return when (policy) {
            OutputPolicy.UNIQUE -> {
                // Collision detection is based on the set of existing child display names
                // returned by the tree provider. createDocument() is called exactly once for
                // the final name; it is never used as an existence probe (which would itself
                // create unwanted documents that are then left behind).
                val existing = existingChildNames(tree)
                val name = OutputFileName.collisionSafeName(jpeg, existing::contains)
                createInTree(tree, name)
            }
            OutputPolicy.REPLACE -> {
                val existingDoc = findChildDocument(tree, jpeg)
                if (existingDoc != null) {
                    resolver.openOutputStream(existingDoc)
                        ?: throw IOException("openOutputStream returned null for $jpeg")
                } else {
                    createInTree(tree, jpeg)
                }
            }
        }
    }

    /** Creates a child document under the SAF tree with [name] and opens a stream to it. */
    private fun createInTree(tree: Uri, name: String): OutputStream {
        val child = DocumentsContract.createDocument(resolver, tree, outputMimeType, name)
            ?: throw IOException("createDocument returned null for $name")
        return resolver.openOutputStream(child)
            ?: throw IOException("openOutputStream returned null for $name")
    }

    /**
     * Queries the tree directory for the display names of its current child documents.
     * Returns an empty set when the provider does not support child enumeration, in which
     * case the caller falls back to a single creation attempt (never a speculative probe).
     */
    private fun existingChildNames(tree: Uri): Set<String> {
        val names = HashSet<String>()
        try {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(
                tree,
                DocumentsContract.getTreeDocumentId(tree),
            )
            val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
            resolver.query(children, projection, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    if (idx >= 0) cursor.getString(idx)?.let { names.add(it.trim()) }
                }
            }
        } catch (e: Exception) {
            // Provider does not support querying; fall back to a single create attempt.
        }
        return names
    }

    /**
     * Task 13 REPLACE: finds the child document (under [tree]) whose display name exactly
     * matches [name], returning its document Uri, or null when no exact match exists.
     *
     * Matching is case-sensitive with no locale-dependent normalization, consistent with the
     * case policy across the app (`IMAGE.jpg` != `image.jpg`). A failed provider query is
     * treated as "no match" so REPLACE falls back to creating the document rather than ever
     * guessing at (and overwriting) an arbitrary existing document.
     */
    private fun findChildDocument(tree: Uri, name: String): Uri? {
        try {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(
                tree,
                DocumentsContract.getTreeDocumentId(tree),
            )
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                OpenableColumns.DISPLAY_NAME,
            )
            resolver.query(children, projection, null, null, null)?.use { cursor ->
                val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val display = if (nameIdx >= 0) cursor.getString(nameIdx)?.trim() else null
                    if (display == name) {
                        val docId = if (idIdx >= 0) cursor.getString(idIdx) else null
                        if (docId != null) {
                            return DocumentsContract.buildDocumentUriUsingTree(tree, docId)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Provider does not support querying; treated as no exact match.
        }
        return null
    }

    /** Inserts a fresh MediaStore Downloads item and opens a stream to it. */
    private fun openInMediaStore(sourceUri: Uri, policy: OutputPolicy): OutputStream {
        val base = OutputFileName.baseName(resolver, sourceUri)
        val jpeg = OutputFileName.jpegName(base)
        return when (policy) {
            OutputPolicy.UNIQUE -> {
                val name = OutputFileName.collisionSafeName(jpeg) { candidate ->
                    mediaStoreItemUri(candidate) != null
                }
                insertMediaStore(name)
            }
            OutputPolicy.REPLACE -> {
                // Deterministic sanitized target; restrict strictly to the KiloPix MediaStore
                // output directory. If an item with the exact DISPLAY_NAME already exists there,
                // write to that item in place; never a ` (1)` suffix, and never an item anywhere
                // outside the KiloPix destination. The existing item is opened for writing and
                // never removed as a speculative collision operation.
                val existing = mediaStoreItemUri(jpeg)
                if (existing != null) {
                    resolver.openOutputStream(existing)
                        ?: throw IOException("openOutputStream returned null for $jpeg")
                } else {
                    insertMediaStore(jpeg)
                }
            }
        }
    }

    private fun insertMediaStore(name: String): OutputStream {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, outputMimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, OutputDestination.defaultRelativePath())
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values)
            ?: throw IOException("MediaStore insert returned null for $name")
        return resolver.openOutputStream(uri)
            ?: throw IOException("openOutputStream returned null for $name")
    }

    /**
     * Returns the content Uri of the Downloads item with the exact [name] inside the KiloPix
     * output directory, or null when no such item exists. Matching uses the exact
     * `DISPLAY_NAME`-style equality on `DISPLAY_NAME` + `RELATIVE_PATH` — case-sensitive,
     * so `IMAGE.jpg` and `image.jpg` are distinct targets, exactly as in UNIQUE mode.
     */
    private fun mediaStoreItemUri(name: String): Uri? {
        return try {
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val projection = arrayOf(MediaStore.MediaColumns._ID)
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
            val args = arrayOf(name, OutputDestination.defaultRelativePath())
            resolver.query(collection, projection, selection, args, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idIndex = c.getColumnIndex(MediaStore.MediaColumns._ID)
                    if (idIndex >= 0) {
                        MediaStore.Downloads.getContentUri(
                            MediaStore.VOLUME_EXTERNAL_PRIMARY,
                            c.getLong(idIndex),
                        )
                    } else {
                        null
                    }
                } else {
                    null
                }
            } ?: null
        } catch (e: Exception) {
            // Failed provider query in UNIQUE mode previously meant "assume free name";
            // in REPLACE mode a failed query must never be treated as a target to overwrite,
            // so fall back to creating the document rather than guessing an existing one.
            null
        }
    }
}
