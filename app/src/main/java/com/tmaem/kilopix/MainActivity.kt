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
import android.view.ViewGroup
import android.widget.LinearLayout
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
 *
 * Task 16 scope (added): a persisted "Strip metadata" toggle ([SettingsStore]) that drives the
 * existing Task 11 EXIF/GPS policy (PRESERVE/PRESERVE when OFF, REMOVE/REMOVE when ON) through
 * [SaveCoordinator].
 */
class MainActivity : Activity() {

    private val selectedUris = ArrayList<Uri>()
    private var saveInProgress = false

    /** Task 14: successful output Uris scoped to the most recent save operation. */
    private var currentShareUris: List<Uri> = emptyList()

    /** Task 15: original byte size per source Uri captured when a save starts. */
    private var sourceSizes: Map<Uri, Long?> = emptyMap()

    private lateinit var selectionSummary: TextView
    private lateinit var selectionNames: TextView
    private lateinit var destinationValue: TextView
    private lateinit var resetDestination: TextView
    private lateinit var saveStatus: TextView
    private lateinit var replaceExistingSwitch: Switch
    private lateinit var stripMetadataSwitch: Switch
    private lateinit var actionShare: View
    private lateinit var actionShareAll: View

    /** Task 15: results card container and the list that holds the per-item rows. */
    private lateinit var resultsContainer: View
    private lateinit var resultsList: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        selectionSummary = findViewById(R.id.selection_summary)
        selectionNames = findViewById(R.id.selection_names)
        destinationValue = findViewById(R.id.option_save_location_value)
        resetDestination = findViewById(R.id.action_reset_destination)
        saveStatus = findViewById(R.id.save_status)
        replaceExistingSwitch = findViewById(R.id.option_replace_existing)
        stripMetadataSwitch = findViewById(R.id.option_strip_metadata)
        actionShare = findViewById(R.id.action_share)
        actionShareAll = findViewById(R.id.action_share_all)
        resultsContainer = findViewById(R.id.results_container)
        resultsList = findViewById(R.id.results_list)

        actionShare.setOnClickListener { shareFirstOutput() }
        actionShareAll.setOnClickListener { shareAllOutputs() }
        renderShareActions()

        // Task 13: restore the persisted "Replace existing files" preference (default OFF)
        // and keep it in sync so the value survives Activity recreation and app restart.
        replaceExistingSwitch.isChecked = OutputPolicyStore.isReplaceEnabled(this)
        replaceExistingSwitch.setOnCheckedChangeListener { _, isChecked ->
            OutputPolicyStore.setReplaceEnabled(this, isChecked)
        }

        // Task 16: restore the persisted "Strip metadata" preference (default OFF) and
        // keep it in sync so the value survives Activity recreation and app restart.
        stripMetadataSwitch.isChecked = SettingsStore.stripMetadata(this)
        stripMetadataSwitch.setOnCheckedChangeListener { _, isChecked ->
            SettingsStore.setStripMetadata(this, isChecked)
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

        // Task 14: clear any previous operation's share state so stale outputs can never
        // be shared after a new save. The new operation populates the fresh results only.
        currentShareUris = emptyList()
        renderShareActions()

        // Task 15: clear the previous results list; a new save always starts fresh.
        hideResults()
        sourceSizes = captureSourceSizes(selectedUris)

        saveInProgress = true
        saveStatus.text = getString(R.string.save_in_progress)
        saveStatus.visibility = View.VISIBLE

        val resolver = applicationContext.contentResolver
        val requests = selectedUris.map { uri ->
            BatchCompressionRequest.Quick(uri)
        }
        // Task 16: capture the strip-metadata state so the policy is stable for the run.
        val strip = stripMetadataSwitch.isChecked
        val coordinator = SaveCoordinator(KiloPixOutputStreamFactory(applicationContext))
        val total = requests.size

        Thread {
            val result = coordinator.process(
                resolver = resolver,
                requests = requests,
                metadataPolicy = if (strip) {
                    ExifMetadataHandler.MetadataPolicy.REMOVE
                } else {
                    ExifMetadataHandler.MetadataPolicy.PRESERVE
                },
                gpsPolicy = if (strip) {
                    ExifMetadataHandler.ExifGpsPolicy.REMOVE
                } else {
                    ExifMetadataHandler.ExifGpsPolicy.PRESERVE
                },
            )
            val items = buildResultItems(result)
            runOnUiThread {
                saveInProgress = false
                saveStatus.text = when {
                    result.successCount == 0 && result.failedCount == 0 ->
                        getString(R.string.save_empty)
                    result.failedCount == 0 ->
                        getString(R.string.save_success, result.successCount, total)
                    result.successCount == 0 ->
                        getString(R.string.save_failed, result.failedCount, total)
                    else ->
                        getString(R.string.save_mixed, result.successCount, result.failedCount, total)
                }
                saveStatus.visibility = View.VISIBLE
                // Populate share state only from the current successful outputs.
                currentShareUris = result.itemResults
                    .filterIsInstance<BatchItemResult.Success>()
                    .mapNotNull { it.outputUri }
                    .let(ImageShareHelper::unique)
                renderShareActions()
                renderResults(items)
            }
        }.start()
    }

    /**
     * Task 15: captures the original byte size of each source via the metadata column
     * [OpenableColumns.SIZE] (no decode). Sizes are looked up by source Uri and paired
     * per item when rendering. Providers that expose no size yield null (savings hidden).
     */
    private fun captureSourceSizes(uris: List<Uri>): Map<Uri, Long?> {
        val resolver = applicationContext.contentResolver
        val dest = LinkedHashMap<Uri, Long?>(uris.size)
        for (uri in uris) {
            dest[uri] = querySize(resolver, uri)
        }
        return dest
    }

    private fun querySize(resolver: android.content.ContentResolver, uri: Uri): Long? {
        return try {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                ?.use { cursor: Cursor ->
                    val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) {
                        cursor.getLong(index)
                    } else {
                        null
                    }
                }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Task 15: derives the UI-layer per-item results from the ordered batch outcome,
     * pairing each item with its (possibly null) original size and display names.
     */
    private fun buildResultItems(result: BatchCompressionResult): List<ResultItem> =
        result.itemResults.mapIndexed { index, item ->
            val sourceName = displayName(item.sourceUri)
                ?: getString(R.string.selection_name_fallback, index + 1)
            when (item) {
                is BatchItemResult.Success -> ResultItem.Success(
                    sourceUri = item.sourceUri,
                    sourceName = sourceName,
                    outputUri = item.outputUri,
                    outputName = item.outputUri?.let(::displayName),
                    originalSize = sourceSizesFor(item.sourceUri),
                    compressedSize = item.bytesWritten,
                    width = item.width,
                    height = item.height,
                    quality = item.quality,
                )

                is BatchItemResult.Failure -> ResultItem.Failure(
                    sourceUri = item.sourceUri,
                    sourceName = sourceName,
                    reason = item.reason,
                )
            }
        }

    private fun sourceSizesFor(uri: Uri): Long? = sourceSizes[uri]

    /** Clears any previously rendered result rows and hides the results card. */
    private fun hideResults() {
        resultsList.removeAllViews()
        resultsContainer.visibility = View.GONE
    }

    /** Renders per-item result rows into the results card. */
    private fun renderResults(items: List<ResultItem>) {
        if (items.isEmpty()) {
            hideResults()
            return
        }

        resultsList.removeAllViews()
        for ((index, item) in items.withIndex()) {
            resultsList.addView(buildResultRow(item))
            if (index != items.lastIndex) {
                resultsList.addView(buildDivider())
            }
        }
        resultsContainer.visibility = View.VISIBLE
    }

    private fun buildResultRow(item: ResultItem): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        // Name takes the available width and truncates; the status label wraps content.
        val name = headerTextView(
            text = displayNameFor(item),
            weight = 1f,
            color = currentTextColor(),
        )
        val statusLabel = headerTextView(
            text = if (item.isSuccess) {
                getString(R.string.result_saved)
            } else {
                getString(R.string.result_failed)
            },
            weight = 0f,
            color = statusColor(item.isSuccess),
        )
        header.addView(name)
        header.addView(statusLabel)

        val details = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            text = detailText(item)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(helperTextColor())
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        row.addView(header)
        row.addView(details)
        return row
    }

    private fun headerTextView(text: String, weight: Float, color: Int): TextView =
        TextView(this).apply {
            val width = if (weight > 0f) 0 else ViewGroup.LayoutParams.WRAP_CONTENT
            layoutParams = LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
            this.text = text
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(color)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

    private fun buildDivider(): View =
        View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1),
            )
            setBackgroundColor(outlineColor())
        }

    private fun displayNameFor(item: ResultItem): String =
        when (item) {
            is ResultItem.Success -> item.outputName ?: item.sourceName
            is ResultItem.Failure -> item.sourceName
        }

    private fun detailText(item: ResultItem): String =
        when (item) {
            is ResultItem.Success -> successDetail(item)
            is ResultItem.Failure -> failureReasonLabel(item.reason)
        }

    private fun successDetail(item: ResultItem.Success): String {
        val dimensions = getString(R.string.result_dimensions, item.width, item.height)
        val quality = getString(R.string.result_quality, item.quality)
        val savings = savingsDetail(item)
        return "$dimensions · $quality · $savings"
    }

    private fun savingsDetail(item: ResultItem.Success): String {
        val original = item.originalSize
        if (original == null) return getString(R.string.savings_unknown)

        val originalText = ResultsFormatter.formatBytes(original)
        val compressedText = ResultsFormatter.formatBytes(item.compressedSize)
        val saved = ResultsFormatter.bytesSaved(original, item.compressedSize)
        val grew = ResultsFormatter.bytesGrowth(original, item.compressedSize)

        return when {
            saved != null -> {
                val percent = ResultsFormatter.percentageReduction(original, item.compressedSize) ?: 0
                val savedText = getString(R.string.savings_saved_bytes, ResultsFormatter.formatBytes(saved))
                val reducedText = getString(R.string.savings_reduced, originalText, compressedText, percent)
                "$reducedText · $savedText"
            }
            grew != null -> {
                val percent = ResultsFormatter.percentageGrowth(original, item.compressedSize) ?: 0
                getString(R.string.savings_grew, ResultsFormatter.formatBytes(grew), percent)
            }
            else -> "$originalText → $compressedText"
        }
    }

    private fun failureReasonLabel(reason: BatchFailureReason): String =
        when (reason) {
            BatchFailureReason.DECODE_FAILURE -> getString(R.string.failure_decode)
            BatchFailureReason.INVALID_REQUEST -> getString(R.string.failure_invalid_request)
            BatchFailureReason.COMPRESSION_FAILURE -> getString(R.string.failure_compression)
            BatchFailureReason.OUTPUT_STREAM_FAILURE -> getString(R.string.failure_output_stream)
            BatchFailureReason.CANCELLATION -> getString(R.string.failure_cancellation)
            BatchFailureReason.MEMORY_FAILURE -> getString(R.string.failure_memory)
            BatchFailureReason.UNEXPECTED_FAILURE -> getString(R.string.failure_unexpected)
        }

    private fun statusColor(success: Boolean): Int =
        getColor(
            if (success) android.R.color.holo_green_dark else android.R.color.holo_red_light,
        )

    private fun currentTextColor(): Int = getColor(R.color.on_surface)

    private fun helperTextColor(): Int = getColor(R.color.on_surface_variant)

    private fun outlineColor(): Int = getColor(R.color.outline)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    /**
     * Task 14 share visibility. Share actions are only shown after a save completed (not
     * while saving) and only when at least one successful output Uri exists:
     *  - exactly one successful output -> Share; Share All hidden.
     *  - two or more successful outputs -> Share All; Share hidden.
     *  - zero successful outputs (or while saving) -> both hidden.
     */
    private fun renderShareActions() {
        val count = currentShareUris.size
        val sharing = !saveInProgress
        actionShare.visibility = if (sharing && count == 1) View.VISIBLE else View.GONE
        actionShareAll.visibility = if (sharing && count >= 2) View.VISIBLE else View.GONE
    }

    /** Shares a single output (the first of the current successful outputs). */
    private fun shareFirstOutput() {
        val uri = currentShareUris.firstOrNull() ?: return
        val intent = ImageShareHelper.singleShareIntent(this, uri, getString(R.string.app_name))
        ImageShareHelper.launch(this, intent, getString(R.string.share_title))
    }

    /** Shares every successful output of the current save operation. */
    private fun shareAllOutputs() {
        if (currentShareUris.isEmpty()) return
        val intent = ImageShareHelper.batchShareIntent(this, currentShareUris, getString(R.string.app_name))
        ImageShareHelper.launch(this, intent, getString(R.string.share_all_title))
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
