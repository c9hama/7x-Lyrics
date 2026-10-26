package com.aalyrics.source

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Follows the media session of whichever player app is currently playing. */
object NowPlayingTracker {
    data class Source(
        val listening: Boolean = false,
        val controller: MediaController? = null,
        val metadata: MediaMetadata? = null,
        val playback: PlaybackState? = null,
        val queue: List<QueueTrack> = emptyList(),
    )

    /** Picked first when several apps have a session. Add your player's package here. */
    private val PREFERRED_PACKAGES = listOf(
        "com.google.android.apps.youtube.music",
        "app.revanced.android.apps.youtube.music",
        "app.morphe.android.apps.youtube.music",
    )

    private val _state = MutableStateFlow(Source())
    val state: StateFlow<Source> = _state.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private var manager: MediaSessionManager? = null
    private var ownPackage = ""
    private val watched = mutableMapOf<String, Pair<MediaController, MediaController.Callback>>()
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { watch(it.orEmpty()) }

    fun hasNotificationAccess(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    /** Returns false when notification access hasn't been granted yet. */
    @MainThread
    fun ensureStarted(context: Context): Boolean {
        if (manager != null) return true
        if (!hasNotificationAccess(context)) return false
        val app = context.applicationContext
        val m = app.getSystemService(MediaSessionManager::class.java)
        val component = ComponentName(app, MediaListenerService::class.java)
        ownPackage = app.packageName
        try {
            m.addOnActiveSessionsChangedListener(sessionsListener, component, main)
            manager = m
            watch(m.getActiveSessions(component))
        } catch (e: SecurityException) {
            manager = null
            return false
        }
        return true
    }

    @MainThread
    fun stop() {
        manager?.removeOnActiveSessionsChangedListener(sessionsListener)
        manager = null
        watched.values.forEach { (c, cb) -> c.unregisterCallback(cb) }
        watched.clear()
        _state.value = Source()
    }

    private fun watch(controllers: List<MediaController>) {
        val current = controllers.filter { it.packageName != ownPackage }.associateBy { it.sessionToken.toString() }
        (watched.keys - current.keys).forEach { key -> watched.remove(key)?.let { (c, cb) -> c.unregisterCallback(cb) } }
        for ((key, c) in current) {
            if (key in watched) continue
            val cb = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) = reselect()
                override fun onPlaybackStateChanged(state: PlaybackState?) = reselect()
                override fun onQueueChanged(queue: MutableList<MediaSession.QueueItem>?) = reselect()
                override fun onSessionDestroyed() {
                    watched.remove(key)?.let { (c, cb) -> c.unregisterCallback(cb) }
                    reselect()
                }
            }
            c.registerCallback(cb, main)
            watched[key] = c to cb
        }
        reselect()
    }

    private fun reselect() {
        val all = watched.values.map { it.first }
        fun rank(c: MediaController) = PREFERRED_PACKAGES.indexOf(c.packageName).let { if (it < 0) Int.MAX_VALUE else it }
        val selectedPkg = _state.value.controller?.packageName
        val playing = all.filter { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        val chosen = playing.minByOrNull(::rank)
            ?: all.firstOrNull { it.packageName == selectedPkg }
            ?: all.minByOrNull(::rank)
        _state.value = Source(
            listening = manager != null,
            controller = chosen,
            metadata = chosen?.metadata,
            playback = chosen?.playbackState,
            queue = chosen?.queue.orEmpty().mapNotNull { item ->
                val d = item.description
                val title = d.title?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                QueueTrack(item.queueId, title, d.subtitle?.toString().orEmpty())
            },
        )
    }
}
