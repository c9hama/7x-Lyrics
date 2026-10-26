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
import android.text.TextPaint
import android.text.TextUtils
import android.view.Choreographer
import android.view.Surface
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.core.content.edit
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.scale
import com.aalyrics.LyricsEngine
import com.aalyrics.R
import com.aalyrics.lyrics.Lyrics
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
    enum class Control { PREVIOUS, PLAY_PAUSE, NEXT, EARLIER, LATER, RESET, DONE, ADJUST, DISMISS }

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

    private val res = context.resources
    private val prefs = context.getSharedPreferences("lyrics_view", Context.MODE_PRIVATE)
    private var textScale = prefs.getFloat(KEY_TEXT_SCALE, 1f)

    private val choreographer = Choreographer.getInstance()
    private val frameCallback = Choreographer.FrameCallback(::drawFrame)
    private var frameScheduled = false
    private var frameDelayed = false
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

    // Lyrics layout and animation
    private var layouts: List<StaticLayout> = emptyList()
    private var layoutWidth = -1
    private var layoutTextSize = -1f
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
    private val iconPath = Path()
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
        requestFrame()
    }

    override fun onSurfaceDestroyed(container: SurfaceContainer) {
        surface = null
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

    override fun onClick(x: Float, y: Float) {
        progressBounds?.takeIf { it.contains(x, y) && durationMs > 0 }?.let { bar ->
            val target = (((x - progressLeft) / progressWidth).coerceIn(0f, 1f) * durationMs).toLong()
            // Show the new position right away; the player's own update follows shortly.
            seekOverride = target to SystemClock.elapsedRealtime()
            onSeek?.invoke(target)
            requestFrame()
            return
        }
        val hit = controlBounds.entries.firstOrNull { it.value.contains(x, y) }?.key ?: return
        pressed = hit
        pressAmount = 1f
        when (hit) {
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
        main.removeCallbacks(exitSyncMode)
        worker.shutdownNow()
        choreographer.removeFrameCallback(frameCallback)
        frameScheduled = false
        surface = null
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
        if (source.playback !== playback) seekOverride = null
        playback = source.playback
        durationMs = source.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 }
            ?: display.track?.durationMs ?: 0L

        val synced = ((display.status as? LyricsEngine.Status.Ready)?.lyrics as? Lyrics.Synced)?.lines
        val newRows: List<String> =
            if (synced == null) emptyList() else listOf(MUSIC_NOTE) + synced.map { it.text.ifBlank { MUSIC_NOTE } }
        if (newRows != rows) {
            rows = newRows
            layoutWidth = -1
            scroll = Float.NaN
            emphasis = FloatArray(newRows.size)
        }
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
        } else {
            // A real blur at a decent resolution takes a few ms, so keep it off the frame loop.
            worker.execute {
                val blurred = makeBackdrop(art)
                val luma = blurred?.let(::averageLuma) ?: 0f
                main.post {
                    if (token == artToken) {
                        backdropLuma = luma
                        applyBackdrop(blurred)
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
        choreographer.removeFrameCallback(frameCallback)
        frameScheduled = true
        frameDelayed = false
        choreographer.postFrameCallback(frameCallback)
    }

    private fun requestFrameDelayed(delayMs: Long) {
        if (surface == null || frameScheduled) return
        frameScheduled = true
        frameDelayed = true
        choreographer.postFrameCallbackDelayed(frameCallback, delayMs)
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
        val driftDue = movingBackground && playing && backdrop != null &&
            frameNanos - lastDriftFrameNanos >= DRIFT_FULL_FRAME_MS * 1_000_000
        val fullFrame = needsFullFrame || driftDue || waveRect == null
        val dirty = if (fullFrame) null else waveRect?.let { Rect(it) }
        val canvas = try {
            s.lockCanvas(dirty)
        } catch (e: Exception) {
            if (lockFailures == 0) Log.w(TAG, "lockCanvas failed, retrying", e)
            if (++lockFailures >= MAX_LOCK_FAILURES) {
                Log.w(TAG, "surface looks dead after $lockFailures failed locks; asking the host for a new one")
                surface = null
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
            animating = drawBackdrop(canvas, dt, frameNanos, recompose = driftDue)
            controlBounds.clear() // re-registered by whatever is drawn this frame
            val headerBottom = drawWarning(canvas, area, u, drawHeader(canvas, area, u))
            val footer = if (syncMode) drawSyncFooter(canvas, area, u, dt) else drawFooter(canvas, area, u, dt)
            animating = footer.animating || animating
            animating = if (message != null || rows.isEmpty()) {
                drawMessage(canvas, area, headerBottom, footer.top, u)
                animating
            } else {
                drawLyrics(canvas, area, headerBottom, footer.top, u, dt) || animating
            }
        } finally {
            s.unlockCanvasAndPost(canvas)
        }

        when {
            animating -> requestFrame()
            else -> {
                // Restart easing from dt = 0 next time instead of jumping.
                lastFrameNanos = 0L
                // While playing, the progress wave (and background drift) keep moving.
                if (playing) requestFrameDelayed(if (waveRect != null) WAVE_FRAME_MS else DRIFT_FULL_FRAME_MS)
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
        fillPaint.color = Color.WHITE
        fillPaint.alpha = when {
            filled -> 240
            pressedNow -> 95
            else -> 42
        }
        canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, fillPaint)
        pillPaint.color = if (filled) ColorUtils.blendARGB(accent, Color.BLACK, 0.6f) else Color.WHITE
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
        progressBounds = null
        waveRect = null
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

    /** Progress bar and centred transport controls. */
    private fun drawFooter(canvas: Canvas, area: Rect, u: Float, dt: Float): Footer {
        val playSize = 56 * u
        val sideSize = 46 * u
        val spacing = 34 * u
        val cx = area.exactCenterX()
        val cy = area.bottom - 16 * u - playSize / 2

        if (pressAmount > 0f) pressAmount = max(0f, pressAmount - dt / PRESS_SEC)
        val waving = advanceWave(dt)
        fun pressScale(c: Control) = if (pressed == c) 1f - 0.14f * pressAmount else 1f

        val isPlaying = playback?.state == PlaybackState.STATE_PLAYING
        val prevX = cx - playSize / 2 - spacing - sideSize / 2
        val nextX = cx + playSize / 2 + spacing + sideSize / 2

        // Play / pause: solid white disc with a dark glyph.
        val playR = playSize / 2 * pressScale(Control.PLAY_PAUSE)
        fillPaint.color = Color.WHITE
        fillPaint.alpha = 240
        canvas.drawCircle(cx, cy, playR, fillPaint)
        fillPaint.color = ColorUtils.blendARGB(accent, Color.BLACK, 0.6f)
        if (isPlaying) drawPause(canvas, cx, cy, playR * 0.78f) else drawPlay(canvas, cx, cy, playR * 0.8f)

        fillPaint.color = Color.WHITE
        fillPaint.alpha = 225
        drawSkip(canvas, prevX, cy, sideSize / 2 * pressScale(Control.PREVIOUS), forward = false)
        fillPaint.alpha = 225
        drawSkip(canvas, nextX, cy, sideSize / 2 * pressScale(Control.NEXT), forward = true)

        val touch = 8 * u
        controlBounds[Control.PREVIOUS] = RectF(prevX - sideSize / 2 - touch, cy - sideSize / 2 - touch, prevX + sideSize / 2 + touch, cy + sideSize / 2 + touch)
        controlBounds[Control.PLAY_PAUSE] = RectF(cx - playSize / 2 - touch, cy - playSize / 2 - touch, cx + playSize / 2 + touch, cy + playSize / 2 + touch)
        controlBounds[Control.NEXT] = RectF(nextX - sideSize / 2 - touch, cy - sideSize / 2 - touch, nextX + sideSize / 2 + touch, cy + sideSize / 2 + touch)

        // Progress, YouTube Music style: the played part is a travelling wave that settles flat
        // when paused, the rest is a dim straight line, with a small vertical thumb between.
        //   ∿∿∿∿∿∿∿∿∿|──────────────
        //   1:15                 3:34
        var footerTop = cy - playSize / 2 - 10 * u
        progressBounds = null
        waveRect = null
        if (durationMs > 0) {
            timePaint.textSize = 12.5f * u
            val timeBaseline = cy - playSize / 2 - 10 * u
            val barY = timeBaseline + timePaint.ascent() - 12 * u
            val barWidth = min(area.width() * 0.7f, 620 * u)
            val left = cx - barWidth / 2
            val right = cx + barWidth / 2
            val fraction = (positionNow().toFloat() / durationMs).coerceIn(0f, 1f)
            val playedX = left + barWidth * fraction
            val stroke = 3.2f * u
            val gap = 7 * u

            // Unplayed: dim straight line after a small gap.
            strokePaint.strokeWidth = stroke
            strokePaint.color = Color.WHITE
            strokePaint.alpha = 77
            if (right > playedX + gap) canvas.drawLine(playedX + gap, barY, right, barY, strokePaint)

            // Played: sine wave whose height eases with play/pause.
            val amplitude = WAVE_AMPLITUDE_DP * u * waveAmount
            val wavelength = WAVE_LENGTH_DP * u
            val end = playedX - gap
            if (end > left) {
                strokePaint.alpha = 240
                if (amplitude < 0.3f) {
                    canvas.drawLine(left, barY, end, barY, strokePaint)
                } else {
                    wavePath.reset()
                    val step = 2 * u
                    var x = left
                    wavePath.moveTo(x, barY + amplitude * sin(2f * PI.toFloat() * (x - left) / wavelength - wavePhase))
                    while (x < end) {
                        x = min(x + step, end)
                        wavePath.lineTo(x, barY + amplitude * sin(2f * PI.toFloat() * (x - left) / wavelength - wavePhase))
                    }
                    canvas.drawPath(wavePath, strokePaint)
                }
            }

            // Thumb: short vertical pill.
            fillPaint.color = Color.WHITE
            fillPaint.alpha = 255
            val thumbW = 4.5f * u
            val thumbH = 18 * u
            canvas.drawRoundRect(playedX - thumbW / 2, barY - thumbH / 2, playedX + thumbW / 2, barY + thumbH / 2, thumbW / 2, thumbW / 2, fillPaint)

            // Generous touch target: the whole row, not just the thin line.
            progressLeft = left
            progressWidth = barWidth
            progressBounds = RectF(left - 12 * u, barY - 20 * u, right + 12 * u, barY + 16 * u)

            timePaint.alpha = 200
            canvas.drawText(formatTime(positionNow()), left, timeBaseline, timePaint)
            val total = formatTime(durationMs)
            canvas.drawText(total, right - timePaint.measureText(total), timeBaseline, timePaint)
            footerTop = barY - 20 * u
            waveRect = Rect(
                (left - 16 * u).toInt(), (barY - 14 * u).toInt(),
                (right + 16 * u).toInt(), (timeBaseline + timePaint.descent() + 4 * u).toInt(),
            )
        }
        return Footer(footerTop, pressAmount > 0f || waving)
    }

    /**
     * Moves the progress wave along while playing and eases its height to flat when paused.
     * Returns true while the wave is still settling (the steady travel is paced separately).
     */
    private fun advanceWave(dt: Float): Boolean {
        val playing = playback?.state == PlaybackState.STATE_PLAYING
        // dt is 0 on the first frame after idling; use the paced frame interval instead.
        val step = if (dt > 0f) dt else if (playing) WAVE_FRAME_MS / 1000f else 0f
        if (playing) wavePhase = (wavePhase + step * WAVE_SPEED * 2f * PI.toFloat()) % (2f * PI.toFloat())
        val target = if (playing) 1f else 0f
        waveAmount += (target - waveAmount) * (1f - exp(-step * 6f))
        if (abs(target - waveAmount) < 0.01f) waveAmount = target
        return waveAmount != target
    }

    private fun drawPlay(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        iconPath.reset()
        iconPath.moveTo(cx - r * 0.32f, cy - r * 0.5f)
        iconPath.lineTo(cx + r * 0.52f, cy)
        iconPath.lineTo(cx - r * 0.32f, cy + r * 0.5f)
        iconPath.close()
        canvas.drawPath(iconPath, fillPaint)
    }

    private fun drawPause(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val w = r * 0.2f
        val h = r * 0.5f
        val gap = r * 0.14f
        canvas.drawRoundRect(cx - gap - w, cy - h, cx - gap, cy + h, w / 3, w / 3, fillPaint)
        canvas.drawRoundRect(cx + gap, cy - h, cx + gap + w, cy + h, w / 3, w / 3, fillPaint)
    }

    /** Two chevron triangles and a bar, mirrored for previous. */
    private fun drawSkip(canvas: Canvas, cx: Float, cy: Float, r: Float, forward: Boolean) {
        val d = if (forward) 1f else -1f
        val h = r * 0.42f
        iconPath.reset()
        iconPath.moveTo(cx - d * r * 0.55f, cy - h)
        iconPath.lineTo(cx - d * r * 0.05f, cy)
        iconPath.lineTo(cx - d * r * 0.55f, cy + h)
        iconPath.close()
        iconPath.moveTo(cx - d * r * 0.08f, cy - h)
        iconPath.lineTo(cx + d * r * 0.42f, cy)
        iconPath.lineTo(cx - d * r * 0.08f, cy + h)
        iconPath.close()
        canvas.drawPath(iconPath, fillPaint)
        val barW = r * 0.1f
        val barX = cx + d * r * 0.5f
        canvas.drawRoundRect(min(barX, barX + d * barW), cy - h, max(barX, barX + d * barW), cy + h, barW / 2, barW / 2, fillPaint)
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
        val text = message ?: return
        linePaint.textSize = 22 * u
        linePaint.alpha = 190
        val width = min(area.width() - 80 * u, 640 * u).toInt().coerceAtLeast(1)
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, linePaint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .build()
        canvas.save()
        canvas.translate(area.exactCenterX() - width / 2f, top + (bottom - top - layout.height) / 2)
        layout.draw(canvas)
        canvas.restore()
        layoutWidth = -1 // linePaint size was changed
    }

    /** Returns true while anything is still easing. */
    private fun drawLyrics(canvas: Canvas, area: Rect, top: Float, bottom: Float, u: Float, dt: Float): Boolean {
        val lineSize = LINE_DP * u * textScale
        val width = min(area.width() - 80 * u, 720 * u).toInt().coerceAtLeast(1)
        ensureLayouts(width, lineSize)

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
        scroll = if (snap) target else scroll + (target - scroll) * k
        if (abs(target - scroll) < 0.5f) scroll = target else animating = true

        val anchor = top + (bottom - top) * ANCHOR
        val fadeZone = (bottom - top) * EDGE_FADE
        val left = area.exactCenterX() - width / 2f

        canvas.save()
        canvas.clipRect(area.left.toFloat(), top, area.right.toFloat(), bottom)
        for (i in 0 until n) {
            val lineTop = anchor + tops[i] - scroll
            if (lineTop > bottom || lineTop + heights[i] < top) continue

            val distance = abs(i - active)
            val base = (NEIGHBOUR_ALPHA - FALLOFF_ALPHA * (distance - 1)).coerceAtLeast(MIN_ALPHA)
            val centre = lineTop + heights[i] / 2
            val edge = (min(centre - top, bottom - centre) / fadeZone).coerceIn(0f, 1f)
            linePaint.alpha = ((base + (1f - base) * emphasis[i]) * edge * 255).toInt()

            canvas.save()
            canvas.translate(left, lineTop)
            canvas.scale(scales[i], scales[i], width / 2f, 0f)
            layouts[i].draw(canvas)
            canvas.restore()
        }
        canvas.restore()
        return animating
    }

    private fun ensureLayouts(width: Int, textSize: Float) {
        if (width == layoutWidth && textSize == layoutTextSize && layouts.size == rows.size) return
        layoutWidth = width
        layoutTextSize = textSize
        linePaint.textSize = textSize
        layouts = rows.map { text ->
            StaticLayout.Builder.obtain(text, 0, text.length, linePaint, width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, 1.04f)
                .build()
        }
    }

    private companion object {
        val DEFAULT_ACCENT = Color.rgb(0x3A, 0x2F, 0x5C)
        val TEXT_SCALES = floatArrayOf(0.85f, 1f, 1.2f)
        const val KEY_TEXT_SCALE = "text_scale"
        const val MUSIC_NOTE = "♪"
        const val KEY_MOVING_BACKGROUND = "moving_background"

        const val LINE_DP = 34f
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
    }
}
