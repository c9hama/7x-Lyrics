package com.aalyrics.lyrics

/**
 * Turns messy YouTube titles ("Artist - Song (Official Video) ft. X") into
 * an ordered list of (title, artist) guesses to search lyrics with.
 */
object TrackCleaner {
    data class Query(val title: String, val artist: String)

    private val BRACKETED = Regex("""\s*[(\[【]([^)\]】]*)[)\]】]""")
    private val NOISE_WORDS = Regex(
        """(?i)\b(official|video|audio|lyrics?|visuali[sz]er|m/?v|hd|hq|4k|remaster(ed)?|explicit|clean|feat\.?|ft\.?|featuring)\b"""
    )
    private val UNBRACKETED_FEAT = Regex("""(?i)\s+(?:feat\.?|ft\.?|featuring)\s+.*?(?=\s+[-–—]\s+|$)""")
    private val VERSION_SUFFIX = Regex(
        """(?i)\s+[-–—]\s+[^-–—]*\b(remaster(ed)?|radio edit|single version|mono|stereo)\b.*$"""
    )
    private val PIPE_SUFFIX = Regex("""\s*[|｜].*$""")
    private val DASH = Regex("""\s+[-–—]\s+""")
    private val ARTIST_SUFFIX = Regex("""(?i)(\s*-\s*topic|vevo|\s+official)$""")
    private val ARTIST_JOINERS = Regex("""(?i)\s*(?:,|&|\bx\b|\band\b)\s*""")

    fun queries(rawTitle: String, rawArtist: String): List<Query> {
        val title = cleanTitle(rawTitle)
        val artist = cleanArtist(rawArtist)
        val out = linkedSetOf<Query>()

        val dash = title.split(DASH, limit = 2)
        val split = if (dash.size == 2) Query(dash[1].trim(), dash[0].trim()) else null
        // Prefer the "Artist - Song" split when the left side looks like the channel/artist.
        if (split != null && (artist.isBlank() || sameArtist(split.artist, artist))) {
            out += split
        }
        out += Query(title, artist)
        split?.let { out += it }

        val primary = artist.split(ARTIST_JOINERS).first().trim()
        if (primary.isNotEmpty() && primary != artist) out += Query(title, primary)

        return out.filter { it.title.isNotBlank() }
    }

    fun cleanTitle(raw: String): String {
        var s = raw
        s = BRACKETED.replace(s) { m -> if (NOISE_WORDS.containsMatchIn(m.groupValues[1])) "" else m.value }
        s = UNBRACKETED_FEAT.replace(s, "")
        s = VERSION_SUFFIX.replace(s, "")
        s = PIPE_SUFFIX.replace(s, "")
        return s.trim().trim('-', '–', '—').trim()
    }

    /** Channel names often drop spaces ("TheBandVEVO"), so compare letters and digits only. */
    private fun sameArtist(a: String, b: String): Boolean {
        val x = a.filter(Char::isLetterOrDigit).lowercase()
        val y = b.filter(Char::isLetterOrDigit).lowercase()
        return x.isNotEmpty() && y.isNotEmpty() && (x.contains(y) || y.contains(x))
    }

    fun cleanArtist(raw: String): String = ARTIST_SUFFIX.replace(raw.trim(), "").trim()
}
