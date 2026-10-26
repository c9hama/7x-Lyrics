package com.aalyrics

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** User options set on the phone and read live by the car screen. */
object AppSettings {
    private const val PREFS = "app_settings"
    private const val KEY_KARAOKE = "karaoke_highlight"

    private val _karaoke = MutableStateFlow(false)
    /** Estimated word-by-word sweep on the current line. Off by default. */
    val karaoke: StateFlow<Boolean> = _karaoke.asStateFlow()

    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        _karaoke.value = prefs(context).getBoolean(KEY_KARAOKE, false)
    }

    fun setKaraoke(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_KARAOKE, enabled) }
        _karaoke.value = enabled
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
