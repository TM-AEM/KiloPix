package com.tmaem.kilopix

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Task 14: framework-only helpers for sharing successfully saved KiloPix outputs.
 *
 * Sharing is built exclusively on the Android framework: [Intent.ACTION_SEND] /
 * [Intent.ACTION_SEND_MULTIPLE], [Intent.EXTRA_STREAM] content Uris, a temporary read grant
 * ([Intent.FLAG_GRANT_READ_URI_PERMISSION]) and [ClipData] so the receiving app can read each
 * shared document. It never uses `file://`, a FileProvider, source Uris, SAF tree/root Uris,
 * storage permissions, MANAGE_EXTERNAL_STORAGE, or any network/upload path.
 */
object ImageShareHelper {

    /** KiloPix always outputs JPEG (Task 12/13). */
    const val MIME_JPEG = "image/jpeg"

    /**
     * Builds a single-image share intent: [Intent.ACTION_SEND] with [uri] as
     * [Intent.EXTRA_STREAM], MIME [MIME_JPEG], a temporary read grant and a [ClipData]
     * entry for robust grant propagation. The caller must pass an output content Uri
     * (never a source, tree/root, `file://` or null Uri).
     */
    fun singleShareIntent(context: Context, uri: Uri, contentLabel: String): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = MIME_JPEG
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(context.contentResolver, contentLabel, uri)
        }

    /**
     * Builds a batch-share intent: [Intent.ACTION_SEND_MULTIPLE] with [uris] as
     * [Intent.EXTRA_STREAM], MIME [MIME_JPEG], a temporary read grant and a [ClipData]
     * item per Uri so every shared Uri receives the read grant.
     *
     * [uris] must be non-empty, deduplicated, and contain only successful output content
     * Uris (never sources, tree/root, `file://`, null or stale Uris).
     */
    fun batchShareIntent(context: Context, uris: List<Uri>, contentLabel: String): Intent {
        require(uris.isNotEmpty()) { "batchShareIntent requires at least one output Uri" }
        return Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = MIME_JPEG
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(context.contentResolver, contentLabel, uris.first()).apply {
                for (index in 1 until uris.size) {
                    addItem(ClipData.Item(uris[index]))
                }
            }
        }
    }

    /**
     * Deduplicates [uris] while preserving order. Used so the same output is never placed
     * in the share collection twice (selection dedup already removes duplicates, but this
     * is a defensive guarantee for the share path).
     */
    fun unique(uris: List<Uri>): List<Uri> = LinkedHashSet(uris).toList()

    /**
     * Launches [chooserTitle] chooser for [intent]. Fails gracefully when no app can
     * handle the share (and must never affect the already-succeeded save result).
     */
    fun launch(activity: Activity, intent: Intent, chooserTitle: String) {
        try {
            activity.startActivity(Intent.createChooser(intent, chooserTitle))
        } catch (e: ActivityNotFoundException) {
            // Ignore: no share target available; the saved outputs remain saved.
        }
    }
}
