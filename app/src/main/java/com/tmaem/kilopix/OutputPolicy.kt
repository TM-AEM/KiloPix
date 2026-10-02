package com.tmaem.kilopix

import android.content.Context

/**
 * Task 13 output naming policy.
 *
 * Two modes, deliberately minimal:
 *  - [UNIQUE] — the default / existing Task 12 behavior: never overwrite an existing
 *    output; de-conflict with a deterministic numeric suffix (`image.jpg`, `image (1).jpg`, ...).
 *  - [REPLACE] — new Task 13 behavior: re-use the deterministic output name and overwrite
 *    the existing output in place when one already exists.
 *
 * Default is [UNIQUE].
 */
enum class OutputPolicy {
    UNIQUE,
    REPLACE,
}

/**
 * Durable persistence of the "Replace existing files" preference.
 *
 * Stored in the same lightweight [android.content.SharedPreferences] used by
 * [OutputDestination] (`kilopix_output`). No database, no new dependency. Defaults to
 * OFF and survives Activity recreation and app restart.
 */
object OutputPolicyStore {

    private const val PREFS_NAME = "kilopix_output"
    private const val KEY_REPLACE_EXISTING = "replace_existing_files"

    /** Returns the current persisted policy; defaults to [OutputPolicy.UNIQUE]. */
    fun current(context: Context): OutputPolicy =
        if (isReplaceEnabled(context)) OutputPolicy.REPLACE else OutputPolicy.UNIQUE

    /** True when the "Replace existing files" preference is currently ON. */
    fun isReplaceEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_REPLACE_EXISTING, false)

    /** Persists the "Replace existing files" preference. */
    fun setReplaceEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_REPLACE_EXISTING, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
