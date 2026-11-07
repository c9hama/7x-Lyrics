package com.aalyrics.lyrics

import com.aalyrics.lyrics.YouTubeMusicLyrics.Row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeMusicLyricsTest {
    private val rows = listOf(
        Row("a", "Paper Boats", "Some Cover Band", 251_000),
        Row("b", "Paper Boats", "The Examples", 214_000),
        Row("c", "Paper Boats (Live)", "The Examples", 260_000),
    )

    @Test
    fun picksTheMatchingArtistAndLength() {
        assertEquals("b", YouTubeMusicLyrics.pickBest(rows, "Paper Boats", "The Examples", 215_000)?.videoId)
    }

    @Test
    fun rejectsVersionsOfTheWrongLength() {
        assertNull(YouTubeMusicLyrics.pickBest(rows.take(1), "Paper Boats", "The Examples", 214_000))
    }

    @Test
    fun rejectsDifferentSongs() {
        assertNull(YouTubeMusicLyrics.pickBest(listOf(Row("x", "Other Song", "The Examples", 214_000)), "Paper Boats", "The Examples", 214_000))
    }

    @Test
    fun unknownLengthStillMatchesByTitleAndArtist() {
        assertEquals("b", YouTubeMusicLyrics.pickBest(rows, "Paper Boats (Official Video)", "The Examples", 0)?.videoId)
    }

    @Test
    fun parsesDurations() {
        assertEquals(227_000L, YouTubeMusicLyrics.parseDuration("3:47"))
        assertEquals(3_725_000L, YouTubeMusicLyrics.parseDuration("1:02:05"))
        assertNull(YouTubeMusicLyrics.parseDuration("1.7B plays"))
    }
}
