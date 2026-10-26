package com.aalyrics.lyrics

import com.aalyrics.lyrics.TrackCleaner.Query
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackCleanerTest {
    @Test
    fun stripsVideoNoise() {
        assertEquals("Paper Boats", TrackCleaner.cleanTitle("Paper Boats (Official Music Video)"))
        assertEquals("Paper Boats", TrackCleaner.cleanTitle("Paper Boats [Lyrics] [4K]"))
        assertEquals("Paper Boats", TrackCleaner.cleanTitle("Paper Boats (feat. Someone Else)"))
        assertEquals("Paper Boats", TrackCleaner.cleanTitle("Paper Boats ft. Someone Else"))
        assertEquals("Paper Boats", TrackCleaner.cleanTitle("Paper Boats - Remastered 2011"))
        assertEquals("Paper Boats", TrackCleaner.cleanTitle("Paper Boats | Live Session"))
    }

    @Test
    fun keepsMeaningfulBrackets() {
        assertEquals("Paper Boats (Acoustic)", TrackCleaner.cleanTitle("Paper Boats (Acoustic)"))
    }

    @Test
    fun cleansChannelNames() {
        assertEquals("The Examples", TrackCleaner.cleanArtist("The Examples - Topic"))
        assertEquals("TheExamples", TrackCleaner.cleanArtist("TheExamplesVEVO"))
    }

    @Test
    fun splitsArtistDashSongUploads() {
        val queries = TrackCleaner.queries("The Examples - Paper Boats (Official Video)", "TheExamplesVEVO")
        assertEquals(Query("Paper Boats", "The Examples"), queries.first())
    }

    @Test
    fun cleanYouTubeMusicMetadataPassesThrough() {
        val queries = TrackCleaner.queries("Paper Boats", "The Examples")
        assertEquals(listOf(Query("Paper Boats", "The Examples")), queries)
    }

    @Test
    fun addsPrimaryArtistFallback() {
        val queries = TrackCleaner.queries("Paper Boats", "The Examples & Guest")
        assertEquals(Query("Paper Boats", "The Examples"), queries.last())
    }
}
