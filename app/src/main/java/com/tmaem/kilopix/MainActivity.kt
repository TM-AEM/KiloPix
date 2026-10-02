package com.tmaem.kilopix

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.view.View
import android.widget.Switch
import android.widget.TextView

/**
 * Single-screen shell for KiloPix.
 *
 * Task 03 scope (preserved): image *import* through [android.content.Intent.ACTION_OPEN_DOCUMENT]
 * with persistable READ permission, retained as content URIs across configuration changes.
 *
 * Task 12 scope (added): a selected output *folder* chosen via
 * [android.content.Intent.ACTION_OPEN_DOCUMENT_TREE], persisted (with its write grant) in
 * durable app state via [OutputDestination], plus a minimal compress-and-save flow that runs
 * the existing pipeline (decode -> Quick compression -> Task 11 metadata -> OutputStreamFactory
 * -> persistent destination) off the main thread. All storage I/O is delegated to the SAF /
 * MediaStore providers; no storage permission and no filesystem paths are used.
 *
 * Task 13 scope (added): an explicit, persisted "Replace existing files" toggle
 * ([OutputPolicyStore]) that selects the UNIQUE (default, Task 12) or REPLACE output policy.
 * The factory derives a sanitized name and, in REPLACE mode, overwrites the existing output in
 * place. Task 12 SAF permission persistence is untouched.
 */
class MainActivity : Activity() {

    private val selectedUris = ArrayList<Uri>()
    private var saveInProgress = false

    private lateinit var selectionSummary: TextView
    private lateinit var selectionNames: TextView
    private lateinit var destinationValue: TextView
    private lateinit var resetDestination: TextView
    private lateinit var saveStatus: TextView
    private lateinit var replaceExistingSwitch: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        selectionSummary = findViewById(R.id.selection_summary)
        selectionNames = findViewById(R.id.selection_names)
        destinationValue = findViewById(R.id.option_save_location_value)
        resetDestination = findViewById(R.id.action_reset_destination)
        saveStatus = findViewById(R.id.save_status)
        replaceExistingSwitch = findViewById(R.id.option_replace_existing)

        // Task 13: restore the persisted "Replace existing files" preference (default OFF)
        // and keep it in sync so the value survives Activity recreation and app restart.
        replaceExistingSwitch.isChecked = OutputPolicyStore.isReplaceEnabled(this)
        replaceExistingSwitch.setOnCheckedChangeListener { _, isChecked ->
            OutputPolicyStore.setReplaceEnabled(this, isChecked)
        }

        restoreSelection(savedInstanceState)

        findViewById<View>(R.id.primary_action).setOnClickListener {
            launchDocumentPicker()
        }

        findViewById<View>(R.id.option_save_location_row).setOnClickListener {
            launchTreePicker()
        }

        resetDestination.setOnClickListener {
            OutputDestination.resetToDefault(this)
            renderDestination()
        }

        findViewById<View>(R.id.action_compress).setOnClickListener {
            startSave()
        }

        renderSelection()
        renderDestination()
    }

    private fun launchDocumentPicker() {
        @Suppress("DEPRECATION")
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

    /** Launches the SAF directory-tree picker to choose the custom output folder. */
    private fun launchTreePicker() {
        @Suppress("DEPRECATION")
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try {
            startActivityForResult(intent, REQUEST_OPEN_TREE)
        } catch (e: ActivityNotFoundException) {
            // No document provider is available; keep the current destination.
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            REQUEST_OPEN_DOCUMENT -> handleDocumentResult(resultCode, data)
            REQUEST_OPEN_TREE -> handleTreeResult(resultCode, data)
        }
    }

    private fun handleDocumentResult(resultCode: Int, data: Intent?) {
        // Cancelled picker is a normal action: keep the existing selection.
        if (resultCode != RESULT_OK || data == null) return
        val picked = collectUris(data)
        if (picked.isEmpty()) return
        selectedUris.clear()
        selectedUris.addAll(picked)
        persistReadPermissions(picked)
        renderSelection()
    }

    private fun handleTreeResult(resultCode: Int, data: Intent?) {
        // Cancelled picker keeps the current destination.
        if (resultCode != RESULT_OK || data?.data == null) return
        val tree = data.data!!
        try {
            contentResolver.takePersistableUriPermission(
                tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            OutputDestination.persistCustomTree(this, tree)
            renderDestination()
        } catch (e: SecurityException) {
            // Provider does not support persistable permissions; do not persist.
            saveStatus.text = getString(R.string.save_failed, 1, 1)
            saveStatus.visibility = View.VISIBLE
        }
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

    /** Shows the current output destination (validated custom tree or default MediaStore). */
    private fun renderDestination() {
        val tree = OutputDestination.validateCustomTree(this)
        if (tree == null) {
            destinationValue.text = getString(R.string.save_destination_default)
            resetDestination.visibility = View.GONE
        } else {
            destinationValue.text = folderDisplayName(tree) ?: getString(R.string.compressed_folder)
            resetDestination.visibility = View.VISIBLE
        }
    }

    /** Resolves the display name of a SAF tree document where the provider exposes it. */
    private fun folderDisplayName(tree: Uri): String? {
        return try {
            val doc = DocumentsContract.buildDocumentUriUsingTree(
                tree,
                DocumentsContract.getTreeDocumentId(tree),
            )
            contentResolver.query(doc, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor: Cursor ->
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
                }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Compresses and saves the selected source images to the active destination. Runs the
     * entire pipeline on a background thread so provider/compression I/O never blocks the UI.
     */
    private fun startSave() {
        if (selectedUris.isEmpty()) {
            saveStatus.text = getString(R.string.save_empty)
            saveStatus.visibility = View.VISIBLE
            return
        }
        if (saveInProgress) return

        saveInProgress = true
        saveStatus.text = getString(R.string.save_in_progress)
        saveStatus.visibility = View.VISIBLE

        val resolver = applicationContext.contentResolver
        val requests = selectedUris.map { uri ->
            BatchCompressionRequest.Quick(uri)
        }
        val coordinator = SaveCoordinator(KiloPixOutputStreamFactory(applicationContext))
        val total = requests.size

        Thread {
            val result = coordinator.process(
                resolver = resolver,
                requests = requests,
                metadataPolicy = ExifMetadataHandler.MetadataPolicy.PRESERVE,
                gpsPolicy = ExifMetadataHandler.ExifGpsPolicy.PRESERVE,
            )
            runOnUiThread {
                saveInProgress = false
                saveStatus.text = if (result.failedCount == 0) {
                    getString(R.string.save_success, result.successCount, total)
                } else {
                    getString(R.string.save_failed, result.failedCount, total)
                }
                saveStatus.visibility = View.VISIBLE
            }
        }.start()
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
        const val REQUEST_OPEN_TREE = 1002
        const val STATE_SELECTION = "com.tmaem.kilopix.selection"
        const val MAX_LISTED_NAMES = 5
        const val ELLIPSIS = "\u2026"
    }
}
