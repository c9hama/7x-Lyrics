package com.aalyrics.mirror

import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.graphics.scale
import androidx.media.MediaBrowserServiceCompat
import com.aalyrics.CrashLog
import com.aalyrics.LyricsEngine
import com.aalyrics.R
import com.aalyrics.car.LyricsView
import com.aalyrics.lyrics.Lyrics
import com.aalyrics.source.NowPlayingTracker
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus

/**
 * The media app Android Auto sees. It copies the real player's session (art,
 * progress, controls) but puts the current lyric line in the title, and
 * forwards every button press back to the real player.
 */
class LyricsMirrorService : MediaBrowserServiceCompat() {
    private lateinit var session: MediaSessionCompat
    private val scope = MainScope() + CrashLog.handler

    private var lastPlayback: Any? = Unit
    private var artFor: MediaMetadata? = null
    private var art: Bitmap? = null
    private var sourceCover: Bitmap? = null
    private var lyricArt: Bitmap? = null
    private var lyricArtKey: Any? = null
    private var listedTrack: LyricsEngine.Track? = null

    override fun onCreate() {
        super.onCreate()
        session = MediaSessionCompat(this, "AALyrics").apply {
            setCallback(ForwardingCallback())
            isActive = true
        }
        sessionToken = session.sessionToken

        NowPlayingTracker.ensureStarted(this)
        LyricsEngine.start(this)
        scope.launch {
            combine(NowPlayingTracker.state, LyricsEngine.display, LyricsView.isOpen, ::Triple)
                .collect { (source, display, lyricsViewOpen) -> publish(source, display, lyricsViewOpen) }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        session.release()
        super.onDestroy()
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?) = BrowserRoot(ROOT_ID, null)

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaItem>>) {
        if (parentId != ROOT_ID) return result.sendResult(mutableListOf())
        val track = LyricsEngine.display.value.track
        val description = MediaDescriptionCompat.Builder()
            .setMediaId(NOW_PLAYING_ID)
            .setTitle(track?.title ?: getString(R.string.now_playing))
            .setSubtitle(track?.artist)
            .build()
        result.sendResult(mutableListOf(MediaItem(description, MediaItem.FLAG_PLAYABLE)))
    }

    private fun publish(source: NowPlayingTracker.Source, display: LyricsEngine.Display, lyricsViewOpen: Boolean) {
        if (display.track != listedTrack) {
            listedTrack = display.track
            notifyChildrenChanged(ROOT_ID)
        }

        if (!source.listening) {
            lastPlayback = Unit
            session.setMetadata(simpleMetadata(getString(R.string.app_name), getString(R.string.need_access_short)))
            session.setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setState(PlaybackStateCompat.STATE_ERROR, 0, 0f)
                    .setErrorMessage(PlaybackStateCompat.ERROR_CODE_APP_ERROR, getString(R.string.need_access_long))
                    .build()
            )
            return
        }
        if (source.controller == null) {
            lastPlayback = Unit
            session.setMetadata(simpleMetadata(getString(R.string.waiting_title), getString(R.string.waiting_subtitle)))
            session.setPlaybackState(PlaybackStateCompat.Builder().setState(PlaybackStateCompat.STATE_NONE, 0, 0f).build())
            return
        }

        session.setMetadata(lyricMetadata(source.metadata, display, plain = lyricsViewOpen))
        if (source.playback !== lastPlayback) {
            lastPlayback = source.playback
            session.setPlaybackState(mirrorPlayback(source.playback))
        }
    }

    /** With [plain] (lyrics view open) the card shows just song and artist instead of the lyric. */
    private fun lyricMetadata(meta: MediaMetadata?, display: LyricsEngine.Display, plain: Boolean): MediaMetadataCompat {
        val title = display.track?.title ?: meta?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artist = display.track?.artist.orEmpty()
        val line = display.currentLine.takeUnless { plain }
        val note = if (plain) null else when (val s = display.status) {
            LyricsEngine.Status.Loading -> getString(R.string.status_loading)
            is LyricsEngine.Status.Failed -> getString(R.string.status_failed)
            is LyricsEngine.Status.Ready -> when (s.lyrics) {
                is Lyrics.Plain -> getString(R.string.status_unsynced)
                Lyrics.Instrumental -> getString(R.string.status_instrumental)
                Lyrics.NotFound -> getString(R.string.status_not_found)
                is Lyrics.Synced -> null
            }
            LyricsEngine.Status.Idle -> null
        }

        // The big title row shows the lyric; the song moves to the subtitle.
        val big = line ?: title
        val small = when {
            line != null -> listOf(title, artist).filter { it.isNotBlank() }.joinToString(" · ")
            note != null -> listOf(artist, note).filter { it.isNotBlank() }.joinToString(" · ")
            else -> artist
        }

        return MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, NOW_PLAYING_ID)
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, big)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, big)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, small)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, small)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, meta?.getString(MediaMetadata.METADATA_KEY_ALBUM))
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L)
            .apply { artwork(meta, display, plain)?.let { putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) } }
            .build()
    }

    /**
     * Metadata is re-sent on every lyric line, so keep the art small (and
     * scaled only once per track) to stay well under the binder size limit.
     */
    /**
     * While a lyric line is being sung, the "album art" is a picture of the lyrics (see
     * [LyricsArt]); otherwise, or while the Lyrics View is open, it's the real cover.
     */
    private fun artwork(meta: MediaMetadata?, display: LyricsEngine.Display, plain: Boolean): Bitmap? {
        val cover = albumArt(meta)
        val line = display.currentLine
        if (plain || line == null) return cover
        val key = Triple(meta, display.lineIndex, display.track)
        if (key == lyricArtKey) return lyricArt
        lyricArtKey = key
        lyricArt = try {
            LyricsArt.render(sourceCover, display.previousLine, line, display.nextLine)
        } catch (e: Exception) {
            CrashLog.record("lyrics art", e)
            cover
        }
        return lyricArt
    }

    private fun albumArt(meta: MediaMetadata?): Bitmap? {
        if (meta === artFor) return art
        artFor = meta
        val src = meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        sourceCover = src
        art = src?.let {
            val factor = ART_MAX_PX.toFloat() / maxOf(it.width, it.height)
            val scaled = if (factor < 1f) it.scale((it.width * factor).toInt(), (it.height * factor).toInt()) else it
            scaled.copy(Bitmap.Config.RGB_565, false)
        }
        return art
    }

    private fun simpleMetadata(title: String, subtitle: String) = MediaMetadataCompat.Builder()
        .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, NOW_PLAYING_ID)
        .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
        .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
        .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, subtitle)
        .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, subtitle)
        .build()

    @Suppress("WrongConstant") // framework and compat state constants share the same values
    private fun mirrorPlayback(pb: PlaybackState?): PlaybackStateCompat {
        val builder = PlaybackStateCompat.Builder()
        if (pb == null) return builder.setState(PlaybackStateCompat.STATE_NONE, 0, 0f).build()
        val actions = (pb.actions.takeIf { it != 0L } ?: DEFAULT_ACTIONS) or ALWAYS_ACTIONS
        return builder
            .setActions(actions)
            .setState(pb.state, pb.position, pb.playbackSpeed, pb.lastPositionUpdateTime)
            .build()
    }

    private inner class ForwardingCallback : MediaSessionCompat.Callback() {
        private val controls get() = NowPlayingTracker.state.value.controller?.transportControls

        override fun onPlay() { controls?.play() }
        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) { controls?.play() }
        override fun onPlayFromSearch(query: String?, extras: Bundle?) { controls?.playFromSearch(query, extras) }
        override fun onPause() { controls?.pause() }
        override fun onStop() { controls?.pause() }
        override fun onSkipToNext() { controls?.skipToNext() }
        override fun onSkipToPrevious() { controls?.skipToPrevious() }
        override fun onSeekTo(pos: Long) { controls?.seekTo(pos) }
        override fun onFastForward() { controls?.fastForward() }
        override fun onRewind() { controls?.rewind() }
    }

    private companion object {
        const val ROOT_ID = "root"
        const val NOW_PLAYING_ID = "now_playing"
        const val ART_MAX_PX = 256

        const val ALWAYS_ACTIONS = PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
            PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH or
            PlaybackStateCompat.ACTION_PLAY_PAUSE
        const val DEFAULT_ACTIONS = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
            PlaybackStateCompat.ACTION_SEEK_TO
    }
}
