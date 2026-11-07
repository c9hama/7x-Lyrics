package com.aalyrics.car

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.SystemClock
import android.util.Log
import com.aalyrics.LyricsEngine
import com.aalyrics.R
import com.aalyrics.lyrics.LrcParser
import com.aalyrics.lyrics.LyricLine
import com.aalyrics.lyrics.Lyrics
import com.aalyrics.source.NowPlayingTracker
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Made-up songs (original lyrics, generated covers) for README screenshots, so no real lyrics
 * or artwork end up in the repository. Debug builds only, switched on by a file:
 *   adb shell run-as com.aalyrics.view sh -c 'echo en > files/demo'   (en | ar | sync)
 *   adb shell run-as com.aalyrics.view rm files/demo
 */
object DemoMode {
    private data class Song(
        val title: String,
        val artist: String,
        val durationMs: Long,
        val startMs: Long,
        val hue: Float,
        val lyrics: String,
    )

    private val songs = mapOf(
        "en" to Song(
            "Paper Boats", "The Examples", 214_000, 71_000, 212f, """
            [00:58.00]We folded every letter into paper boats
            [01:03.50]And let the river carry what we never wrote
            [01:09.00]Hold on, the current knows the way
            [01:13.50]Paper boats, paper boats, drifting into day
            [01:18.50]Every word we couldn't say
            [01:22.00]Floating somewhere far away
            [01:26.00]Ink on the water, ink on the sky
            [01:31.00]Folding the summer into a goodbye
            """.trimIndent()
        ),
        "ar" to Song(
            "درب الليل", "The Examples", 236_000, 47_400, 28f, """
            [00:38.00]نمشي على الطريق والليل طويل
            [00:42.50]والقمر يرافقنا في كل ميل
            [00:47.00]يا قلبي غنّي للأيام الجميلة
            [00:52.00]خلّي الأغاني تقرّب المسافة
            [00:56.50]نرجع للبيت مع أول ضوء
            [01:01.00]والطريق يحفظ كل الحكايات
            """.trimIndent()
        ),
        "sync" to Song(
            "Neon Harbor", "Glass Atlas", 197_000, 33_500, 330f, """
            [00:24.00]Lights on the water, slow and gold
            [00:28.50]Every engine humming songs we used to know
            [00:33.00]Turn the corner, there's the sea
            [00:37.50]Neon harbor, wait for me
            [00:42.00]Count the stars along the pier
            [00:46.50]All the summers end up here
            """.trimIndent()
        ),
    )

    private const val LOOP_MS = 12_000L
    private val covers = HashMap<String, Bitmap>()
    private val startedAt = HashMap<String, Long>()

    /**
     * [real] frames normally; demo frames while the flag file exists (checked continuously, so
     * the flag can be switched while the car screen is open).
     */
    fun wrap(
        context: Context,
        real: Flow<Pair<NowPlayingTracker.Source, LyricsEngine.Display>>,
    ): Flow<Pair<NowPlayingTracker.Source, LyricsEngine.Display>> {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return real
        val flag = File(context.filesDir, "demo")
        return channelFlow {
            launch { real.collect { if (!flag.exists()) send(it) } }
            var logged: String? = null
            while (true) {
                if (flag.exists()) {
                    val key = flag.readText().trim().takeIf { it in songs } ?: "en"
                    if (key != logged) { Log.i("DemoMode", "showing demo song: $key"); logged = key }
                    send(frame(key))
                } else {
                    logged = null
                }
                delay(200)
            }
        }
    }

    private fun frame(key: String): Pair<NowPlayingTracker.Source, LyricsEngine.Display> {
        val song = songs.getValue(key)
        val now = SystemClock.elapsedRealtime()
        // Loop over the lyric section so the demo never runs out of lines.
        val position = song.startMs + (now - startedAt.getOrPut(key) { now }) % LOOP_MS
        val lines: List<LyricLine> = LrcParser.parse(song.lyrics)
        val metadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, song.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, song.artist)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, song.durationMs)
            .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, covers.getOrPut(key) { cover(song.hue) })
            .build()
        val icon = R.drawable.ic_stat_lyrics
        val playback = PlaybackState.Builder()
            .setState(PlaybackState.STATE_PLAYING, position, 1f, now)
            .addCustomAction(PlaybackState.CustomAction.Builder("like", "Undo like", icon).build())
            .addCustomAction(PlaybackState.CustomAction.Builder("shuffle", "Shuffle on", icon).build())
            .addCustomAction(PlaybackState.CustomAction.Builder("repeat", "Repeat off", icon).build())
            .addCustomAction(PlaybackState.CustomAction.Builder("dislike", "Dislike", icon).build())
            .build()
        val source = NowPlayingTracker.Source(listening = true, metadata = metadata, playback = playback)
        // "sync": lyrics timed for a recording 6s shorter, so the timing warning shows.
        val sourceDuration = if (key == "sync") song.durationMs - 6_000 else song.durationMs
        val display = LyricsEngine.Display(
            track = LyricsEngine.Track(song.title, song.artist, song.durationMs),
            status = LyricsEngine.Status.Ready(Lyrics.Synced(lines, sourceDuration)),
            lineIndex = LrcParser.indexAt(lines, position + LyricsEngine.LEAD_MS),
        )
        return source to display
    }

    /** An abstract generated cover: soft gradient, a glow and a few rings in one hue family. */
    private fun cover(hue: Float): Bitmap {
        val size = 512
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        fun hsv(h: Float, s: Float, v: Float) = Color.HSVToColor(floatArrayOf((h + 360f) % 360f, s, v))
        p.shader = LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), hsv(hue - 20, 0.75f, 0.25f), hsv(hue + 25, 0.65f, 0.85f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), p)
        p.shader = RadialGradient(size * 0.62f, size * 0.38f, size * 0.42f, hsv(hue + 40, 0.35f, 1f), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawCircle(size * 0.62f, size * 0.38f, size * 0.42f, p)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.color = Color.argb(90, 255, 255, 255)
        for (i in 1..5) {
            p.strokeWidth = 3f + i
            c.drawCircle(size * 0.32f, size * 0.7f, i * 34f, p)
        }
        return bmp
    }
}
