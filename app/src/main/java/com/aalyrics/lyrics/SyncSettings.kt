package com.aalyrics.lyrics

import android.content.Context
import androidx.core.content.edit

/** Per-song timing adjustments and dismissed "timing may be off" warnings, kept on the phone. */
class SyncSettings(context: Context) {
    private val prefs = context.getSharedPreferences("sync_settings", Context.MODE_PRIVATE)

    fun offsetMs(key: String): Long = prefs.getLong("offset:$key", 0L)

    fun setOffsetMs(key: String, offsetMs: Long) = prefs.edit {
        if (offsetMs == 0L) remove("offset:$key") else putLong("offset:$key", offsetMs)
    }

    fun isWarningDismissed(key: String): Boolean = prefs.getBoolean("dismissed:$key", false)

    fun dismissWarning(key: String) = prefs.edit { putBoolean("dismissed:$key", true) }

    companion object {
        fun keyFor(title: String, artist: String): String = "${title.trim().lowercase()}|${artist.trim().lowercase()}"
    }
}
