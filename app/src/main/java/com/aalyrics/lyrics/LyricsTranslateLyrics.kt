package com.aalyrics.lyrics

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Plain-text lyrics from lyricstranslate.com, strong for Kurdish songs. Last-resort source: used
 * only when YouTube Music and LRCLIB have nothing. It stays off the site's search pages (which
 * its robots.txt asks programs to avoid) and goes through the public artist page instead:
 *   /en/<artist-slug>-lyrics.html  ->  link whose text matches the title  ->  song page
 * Lyrics there have no timing; they pre-fill the tap-to-sync editor.
 * Blocking; call from a background thread.
 */
class LyricsTranslateLyrics {
    /** The site answered with a bot challenge instead of the page; try again some other time. */
    class BlockedException : IOException("LyricsTranslate asked for a browser check")

    fun find(title: String, artist: String): String? {
        val query = TrackCleaner.queries(title, artist).firstOrNull() ?: return null
        if (query.artist.isBlank()) return null
        val slug = slugify(query.artist)
        val artistHtml = get("$BASE/en/${encodePath(slug)}-lyrics.html") ?: return null
        val path = findSongPath(artistHtml, slug, query.title) ?: return null
        return fromUrl("$BASE$path")
    }

    /** Lyrics from a song page the user pasted into the editor; null for other sites. */
    fun fromUrl(url: String): String? {
        val parsed = runCatching { URL(url.trim()) }.getOrNull() ?: return null
        if (parsed.host.removePrefix("www.") != "lyricstranslate.com") return null
        return parseSongText(get(parsed.toString()) ?: return null)
    }

    /** Page HTML, or null for 404. */
    private fun get(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 8_000
        conn.setRequestProperty("User-Agent", USER_AGENT)
        try {
            when (conn.responseCode) {
                200 -> Unit
                404 -> return null
                403, 503 -> throw BlockedException()
                else -> throw IOException("LyricsTranslate HTTP ${conn.responseCode}")
            }
            val html = conn.inputStream.bufferedReader().use { it.readText() }
            if ("cf-chl" in html || "Just a moment..." in html) throw BlockedException()
            return html
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val BASE = "https://lyricstranslate.com"
        private const val USER_AGENT = "AALyrics/0.4 (personal Android Auto lyrics app)"

        /** "Ardalan Bakr" -> "ardalan-bakr", keeping non-Latin letters as the site does. */
        fun slugify(name: String): String =
            name.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-')

        private fun encodePath(slug: String) = URLEncoder.encode(slug, "UTF-8").replace("+", "%20")

        /** Href of the song on an artist page whose link text matches [title]. */
        fun findSongPath(artistHtml: String, artistSlug: String, title: String): String? {
            val want = normalize(title)
            if (want.isEmpty()) return null
            val link = Regex("<a href=\"(/en/[^\"]+-lyrics\\.html)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
            // Each song can be named three ways: link text (often native script), a Latin spelling
            // in brackets, and the address slug (which may use yet another spelling).
            val candidates = link.findAll(artistHtml).mapNotNull { m ->
                val href = m.groupValues[1]
                val decoded = runCatching { URLDecoder.decode(href, "UTF-8") }.getOrDefault(href)
                val prefix = "/en/$artistSlug-"
                if (!decoded.startsWith(prefix)) return@mapNotNull null
                val text = unescape(m.groupValues[2].replace(Regex("<[^>]+>"), "")).trim()
                val names = listOf(
                    text.substringBefore("("),
                    text.substringAfter("(", "").substringBefore(")"),
                    text,
                    decoded.removePrefix(prefix).removeSuffix("-lyrics.html").replace('-', ' '),
                ).map(::normalize).filter { it.isNotEmpty() }
                href to names
            }.toList()
            return (candidates.firstOrNull { (_, names) -> names.any { it == want } }
                ?: candidates.firstOrNull { (_, names) -> names.any { it.startsWith(want) || (it.length >= 4 && want.startsWith(it)) } })
                ?.first
        }

        /** Lyric lines from a song page's #song-body, stanzas separated by blank lines. */
        fun parseSongText(html: String): String? {
            val start = html.indexOf("id=\"song-body\"").takeIf { it >= 0 } ?: return null
            val endMarkers = listOf("id=\"song-transliteration\"", "id=\"song-video", "class=\"song-actions")
            val end = endMarkers.map { html.indexOf(it, start) }.filter { it > start }.minOrNull()
                ?: (start + 50_000).coerceAtMost(html.length)
            val body = html.substring(start, end)
            val lineDiv = Regex("<div class=\"ll-[^\"]*\"[^>]*>(.*?)</div>", RegexOption.DOT_MATCHES_ALL)
            // Each stanza is a "par" block; join stanzas with a blank line.
            val stanzas = body.split("class=\"par\"").map { block ->
                lineDiv.findAll(block).map { m ->
                    unescape(m.groupValues[1].replace(Regex("<br\\s*/?>"), " ").replace(Regex("<[^>]+>"), "")).trim()
                }.filter { it.isNotEmpty() }.joinToString("\n")
            }.filter { it.isNotEmpty() }
            return stanzas.joinToString("\n\n").takeIf { it.isNotEmpty() }
        }

        /** Lowercase letters and digits only, accents removed ("Dêmewe" -> "demewe"). */
        private fun normalize(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "").filter { it.isLetterOrDigit() }

        private fun unescape(s: String): String = s
            .replace(Regex("&#x([0-9a-fA-F]+);")) { it.groupValues[1].toInt(16).toChar().toString() }
            .replace(Regex("&#(\\d+);")) { it.groupValues[1].toInt().toChar().toString() }
            .replace("&nbsp;", " ").replace("&quot;", "\"").replace("&apos;", "'")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
    }
}
