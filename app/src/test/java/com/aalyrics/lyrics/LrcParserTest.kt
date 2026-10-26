package com.aalyrics.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class LrcParserTest {
    @Test
    fun parsesCentisecondsMillisecondsAndRepeatedTags() {
        val lrc = """
            [ar:Test Artist]
            [00:01.50]first line
            [00:03.250]second line
            [00:05.00][00:09.00]chorus line
            [00:07.10]
        """.trimIndent()

        val lines = LrcParser.parse(lrc)

        assertEquals(
            listOf(
                LyricLine(1_500, "first line"),
                LyricLine(3_250, "second line"),
                LyricLine(5_000, "chorus line"),
                LyricLine(7_100, ""),
                LyricLine(9_000, "chorus line"),
            ),
            lines,
        )
    }

    @Test
    fun undoesDoubledApostrophes() {
        assertEquals(listOf(LyricLine(2_000, "it's fine, don't worry")), LrcParser.parse("[00:02.00]it''s fine, don''t worry"))
    }

    @Test
    fun indexAtFindsActiveLine() {
        val lines = listOf(LyricLine(1_000, "a"), LyricLine(2_000, "b"), LyricLine(3_000, "c"))
        assertEquals(-1, LrcParser.indexAt(lines, 500))
        assertEquals(0, LrcParser.indexAt(lines, 1_000))
        assertEquals(1, LrcParser.indexAt(lines, 2_999))
        assertEquals(2, LrcParser.indexAt(lines, 60_000))
        assertEquals(-1, LrcParser.indexAt(emptyList(), 1_000))
    }
}
