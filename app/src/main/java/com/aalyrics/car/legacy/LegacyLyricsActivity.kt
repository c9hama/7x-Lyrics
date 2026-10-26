package com.aalyrics.car.legacy

import android.graphics.Rect
import android.media.session.PlaybackState
import android.os.Bundle
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.car.app.SurfaceContainer
import com.aalyrics.AppSettings
import com.aalyrics.CrashLog
import com.aalyrics.LyricsEngine
import com.aalyrics.car.CarKeepAliveService
import com.aalyrics.car.LyricsRenderer
import com.aalyrics.source.NowPlayingTracker
import com.google.android.apps.auto.sdk.CarActivity
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus

/**
 * The legacy-SDK car screen: a plain SurfaceView handed to the same [LyricsRenderer] the
 * Car App Library version uses, so both look identical.
 */
class LegacyLyricsActivity : CarActivity() {
    private lateinit var renderer: LyricsRenderer
    private val scope = MainScope() + CrashLog.handler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        CarKeepAliveService.start(this)
        NowPlayingTracker.ensureStarted(this)
        LyricsEngine.start(this)
        AppSettings.load(this)
        hideCarChrome()

        renderer = LyricsRenderer(this)
        renderer.onControl = ::handleControl
        renderer.onSeek = { NowPlayingTracker.state.value.controller?.transportControls?.seekTo(it) }
        // No host to ask for a fresh surface here; the SurfaceView recreates its own.
        renderer.onSurfaceLost = {}

        val view = SurfaceView(this)
        // A SurfaceView normally sits behind the window, and the SDK paints its (hidden) app-bar
        // background over the top strip of that window. Draw above the window instead.
        view.setZOrderOnTop(true)
        view.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = Unit

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                val dpi = resources.displayMetrics.densityDpi
                renderer.onSurfaceAvailable(SurfaceContainer(holder.surface, width, height, dpi))
                renderer.onVisibleAreaChanged(Rect(0, 0, width, height))
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                renderer.onSurfaceDestroyed(SurfaceContainer(holder.surface, 0, 0, 0))
            }
        })
        view.setOnTouchListener { v, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                renderer.onClick(event.x, event.y)
                v.performClick()
            }
            true
        }
        setContentView(view)

        scope.launch {
            combine(NowPlayingTracker.state, LyricsEngine.display, ::Pair).collect { (source, display) ->
                try {
                    renderer.update(source, display)
                } catch (e: Exception) {
                    CrashLog.record("legacy renderer.update", e)
                }
            }
        }
        scope.launch { AppSettings.karaoke.collect(renderer::setKaraoke) }
    }

    /** Full screen: hide the SDK's title bar and menu button (as Fermata does). */
    private fun hideCarChrome() {
        setIgnoreConfigChanges(-1)
        runCatching {
            val ui = carUiController
            ui.statusBarController.hideAppHeader()
            ui.statusBarController.setAppBarAlpha(0f)
            ui.menuController.hideMenuButton()
        }.onFailure { CrashLog.record("hide car chrome", it) }
    }

    override fun onStart() {
        super.onStart()
        hideCarChrome()
    }

    private fun handleControl(control: LyricsRenderer.Control) {
        val controls = NowPlayingTracker.state.value.controller?.transportControls
        val playing = NowPlayingTracker.state.value.playback?.state == PlaybackState.STATE_PLAYING
        when (control) {
            LyricsRenderer.Control.PREVIOUS -> controls?.skipToPrevious()
            LyricsRenderer.Control.PLAY_PAUSE -> if (playing) controls?.pause() else controls?.play()
            LyricsRenderer.Control.NEXT -> controls?.skipToNext()
            LyricsRenderer.Control.EARLIER -> LyricsEngine.nudge(LyricsEngine.SYNC_STEP_MS)
            LyricsRenderer.Control.LATER -> LyricsEngine.nudge(-LyricsEngine.SYNC_STEP_MS)
            LyricsRenderer.Control.RESET -> LyricsEngine.resetOffset()
            LyricsRenderer.Control.DISMISS -> LyricsEngine.dismissTimingWarning()
            LyricsRenderer.Control.ADJUST, LyricsRenderer.Control.DONE -> Unit
        }
    }

    override fun onDestroy() {
        scope.cancel()
        renderer.release()
        CarKeepAliveService.stop(this)
        super.onDestroy()
    }
}
