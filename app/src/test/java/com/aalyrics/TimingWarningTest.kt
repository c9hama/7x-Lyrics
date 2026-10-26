package com.aalyrics

import com.aalyrics.LyricsEngine.Display
import com.aalyrics.LyricsEngine.Status
import com.aalyrics.LyricsEngine.Track
import com.aalyrics.lyrics.LyricLine
import com.aalyrics.lyrics.Lyrics
import com.aalyrics.lyrics.SyncSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimingWarningTest {
    private val lines = listOf(LyricLine(1_000, "first line"), LyricLine(4_000, "second line"))

    private fun display(playingMs: Long, sourceMs: Long, offsetMs: Long = 0, dismissed: Boolean = false) = Display(
        track = Track("Paper Boats", "The Examples", playingMs),
        status = Status.Ready(Lyrics.Synced(lines, sourceMs)),
        offsetMs = offsetMs,
        warningDismissed = dismissed,
    )

    @Test
    fun warnsWhenLengthsDifferByMoreThanTwoSeconds() {
        assertTrue(display(playingMs = 251_000, sourceMs = 244_000).timingMayBeOff)
    }

    @Test
    fun quietForMatchingVersions() {
        assertFalse(display(playingMs = 294_800, sourceMs = 295_000).timingMayBeOff)
    }

    @Test
    fun quietOnceDismissedOrAdjusted() {
        assertFalse(display(playingMs = 251_000, sourceMs = 244_000, dismissed = true).timingMayBeOff)
        assertFalse(display(playingMs = 251_000, sourceMs = 244_000, offsetMs = -500).timingMayBeOff)
    }

    @Test
    fun quietWhenALengthIsUnknown() {
        assertFalse(display(playingMs = 0, sourceMs = 244_000).timingMayBeOff)
        assertFalse(display(playingMs = 251_000, sourceMs = 0).timingMayBeOff)
    }

    @Test
    fun settingsKeyIgnoresCaseAndSpacing() {
        assertEquals(SyncSettings.keyFor("Paper Boats", "The Examples"), SyncSettings.keyFor(" paper boats ", "THE EXAMPLES"))
    }
}
