package com.aalyrics.lyrics

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class LrcLibRecord(
    val id: Long,
    val trackName: String,
    val artistName: String,
    val durationSec: Double,
    val instrumental: Boolean,
    val plainLyrics: String?,
    val syncedLyrics: String?,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("trackName", trackName)
        .put("artistName", artistName)
        .put("duration", durationSec)
        .put("instrumental", instrumental)
        .put("plainLyrics", plainLyrics ?: JSONObject.NULL)
        .put("syncedLyrics", syncedLyrics ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject) = LrcLibRecord(
            id = o.optLong("id"),
            trackName = o.nullableString("trackName").orEmpty(),
            artistName = o.nullableString("artistName").orEmpty(),
            durationSec = o.optDouble("duration", 0.0),
            instrumental = o.optBoolean("instrumental"),
            plainLyrics = o.nullableString("plainLyrics"),
            syncedLyrics = o.nullableString("syncedLyrics"),
        )

        private fun JSONObject.nullableString(key: String): String? =
            if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
    }
}

/** LRCLIB answered with an error; 429/503 mean "slow down", optionally with a Retry-After. */
class LrcLibHttpException(val code: Int, val retryAfterSec: Int?) : IOException("LRCLIB HTTP $code") {
    val isRateLimit get() = code == 429 || code == 503
}

/** Minimal blocking client for https://lrclib.net. Call from a background thread. */
class LrcLibClient(private val baseUrl: String = "https://lrclib.net") {

    fun search(trackName: String? = null, artistName: String? = null, query: String? = null): List<LrcLibRecord> {
        val params = buildList {
            query?.let { add("q" to it) }
            trackName?.let { add("track_name" to it) }
            artistName?.takeIf { it.isNotBlank() }?.let { add("artist_name" to it) }
        }
        val qs = params.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val conn = URL("$baseUrl/api/search?$qs").openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 8_000
        conn.setRequestProperty("User-Agent", USER_AGENT)
        try {
            if (conn.responseCode != 200) {
                throw LrcLibHttpException(conn.responseCode, conn.getHeaderField("Retry-After")?.toIntOrNull())
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            // Mobile networks sometimes answer with a portal or truncated page instead of JSON;
            // treat that like a network failure rather than letting a JSONException escape.
            try {
                val arr = JSONArray(body)
                return List(arr.length()) { LrcLibRecord.fromJson(arr.getJSONObject(it)) }
            } catch (e: JSONException) {
                throw IOException("LRCLIB returned an unexpected response", e)
            }
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val USER_AGENT = "7xLyrics/0.0.5 (personal Android Auto lyrics app; 7xdevs.com)"
    }
}
