package org.arnor.extshare

import android.content.Context

/** Toggles for the in-app test lab, persisted so they survive restarts. */
object TestSettings {
    private const val PREFS = "test_settings"
    const val WHOLE_SCREEN = "whole_screen"
    const val KEEP_CAPTURE = "keep_capture"
    const val AUTO_REOPEN = "auto_reopen"
    const val AUTO_PIP = "auto_pip"

    private val defaults = mapOf(
        WHOLE_SCREEN to true,
        KEEP_CAPTURE to true,
        AUTO_REOPEN to true,
        AUTO_PIP to true
    )

    fun get(context: Context, key: String): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(key, defaults.getValue(key))

    fun set(context: Context, key: String, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(key, value).apply()
    }
}
