package com.aalyrics.car

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Layout
import android.util.Log
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.TextUtils
import android.view.Surface
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.core.content.edit
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.PathParser
import androidx.core.graphics.scale
import com.aalyrics.CrashLog
import com.aalyrics.LyricsEngine
import com.aalyrics.R
import com.aalyrics.lyrics.Lyrics
import com.aalyrics.lyrics.SweepEstimate
import com.aalyrics.source.NowPlayingTracker
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws the lyrics view onto the car's map surface:
 *
 *   blurred album art backdrop
 *   [art] Title / Artist           (centred header)
 *   faded previous line
 *   CURRENT LINE                   (big, bright, eased between lines)
 *   faded upcoming lines
 *   0:42 ───────●────── 3:15        (centred footer)
 *        ⏮   ( ▶ )   ⏭
 */
class LyricsRenderer(context: Context) : SurfaceCallback {
    enum class Control { PREVIOUS, PLAY_PAUSE, NEXT, LIKE, DISLIKE, SHUFFLE, REPEAT, EARLIER, LATER, RESET, DONE, ADJUST, DISMISS }

    /** Called when one of the footer buttons is tapped. */
    var onControl: ((Control) -> Unit)? = null

    /** Called with a position in ms when the progress bar is tapped. */
    var onSeek: ((Long) -> Unit)? = null

    /**
     * Called when the surface stopped accepting frames without the host saying so (seen after
     * the car display reconnects while the app keeps running). Re-registering asks for a new one.
     */
    var onSurfaceLost: (() -> Unit)? = null
    private var lockFailures = 0

    /** Something other than the progress wave changed, so the next frame repaints everything. */
    private var needsFullFrame = true
    private var lastDriftFrameNanos = 0L
    /** Screen strip holding the progress bar and times; wave-only frames repaint just this. */
    private var waveRect: Rect? = null
    /** Screen area of the line being swept (karaoke highlight); repainted with the wave. */
    private var sweepRect: Rect? = null
    /** Start time of each lyric line, to estimate the karaoke sweep. */
    private var lineStarts: LongArray? = null
    private var karaoke = false
    /** Most lines of the current song are right-to-left (Arabic, Kurdish, Persian, Hebrew...). */
    private var rtlSong = false
    /** Thumbs-up state from the player (null: not offered), with a brief optimistic override. */
    private var liked: Boolean? = null
    private var likeOverride: Boolean? = null
    private var disliked: Boolean? = null
    // Browsing the lyrics by touch: how far the view is moved from the sung line, plus fling.
    private var manualOffset = 0f
    private var flingVelocity = 0f
    private var browseUntil = 0L
    private var browseAmount = 0f
    private val endBrowse = Runnable { requestFrame() }
    /** On-screen rectangle of each visible lyric row, for tap-to-seek. */
    private val lineHits = ArrayList<Pair<RectF, Int>>()
    private var lyricsBounds: RectF? = null
    private var shuffleOn: Boolean? = null
    private var repeatMode: Int? = null
    /** System "Remove animations" is on: keep the background and progress wave still. */
    private val reduceMotion = runCatching {
        android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }.getOrDefault(false)

    private val res = context.resources
    private val prefs = context.getSharedPreferences("lyrics_view", Context.MODE_PRIVATE)
    private var textScale = prefs.getFloat(KEY_TEXT_SCALE, 1f)
    /** Size and background are also set from the phone app (the legacy car screen has no buttons). */
    private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
        when (key) {
            KEY_TEXT_SCALE -> { textScale = p.getFloat(KEY_TEXT_SCALE, 1f); layoutWidth = -1 }
            KEY_MOVING_BACKGROUND -> { movingBackground = p.getBoolean(KEY_MOVING_BACKGROUND, true); stillDirty = true; lastDriftNanos = 0L }
        }
        requestFrame()
    }.also { prefs.registerOnSharedPreferenceChangeListener(it) }

    // Frames are paced with a plain main-thread Handler, not Choreographer: Choreographer follows
    // the phone's display vsync, which the phone may throttle or stop for an app with no visible
    // window there, freezing the car screen while the app keeps running.
    private val frameHandler = Handler(Looper.getMainLooper())
    private val frameRunnable = Runnable { drawFrame(System.nanoTime()) }
    private var frameScheduled = false
    private var frameDelayed = false

    /** Last time a frame reached the screen, and since when the surface has been missing. */
    private var lastDrawUptime = 0L
    private var surfaceMissingSince = 0L
    private val watchdog = object : Runnable {
        override fun run() {
            checkStalled()
            frameHandler.postDelayed(this, WATCHDOG_MS)
        }
    }
    private var lastFrameNanos = 0L

    private var surface: Surface? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var density = 1f
    private var visibleArea: Rect? = null
    private var stableArea: Rect? = null

    // Content
    private var title = ""
    private var artist = ""
    private var message: String? = null
    /** Rows to draw; intro and instrumental breaks show [MUSIC_NOTE], like YouTube Music. */
    private var rows: List<String> = emptyList()
    private var currentRow = -1
    private var playback: PlaybackState? = null
    private var durationMs = 0L

    // Artwork
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var artToken = 0
    private var artKey: String? = null
    private var backdrop: Bitmap? = null
    private var previousBackdrop: Bitmap? = null
    private var backdropFade = 1f
    private var thumb: Bitmap? = null
    private var thumbShader: BitmapShader? = null
    private var accent = DEFAULT_ACCENT

    // Button/slider colours taken from the cover, faded between songs.
    private var toneFrom = CoverTones.NEUTRAL
    private var toneTo = CoverTones.NEUTRAL
    private var toneFade = 1f
    private val toneLight get() = CoverTones.blend(toneFrom.light, toneTo.light, toneFade)
    private val toneOnLight get() = CoverTones.blend(toneFrom.onLight, toneTo.onLight, toneFade)

    // Lyrics layout and animation
    private var layouts: List<StaticLayout> = emptyList()
    private var layoutWidth = -1
    private var layoutTextSize = -1f
    private var layoutCentered = true
    private var emphasis = FloatArray(0)
    private var scroll = Float.NaN

    // Sync adjustment
    private var syncMode = false
    private var offsetMs = 0L
    private var timingWarning = false
    private val exitSyncMode = Runnable { setSyncMode(false) }

    // Footer controls
    private val controlBounds = mutableMapOf<Control, RectF>()
    private var progressBounds: RectF? = null
    private var progressLeft = 0f
    private var progressWidth = 1f
    private var progressFlipped = false
    /** Position (ms) and time of a tap-to-seek, used until the player reports the new position. */
    private var seekOverride: Pair<Long, Long>? = null
    private var pressed: Control? = null
    private var pressAmount = 0f

    private val bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    private val linePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = bold }
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = bold }
    private val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val timePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { isDither = true }
    private val scrimPaint = Paint().apply { isDither = true }
    private val layerPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val wavePath = Path()
    private var wavePhase = 0f
    /** 1 while playing (full wave), eases to 0 (flat line) when paused. */
    private var waveAmount = 0f
    private val driftScrimPaint = Paint().apply { isDither = true }
    private var driftScrimKey: Pair<Int, Int>? = null
    private var stillCache: Bitmap? = null
    private var stillCanvas: Canvas? = null
    private var stillDirty = true
    private val driftMatrix = Matrix()
    private var driftBuffer: Bitmap? = null
    private var movingCache: Bitmap? = null
    private var movingCacheCanvas: Canvas? = null
    private var driftCanvas: Canvas? = null
    private var driftSeconds = 0f
    private var lastDriftNanos = 0L
    private var movingBackground = prefs.getBoolean(KEY_MOVING_BACKGROUND, true)
    private var scrimKey: Pair<Int, Int>? = null
    /** Average brightness (0..1) of the current backdrop; bright art gets a darker scrim. */
    private var backdropLuma = 0f
    private val shaderMatrix = Matrix()

    // ---- SurfaceCallback -------------------------------------------------------------------

    override fun onSurfaceAvailable(container: SurfaceContainer) {
        surface = container.surface
        lockFailures = 0
        surfaceWidth = container.width
        surfaceHeight = container.height
        density = container.dpi / 160f
        Log.i(TAG, "surface available ${container.width}x${container.height} @${container.dpi}dpi, valid=${surface?.isValid}")
        layoutWidth = -1
        surfaceMissingSince = 0L
        startWatchdog()
        requestFrame()
    }

    override fun onSurfaceDestroyed(container: SurfaceContainer) {
        Log.i(TAG, "surface destroyed")
        surface = null
        surfaceMissingSince = SystemClock.uptimeMillis()
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        Log.i(TAG, "visible area $visibleArea (surface ${surfaceWidth}x$surfaceHeight)")
        this.visibleArea = Rect(visibleArea)
        requestFrame()
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        Log.i(TAG, "stable area $stableArea")
        this.stableArea = Rect(stableArea)
        requestFrame()
    }

    /** True when ([x], [y]) is over the lyrics, so a drag there scrolls them. */
    fun isOverLyrics(x: Float, y: Float) = lyricsBounds?.contains(x, y) == true

    override fun onScroll(distanceX: Float, distanceY: Float) {
        if (rows.isEmpty()) return
        manualOffset += distanceY
        flingVelocity = 0f
        holdBrowse()
    }

    override fun onFling(velocityX: Float, velocityY: Float) {
        if (rows.isEmpty()) return
        flingVelocity = -velocityY
        holdBrowse()
    }

    private fun holdBrowse() {
        browseUntil = SystemClock.uptimeMillis() + BROWSE_HOLD_MS
        main.removeCallbacks(endBrowse)
        main.postDelayed(endBrowse, BROWSE_HOLD_MS + 20)
        requestFrame()
    }

    /** Jumps the song to the start of lyric [row] (row 0 is the intro). */
    private fun seekToRow(row: Int) {
        val starts = lineStarts ?: return
        val lineStart = if (row <= 0) 0L else starts.getOrNull(row - 1) ?: return
        // Land just before the line so it's heard from its start (lines show LEAD_MS early).
        val target = (lineStart - offsetMs - LyricsEngine.LEAD_MS + 30).coerceIn(0L, max(durationMs, 0L))
        seekOverride = target to SystemClock.elapsedRealtime()
        onSeek?.invoke(target)
        manualOffset = 0f
        flingVelocity = 0f
        browseUntil = 0L
        requestFrame()
    }

    override fun onClick(x: Float, y: Float) {
        progressBounds?.takeIf { it.contains(x, y) && durationMs > 0 }?.let { bar ->
            val t = ((x - progressLeft) / progressWidth).coerceIn(0f, 1f)
            val target = ((if (progressFlipped) 1f - t else t) * durationMs).toLong()
            // Show the new position right away; the player's own update follows shortly.
            seekOverride = target to SystemClock.elapsedRealtime()
            onSeek?.invoke(target)
            requestFrame()
            return
        }
        val hit = controlBounds.entries.firstOrNull { it.value.contains(x, y) }?.key
        if (hit == null) {
            lineHits.firstOrNull { it.first.contains(x, y) }?.let { seekToRow(it.second) }
            return
        }
        pressed = hit
        pressAmount = 1f
        when (hit) {
            Control.LIKE -> {
                likeOverride = !(likeOverride ?: liked ?: false)
                onControl?.invoke(hit)
            }
            Control.ADJUST -> setSyncMode(true)
            Control.DONE -> setSyncMode(false)
            else -> {
                if (syncMode) setSyncMode(true) // restart the idle timeout
                onControl?.invoke(hit)
            }
        }
        requestFrame()
    }

    /** Shows or hides the sync controls in place of the playback footer. */
    fun toggleSyncMode() = setSyncMode(!syncMode)

    private fun setSyncMode(on: Boolean) {
        syncMode = on
        main.removeCallbacks(exitSyncMode)
        if (on) main.postDelayed(exitSyncMode, SYNC_IDLE_MS)
        requestFrame()
    }

    // ---- Public API ------------------------------------------------------------------------

    fun release() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        main.removeCallbacks(exitSyncMode)
        main.removeCallbacks(endBrowse)
        worker.shutdownNow()
        frameHandler.removeCallbacks(frameRunnable)
        frameHandler.removeCallbacks(watchdog)
        watchdogRunning = false
        frameScheduled = false
        surface = null
    }

    /** Turns the estimated karaoke sweep on the current line on or off (phone app setting). */
    fun setKaraoke(enabled: Boolean) {
        if (karaoke == enabled) return
        karaoke = enabled
        requestFrame()
    }

    /** Toggles moving/still background and returns the label of the new mode. */
    fun toggleBackground(): String {
        movingBackground = !movingBackground
        prefs.edit { putBoolean(KEY_MOVING_BACKGROUND, movingBackground) }
        stillDirty = true
        lastDriftNanos = 0L
        requestFrame()
        return res.getString(if (movingBackground) R.string.background_moving else R.string.background_still)
    }

    /** Cycles the lyric text size and returns the label of the new size. */
    fun cycleTextSize(): String {
        val i = (TEXT_SCALES.indexOfFirst { abs(it - textScale) < 0.01f } + 1) % TEXT_SCALES.size
        textScale = TEXT_SCALES[i]
        prefs.edit { putFloat(KEY_TEXT_SCALE, textScale) }
        layoutWidth = -1
        requestFrame()
        return res.getStringArray(R.array.text_size_labels)[i]
    }

    fun update(source: NowPlayingTracker.Source, display: LyricsEngine.Display) {
        title = display.track?.title.orEmpty()
        artist = display.track?.artist.orEmpty()
        if (source.playback !== playback) {
            seekOverride = null
            likeOverride = null // the player has answered (or moved on)
        }
        playback = source.playback
        liked = NowPlayingTracker.isLiked(source.playback)
        disliked = NowPlayingTracker.isDisliked(source.playback)
        shuffleOn = NowPlayingTracker.isShuffleOn(source.playback)
        repeatMode = NowPlayingTracker.repeatMode(source.playback)
        durationMs = source.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 }
            ?: display.track?.durationMs ?: 0L

        val synced = ((display.status as? LyricsEngine.Status.Ready)?.lyrics as? Lyrics.Synced)?.lines
        val newRows: List<String> =
            if (synced == null) emptyList() else listOf(MUSIC_NOTE) + synced.map { it.text.ifBlank { MUSIC_NOTE } }
        if (newRows != rows) {
            rows = newRows
            rtlSong = isMostlyRtl(newRows)
            manualOffset = 0f
            flingVelocity = 0f
            browseUntil = 0L
            layoutWidth = -1
            scroll = Float.NaN
            emphasis = FloatArray(newRows.size)
        }
        lineStarts = synced?.map { it.timeMs }?.toLongArray()
        // Row 0 is the intro note, so lyric line i is row i + 1.
        currentRow = if (synced == null) -1 else display.lineIndex + 1
        message = if (synced != null) null else messageFor(display)
        offsetMs = display.offsetMs
        timingWarning = display.timingMayBeOff

        val art = source.metadata?.let {
            it.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: it.getBitmap(MediaMetadata.METADATA_KEY_ART)
        }
        val key = "$title|$artist|${art != null}"
        if (key != artKey) {
            artKey = key
            setArt(art)
        }
        requestFrame()
    }

    private fun messageFor(display: LyricsEngine.Display): String = when (val s = display.status) {
        LyricsEngine.Status.Idle -> res.getString(R.string.waiting_subtitle)
        LyricsEngine.Status.Loading -> res.getString(R.string.view_loading)
        is LyricsEngine.Status.Failed -> res.getString(R.string.view_failed)
        is LyricsEngine.Status.Ready -> when (s.lyrics) {
            is Lyrics.Plain -> res.getString(R.string.view_unsynced)
            Lyrics.Instrumental -> res.getString(R.string.view_instrumental)
            Lyrics.NotFound -> res.getString(R.string.view_not_found)
            is Lyrics.Synced -> ""
        }
    }

    // ---- Artwork ---------------------------------------------------------------------------

    private fun setArt(art: Bitmap?) {
        val token = ++artToken
        if (art == null) {
            applyBackdrop(null)
            setTones(CoverTones.NEUTRAL)
        } else {
            // A real blur at a decent resolution takes a few ms, so keep it off the frame loop.
            worker.execute {
                val blurred = makeBackdrop(art)
                val luma = blurred?.let(::averageLuma) ?: 0f
                val tones = coverTones(art)
                main.post {
                    if (token == artToken) {
                        backdropLuma = luma
                        applyBackdrop(blurred)
                        setTones(tones)
                    }
                }
            }
        }
        thumb = art?.let { centerCrop(it, THUMB_PX) }
        thumbShader = thumb?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        accent = art?.let { runCatching { it.scale(1, 1).getPixel(0, 0) }.getOrNull() } ?: DEFAULT_ACCENT
    }

    private fun averageLuma(bmp: Bitmap): Float {
        val c = bmp.scale(1, 1).getPixel(0, 0)
        return ((0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f)
    }

    private fun coverTones(art: Bitmap): CoverTones.Tones = runCatching {
        val small = art.scale(32, 32)
        val pixels = IntArray(32 * 32).also { small.getPixels(it, 0, 32, 0, 0, 32, 32) }
        CoverTones.fromPixels(pixels)
    }.getOrDefault(CoverTones.NEUTRAL)

    private fun setTones(tones: CoverTones.Tones) {
        if (tones == toneTo && toneFade >= 1f) return
        toneFrom = CoverTones.Tones(toneLight, toneOnLight)
        toneTo = tones
        toneFade = 0f
        requestFrame()
    }

    private fun applyBackdrop(blurred: Bitmap?) {
        if (blurred == null) backdropLuma = 0f
        stillDirty = true
        previousBackdrop = backdrop ?: previousBackdrop
        backdrop = blurred
        backdropFade = 0f
        requestFrame()
    }

    /**
     * Blurs a medium-size copy of the art. Three box blurs approximate a Gaussian, and because
     * the result is only stretched ~5x on screen it stays smooth instead of turning blocky.
     */
    private fun makeBackdrop(art: Bitmap): Bitmap? = runCatching {
        val w = BACKDROP_PX
        val h = max(1, BACKDROP_PX * art.height / max(1, art.width))
        // Slightly richer colours so the wash reads as the album, not grey. Baked in once per
        // song rather than applied as a paint filter on every frame.
        val saturated = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(saturated).drawBitmap(art.scale(w, h), 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.35f) })
        })
        val pixels = IntArray(w * h)
        saturated.getPixels(pixels, 0, w, 0, 0, w, h)
        repeat(3) { boxBlur(pixels, w, h, BLUR_RADIUS) }
        Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }.getOrNull()

    private fun centerCrop(src: Bitmap, size: Int): Bitmap? = runCatching {
        val side = min(src.width, src.height)
        Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side).scale(size, size)
    }.getOrNull()

    /** One horizontal + one vertical running-sum box blur, in place, edges clamped. */
    private fun boxBlur(pixels: IntArray, w: Int, h: Int, radius: Int) {
        val tmp = IntArray(pixels.size)
        blurPass(pixels, tmp, lines = h, length = w, lineStep = w, step = 1, radius = radius)
        blurPass(tmp, pixels, lines = w, length = h, lineStep = 1, step = w, radius = radius)
    }

    private fun blurPass(src: IntArray, dst: IntArray, lines: Int, length: Int, lineStep: Int, step: Int, radius: Int) {
        val window = 2 * radius + 1
        for (line in 0 until lines) {
            val base = line * lineStep
            fun at(i: Int) = src[base + i.coerceIn(0, length - 1) * step]
            var r = 0
            var g = 0
            var b = 0
            for (d in -radius..radius) {
                val c = at(d)
                r += Color.red(c); g += Color.green(c); b += Color.blue(c)
            }
            for (i in 0 until length) {
                dst[base + i * step] = Color.rgb(r / window, g / window, b / window)
                val add = at(i + radius + 1)
                val remove = at(i - radius)
                r += Color.red(add) - Color.red(remove)
                g += Color.green(add) - Color.green(remove)
                b += Color.blue(add) - Color.blue(remove)
            }
        }
    }

    // ---- Frame loop ------------------------------------------------------------------------

    private fun requestFrame() {
        needsFullFrame = true
        if (surface == null) return
        if (frameScheduled && !frameDelayed) return
        frameHandler.removeCallbacks(frameRunnable)
        frameScheduled = true
        frameDelayed = false
        frameHandler.post(frameRunnable)
    }

    private fun requestFrameDelayed(delayMs: Long) {
        if (surface == null || frameScheduled) return
        frameScheduled = true
        frameDelayed = true
        frameHandler.postDelayed(frameRunnable, delayMs)
    }

    private var watchdogRunning = false

    private fun startWatchdog() {
        if (watchdogRunning) return
        watchdogRunning = true
        frameHandler.postDelayed(watchdog, WATCHDOG_MS)
    }

    /**
     * Safety net for a car screen that stops updating: re-kicks the frame loop if frames should be
     * flowing but none has drawn for a while, and asks the host for a new surface if it took ours
     * away and never gave one back.
     */
    private fun checkStalled() {
        val now = SystemClock.uptimeMillis()
        if (surface == null) {
            if (surfaceMissingSince != 0L && now - surfaceMissingSince > STALL_MS) {
                Log.w(TAG, "no surface for ${now - surfaceMissingSince}ms; asking the host for a new one")
                surfaceMissingSince = now
                onSurfaceLost?.invoke()
            }
            return
        }
        val playing = playback?.state == PlaybackState.STATE_PLAYING
        if ((playing || needsFullFrame) && now - lastDrawUptime > STALL_MS) {
            Log.w(TAG, "no frame for ${now - lastDrawUptime}ms; restarting the frame loop")
            frameHandler.removeCallbacks(frameRunnable)
            frameScheduled = false
            frameDelayed = false
            requestFrame()
        }
    }

    private fun drawFrame(frameNanos: Long) {
        frameScheduled = false
        frameDelayed = false
        val s = surface?.takeIf { it.isValid } ?: run {
            Log.w(TAG, "drawFrame without a valid surface (surface=$surface)")
            return
        }
        val dt = if (lastFrameNanos == 0L) 0f else ((frameNanos - lastFrameNanos) / 1e9f).coerceAtMost(0.1f)
        lastFrameNanos = frameNanos
        val area = layoutArea()
        // Scale the whole design with the screen, around a 400dp-tall reference.
        val u = density * (area.height() / density / 400f).coerceIn(0.85f, 1.6f)

        // Software canvas, as in Google's Car App samples: if the host's surface can't be
        // connected (e.g. still attached elsewhere) this throws instead of aborting the process
        // like lockHardwareCanvas() does.
        // Most frames while playing only move the progress wave: repaint just that strip and let
        // the surface keep the rest. Full frames happen on real changes and for the drift (10fps).
        val playing = playback?.state == PlaybackState.STATE_PLAYING
        val driftDue = movingBackground && !reduceMotion && playing && backdrop != null &&
            frameNanos - lastDriftFrameNanos >= DRIFT_FULL_FRAME_MS * 1_000_000
        val partialRegion = when {
            waveRect != null && sweepRect != null -> Rect(waveRect).apply { union(sweepRect!!) }
            else -> (waveRect ?: sweepRect)?.let { Rect(it) }
        }
        val fullFrame = needsFullFrame || driftDue || partialRegion == null
        val dirty = if (fullFrame) null else partialRegion
        val canvas = try {
            s.lockCanvas(dirty)
        } catch (e: Exception) {
            if (lockFailures == 0) Log.w(TAG, "lockCanvas failed, retrying", e)
            if (++lockFailures >= MAX_LOCK_FAILURES) {
                Log.w(TAG, "surface looks dead after $lockFailures failed locks; asking the host for a new one")
                surface = null
                surfaceMissingSince = SystemClock.uptimeMillis()
                lockFailures = 0
                onSurfaceLost?.invoke()
            } else {
                requestFrameDelayed(LOCK_RETRY_MS)
            }
            return
        }
        lockFailures = 0
        needsFullFrame = false
        if (driftDue) lastDriftFrameNanos = frameNanos
        var animating: Boolean
        try {
            val tonesFading = toneFade < 1f
            if (tonesFading) toneFade = min(1f, toneFade + dt / TONE_FADE_SEC)
            animating = drawBackdrop(canvas, dt, frameNanos, recompose = driftDue) || tonesFading
            controlBounds.clear() // re-registered by whatever is drawn this frame
            progressBounds = null
            waveRect = null
            if (isWide(area, u)) {
                // Lyrics get the left 70% at full height; song, art and controls the right 30%.
                val panel = Rect(area.right - (area.width() * PANEL_FRACTION).toInt(), area.top, area.right, area.bottom)
                val lyricsArea = Rect(area.left, area.top, panel.left, area.bottom)
                drawPanel(canvas, panel, u)
                val top = drawWarning(canvas, lyricsArea, u, lyricsArea.top + 14 * u)
                val footer = if (syncMode) drawSyncFooter(canvas, lyricsArea, u, dt) else drawFooter(canvas, lyricsArea, u, dt, compact = true)
                animating = footer.animating || animating
                val bottom = footer.top
                animating = if (message != null || rows.isEmpty()) {
                    drawMessage(canvas, lyricsArea, top, bottom, u)
                    animating
                } else {
                    drawLyrics(canvas, lyricsArea, top, bottom, u, dt, centered = false) || animating
                }
            } else {
                val headerBottom = drawWarning(canvas, area, u, drawHeader(canvas, area, u))
                val footer = if (syncMode) drawSyncFooter(canvas, area, u, dt) else drawFooter(canvas, area, u, dt)
                animating = footer.animating || animating
                animating = if (message != null || rows.isEmpty()) {
                    drawMessage(canvas, area, headerBottom, footer.top, u)
                    animating
                } else {
                    drawLyrics(canvas, area, headerBottom, footer.top, u, dt, centered = true) || animating
                }
            }
        } catch (e: Exception) {
            // A drawing bug must not crash the app: Android Auto hides a car app that keeps
            // crashing. Skip this frame and keep going.
            CrashLog.record("drawFrame", e)
            animating = false
        } finally {
            s.unlockCanvasAndPost(canvas)
            lastDrawUptime = SystemClock.uptimeMillis()
        }

        when {
            animating -> requestFrame()
            else -> {
                // Restart easing from dt = 0 next time instead of jumping.
                lastFrameNanos = 0L
                // While playing, the progress wave (and background drift) keep moving.
                val moving = waveRect != null || sweepRect != null
                if (playing) requestFrameDelayed(if (moving) WAVE_FRAME_MS else DRIFT_FULL_FRAME_MS)
            }
        }
    }

    /**
     * In full screen Android Auto reserves the whole top row for the action strip (visible area
     * starts ~88px down) although the strip only sits in the top-right corner. Reclaim that row;
     * [drawHeader] keeps clear of the corner. In split screen the visible area is the panel, so use it.
     */
    private fun layoutArea(): Rect {
        val full = Rect(0, 0, surfaceWidth, surfaceHeight)
        val visible = visibleArea?.takeUnless { it.isEmpty } ?: return full
        val isFullScreen = visible.width() >= surfaceWidth * 0.85f
        return if (isFullScreen) Rect(visible.left, 0, visible.right, visible.bottom) else visible
    }

    // ---- Drawing: background -----------------------------------------------------------------

    /** Drift time only advances while music plays, so the background rests when paused. */
    private fun advanceDrift(frameNanos: Long) {
        val playing = playback?.state == PlaybackState.STATE_PLAYING
        if (playing && lastDriftNanos != 0L) driftSeconds += ((frameNanos - lastDriftNanos) / 1e9f).coerceIn(0f, 0.1f)
        lastDriftNanos = if (playing) frameNanos else 0L
    }

    /**
     * Apple-Music-style moving background: three oversized copies of the blurred art, each
     * slowly rotating and drifting at its own pace, composited into a 1/4-resolution buffer
     * and stretched to the screen. Layers always cover the buffer at any angle, so no edges show.
     */
    private fun drawMovingBackdrop(canvas: Canvas, previousAlpha: Float, recompose: Boolean) {
        val bw = max(1, surfaceWidth / DRIFT_DOWNSCALE)
        val bh = max(1, surfaceHeight / DRIFT_DOWNSCALE)
        var fresh = false
        val buffer = driftBuffer?.takeIf { it.width == bw && it.height == bh }
            ?: Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888).also {
                driftBuffer = it
                driftCanvas = Canvas(it)
                fresh = true
            }
        val bc = driftCanvas ?: return
        val cache = movingCache?.takeIf { it.width == surfaceWidth && it.height == surfaceHeight }
            ?: Bitmap.createBitmap(surfaceWidth, surfaceHeight, Bitmap.Config.ARGB_8888).also {
                movingCache = it
                movingCacheCanvas = Canvas(it)
                fresh = true
            }
        // Stretching the small buffer to full screen is the expensive part, so do it only when
        // the drift actually moves (or during a crossfade); every other frame copies the result.
        if (recompose || fresh || backdropFade < 1f) {
            composeDrift(bc, previousAlpha, bw, bh)
            bitmapPaint.alpha = 255
            movingCacheCanvas?.drawBitmap(buffer, null, RectF(0f, 0f, surfaceWidth.toFloat(), surfaceHeight.toFloat()), bitmapPaint)
        }
        canvas.drawBitmap(cache, 0f, 0f, null)
    }

    private fun composeDrift(bc: Canvas, previousAlpha: Float, bw: Int, bh: Int) {
        bc.drawColor(ColorUtils.blendARGB(accent, Color.BLACK, 0.6f))
        previousBackdrop?.let { drawDriftLayers(bc, it, previousAlpha, bw, bh) }
        backdrop?.let { drawDriftLayers(bc, it, backdropFade, bw, bh) }
        // The scrim is a smooth gradient, so drawing it at 1/4 resolution looks identical and
        // leaves a single full-screen bitmap draw per frame.
        bc.drawRect(0f, 0f, bw.toFloat(), bh.toFloat(), scrimFor(bh, driftScrimPaint, driftScrimKey).also { driftScrimKey = it.second }.first)
    }

    private fun drawDriftLayers(bc: Canvas, art: Bitmap, alpha: Float, bw: Int, bh: Int) {
        if (alpha <= 0f) return
        val t = driftSeconds
        // Big enough that the art covers the buffer's diagonal at every rotation.
        val diagonal = kotlin.math.sqrt((bw * bw + bh * bh).toFloat())
        val cover = diagonal / min(art.width, art.height) * 1.08f
        for (layer in DRIFT_LAYERS) {
            val angle = layer.startDeg + t * layer.degPerSec
            val phase = (t / layer.orbitSec) * 2f * PI.toFloat()
            val cx = bw / 2f + kotlin.math.cos(phase) * bw * layer.orbit
            val cy = bh / 2f + sin(phase) * bh * layer.orbit
            val scale = cover * layer.scale
            driftMatrix.reset()
            driftMatrix.postTranslate(-art.width / 2f, -art.height / 2f)
            driftMatrix.postScale(scale, scale)
            driftMatrix.postRotate(angle)
            driftMatrix.postTranslate(cx, cy)
            layerPaint.alpha = (alpha * layer.alpha * 255).toInt()
            bc.drawBitmap(art, driftMatrix, layerPaint)
        }
    }

    private fun drawBackdrop(canvas: Canvas, dt: Float, frameNanos: Long, recompose: Boolean): Boolean {
        val w = surfaceWidth.toFloat()
        val h = surfaceHeight.toFloat()
        val animating = backdropFade < 1f
        if (animating) backdropFade = min(1f, backdropFade + dt / BACKDROP_FADE_SEC)
        val previousAlpha = if (backdrop == null) 1f - backdropFade else 1f
        if (movingBackground && (backdrop != null || previousBackdrop != null)) {
            if (recompose) advanceDrift(frameNanos)
            drawMovingBackdrop(canvas, previousAlpha, recompose)
            if (backdropFade >= 1f) previousBackdrop = null
            return animating
        }
        // Still mode: compose cover + scrim once into a cached full-size image (again during a
        // crossfade) so lyric animation frames only copy pixels instead of rescaling and blending.
        val cache = stillCache?.takeIf { it.width == surfaceWidth && it.height == surfaceHeight }
            ?: Bitmap.createBitmap(surfaceWidth, surfaceHeight, Bitmap.Config.ARGB_8888).also {
                stillCache = it
                stillCanvas = Canvas(it)
                stillDirty = true
            }
        val sc = stillCanvas
        if (sc != null && (stillDirty || animating)) {
            fillPaint.shader = null
            fillPaint.color = ColorUtils.blendARGB(accent, Color.BLACK, 0.75f)
            sc.drawRect(0f, 0f, w, h, fillPaint)
            previousBackdrop?.let { drawCover(sc, it, previousAlpha) }
            backdrop?.let { drawCover(sc, it, backdropFade) }
            scrimKey = scrimFor(surfaceHeight, scrimPaint, scrimKey).second
            sc.drawRect(0f, 0f, w, h, scrimPaint)
            stillDirty = false
        }
        if (backdropFade >= 1f) previousBackdrop = null
        canvas.drawBitmap(cache, 0f, 0f, null)
        return animating
    }

    /**
     * Darkening gradient for a canvas of [height]; bright art gets more darkening so white
     * lyrics stay readable. Rebuilds the shader only when height or brightness changes.
     */
    private fun scrimFor(height: Int, paint: Paint, key: Pair<Int, Int>?): Pair<Paint, Pair<Int, Int>> {
        val darken = (backdropLuma * 100).toInt() // 0 for dark art, up to 100 for white art
        val newKey = height to darken
        if (key != newKey) {
            val extra = darken * 0.9f
            paint.shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                intArrayOf(
                    Color.argb((95 + extra).toInt().coerceAtMost(235), 0, 0, 0),
                    Color.argb((125 + extra).toInt().coerceAtMost(235), 0, 0, 0),
                    Color.argb((205 + extra * 0.4f).toInt().coerceAtMost(240), 0, 0, 0),
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        return paint to newKey
    }

    private fun drawCover(canvas: Canvas, bmp: Bitmap, alpha: Float) {
        if (alpha <= 0f) return
        val scale = max(surfaceWidth.toFloat() / bmp.width, surfaceHeight.toFloat() / bmp.height) * 1.1f
        val w = bmp.width * scale
        val h = bmp.height * scale
        val left = (surfaceWidth - w) / 2
        val top = (surfaceHeight - h) / 2
        bitmapPaint.alpha = (alpha * 255).toInt()
        canvas.drawBitmap(bmp, null, RectF(left, top, left + w, top + h), bitmapPaint)
    }

    // ---- Drawing: header ---------------------------------------------------------------------

    /** Centred art + title + artist. Returns the y where the lyrics area starts. */
    private fun drawHeader(canvas: Canvas, area: Rect, u: Float): Float {
        val top = area.top + 12 * u
        if (title.isEmpty()) return top

        val thumbSize = 48 * u
        val gap = 14 * u
        titlePaint.textSize = 21 * u
        artistPaint.textSize = 16 * u
        val hasThumb = thumbShader != null
        // Stay clear of the action strip button in the top-right corner (mirrored for symmetry).
        val cornerClearance = 2 * 84 * u
        val maxText = min(area.width() - cornerClearance, 560 * u) - if (hasThumb) thumbSize + gap else 0f
        val t = TextUtils.ellipsize(title, titlePaint, maxText, TextUtils.TruncateAt.END).toString()
        val a = TextUtils.ellipsize(artist, artistPaint, maxText, TextUtils.TruncateAt.END).toString()
        val textWidth = max(titlePaint.measureText(t), artistPaint.measureText(a))
        val groupWidth = textWidth + if (hasThumb) thumbSize + gap else 0f
        var x = area.exactCenterX() - groupWidth / 2

        val titleHeight = titlePaint.descent() - titlePaint.ascent()
        val artistHeight = if (a.isEmpty()) 0f else artistPaint.descent() - artistPaint.ascent()
        val textBlock = titleHeight + artistHeight
        val blockHeight = max(if (hasThumb) thumbSize else 0f, textBlock)

        thumbShader?.let { shader ->
            val bmp = thumb ?: return@let
            val dst = RectF(x, top + (blockHeight - thumbSize) / 2, x + thumbSize, top + (blockHeight + thumbSize) / 2)
            shaderMatrix.setRectToRect(RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat()), dst, Matrix.ScaleToFit.FILL)
            shader.setLocalMatrix(shaderMatrix)
            fillPaint.shader = shader
            fillPaint.alpha = 255
            canvas.drawRoundRect(dst, 10 * u, 10 * u, fillPaint)
            fillPaint.shader = null
            x += thumbSize + gap
        }

        var y = top + (blockHeight - textBlock) / 2 - titlePaint.ascent()
        titlePaint.alpha = 255
        canvas.drawText(t, x, y, titlePaint)
        if (a.isNotEmpty()) {
            y += titlePaint.descent() - artistPaint.ascent()
            artistPaint.alpha = 185
            canvas.drawText(a, x, y, artistPaint)
        }
        return top + blockHeight + 4 * u
    }

    // ---- Drawing: footer ---------------------------------------------------------------------

    private class Footer(val top: Float, val animating: Boolean)

    private val pillPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = bold }

    /** Rounded button; [filled] is the primary (white) style, otherwise translucent. */
    private fun drawPill(canvas: Canvas, rect: RectF, text: String, control: Control?, filled: Boolean) {
        val pressedNow = control != null && pressed == control && pressAmount > 0f
        fillPaint.shader = null
        fillPaint.color = if (filled) toneLight else Color.WHITE
        fillPaint.alpha = when {
            filled -> 255
            pressedNow -> 95
            else -> 42
        }
        canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, fillPaint)
        pillPaint.color = if (filled) toneOnLight else Color.WHITE
        pillPaint.alpha = 255
        val baseline = rect.centerY() - (pillPaint.ascent() + pillPaint.descent()) / 2
        canvas.drawText(text, rect.centerX() - pillPaint.measureText(text) / 2, baseline, pillPaint)
        control?.let { controlBounds[it] = RectF(rect).apply { inset(-6f, -8f) } }
    }

    private fun offsetLabel(): String = when {
        offsetMs == 0L -> res.getString(R.string.sync_in_sync)
        offsetMs > 0 -> res.getString(R.string.sync_shown_earlier, "%.1f".format(offsetMs / 1000f))
        else -> res.getString(R.string.sync_shown_later, "%.1f".format(-offsetMs / 1000f))
    }

    /** Earlier / [offset · tap to reset] / Later / Done, in place of the playback footer. */
    private fun drawSyncFooter(canvas: Canvas, area: Rect, u: Float, dt: Float): Footer {
        if (pressAmount > 0f) pressAmount = max(0f, pressAmount - dt / PRESS_SEC)

        val earlier = res.getString(R.string.sync_earlier)
        val later = res.getString(R.string.sync_later)
        val done = res.getString(R.string.sync_done)
        val label = offsetLabel()

        pillPaint.textSize = 16 * u
        val pad = 22 * u
        val gap = 10 * u
        // Fixed-width centre so the row doesn't jump as the value changes.
        val centreWidth = max(pillPaint.measureText(res.getString(R.string.sync_shown_earlier, "88.8")), pillPaint.measureText(label)) + 2 * pad
        val widths = floatArrayOf(pillPaint.measureText(earlier) + 2 * pad, centreWidth, pillPaint.measureText(later) + 2 * pad, pillPaint.measureText(done) + 2 * pad)
        var total = widths.sum() + 3 * gap
        val fit = min(1f, (area.width() - 32 * u) / total)
        if (fit < 1f) {
            pillPaint.textSize *= fit
            for (i in widths.indices) widths[i] *= fit
            total *= fit
        }
        val h = 46 * u
        val cy = area.bottom - 16 * u - 28 * u
        var x = area.exactCenterX() - total / 2
        val controls = arrayOf(Control.EARLIER, Control.RESET, Control.LATER, Control.DONE)
        val texts = arrayOf(earlier, label, later, done)
        for (i in widths.indices) {
            val rect = RectF(x, cy - h / 2, x + widths[i], cy + h / 2)
            drawPill(canvas, rect, texts[i], controls[i], filled = controls[i] == Control.DONE)
            x += widths[i] + gap
        }

        timePaint.textSize = 13 * u
        timePaint.alpha = 190
        val hint = res.getString(R.string.sync_hint)
        val hintY = cy - h / 2 - 14 * u
        canvas.drawText(hint, area.exactCenterX() - timePaint.measureText(hint) / 2, hintY, timePaint)
        return Footer(hintY + timePaint.ascent() - 10 * u, pressAmount > 0f)
    }

    /** Dismissable "timing may be off" pill under the header. Returns the new content top. */
    private fun drawWarning(canvas: Canvas, area: Rect, u: Float, top: Float): Float {
        if (!timingWarning || syncMode || message != null) return top
        pillPaint.textSize = 13.5f * u
        val h = 32 * u
        val adjust = res.getString(R.string.timing_adjust)
        val adjustWidth = pillPaint.measureText(adjust) + 28 * u
        val closeWidth = h
        val maxText = area.width() - 32 * u - adjustWidth - closeWidth - 34 * u
        val text = TextUtils.ellipsize(res.getString(R.string.timing_warning), pillPaint, maxText, TextUtils.TruncateAt.END).toString()
        val width = 16 * u + pillPaint.measureText(text) + 12 * u + adjustWidth + 6 * u + closeWidth
        val rect = RectF(area.exactCenterX() - width / 2, top + 2 * u, area.exactCenterX() + width / 2, top + 2 * u + h)

        fillPaint.shader = null
        fillPaint.color = Color.rgb(0xFF, 0xB7, 0x4D) // amber
        fillPaint.alpha = 60
        canvas.drawRoundRect(rect, h / 2, h / 2, fillPaint)
        pillPaint.color = Color.rgb(0xFF, 0xE0, 0xB2)
        pillPaint.alpha = 255
        val baseline = rect.centerY() - (pillPaint.ascent() + pillPaint.descent()) / 2
        canvas.drawText(text, rect.left + 16 * u, baseline, pillPaint)

        val adjustRect = RectF(rect.right - closeWidth - 6 * u - adjustWidth, rect.top + 3 * u, rect.right - closeWidth - 6 * u, rect.bottom - 3 * u)
        drawPill(canvas, adjustRect, adjust, Control.ADJUST, filled = false)

        val closeRect = RectF(rect.right - closeWidth, rect.top, rect.right, rect.bottom)
        pillPaint.color = Color.rgb(0xFF, 0xE0, 0xB2)
        canvas.drawText("✕", closeRect.centerX() - pillPaint.measureText("✕") / 2, baseline, pillPaint)
        controlBounds[Control.DISMISS] = RectF(closeRect).apply { inset(-8f, -8f) }
        return rect.bottom + 4 * u
    }

    /** Stacked layout (split screen): progress bar and centred transport controls. */
    private fun drawFooter(canvas: Canvas, area: Rect, u: Float, dt: Float, compact: Boolean = false): Footer {
        // Compact: the wide layout's row under the lyrics, a little smaller and closer to the edge.
        val playSize = (if (compact) 46 else 56) * u
        val side = (if (compact) 40 else 46) * u
        val spacing = (if (compact) 26 else 34) * u
        val cx = area.exactCenterX()
        val cy = area.bottom - (if (compact) 10 else 16) * u - playSize / 2
        if (pressAmount > 0f) pressAmount = max(0f, pressAmount - dt / PRESS_SEC)
        val waving = advanceWave(dt)
        drawTransport(canvas, cx, cy, u, playSize, sideSize = side, spacing = spacing)
        drawExtras(canvas, area, cx, cy, u, transportHalf = playSize * PILL_ASPECT / 2 + spacing + side)

        var footerTop = cy - playSize / 2 - 10 * u
        if (durationMs > 0) {
            timePaint.textSize = TIME_DP * u
            val timeBaseline = cy - playSize / 2 - (if (compact) 6 else 10) * u
            val barY = timeBaseline + timePaint.ascent() - (if (compact) 10 else 12) * u
            val barWidth = if (compact) min(area.width() * 0.78f, 460 * u) else min(area.width() * 0.7f, 620 * u)
            drawProgress(canvas, cx - barWidth / 2, cx + barWidth / 2, barY, timeBaseline, u)
            footerTop = barY - 20 * u
        }
        return Footer(footerTop, pressAmount > 0f || waving)
    }

    /** Wide layout: the right column with the album art and, under it, title and artist. */
    private fun drawPanel(canvas: Canvas, panel: Rect, u: Float) {
        val pad = 18 * u
        val innerW = panel.width() - 2 * pad
        titlePaint.textSize = 19 * u
        artistPaint.textSize = 16 * u
        val titleH = titlePaint.descent() - titlePaint.ascent()
        val artistH = if (artist.isEmpty()) 0f else artistPaint.descent() - artistPaint.ascent()
        val textGap = 14 * u
        var art = min(innerW, panel.height() - 2 * pad - titleH - artistH - textGap)
        if (thumbShader == null || art < 72 * u) art = 0f
        val colW = if (art > 0f) art else innerW
        val colL = panel.exactCenterX() - colW / 2
        var y = panel.top + (panel.height() - (titleH + artistH + if (art > 0f) art + textGap else 0f)) / 2

        if (art > 0f) {
            val shader = thumbShader!!
            val bmp = thumb!!
            val dst = RectF(colL, y, colL + art, y + art)
            shaderMatrix.setRectToRect(RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat()), dst, Matrix.ScaleToFit.FILL)
            shader.setLocalMatrix(shaderMatrix)
            fillPaint.shader = shader
            fillPaint.alpha = 255
            canvas.drawRoundRect(dst, 12 * u, 12 * u, fillPaint)
            fillPaint.shader = null
            y += art + textGap
        }
        titlePaint.alpha = 255
        canvas.drawText(TextUtils.ellipsize(title, titlePaint, colW, TextUtils.TruncateAt.END).toString(), colL, y - titlePaint.ascent(), titlePaint)
        if (artistH > 0f) {
            artistPaint.alpha = 200
            canvas.drawText(TextUtils.ellipsize(artist, artistPaint, colW, TextUtils.TruncateAt.END).toString(), colL, y + titleH - artistPaint.ascent(), artistPaint)
        }
    }

    /** Previous / play-pause pill / next, centred at ([cx], [cy]); registers their tap areas. */
    private fun drawTransport(canvas: Canvas, cx: Float, cy: Float, u: Float, playSize: Float, sideSize: Float, spacing: Float) {
        fun pressScale(c: Control) = if (pressed == c) 1f - 0.14f * pressAmount else 1f
        val isPlaying = playback?.state == PlaybackState.STATE_PLAYING
        // Like YouTube Music's car player: a wide pill in the cover's light tone for play/pause,
        // flanked by outlined skip icons.
        val pillW = playSize * PILL_ASPECT
        val prevX = cx - pillW / 2 - spacing - sideSize / 2
        val nextX = cx + pillW / 2 + spacing + sideSize / 2
        val ps = pressScale(Control.PLAY_PAUSE)
        val w = pillW * ps
        val h = playSize * ps
        fillPaint.shader = null
        fillPaint.color = toneLight
        canvas.drawRoundRect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, h / 2, h / 2, fillPaint)
        drawSymbol(canvas, if (isPlaying) Symbols.pause else Symbols.play, cx, cy, h * 0.56f, toneOnLight)
        drawSymbol(canvas, Symbols.skipPrevious, prevX, cy, sideSize * 0.8f * pressScale(Control.PREVIOUS), Color.WHITE, 235)
        drawSymbol(canvas, Symbols.skipNext, nextX, cy, sideSize * 0.8f * pressScale(Control.NEXT), Color.WHITE, 235)

        // Tap areas at least 48dp (Android Auto's minimum), whatever the drawn size.
        val half = max(sideSize / 2 + 4 * u, 24 * u)
        controlBounds[Control.PREVIOUS] = RectF(prevX - half, cy - half, prevX + half, cy + half)
        controlBounds[Control.PLAY_PAUSE] = RectF(cx - pillW / 2 - 6 * u, cy - max(playSize / 2, 24 * u) - 4 * u, cx + pillW / 2 + 6 * u, cy + max(playSize / 2, 24 * u) + 4 * u)
        controlBounds[Control.NEXT] = RectF(nextX - half, cy - half, nextX + half, cy + half)
    }

    /**
     * Secondary buttons flanking the transport, YouTube Music style:
     *   dislike  shuffle  |  ⏮  ( ▶ )  ⏭  |  repeat  like
     * Only the ones the player offers are shown; on narrow screens the outer pair goes first.
     */
    private fun drawExtras(canvas: Canvas, area: Rect, cx: Float, cy: Float, u: Float, transportHalf: Float) {
        val slot = 44 * u
        val gap = 10 * u
        val room = min(cx - area.left, area.right - cx) - transportHalf - 8 * u
        val inner = listOfNotNull(Control.SHUFFLE.takeIf { shuffleOn != null }, Control.REPEAT.takeIf { repeatMode != null })
        val outer = listOfNotNull(Control.DISLIKE.takeIf { disliked != null }, Control.LIKE.takeIf { liked != null })
        val pairs = buildList {
            if (inner.isNotEmpty()) add(Control.SHUFFLE to Control.REPEAT)
            if (outer.isNotEmpty()) add(Control.DISLIKE to Control.LIKE)
        }
        var offset = transportHalf
        for ((left, right) in pairs) {
            if (offset - transportHalf + gap + slot > room) break
            offset += gap + slot / 2
            drawExtra(canvas, left, cx - offset, cy, u)
            drawExtra(canvas, right, cx + offset, cy, u)
            offset += slot / 2
        }
    }

    private fun drawExtra(canvas: Canvas, control: Control, x: Float, cy: Float, u: Float) {
        if (control == Control.LIKE) return drawLike(canvas, x, cy, u)
        val (path, active) = when (control) {
            Control.DISLIKE -> (if (disliked == true) Symbols.thumbDownFilled else Symbols.thumbDown) to (disliked ?: return)
            Control.SHUFFLE -> Symbols.shuffle to (shuffleOn ?: return)
            Control.REPEAT -> (if (repeatMode == 2) Symbols.repeatOne else Symbols.repeat) to ((repeatMode ?: return) != 0)
            else -> return
        }
        val scale = if (pressed == control) 1f - 0.16f * pressAmount else 1f
        drawSymbol(canvas, path, x, cy, 26 * u * scale, if (active) toneLight else Color.WHITE, if (active) 255 else 200)
        // On/off isn't shown by colour alone: an active shuffle/repeat also gets a dot underneath.
        if (active && control != Control.DISLIKE) {
            fillPaint.color = toneLight
            canvas.drawCircle(x, cy + 18 * u, 2.5f * u, fillPaint)
        }
        controlBounds[control] = RectF(x - 24 * u, cy - 24 * u, x + 24 * u, cy + 24 * u)
    }

    /** Thumbs-up, filled when liked; only drawn when the player offers liking. */
    private fun drawLike(canvas: Canvas, cx: Float, cy: Float, u: Float) {
        val state = likeOverride ?: liked ?: return
        val scale = if (pressed == Control.LIKE) 1f - 0.16f * pressAmount else 1f
        drawSymbol(canvas, if (state) Symbols.thumbUpFilled else Symbols.thumbUp, cx, cy, 28 * u * scale,
            if (state) toneLight else Color.WHITE, if (state) 255 else 230)
        controlBounds[Control.LIKE] = RectF(cx - 24 * u, cy - 24 * u, cx + 24 * u, cy + 24 * u)
    }

    /**
     * Wavy progress bar between [left] and [right] with times underneath; sets the seek area and
     * the strip repainted on wave-only frames.
     */
    private fun drawProgress(canvas: Canvas, left: Float, right: Float, barY: Float, timeBaseline: Float, u: Float) {
        val barWidth = right - left
        val fraction = (positionNow().toFloat() / durationMs).coerceIn(0f, 1f)
        // Always left-to-right, whatever the lyrics' script: it follows the player and the interface.
        val dir = 1f
        val start = left
        val end = right
        val playedX = start + dir * barWidth * fraction
        val stroke = 3.2f * u
        val gap = 7 * u

        strokePaint.strokeWidth = stroke
        strokePaint.color = Color.WHITE
        strokePaint.alpha = 110 // ~3:1 on the dark scrim, like any control boundary
        if ((end - playedX) * dir > gap) canvas.drawLine(playedX + dir * gap, barY, end, barY, strokePaint)

        val amplitude = WAVE_AMPLITUDE_DP * u * waveAmount
        val wavelength = WAVE_LENGTH_DP * u
        val playedEnd = playedX - dir * gap
        if ((playedEnd - start) * dir > 0f) {
            strokePaint.color = toneLight
            if (amplitude < 0.3f) {
                canvas.drawLine(start, barY, playedEnd, barY, strokePaint)
            } else {
                wavePath.reset()
                val step = 2 * u
                var d = 0f
                val len = abs(playedEnd - start)
                wavePath.moveTo(start, barY + amplitude * sin(-wavePhase))
                while (d < len) {
                    d = min(d + step, len)
                    wavePath.lineTo(start + dir * d, barY + amplitude * sin(2f * PI.toFloat() * d / wavelength - wavePhase))
                }
                canvas.drawPath(wavePath, strokePaint)
            }
        }
        fillPaint.shader = null
        fillPaint.color = toneLight
        val thumbW = 4.5f * u
        val thumbH = 18 * u
        canvas.drawRoundRect(playedX - thumbW / 2, barY - thumbH / 2, playedX + thumbW / 2, barY + thumbH / 2, thumbW / 2, thumbW / 2, fillPaint)

        progressLeft = left
        progressWidth = barWidth
        progressFlipped = false
        progressBounds = RectF(left - 12 * u, barY - 22 * u, right + 12 * u, barY + 18 * u)

        timePaint.alpha = 210
        val elapsed = formatTime(positionNow())
        val total = formatTime(durationMs)
        val startText = elapsed
        val endText = total
        canvas.drawText(startText, left, timeBaseline, timePaint)
        canvas.drawText(endText, right - timePaint.measureText(endText), timeBaseline, timePaint)
        val strip = Rect((left - 16 * u).toInt(), (barY - 14 * u).toInt(), (right + 16 * u).toInt(), (timeBaseline + timePaint.descent() + 4 * u).toInt())
        waveRect = waveRect?.apply { union(strip) } ?: strip
    }

    /**
     * Moves the progress wave along while playing and eases its height to flat when paused.
     * Returns true while the wave is still settling (the steady travel is paced separately).
     */
    private fun advanceWave(dt: Float): Boolean {
        val playing = playback?.state == PlaybackState.STATE_PLAYING
        // dt is 0 on the first frame after idling; use the paced frame interval instead.
        val step = if (dt > 0f) dt else if (playing) WAVE_FRAME_MS / 1000f else 0f
        if (playing && !reduceMotion) wavePhase = (wavePhase + step * WAVE_SPEED * 2f * PI.toFloat()) % (2f * PI.toFloat())
        val target = if (playing) 1f else 0f
        waveAmount += (target - waveAmount) * (1f - exp(-step * 6f))
        if (abs(target - waveAmount) < 0.01f) waveAmount = target
        return waveAmount != target
    }

    /** Draws a 960-unit Material Symbols path centred at ([cx], [cy]), [size] px square. */
    private fun drawSymbol(canvas: Canvas, path: Path, cx: Float, cy: Float, size: Float, color: Int, alpha: Int = 255) {
        fillPaint.shader = null
        fillPaint.color = color
        fillPaint.alpha = alpha
        canvas.save()
        canvas.translate(cx - size / 2, cy - size / 2)
        canvas.scale(size / 960f, size / 960f)
        canvas.translate(0f, 960f) // Material Symbols use a 0,-960 960x960 viewBox
        canvas.drawPath(path, fillPaint)
        canvas.restore()
    }

    /**
     * Material Symbols (Apache 2.0), the icon family YouTube Music's player uses:
     * rounded play, outlined skip buttons. Pause is built as two equal rounded bars in the same
     * 960-unit space.
     */
    private object Symbols {
        val play: Path = parse("M320-273v-414q0-17 12-28.5t28-11.5q5 0 10.5 1.5T381-721l326 207q9 6 13.5 15t4.5 19q0 10-4.5 19T707-446L381-239q-5 3-10.5 4.5T360-233q-16 0-28-11.5T320-273Z")
        val pause: Path = Path().apply {
            addRoundRect(RectF(240f, -760f, 400f, -200f), 40f, 40f, Path.Direction.CW)
            addRoundRect(RectF(560f, -760f, 720f, -200f), 40f, 40f, Path.Direction.CW)
        }
        val skipNext: Path = parse("M660-240v-480h80v480h-80Zm-440 0v-480l360 240-360 240Zm80-240Zm0 90 136-90-136-90v180Z")
        val skipPrevious: Path = parse("M220-240v-480h80v480h-80Zm520 0L380-480l360-240v480Zm-80-240Zm0 90v-180l-136 90 136 90Z")

        val thumbUpFilled: Path = parse24("M1 21h4V9H1v12zm22-11c0-1.1-.9-2-2-2h-6.31l.95-4.57.03-.32c0-.41-.17-.79-.44-1.06L14.17 1 7.59 7.59C7.22 7.95 7 8.45 7 9v10c0 1.1.9 2 2 2h9c.83 0 1.54-.5 1.84-1.22l3.02-7.05c.09-.23.14-.47.14-.73v-2z")
        val thumbDownFilled: Path = parse24("M15 3H6c-.83 0-1.54.5-1.84 1.22l-3.02 7.05c-.09.23-.14.47-.14.73v2c0 1.1.9 2 2 2h6.31l-.95 4.57-.03.32c0 .41.17.79.44 1.06L9.83 23l6.59-6.59c.36-.36.58-.86.58-1.41V5c0-1.1-.9-2-2-2zm4 0v12h4V3h-4z")
        val thumbDown: Path = parse24("M15 3H6c-.83 0-1.54.5-1.84 1.22l-3.02 7.05c-.09.23-.14.47-.14.73v2c0 1.1.9 2 2 2h6.31l-.95 4.57-.03.32c0 .41.17.79.44 1.06L9.83 23l6.59-6.59c.36-.36.58-.86.58-1.41V5c0-1.1-.9-2-2-2zm0 12l-4.34 4.34L12 14H3v-2l3-7h9v10zm4-12h4v12h-4z")
        val shuffle: Path = parse24("M10.59 9.17L5.41 4 4 5.41l5.17 5.17 1.42-1.41zM14.5 4l2.04 2.04L4 18.59 5.41 20 17.96 7.46 20 9.5V4h-5.5zm.33 9.41l-1.41 1.41 3.13 3.13L14.5 20H20v-5.5l-2.04 2.04-3.13-3.13z")
        val repeat: Path = parse24("M7 7h10v3l4-4-4-4v3H5v6h2V7zm10 10H7v-3l-4 4 4 4v-3h12v-6h-2v4z")
        val repeatOne: Path = parse24("M7 7h10v3l4-4-4-4v3H5v6h2V7zm10 10H7v-3l-4 4 4 4v-3h12v-6h-2v4zm-4-2V9h-1l-2 1v1h1.5v4H13z")
        val thumbUp: Path = parse24("M9 21h9c.83 0 1.54-.5 1.84-1.22l3.02-7.05c.09-.23.14-.47.14-.73v-2c0-1.1-.9-2-2-2h-6.31l.95-4.57.03-.32c0-.41-.17-.79-.44-1.06L14.17 1 7.58 7.59C7.22 7.95 7 8.45 7 9v10c0 1.1.9 2 2 2zM9 9l4.34-4.34L12 10h9v2l-3 7H9V9zM1 9h4v12H1z")

        // Even-odd so the outlined triangles keep their hollow centres.
        private fun parse(data: String) = PathParser.createPathFromPathData(data).apply { fillType = Path.FillType.EVEN_ODD }

        /** Material Icons use a 0..24 box; map it onto the 0,-960 box the other symbols use. */
        private fun parse24(data: String) = PathParser.createPathFromPathData(data).apply {
            transform(Matrix().apply { setScale(40f, 40f); postTranslate(0f, -960f) })
        }
    }

    private fun positionNow(): Long {
        val pb = playback ?: return 0L
        val (base, since) = seekOverride ?: (pb.position to pb.lastPositionUpdateTime)
        if (pb.state != PlaybackState.STATE_PLAYING) return base.coerceIn(0, max(durationMs, 0))
        val elapsed = SystemClock.elapsedRealtime() - since
        return (base + (elapsed * pb.playbackSpeed).toLong()).coerceIn(0, max(durationMs, 0))
    }

    private fun formatTime(ms: Long): String {
        val total = ms / 1000
        return "%d:%02d".format(total / 60, total % 60)
    }

    // ---- Drawing: lyrics -----------------------------------------------------------------------

    private fun drawMessage(canvas: Canvas, area: Rect, top: Float, bottom: Float, u: Float) {
        sweepRect = null
        lineHits.clear()
        lyricsBounds = null
        val text = message ?: return
        linePaint.textSize = 22 * u
        linePaint.color = Color.WHITE // lyric lines leave it tinted
        linePaint.alpha = 190
        val width = min(area.width() - 80 * u, 640 * u).toInt().coerceAtLeast(1)
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, linePaint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .build()
        val textTop = top + (bottom - top - layout.height) / 2
        canvas.save()
        canvas.translate(area.exactCenterX() - width / 2f, textTop)
        layout.draw(canvas)
        canvas.restore()
        // Brand mark under the message: "7x Lyrics" in the 7X amber.
        linePaint.textSize = 15 * u
        linePaint.color = BRAND_AMBER
        linePaint.alpha = 220
        val mark = res.getString(R.string.app_name)
        canvas.drawText(mark, area.exactCenterX() - linePaint.measureText(mark) / 2, textTop + layout.height + 30 * u, linePaint)
        layoutWidth = -1 // linePaint size was changed
    }

    /** Returns true while anything is still easing. */
    private fun drawLyrics(canvas: Canvas, area: Rect, top: Float, bottom: Float, u: Float, dt: Float, centered: Boolean): Boolean {
        val lineSize = LINE_DP * u * textScale
        val sidePad = if (centered) 40 * u else 32 * u
        val width = (if (centered) min(area.width() - 2 * sidePad, 720 * u) else area.width() - 2 * sidePad).toInt().coerceAtLeast(1)
        ensureLayouts(width, lineSize, centered)

        val snap = scroll.isNaN()
        val k = if (snap) 1f else 1f - exp(-dt * EASE_PER_SEC)
        var animating = false

        for (i in emphasis.indices) {
            val target = if (i == currentRow) 1f else 0f
            val e = emphasis[i] + (target - emphasis[i]) * k
            emphasis[i] = if (abs(target - e) < 0.002f) target else e
            if (emphasis[i] != target) animating = true
        }

        val n = rows.size
        val gap = 12 * u * textScale
        val scales = FloatArray(n) { MIN_SCALE + (1f - MIN_SCALE) * emphasis[it] }
        val heights = FloatArray(n) { layouts[it].height * scales[it] }
        val tops = FloatArray(n)
        var y = 0f
        for (i in 0 until n) {
            tops[i] = y
            y += heights[i] + gap
        }

        val active = currentRow.coerceIn(0, n - 1)
        val target = tops[active] + heights[active] / 2

        // Touch browsing: follow the finger (and fling), then glide back to the sung line.
        val now = SystemClock.uptimeMillis()
        if (flingVelocity != 0f) {
            manualOffset += flingVelocity * dt
            flingVelocity *= exp(-dt * FLING_DECAY)
            if (abs(flingVelocity) < 40f) flingVelocity = 0f
            browseUntil = max(browseUntil, now + BROWSE_HOLD_MS)
            animating = true
        }
        manualOffset = manualOffset.coerceIn(heights[0] / 2 - target, tops[n - 1] + heights[n - 1] / 2 - target)
        val browsing = now < browseUntil
        if (!browsing && manualOffset != 0f) {
            manualOffset -= manualOffset * k
            if (abs(manualOffset) < 0.5f) manualOffset = 0f
            animating = true
        }
        val browseTarget = if (browsing) 1f else 0f
        browseAmount += (browseTarget - browseAmount) * k
        if (abs(browseTarget - browseAmount) < 0.01f) browseAmount = browseTarget else animating = true

        val wanted = target + manualOffset
        scroll = if (snap || browsing) wanted else scroll + (wanted - scroll) * k
        if (abs(wanted - scroll) < 0.5f) scroll = wanted else animating = true

        val anchor = top + (bottom - top) * ANCHOR
        val fadeZone = (bottom - top) * EDGE_FADE
        val left = if (centered) area.exactCenterX() - width / 2f else area.left + sidePad
        // Lines scale towards their start edge: the centre when centred, else the reading start.
        val pivotX = if (centered) width / 2f else if (rtlSong) width.toFloat() else 0f
        sweepRect = null
        lineHits.clear()
        lyricsBounds = RectF(area.left.toFloat(), top, area.right.toFloat(), bottom)
        val currentLineColor = CoverTones.blend(Color.WHITE, toneLight, CURRENT_LINE_TINT)

        canvas.save()
        canvas.clipRect(area.left.toFloat(), top, area.right.toFloat(), bottom)
        for (i in 0 until n) {
            val lineTop = anchor + tops[i] - scroll
            if (lineTop > bottom || lineTop + heights[i] < top) continue

            val distance = abs(i - active)
            val follow = (NEIGHBOUR_ALPHA - FALLOFF_ALPHA * (distance - 1)).coerceAtLeast(MIN_ALPHA)
            val base = follow + (BROWSE_ALPHA - follow).coerceAtLeast(0f) * browseAmount
            val centre = lineTop + heights[i] / 2
            val edge = (min(centre - top, bottom - centre) / fadeZone).coerceIn(0f, 1f)
            // Neighbouring lines take the cover's pastel tone; the line being sung blends towards
            // near-white (keeping a hint of the tone) so it stays the most readable thing on screen.
            linePaint.color = CoverTones.blend(toneLight, currentLineColor, emphasis[i])
            val alpha = (base + (1f - base) * emphasis[i]) * edge
            val sweep = sweepProgress(i)
            if (centre in top..bottom) lineHits += RectF(left, lineTop, left + width, lineTop + heights[i]) to i

            canvas.save()
            canvas.translate(left, lineTop)
            canvas.scale(scales[i], scales[i], pivotX, 0f)
            if (sweep == null) {
                linePaint.alpha = (alpha * 255).toInt()
                layouts[i].draw(canvas)
            } else {
                // Karaoke: the whole line dimmed, then the sung part bright on top.
                linePaint.alpha = (alpha * (1f - (1f - SWEEP_DIM) * emphasis[i]) * 255).toInt()
                layouts[i].draw(canvas)
                linePaint.alpha = (alpha * 255).toInt()
                drawSweptPart(canvas, layouts[i], rows[i].length * sweep)
                sweepRect = Rect(left.toInt(), lineTop.toInt(), (left + width).toInt(), (lineTop + heights[i]).toInt() + 1)
            }
            canvas.restore()
        }
        canvas.restore()
        return animating
    }

    /** Sung fraction of the current lyric row, or null when it shouldn't sweep (notes, other rows). */
    private fun sweepProgress(row: Int): Float? {
        if (!karaoke || row != currentRow || rows.getOrNull(row) == MUSIC_NOTE) return null
        val starts = lineStarts ?: return null
        val line = row - 1 // row 0 is the intro note
        val start = starts.getOrNull(line) ?: return null
        val position = positionNow() + LyricsEngine.LEAD_MS + offsetMs
        return SweepEstimate.progress(rows[row], start, starts.getOrNull(line + 1), position)
    }

    /**
     * Redraws [layout] clipped to its first [chars] characters, line by line for wrapped text.
     * Right-to-left text (Arabic, Kurdish...) fills from the right edge towards the left.
     */
    private fun drawSweptPart(canvas: Canvas, layout: StaticLayout, chars: Float) {
        for (j in 0 until layout.lineCount) {
            val lineStart = layout.getLineStart(j)
            val lineEnd = layout.getLineEnd(j)
            if (chars <= lineStart) break
            val lineL = layout.getLineLeft(j)
            val lineR = layout.getLineRight(j)
            val clipL: Float
            val clipR: Float
            if (chars >= lineEnd) {
                clipL = lineL
                clipR = lineR
            } else {
                val c = chars.toInt().coerceIn(lineStart, lineEnd - 1)
                val rtl = layout.isRtlCharAt(c)
                val x0 = layout.getPrimaryHorizontal(c)
                val x1 = if (c + 1 < lineEnd) layout.getPrimaryHorizontal(c + 1) else if (rtl) lineL else lineR
                val edge = x0 + (x1 - x0) * (chars - c)
                if (rtl) { clipL = edge; clipR = lineR } else { clipL = lineL; clipR = edge }
            }
            canvas.save()
            canvas.clipRect(clipL, layout.getLineTop(j).toFloat(), clipR, layout.getLineBottom(j).toFloat())
            layout.draw(canvas)
            canvas.restore()
        }
    }

    private fun ensureLayouts(width: Int, textSize: Float, centered: Boolean) {
        if (width == layoutWidth && textSize == layoutTextSize && centered == layoutCentered && layouts.size == rows.size) return
        layoutWidth = width
        layoutTextSize = textSize
        layoutCentered = centered
        linePaint.textSize = textSize
        // One paragraph direction for the whole song, so every line shares an alignment edge
        // (right-to-left.md: consistent alignment in a list); bidi inside a line still works.
        val direction = if (rtlSong) TextDirectionHeuristics.RTL else TextDirectionHeuristics.LTR
        layouts = rows.map { text ->
            StaticLayout.Builder.obtain(text, 0, text.length, linePaint, width)
                .setAlignment(if (centered) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL)
                .setTextDirection(direction)
                .setLineSpacing(0f, 1.04f)
                .build()
        }
    }

    private fun isWide(area: Rect, u: Float) = area.width() >= area.height() * 1.45f && area.width() >= 560 * u

    /** True when most lyric lines start with a right-to-left letter. */
    private fun isMostlyRtl(lines: List<String>): Boolean {
        var rtl = 0
        var ltr = 0
        for (line in lines) {
            for (ch in line) {
                when (Character.getDirectionality(ch)) {
                    Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> { rtl++; break }
                    Character.DIRECTIONALITY_LEFT_TO_RIGHT -> { ltr++; break }
                    else -> Unit
                }
            }
        }
        return rtl > ltr
    }

    private companion object {
        val DEFAULT_ACCENT = Color.rgb(0x3A, 0x2F, 0x5C)
        /** 7X brand amber (7xdevs.com). */
        val BRAND_AMBER = Color.rgb(0xF5, 0xA5, 0x24)
        val TEXT_SCALES = floatArrayOf(0.85f, 1f, 1.2f)
        const val KEY_TEXT_SCALE = "text_scale"
        const val MUSIC_NOTE = "♪"
        const val KEY_MOVING_BACKGROUND = "moving_background"

        const val LINE_DP = 34f
        /** Share of a wide screen given to the now-playing column (lyrics get the rest). */
        const val PANEL_FRACTION = 0.30f
        /** Elapsed/total time labels; 14dp keeps them readable at arm's length. */
        const val TIME_DP = 14f
        const val THUMB_PX = 192
        const val BACKDROP_PX = 160
        const val BLUR_RADIUS = 10

        /** Neighbour lines are drawn at this fraction of the current line's size. */
        const val MIN_SCALE = 0.66f
        const val NEIGHBOUR_ALPHA = 0.5f
        const val FALLOFF_ALPHA = 0.14f
        const val MIN_ALPHA = 0.1f

        /** Where the current line sits, as a fraction of the lyrics area height. */
        const val ANCHOR = 0.4f
        const val EDGE_FADE = 0.2f
        const val EASE_PER_SEC = 8f
        const val BACKDROP_FADE_SEC = 0.7f
        const val PRESS_SEC = 0.22f
        const val LOCK_RETRY_MS = 250L
        const val WATCHDOG_MS = 1_000L
        /** A playing screen that hasn't drawn for this long is considered stuck. */
        const val STALL_MS = 2_000L
        const val TONE_FADE_SEC = 0.6f
        /** How much of the cover tone shows in the current lyric line (0 = pure white). */
        const val CURRENT_LINE_TINT = 0.3f
        /** Brightness of the not-yet-sung part of the current line, relative to the sung part. */
        const val SWEEP_DIM = 0.45f
        /** Play/pause pill width relative to its height. */
        const val PILL_ASPECT = 1.8f
        const val MAX_LOCK_FAILURES = 4
        /** Sync controls close themselves after this long without a tap. */
        const val SYNC_IDLE_MS = 12_000L
        /** Background drift advances at this interval (8fps is plenty for motion this slow). */
        const val DRIFT_FULL_FRAME_MS = 125L
        /** The progress wave needs a steadier rate than the background drift to look smooth. */
        const val WAVE_FRAME_MS = 33L
        const val WAVE_AMPLITUDE_DP = 4f
        const val WAVE_LENGTH_DP = 40f
        /** Wave crests travel at this many wavelengths per second while playing. */
        const val WAVE_SPEED = 0.32f
        const val DRIFT_DOWNSCALE = 4

        private class DriftLayer(
            val scale: Float, val alpha: Float, val startDeg: Float, val degPerSec: Float,
            val orbit: Float, val orbitSec: Float,
        )

        /** Slow, mismatched speeds so the pattern never visibly repeats. */
        private val DRIFT_LAYERS = listOf(
            DriftLayer(scale = 1.0f, alpha = 1.0f, startDeg = 0f, degPerSec = 4.0f, orbit = 0.10f, orbitSec = 37f),
            DriftLayer(scale = 1.25f, alpha = 0.65f, startDeg = 120f, degPerSec = -5.5f, orbit = 0.16f, orbitSec = 29f),
            DriftLayer(scale = 1.5f, alpha = 0.5f, startDeg = 240f, degPerSec = 3.0f, orbit = 0.22f, orbitSec = 43f),
        )
        const val TAG = "LyricsRenderer"
        /** How long the lyrics stay where the user scrolled them before following the song again. */
        const val BROWSE_HOLD_MS = 4_000L
        const val FLING_DECAY = 4f
        /** Brightness of every line while browsing, so the whole song can be read. */
        const val BROWSE_ALPHA = 0.72f
    }
}
