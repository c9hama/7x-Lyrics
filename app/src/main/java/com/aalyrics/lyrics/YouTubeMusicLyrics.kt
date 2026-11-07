package com.aalyrics.lyrics

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

/**
 * Lyrics from YouTube Music itself (licensed from Musixmatch), via the private "InnerTube" API
 * its apps use. Search finds the song, `next` gives its lyrics page id, and browsing that page
 * as the Android app returns time-synced lines when YouTube has them (plain text otherwise).
 *
 * Unofficial: Google can change it at any time, so every failure just means "no lyrics here".
 * Blocking; call from a background thread.
 */
class YouTubeMusicLyrics {
    /** One song row from search results; [durationMs] is 0 when not shown. */
    data class Row(val videoId: String, val title: String, val artist: String, val durationMs: Long)

    sealed interface Result {
        data class Synced(val lines: List<LyricLine>, val durationMs: Long) : Result
        data class Plain(val text: String) : Result
    }

    fun find(title: String, artist: String, durationMs: Long): Result? {
        val query = TrackCleaner.queries(title, artist).firstOrNull() ?: return null
        val rows = search("${query.title} ${query.artist}".trim())
        val row = pickBest(rows, query.title, query.artist, durationMs) ?: return null
        val pageId = lyricsPageId(row.videoId) ?: return null
        timedLines(pageId)?.takeIf { it.isNotEmpty() }?.let { return Result.Synced(it, row.durationMs) }
        return plainText(pageId)?.let { Result.Plain(it) }
    }

    private fun search(query: String): List<Row> {
        val response = post("search", JSONObject().put("query", query).put("params", SONGS_ONLY), WEB, WEB_UA)
        val rows = mutableListOf<Row>()
        findAll(response, "musicResponsiveListItemRenderer").forEach { item ->
            item as JSONObject
            val videoId = item.optJSONObject("playlistItemData")?.optString("videoId").orEmpty()
            val columns = item.optJSONArray("flexColumns") ?: return@forEach
            fun column(i: Int): List<String> {
                val runs = columns.optJSONObject(i)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                    ?.optJSONObject("text")?.optJSONArray("runs") ?: return emptyList()
                return List(runs.length()) { runs.getJSONObject(it).optString("text") }
            }
            val name = column(0).joinToString("")
            // Second column: "Artist • Album • 3:47" as separate runs.
            val details = column(1).filter { it.isNotBlank() && it.trim() !in setOf("•", "&", ",") }
            val duration = details.firstNotNullOfOrNull { parseDuration(it) } ?: 0L
            val artistName = details.firstOrNull().orEmpty()
            if (videoId.isNotEmpty() && name.isNotEmpty()) rows += Row(videoId, name, artistName, duration)
        }
        return rows
    }

    private fun lyricsPageId(videoId: String): String? {
        val response = post("next", JSONObject().put("videoId", videoId).put("isAudioOnly", true), WEB, WEB_UA)
        return findAll(response, "browseId").map { it.toString() }.firstOrNull { it.startsWith("MPLY") }
    }

    private fun timedLines(pageId: String): List<LyricLine>? {
        val response = post("browse", JSONObject().put("browseId", pageId), ANDROID, ANDROID_UA)
        val data = findAll(response, "timedLyricsData").firstOrNull() as? JSONArray ?: return null
        return List(data.length()) { i ->
            val entry = data.getJSONObject(i)
            val start = entry.optJSONObject("cueRange")?.optString("startTimeMilliseconds")?.toLongOrNull() ?: 0L
            LyricLine(start, entry.optString("lyricLine").trim().let { if (it == "♪") "" else it })
        }.sortedBy { it.timeMs }
    }

    private fun plainText(pageId: String): String? {
        val response = post("browse", JSONObject().put("browseId", pageId), WEB, WEB_UA)
        val shelf = findAll(response, "musicDescriptionShelfRenderer").firstOrNull() as? JSONObject ?: return null
        val runs = shelf.optJSONObject("description")?.optJSONArray("runs") ?: return null
        return (0 until runs.length()).joinToString("") { runs.getJSONObject(it).optString("text") }.takeIf { it.isNotBlank() }
    }

    private fun post(endpoint: String, body: JSONObject, client: JSONObject, userAgent: String): JSONObject {
        val conn = URL("$BASE$endpoint?prettyPrint=false").openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 8_000
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("User-Agent", userAgent)
        try {
            conn.outputStream.use { it.write(body.put("context", JSONObject().put("client", client)).toString().toByteArray()) }
            if (conn.responseCode != 200) throw IOException("YouTube Music HTTP ${conn.responseCode}")
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } catch (e: org.json.JSONException) {
            throw IOException("YouTube Music returned an unexpected response", e)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val BASE = "https://music.youtube.com/youtubei/v1/"
        /** Search filter for "Songs" (not videos, albums or playlists). */
        private const val SONGS_ONLY = "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"
        private const val WEB_UA = "Mozilla/5.0"
        private const val ANDROID_VERSION = "7.27.52"
        private const val ANDROID_UA = "com.google.android.apps.youtube.music/$ANDROID_VERSION (Linux; U; Android 14) gzip"
        private val WEB get() = JSONObject().put("clientName", "WEB_REMIX").put("clientVersion", "1.20241120.01.00").put("hl", "en")
        private val ANDROID get() = JSONObject().put("clientName", "ANDROID_MUSIC").put("clientVersion", ANDROID_VERSION)
            .put("androidSdkVersion", 34).put("hl", "en")
        private const val MAX_DURATION_DIFF_MS = 4_000L

        /**
         * The row for this song: the title must match (after cleaning), the artist should, and
         * with a known length it must be within 4s, so a cover or live version isn't used.
         */
        fun pickBest(rows: List<Row>, title: String, artist: String, durationMs: Long): Row? {
            val wantTitle = normalize(TrackCleaner.cleanTitle(title))
            val wantArtist = normalize(artist)
            return rows.filter { row ->
                val t = normalize(TrackCleaner.cleanTitle(row.title))
                (t == wantTitle || (wantTitle.length >= 4 && t.isNotEmpty() && (t.contains(wantTitle) || wantTitle.contains(t)))) &&
                    (durationMs <= 0 || row.durationMs <= 0 || abs(row.durationMs - durationMs) <= MAX_DURATION_DIFF_MS)
            }.sortedWith(
                compareBy<Row>(
                    { if (wantArtist.isNotEmpty() && normalize(it.artist).let { a -> a.contains(wantArtist) || wantArtist.contains(a) }) 0 else 1 },
                    { if (durationMs > 0 && it.durationMs > 0) abs(it.durationMs - durationMs) else 0L },
                )
            ).firstOrNull()
        }

        /** "3:47" or "1:02:05" -> milliseconds, else null. */
        fun parseDuration(text: String): Long? {
            val parts = text.trim().split(":")
            if (parts.size !in 2..3 || parts.any { it.isEmpty() || !it.all(Char::isDigit) }) return null
            return parts.fold(0L) { acc, p -> acc * 60 + p.toLong() } * 1000
        }

        private fun normalize(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

        /** Every value stored under [key] anywhere in [node], depth-first. */
        private fun findAll(node: Any?, key: String, out: MutableList<Any> = mutableListOf()): List<Any> {
            when (node) {
                is JSONObject -> node.keys().forEach { k ->
                    val v = node.opt(k)
                    if (k == key && v != null) out += v
                    findAll(v, key, out)
                }
                is JSONArray -> for (i in 0 until node.length()) findAll(node.opt(i), key, out)
            }
            return out
        }
    }
}
