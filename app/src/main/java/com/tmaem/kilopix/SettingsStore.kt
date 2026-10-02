package com.tmaem.kilopix

import android.content.Context

/**
 * Task 16 (corrective): lightweight, framework-only user preference backed by
 * [android.content.SharedPreferences]. No DataStore, Room, database or third-party
 * settings library.
 *
 * Holds exactly one value: the "Strip metadata" boolean that drives the existing
 * Task 11 EXIF/GPS policy through [SaveCoordinator].
 *
 * The default-compression-mode preference is intentionally NOT implemented here: the
 * current UI provides no inputs for [BatchCompressionRequest.Target] (a byte budget) or
 * [BatchCompressionRequest.Manual] (quality and output dimensions), so persisting a
 * selectable default mode would have no effect on the actual operation. Launching a
 * mode without its required parameters is avoided; a future task that adds the real
 * inputs can introduce mode persistence.
 *
 * Safe default: "strip metadata" is OFF (Preserve). A missing or invalid stored value
 * falls back to false, preserving the pre-Task-16 behavior.
 */
object SettingsStore {

    private const val PREFS_NAME = "kilopix_settings"
    private const val KEY_STRIP_METADATA = "strip_metadata"

    /**
     * True when EXIF/GPS metadata should be stripped from the output. Defaults to false
     * (Preserve) when the preference is missing.
     */
    fun stripMetadata(context: Context): Boolean =
        prefs(context).getBoolean(KEY_STRIP_METADATA, false)

    /** Persists the "Strip metadata" preference. */
    fun setStripMetadata(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_STRIP_METADATA, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
