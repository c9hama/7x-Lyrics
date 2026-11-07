package com.aalyrics.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LyricsTranslateLyricsTest {
    private val artistPage = """
        <a href="/en/the-examples-lyrics.html">The Examples</a>
        <a href="/en/the-examples-paper-boats-%D9%82%D8%A7%D8%B1-lyrics.html">Paper Boats (قار)</a>
        <a href="/en/the-examples-river-song-lyrics.html">River Song</a>
        <a href="/en/someone-else-paper-boats-lyrics.html">Paper Boats</a>
    """

    @Test
    fun slugsLikeTheSite() {
        assertEquals("ardalan-bakr", LyricsTranslateLyrics.slugify("Ardalan Bakr"))
        assertEquals("the-examples", LyricsTranslateLyrics.slugify("  The Examples! "))
        assertEquals("ئەردەڵان", LyricsTranslateLyrics.slugify("ئەردەڵان"))
    }

    @Test
    fun findsTheSongLinkOnTheArtistPage() {
        assertEquals(
            "/en/the-examples-paper-boats-%D9%82%D8%A7%D8%B1-lyrics.html",
            LyricsTranslateLyrics.findSongPath(artistPage, "the-examples", "Paper Boats"),
        )
        assertEquals("/en/the-examples-river-song-lyrics.html", LyricsTranslateLyrics.findSongPath(artistPage, "the-examples", "River Song"))
    }

    @Test
    fun ignoresOtherArtistsAndMissingSongs() {
        assertNull(LyricsTranslateLyrics.findSongPath(artistPage, "the-examples", "Not On This Page"))
    }

    @Test
    fun readsLinesAndStanzasFromTheSongBody() {
        val page = """
            <div id="song-body"><div class="ltf">
              <div class="par"><div class="ll-0-1">first line</div><div class="ll-0-2">second &amp; line</div></div>
              <div class="emptyline"></div>
              <div class="par"><div class="ll-1-1">third line</div></div>
            </div></div>
            <div id="song-transliteration"><div class="ll-9-9">not lyrics</div></div>
        """
        assertEquals("first line\nsecond & line\n\nthird line", LyricsTranslateLyrics.parseSongText(page))
    }

    @Test
    fun noSongBodyMeansNoLyrics() {
        assertNull(LyricsTranslateLyrics.parseSongText("<html><body>Just a page</body></html>"))
    }
}
