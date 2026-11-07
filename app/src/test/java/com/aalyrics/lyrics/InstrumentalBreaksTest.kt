package com.aalyrics.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class InstrumentalBreaksTest {
    @Test
    fun addsANoteInALongGap() {
        // "short line" (~1.3s to sing) at 100s, singing resumes at 148s.
        val lines = listOf(LyricLine(100_000, "short line"), LyricLine(148_000, "back again"))
        val out = InstrumentalBreaks.insert(lines)
        assertEquals(3, out.size)
        assertEquals("", out[1].text)
        assertEquals(100_000 + SweepEstimate.sungDurationMs("short line") + 1_500, out[1].timeMs)
    }

    @Test
    fun leavesNormalGapsAlone() {
        val lines = listOf(LyricLine(0, "one line here"), LyricLine(4_000, "the next one"))
        assertEquals(lines, InstrumentalBreaks.insert(lines))
    }

    @Test
    fun keepsBreaksTheLyricsAlreadyMark() {
        val lines = listOf(LyricLine(0, "sung"), LyricLine(5_000, ""), LyricLine(40_000, "sung again"))
        assertEquals(lines, InstrumentalBreaks.insert(lines))
    }
}
