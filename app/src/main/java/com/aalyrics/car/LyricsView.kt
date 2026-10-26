package com.aalyrics.car

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether the lyrics view is on the car screen, so the media card can stop repeating the lyric. */
object LyricsView {
    private val _isOpen = MutableStateFlow(false)
    val isOpen: StateFlow<Boolean> = _isOpen.asStateFlow()

    internal fun setOpen(open: Boolean) {
        _isOpen.value = open
    }
}
