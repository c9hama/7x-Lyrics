package com.aalyrics.lyrics

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.math.abs

sealed interface Lyrics {
    /** [sourceDurationMs] is the length of the recording the timings were made for (0 if unknown). */
    data class Synced(val lines: List<LyricLine>, val sourceDurationMs: Long = 0L) : Lyrics
    data class Plain(val text: String) : Lyrics
    data object Instrumental : Lyrics
    data object NotFound : Lyrics
}

/**
 * Finds lyrics and caches them on disk. Sources, best first:
 *  1. lyrics the user synced themselves (tap-to-sync editor),
 *  2. YouTube Music's time-synced lyrics (timed to the exact recording being played),
 *  3. LRCLIB,
 *  4. unsynced text from either, or from LyricsTranslate as a last resort, shown as
 *     "only unsynced lyrics" and used to start tap-to-sync.
 *
 * The LRCLIB cache keeps every candidate version found for a song, not just one pick.
 * The cache keeps every candidate version found for a song, not just one pick. Songs fetched
 * ahead of time (from the queue, which has no durations) can then still choose the version
 * matching the recording's length when they actually play. Misses are cached for a few days.
 */
class LyricsRepository(
    context: Context,
    private val client: LrcLibClient = LrcLibClient(),
    private val youTube: YouTubeMusicLyrics = YouTubeMusicLyrics(),
    private val lyricsTranslate: LyricsTranslateLyrics = LyricsTranslateLyrics(),
) {
    private val dir = File(context.filesDir, "lyrics").apply { mkdirs() }
    private val userDir = File(context.filesDir, "user_lyrics").apply { mkdirs() }

    private sealed interface Cached {
        /** [searched]: a full, length-aware lookup was done (vs. a quick prefetch). */
        data class Hit(val candidates: List<LrcLibRecord>, val searched: Boolean) : Cached
        data object Miss : Cached
    }

    suspend fun find(title: String, artist: String, durationMs: Long): Lyrics = withContext(Dispatchers.IO) {
        userLyrics(title, artist)?.let { Log.d(TAG, "user lyrics"); return@withContext it }
        var offline: IOException? = null
        val fromYouTube = try { youTubeLyrics(title, artist, durationMs) } catch (e: IOException) { offline = e; null }
        if (fromYouTube is Lyrics.Synced) { Log.d(TAG, "YouTube Music synced"); return@withContext fromYouTube }
        val fromLrcLib = try { lrcLibLyrics(title, artist, durationMs) } catch (e: IOException) { offline = offline ?: e; null }
        val (lyrics, source) = when {
            fromLrcLib is Lyrics.Synced || fromLrcLib is Lyrics.Instrumental || fromLrcLib is Lyrics.Plain -> fromLrcLib to "LRCLIB"
            fromYouTube is Lyrics.Plain -> fromYouTube to "YouTube Music plain"
            // Last resort, and only when we're online (offline errors fall through to below).
            offline == null -> (lyricsTranslateText(title, artist)?.let { Lyrics.Plain(it) to "LyricsTranslate plain" }
                ?: (fromLrcLib ?: Lyrics.NotFound) to "none")
            fromLrcLib != null -> fromLrcLib to "none"
            offline != null -> throw offline
            else -> Lyrics.NotFound to "none"
        }
        Log.d(TAG, "$source ${lyrics::class.simpleName}")
        lyrics
    }

    // ---- User-synced lyrics ----------------------------------------------------------------

    /** Saves lyrics synced in the tap-to-sync editor; they take priority over every source. */
    fun saveUserLyrics(title: String, artist: String, lines: List<LyricLine>) {
        userFile(title, artist).writeText(lines.joinToString("\n") { "[${formatTag(it.timeMs)}]${it.text}" })
    }

    fun deleteUserLyrics(title: String, artist: String) = userFile(title, artist).delete()

    fun hasUserLyrics(title: String, artist: String) = userFile(title, artist).exists()

    private fun userLyrics(title: String, artist: String): Lyrics.Synced? {
        val file = userFile(title, artist).takeIf { it.exists() } ?: return null
        return LrcParser.parse(file.readText()).takeIf { it.isNotEmpty() }?.let { Lyrics.Synced(it) }
    }

    private fun userFile(title: String, artist: String) = File(userDir, key(title, artist) + ".lrc")

    private fun formatTag(ms: Long) = "%02d:%02d.%02d".format(ms / 60_000, ms / 1000 % 60, ms / 10 % 100)

    /** Unsynced text known for a song (from any source), to start the tap-to-sync editor with. */
    fun plainTextFor(title: String, artist: String): String? {
        readYouTubeCache(title, artist)?.let { if (it is Lyrics.Plain) return it.text }
        readLyricsTranslateCache(title, artist)?.let { return it }
        val cached = readCache(cacheFile(title, artist)) as? Cached.Hit ?: return null
        return cached.candidates.firstNotNullOfOrNull { it.plainLyrics }
    }

    // ---- LyricsTranslate (last resort, plain text) ----------------------------------------

    /** Cached text, or a fresh lookup; a bot challenge isn't cached, so it's retried later. */
    private fun lyricsTranslateText(title: String, artist: String): String? {
        val file = lyricsTranslateFile(title, artist)
        if (file.exists()) return readLyricsTranslateCache(title, artist)
        val text = try {
            lyricsTranslate.find(title, artist)
        } catch (e: IOException) {
            Log.d(TAG, "LyricsTranslate unavailable: ${e.message}")
            return null
        }
        file.writeText((text?.let { JSONObject().put("plain", it) } ?: JSONObject().put("none", true).put("at", System.currentTimeMillis())).toString())
        return text
    }

    private fun readLyricsTranslateCache(title: String, artist: String): String? {
        val file = lyricsTranslateFile(title, artist).takeIf { it.exists() } ?: return null
        val o = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return null.also { file.delete() }
        if (!o.has("plain") && System.currentTimeMillis() - o.optLong("at") >= MISS_TTL_MS) file.delete()
        return o.optString("plain").takeIf { o.has("plain") && it.isNotBlank() }
    }

    /** Text from a LyricsTranslate song link the user pasted in the editor (and caches it). */
    fun lyricsTranslateFromLink(title: String, artist: String, url: String): String? {
        val text = lyricsTranslate.fromUrl(url) ?: return null
        lyricsTranslateFile(title, artist).writeText(JSONObject().put("plain", text).toString())
        return text
    }

    private fun lyricsTranslateFile(title: String, artist: String) = File(dir, key(title, artist) + ".lt.json")

    // ---- YouTube Music ---------------------------------------------------------------------

    private fun youTubeLyrics(title: String, artist: String, durationMs: Long): Lyrics {
        readYouTubeCache(title, artist)?.let { return it }
        val result = youTube.find(title, artist, durationMs)
        val json = when (result) {
            is YouTubeMusicLyrics.Result.Synced -> JSONObject().put("duration", result.durationMs)
                .put("lines", JSONArray(result.lines.map { JSONArray().put(it.timeMs).put(it.text) }))
            is YouTubeMusicLyrics.Result.Plain -> JSONObject().put("plain", result.text)
            null -> JSONObject().put("none", true).put("at", System.currentTimeMillis())
        }
        youTubeFile(title, artist).writeText(json.toString())
        return readYouTubeCache(title, artist) ?: Lyrics.NotFound
    }

    private fun readYouTubeCache(title: String, artist: String): Lyrics? {
        val file = youTubeFile(title, artist).takeIf { it.exists() } ?: return null
        return try {
            val o = JSONObject(file.readText())
            when {
                o.has("lines") -> {
                    val arr = o.getJSONArray("lines")
                    Lyrics.Synced(List(arr.length()) { LyricLine(arr.getJSONArray(it).getLong(0), arr.getJSONArray(it).getString(1)) }, o.optLong("duration"))
                }
                o.has("plain") -> Lyrics.Plain(o.getString("plain"))
                System.currentTimeMillis() - o.optLong("at") < MISS_TTL_MS -> Lyrics.NotFound
                else -> null
            }
        } catch (e: Exception) {
            file.delete()
            null
        }
    }

    private fun youTubeFile(title: String, artist: String) = File(dir, key(title, artist) + ".yt.json")

    // ---- LRCLIB ----------------------------------------------------------------------------

    private fun lrcLibLyrics(title: String, artist: String, durationMs: Long): Lyrics {
        val durationSec = durationMs.takeIf { it > 0 }?.div(1000.0)
        val file = cacheFile(title, artist)
        val cached = readCache(file)
        if (cached is Cached.Miss) return Lyrics.NotFound
        val known = (cached as? Cached.Hit)?.candidates.orEmpty()

        best(known, durationSec)?.let { return it.toLyrics() }
        // Nothing cached matches this recording's length. If we already searched properly, use
        // the closest version (the timing warning and sync adjust cover the difference).
        if (cached is Cached.Hit && cached.searched) return best(known, null).toLyrics()

        val fresh = try {
            lookup(title, artist, durationSec)
        } catch (e: IOException) {
            // Offline: the closest cached version beats nothing.
            if (known.isNotEmpty()) return best(known, null).toLyrics()
            throw e
        }
        val all = (known + fresh).distinctBy { it.id }
        writeCache(file, all, searched = true)
        return (best(all, durationSec) ?: best(all, null)).toLyrics()
    }

    /** True when this song is already cached (hit or recent miss), so prefetching can skip it. */
    fun isCached(title: String, artist: String): Boolean = readCache(cacheFile(title, artist)) != null

    /**
     * Fetches a queued song's candidates ahead of time (length unknown). Returns true if the
     * network was used, so the caller can pace requests.
     */
    fun prefetch(title: String, artist: String): Boolean {
        val file = cacheFile(title, artist)
        var used = false
        if (readYouTubeCache(title, artist) == null) {
            runCatching { youTubeLyrics(title, artist, 0L) }
            used = true
        }
        if (readCache(file) == null) {
            writeCache(file, lookup(title, artist, durationSec = null), searched = false)
            used = true
        }
        return used
    }

    /**
     * Usable candidates for a song. With a known length it keeps trying query variants until
     * one matches; without (prefetch) it stops at the first variant that finds anything.
     */
    private fun lookup(title: String, artist: String, durationSec: Double?): List<LrcLibRecord> {
        val seen = LinkedHashMap<Long, LrcLibRecord>()
        val queries = TrackCleaner.queries(title, artist)
        for (q in queries) {
            val found = client.search(trackName = q.title, artistName = q.artist).filter(::usable)
            found.forEach { seen.putIfAbsent(it.id, it) }
            if (best(found, durationSec) != null) return seen.values.toList()
        }
        queries.firstOrNull()?.let { first ->
            client.search(query = "${first.artist} ${first.title}".trim()).filter(::usable)
                .forEach { seen.putIfAbsent(it.id, it) }
        }
        return seen.values.toList()
    }

    private fun usable(r: LrcLibRecord) = r.syncedLyrics != null || r.plainLyrics != null || r.instrumental

    /** Prefers synced over plain over instrumental, then the closest duration. */
    private fun best(candidates: List<LrcLibRecord>, durationSec: Double?): LrcLibRecord? =
        candidates
            .filter { durationSec == null || abs(it.durationSec - durationSec) <= MAX_DURATION_DIFF_SEC }
            .minWithOrNull(
                compareBy<LrcLibRecord>(
                    { if (it.syncedLyrics != null) 0 else if (it.plainLyrics != null) 1 else 2 },
                    { durationSec?.let { d -> abs(it.durationSec - d) } ?: 0.0 },
                )
            )

    private fun key(title: String, artist: String) = sha1("${title.lowercase()}|${artist.lowercase()}")

    private fun cacheFile(title: String, artist: String) = File(dir, key(title, artist) + ".json")

    private fun readCache(file: File): Cached? {
        if (!file.exists()) return null
        return try {
            val o = JSONObject(file.readText())
            when {
                o.optBoolean("notFound") ->
                    if (System.currentTimeMillis() - o.optLong("at") < MISS_TTL_MS) Cached.Miss else null
                o.has("candidates") -> {
                    val arr = o.getJSONArray("candidates")
                    Cached.Hit(List(arr.length()) { LrcLibRecord.fromJson(arr.getJSONObject(it)) }, o.optBoolean("searched"))
                }
                // Older cache files hold a single chosen record.
                else -> Cached.Hit(listOf(LrcLibRecord.fromJson(o)), searched = true)
            }
        } catch (e: Exception) {
            file.delete()
            null
        }
    }

    private fun writeCache(file: File, candidates: List<LrcLibRecord>, searched: Boolean) {
        val json = if (candidates.isEmpty()) {
            JSONObject().put("notFound", true).put("at", System.currentTimeMillis())
        } else {
            JSONObject().put("searched", searched).put("candidates", JSONArray(candidates.map { it.toJson() }))
        }
        file.writeText(json.toString())
    }

    private fun LrcLibRecord?.toLyrics(): Lyrics = when {
        this == null -> Lyrics.NotFound
        syncedLyrics != null -> LrcParser.parse(syncedLyrics).takeIf { it.isNotEmpty() }
            ?.let { Lyrics.Synced(it, (durationSec * 1000).toLong()) }
            ?: plainLyrics?.let { Lyrics.Plain(it) } ?: Lyrics.NotFound
        plainLyrics != null -> Lyrics.Plain(plainLyrics)
        instrumental -> Lyrics.Instrumental
        else -> Lyrics.NotFound
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "LyricsSource"
        const val MAX_DURATION_DIFF_SEC = 4.0
        const val MISS_TTL_MS = 3L * 24 * 60 * 60 * 1000
    }
}
