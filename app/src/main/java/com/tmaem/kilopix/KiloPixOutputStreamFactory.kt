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
 *  2. Derives a collision-safe output name from the source.
 *  3. Creates a fresh output document and returns a writable [OutputStream] via
 *     [ContentResolver.openOutputStream].
 *
 * It never overwrites the source, never silently overwrites an existing output, and never
 * uses [java.io.File] against a SAF tree or MediaStore destination. All destination I/O is
 * delegated to the framework providers.
 */
class KiloPixOutputStreamFactory(
    private val context: Context,
) : OutputStreamFactory {

    private val resolver: ContentResolver get() = context.contentResolver

    /** KiloPix always encodes JPEG. */
    private val outputMimeType: String = "image/jpeg"

    override fun open(sourceUri: Uri): OutputStream {
        val tree = OutputDestination.validateCustomTree(context)
        return if (tree != null) openInTree(tree, sourceUri) else openInMediaStore(sourceUri)
    }

    /** Creates a child document under the SAF tree and opens a stream to it. */
    private fun openInTree(tree: Uri, sourceUri: Uri): OutputStream {
        val base = OutputFileName.baseName(resolver, sourceUri)
        // Collision detection is based on the set of existing child display names returned
        // by the tree provider. createDocument() is called exactly once for the final name;
        // it is never used as an existence probe (which would itself create unwanted
        // documents that are then left behind).
        val existing = existingChildNames(tree)
        val name = OutputFileName.collisionSafeName(OutputFileName.jpegName(base), existing::contains)
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

    /** Inserts a fresh MediaStore Downloads item and opens a stream to it. */
    private fun openInMediaStore(sourceUri: Uri): OutputStream {
        val base = OutputFileName.baseName(resolver, sourceUri)
        val name = OutputFileName.collisionSafeName(OutputFileName.jpegName(base)) { candidate ->
            mediaStoreNameExists(candidate)
        }
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

    /** True when a Downloads item with the same display name already exists in our folder. */
    private fun mediaStoreNameExists(name: String): Boolean {
        return try {
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val projection = arrayOf(MediaStore.MediaColumns._ID)
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
            val args = arrayOf(name, OutputDestination.defaultRelativePath())
            resolver.query(collection, projection, selection, args, null)?.use { c ->
                c.count > 0
            } ?: false
        } catch (e: Exception) {
            false
        }
    }
}
