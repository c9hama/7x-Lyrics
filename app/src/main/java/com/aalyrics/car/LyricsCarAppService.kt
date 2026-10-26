package com.aalyrics.car

import android.content.Intent
import android.media.session.PlaybackState
import androidx.car.app.AppManager
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.aalyrics.LyricsEngine
import com.aalyrics.source.NowPlayingTracker
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Registers as a navigation app, the only Android Auto app type allowed to
 * draw freely on the car screen, and draws the lyrics view instead of a map.
 */
class LyricsCarAppService : CarAppService() {
    // Personal sideload build: accept any Android Auto host.
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = LyricsSession()
}

private class LyricsSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        NowPlayingTracker.ensureStarted(carContext)
        LyricsEngine.start(carContext)

        val renderer = LyricsRenderer(carContext)
        // Nav apps must handle "stop navigation"; there's nothing to stop here.
        carContext.getCarService(NavigationManager::class.java).setNavigationManagerCallback(
            object : NavigationManagerCallback {}
        )
        renderer.onControl = { control ->
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
                // Handled inside the renderer (show/hide the sync controls).
                LyricsRenderer.Control.ADJUST, LyricsRenderer.Control.DONE -> Unit
            }
        }
        renderer.onSeek = { positionMs -> NowPlayingTracker.state.value.controller?.transportControls?.seekTo(positionMs) }
        renderer.onSurfaceLost = {
            val appManager = carContext.getCarService(AppManager::class.java)
            appManager.setSurfaceCallback(null)
            appManager.setSurfaceCallback(renderer)
        }
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                // Register on every start, not just once: when Android Auto revives the app on
                // connect, the first registration can fail because the host isn't ready yet.
                carContext.getCarService(AppManager::class.java).setSurfaceCallback(renderer)
                LyricsView.setOpen(true)
            }

            override fun onStop(owner: LifecycleOwner) = LyricsView.setOpen(false)
            override fun onDestroy(owner: LifecycleOwner) = renderer.release()
        })
        lifecycleScope.launch {
            combine(NowPlayingTracker.state, LyricsEngine.display, ::Pair).collect { (source, display) ->
                renderer.update(source, display)
            }
        }
        return LyricsScreen(carContext, renderer)
    }
}
