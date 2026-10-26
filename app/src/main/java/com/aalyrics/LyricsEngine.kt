package com.aalyrics

import android.content.Context
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.SystemClock
import com.aalyrics.lyrics.LrcParser
import com.aalyrics.lyrics.Lyrics
import com.aalyrics.lyrics.LyricsRepository
import com.aalyrics.lyrics.SyncSettings
import com.aalyrics.source.NowPlayingTracker
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Fetches lyrics for the current track and keeps track of which line is being sung. */
object LyricsEngine {
    /** Show each line slightly early so it's readable as it's sung. */
    private const val LEAD_MS = 250L
    private const val RETRY_DELAY_MS = 15_000L
    private const val MAX_ATTEMPTS = 3
    private const val MAX_OFFSET_MS = 30_000L

    data class Track(val title: String, val artist: String, val durationMs: Long)

    sealed interface Status {
        data object Idle : Status
        data object Loading : Status
        data class Failed(val message: String) : Status
        data class Ready(val lyrics: Lyrics) : Status
    }

    /**
     * [offsetMs] is the user's per-song timing adjustment: positive shows lyrics earlier,
     * negative later.
     */
    data class Display(
        val track: Track? = null,
        val status: Status = Status.Idle,
        val lineIndex: Int = -1,
        val offsetMs: Long = 0L,
        val warningDismissed: Boolean = false,
    ) {
        private val synced get() = (status as? Status.Ready)?.lyrics as? Lyrics.Synced
        private val lines get() = synced?.lines
        val currentLine: String? get() = lines?.getOrNull(lineIndex)?.text?.ifBlank { "♪" }
        val nextLine: String? get() = lines?.getOrNull(lineIndex + 1)?.text?.ifBlank { "♪" }

        /**
         * True when the playing recording's length differs noticeably from the one the lyrics
         * were timed for (e.g. a music-video cut with a longer intro), unless the user already
         * adjusted this song or dismissed the warning.
         */
        val timingMayBeOff: Boolean
            get() {
                val source = synced?.sourceDurationMs ?: return false
                val playing = track?.durationMs ?: return false
                if (warningDismissed || offsetMs != 0L || source <= 0 || playing <= 0) return false
                return kotlin.math.abs(playing - source) > LENGTH_MISMATCH_MS
            }
    }

    const val SYNC_STEP_MS = 500L
    private const val LENGTH_MISMATCH_MS = 2_000L

    private val scope = MainScope()
    private val _display = MutableStateFlow(Display())
    val display: StateFlow<Display> = _display.asStateFlow()
    private var started = false
    private lateinit var settings: SyncSettings

    fun start(context: Context) {
        if (started) return
        started = true
        val repo = LyricsRepository(context.applicationContext)
        settings = SyncSettings(context.applicationContext)

        scope.launch {
            NowPlayingTracker.state
                .map { it.metadata?.toTrack() }
                .distinctUntilChanged { a, b -> a?.title == b?.title && a?.artist == b?.artist }
                .collectLatest { track ->
                    if (track == null) {
                        _display.value = Display()
                        return@collectLatest
                    }
                    val key = SyncSettings.keyFor(track.title, track.artist)
                    val offset = settings.offsetMs(key)
                    val dismissed = settings.isWarningDismissed(key)
                    _display.value = Display(track, Status.Loading, offsetMs = offset, warningDismissed = dismissed)
                    delay(400) // duration often arrives a moment after the title
                    val latest = NowPlayingTracker.state.value.metadata?.toTrack() ?: track
                    repeat(MAX_ATTEMPTS) { attempt ->
                        val status = try {
                            Status.Ready(repo.find(latest.title, latest.artist, latest.durationMs))
                        } catch (e: Exception) {
                            Status.Failed(e.message ?: "Network error")
                        }
                        _display.update { it.copy(track = latest, status = status, lineIndex = -1) }
                        if (status is Status.Ready || attempt == MAX_ATTEMPTS - 1) return@collectLatest
                        delay(RETRY_DELAY_MS)
                    }
                }
        }

        scope.launch {
            combine(
                NowPlayingTracker.state.map { it.playback },
                _display.map { it.status }.distinctUntilChanged(),
                _display.map { it.offsetMs }.distinctUntilChanged(),
                ::Triple,
            ).collectLatest { (playback, status, offset) -> followLines(playback, status, offset) }
        }
    }

    /** Shifts the current song's lyrics; positive shows them earlier. Saved for next time. */
    fun nudge(deltaMs: Long) = setOffset(_display.value.offsetMs + deltaMs)

    fun resetOffset() = setOffset(0L)

    fun dismissTimingWarning() {
        val track = _display.value.track ?: return
        if (!::settings.isInitialized) return
        settings.dismissWarning(SyncSettings.keyFor(track.title, track.artist))
        _display.update { it.copy(warningDismissed = true) }
    }

    private fun setOffset(offsetMs: Long) {
        val track = _display.value.track ?: return
        if (!::settings.isInitialized) return
        val clamped = offsetMs.coerceIn(-MAX_OFFSET_MS, MAX_OFFSET_MS)
        settings.setOffsetMs(SyncSettings.keyFor(track.title, track.artist), clamped)
        _display.update { it.copy(offsetMs = clamped) }
    }

    private suspend fun followLines(playback: PlaybackState?, status: Status, offsetMs: Long) {
        val lines = ((status as? Status.Ready)?.lyrics as? Lyrics.Synced)?.lines
        if (lines == null || playback == null) {
            setLine(-1)
            return
        }
        val speed = playback.playbackSpeed.takeIf { it > 0f } ?: 1f
        while (true) {
            val position = positionNow(playback) + LEAD_MS + offsetMs
            val index = LrcParser.indexAt(lines, position)
            setLine(index)
            if (playback.state != PlaybackState.STATE_PLAYING) return
            val next = lines.getOrNull(index + 1)?.timeMs ?: return
            delay(((next - position) / speed).toLong().coerceIn(30L, 1_000L))
        }
    }

    private fun setLine(index: Int) = _display.update { if (it.lineIndex == index) it else it.copy(lineIndex = index) }

    private fun positionNow(pb: PlaybackState): Long {
        if (pb.state != PlaybackState.STATE_PLAYING) return pb.position
        val elapsed = SystemClock.elapsedRealtime() - pb.lastPositionUpdateTime
        return pb.position + (elapsed * pb.playbackSpeed).toLong()
    }

    private fun MediaMetadata.toTrack(): Track? {
        val title = getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: return null
        val artist = getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: ""
        return Track(title, artist, getLong(MediaMetadata.METADATA_KEY_DURATION))
    }
}
