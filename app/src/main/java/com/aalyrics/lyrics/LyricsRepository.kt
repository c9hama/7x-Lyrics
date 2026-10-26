package com.aalyrics.lyrics

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs

sealed interface Lyrics {
    /** [sourceDurationMs] is the length of the recording the timings were made for (0 if unknown). */
    data class Synced(val lines: List<LyricLine>, val sourceDurationMs: Long = 0L) : Lyrics
    data class Plain(val text: String) : Lyrics
    data object Instrumental : Lyrics
    data object NotFound : Lyrics
}

/** Finds lyrics on LRCLIB, caching hits forever and misses for a few days on disk. */
class LyricsRepository(context: Context, private val client: LrcLibClient = LrcLibClient()) {
    private val dir = File(context.filesDir, "lyrics").apply { mkdirs() }

    suspend fun find(title: String, artist: String, durationMs: Long): Lyrics = withContext(Dispatchers.IO) {
        val file = File(dir, sha1("${title.lowercase()}|${artist.lowercase()}") + ".json")
        readCache(file)?.let { return@withContext it }

        val record = lookup(title, artist, durationMs)
        writeCache(file, record)
        record.toLyrics()
    }

    private fun lookup(title: String, artist: String, durationMs: Long): LrcLibRecord? {
        val durationSec = durationMs.takeIf { it > 0 }?.div(1000.0)
        val queries = TrackCleaner.queries(title, artist)
        for (q in queries) {
            best(client.search(trackName = q.title, artistName = q.artist), durationSec)?.let { return it }
        }
        val first = queries.firstOrNull() ?: return null
        return best(client.search(query = "${first.artist} ${first.title}".trim()), durationSec)
    }

    /** Prefers synced over plain over instrumental, then the closest duration. */
    private fun best(candidates: List<LrcLibRecord>, durationSec: Double?): LrcLibRecord? =
        candidates
            .filter { durationSec == null || abs(it.durationSec - durationSec) <= MAX_DURATION_DIFF_SEC }
            .filter { it.syncedLyrics != null || it.plainLyrics != null || it.instrumental }
            .minWithOrNull(
                compareBy<LrcLibRecord>(
                    { if (it.syncedLyrics != null) 0 else if (it.plainLyrics != null) 1 else 2 },
                    { durationSec?.let { d -> abs(it.durationSec - d) } ?: 0.0 },
                )
            )

    private fun readCache(file: File): Lyrics? {
        if (!file.exists()) return null
        return try {
            val o = JSONObject(file.readText())
            if (o.optBoolean("notFound")) {
                val fresh = System.currentTimeMillis() - o.optLong("at") < MISS_TTL_MS
                if (fresh) Lyrics.NotFound else null
            } else {
                LrcLibRecord.fromJson(o).toLyrics()
            }
        } catch (e: Exception) {
            file.delete()
            null
        }
    }

    private fun writeCache(file: File, record: LrcLibRecord?) {
        val json = record?.toJson() ?: JSONObject().put("notFound", true).put("at", System.currentTimeMillis())
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
