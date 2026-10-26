package com.aalyrics.lyrics

import org.json.JSONArray
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
            if (conn.responseCode != 200) throw IOException("LRCLIB HTTP ${conn.responseCode}")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONArray(body)
            return List(arr.length()) { LrcLibRecord.fromJson(arr.getJSONObject(it)) }
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val USER_AGENT = "AALyrics/0.1 (personal Android Auto lyrics app)"
    }
}
