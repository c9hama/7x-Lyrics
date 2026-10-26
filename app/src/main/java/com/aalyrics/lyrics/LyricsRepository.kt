package com.aalyrics.lyrics

import android.content.Context
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
 * Finds lyrics on LRCLIB and caches them on disk.
 *
 * The cache keeps every candidate version found for a song, not just one pick. Songs fetched
 * ahead of time (from the queue, which has no durations) can then still choose the version
 * matching the recording's length when they actually play. Misses are cached for a few days.
 */
class LyricsRepository(context: Context, private val client: LrcLibClient = LrcLibClient()) {
    private val dir = File(context.filesDir, "lyrics").apply { mkdirs() }

    private sealed interface Cached {
        /** [searched]: a full, length-aware lookup was done (vs. a quick prefetch). */
        data class Hit(val candidates: List<LrcLibRecord>, val searched: Boolean) : Cached
        data object Miss : Cached
    }

    suspend fun find(title: String, artist: String, durationMs: Long): Lyrics = withContext(Dispatchers.IO) {
        val durationSec = durationMs.takeIf { it > 0 }?.div(1000.0)
        val file = cacheFile(title, artist)
        val cached = readCache(file)
        if (cached is Cached.Miss) return@withContext Lyrics.NotFound
        val known = (cached as? Cached.Hit)?.candidates.orEmpty()

        best(known, durationSec)?.let { return@withContext it.toLyrics() }
        // Nothing cached matches this recording's length. If we already searched properly, use
        // the closest version (the timing warning and sync adjust cover the difference).
        if (cached is Cached.Hit && cached.searched) return@withContext best(known, null).toLyrics()

        val fresh = try {
            lookup(title, artist, durationSec)
        } catch (e: IOException) {
            // Offline: the closest cached version beats nothing.
            if (known.isNotEmpty()) return@withContext best(known, null).toLyrics()
            throw e
        }
        val all = (known + fresh).distinctBy { it.id }
        writeCache(file, all, searched = true)
        (best(all, durationSec) ?: best(all, null)).toLyrics()
    }

    /** True when this song is already cached (hit or recent miss), so prefetching can skip it. */
    fun isCached(title: String, artist: String): Boolean = readCache(cacheFile(title, artist)) != null

    /**
     * Fetches a queued song's candidates ahead of time (length unknown). Returns true if the
     * network was used, so the caller can pace requests.
     */
    fun prefetch(title: String, artist: String): Boolean {
        val file = cacheFile(title, artist)
        if (readCache(file) != null) return false
        writeCache(file, lookup(title, artist, durationSec = null), searched = false)
        return true
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

    private fun cacheFile(title: String, artist: String) =
        File(dir, sha1("${title.lowercase()}|${artist.lowercase()}") + ".json")

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
        const val MAX_DURATION_DIFF_SEC = 4.0
        const val MISS_TTL_MS = 3L * 24 * 60 * 60 * 1000
    }
}
