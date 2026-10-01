package com.tmaem.kilopix

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.TextView

/**
 * Single-screen shell for KiloPix.
 *
 * Task 03 scope: image *import* only. The user picks one or more images through
 * the Storage Access Framework, and the selection is retained as content URIs.
 * No image is decoded, previewed, transformed, or compressed here.
 *
 * The selected URIs are temporary in-memory state, restored across
 * configuration changes through [onSaveInstanceState].
 */
class MainActivity : Activity() {

    private val selectedUris = ArrayList<Uri>()

    private lateinit var selectionSummary: TextView
    private lateinit var selectionNames: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        selectionSummary = findViewById(R.id.selection_summary)
        selectionNames = findViewById(R.id.selection_names)

        restoreSelection(savedInstanceState)

        findViewById<View>(R.id.primary_action).setOnClickListener {
            launchDocumentPicker()
        }

        renderSelection()
    }

    @Suppress("DEPRECATION")
    private fun launchDocumentPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try {
            startActivityForResult(intent, REQUEST_OPEN_DOCUMENT)
        } catch (e: ActivityNotFoundException) {
            // No document provider is available; keep the current state stable.
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != REQUEST_OPEN_DOCUMENT) return
        // Cancelled picker is a normal action: keep the existing selection.
        if (resultCode != RESULT_OK || data == null) return

        val picked = collectUris(data)
        if (picked.isEmpty()) return

        selectedUris.clear()
        selectedUris.addAll(picked)
        persistReadPermissions(picked)
        renderSelection()
    }

    private fun collectUris(data: Intent): List<Uri> {
        val unique = LinkedHashSet<Uri>()
        val clip = data.clipData
        if (clip != null) {
            for (index in 0 until clip.itemCount) {
                clip.getItemAt(index).uri?.let { unique.add(it) }
            }
        } else {
            data.data?.let { unique.add(it) }
        }
        return unique.toList()
    }

    private fun persistReadPermissions(uris: List<Uri>) {
        for (uri in uris) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (e: SecurityException) {
                // Provider does not support persistable permissions; use temporary access.
            }
        }
    }

    private fun displayName(uri: Uri): String? {
        return try {
            contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor: Cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
                }
        } catch (e: Exception) {
            // Metadata is best-effort; an unreadable provider must not break the UI.
            null
        }
    }

    private fun renderSelection() {
        val count = selectedUris.size
        if (count == 0) {
            selectionSummary.text = getString(R.string.selection_none)
            selectionNames.visibility = View.GONE
            return
        }

        selectionSummary.text = resources.getQuantityString(R.plurals.selection_count, count, count)

        val names = selectedUris.mapIndexed { index, uri ->
            displayName(uri) ?: getString(R.string.selection_name_fallback, index + 1)
        }
        val shown = if (names.size > MAX_LISTED_NAMES) names.take(MAX_LISTED_NAMES) + ELLIPSIS else names
        selectionNames.text = shown.joinToString(separator = "\n")
        selectionNames.visibility = View.VISIBLE
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(STATE_SELECTION, ArrayList(selectedUris.map { it.toString() }))
    }

    private fun restoreSelection(state: Bundle?) {
        val stored = state?.getStringArrayList(STATE_SELECTION) ?: return
        selectedUris.clear()
        for (value in stored) {
            if (value.isNotEmpty()) selectedUris.add(Uri.parse(value))
        }
    }

    private companion object {
        const val REQUEST_OPEN_DOCUMENT = 1001
        const val STATE_SELECTION = "com.tmaem.kilopix.selection"
        const val MAX_LISTED_NAMES = 5
        const val ELLIPSIS = "\u2026"
    }
}
