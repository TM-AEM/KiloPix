package com.tmaem.kilopix

import android.content.Context
import android.net.Uri
import android.os.Environment

/**
 * Lightweight, framework-only persistence and validation of the user-selected custom
 * output folder, represented as SAF **tree** Uri obtained from [android.content.Intent.ACTION_OPEN_DOCUMENT_TREE].
 *
 * The chosen tree Uri string is stored in [android.content.SharedPreferences] (durable app
 * state, not transient Activity state) alongside its persisted write grant. When no valid
 * custom tree is held, output falls back to the default MediaStore Downloads destination
 * (Android 11+ scoped storage; no storage permission required). See [defaultRelativePath].
 *
 * This class never converts a tree Uri into a filesystem path and never uses [java.io.File]
 * against it; destination resolution is left to the SAF/MediaStore providers.
 */
object OutputDestination {

    private const val PREFS_NAME = "kilopix_output"
    private const val KEY_CUSTOM_TREE = "custom_tree_uri"

    /** Sub-folder name used for the default MediaStore Downloads destination. */
    private const val OUTPUT_SUB_FOLDER = "KiloPix"

    /** Default `RELATIVE_PATH` inside the Downloads tree, e.g. `Download/KiloPix`. */
    fun defaultRelativePath(): String = Environment.DIRECTORY_DOWNLOADS + "/" + OUTPUT_SUB_FOLDER

    /**
     * Returns the persisted custom tree Uri if one was stored, otherwise null. This does
     * **not** verify that the underlying grant still exists.
     */
    fun customTreeUri(context: Context): Uri? =
        prefs(context).getString(KEY_CUSTOM_TREE, null)?.takeIf { it.isNotEmpty() }?.let(Uri::parse)

    /** Persists (or, when [uri] is null, clears) the custom tree Uri. */
    fun persistCustomTree(context: Context, uri: Uri?) {
        prefs(context).edit().putString(KEY_CUSTOM_TREE, uri?.toString()).apply()
    }

    /**
     * Clears the persisted custom tree, reverting to the default destination.
     */
    fun resetToDefault(context: Context) = persistCustomTree(context, null)

    /**
     * Validates the persisted custom tree on startup / before use.
     *
     * @return the tree Uri when it is present **and** currently carries a persisted write
     *         grant, otherwise null. On invalid/revoked state the invalid entry is cleared
     *         so the app falls back to the default without claiming the custom folder is
     *         still active, and without crashing.
     */
    fun validateCustomTree(context: Context): Uri? {
        val tree = customTreeUri(context) ?: return null
        val granted = try {
            context.contentResolver.persistedUriPermissions
                .any { it.uri == tree && it.isWritePermission }
        } catch (e: Exception) {
            false
        }
        if (!granted) {
            // Revoked or invalid: clear it and fall back to the default destination.
            persistCustomTree(context, null)
            return null
        }
        return tree
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
