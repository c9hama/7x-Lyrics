package com.aalyrics

import android.graphics.Typeface
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.aalyrics.lyrics.LyricLine
import com.aalyrics.lyrics.Lyrics
import com.aalyrics.source.NowPlayingTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tap-to-sync: for songs no source has synced lyrics for. Paste (or keep the pre-filled) text,
 * press Start, and tap once as each line begins; the song plays from the start while you tap.
 * The result is saved on the phone and used before any other source.
 */
class SyncEditorActivity : ComponentActivity() {
    private lateinit var title: String
    private lateinit var artist: String
    /** Timed entries so far: text lines in order, plus empty ones where a music break was marked. */
    private val entries = mutableListOf<LyricLine>()
    private val timedLines get() = entries.count { it.text.isNotEmpty() }
    private var lines: List<String> = emptyList()

    private lateinit var editStep: View
    private lateinit var tapStep: View
    private lateinit var textBox: EditText
    private lateinit var linkBox: EditText
    private lateinit var progress: TextView
    private lateinit var previousLine: TextView
    private lateinit var nextLine: TextView
    private lateinit var afterLine: TextView
    private lateinit var tapButton: Button
    private lateinit var breakButton: Button
    private lateinit var breakState: TextView
    private lateinit var saveButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val track = LyricsEngine.display.value.track
        if (track == null) {
            Toast.makeText(this, R.string.sync_editor_no_song, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        title = track.title
        artist = track.artist
        setContentView(buildLayout())
        textBox.setText(initialText())
    }

    /** Unsynced text from a source, else the text of whatever synced lyrics exist. */
    private fun initialText(): String {
        LyricsEngine.repository.plainTextFor(title, artist)?.let { return it.trim() }
        val ready = (LyricsEngine.display.value.status as? LyricsEngine.Status.Ready)?.lyrics
        return when (ready) {
            is Lyrics.Plain -> ready.text.trim()
            is Lyrics.Synced -> ready.lines.map { it.text }.filter { it.isNotBlank() }.joinToString("\n")
            else -> ""
        }
    }

    /** Loads lyrics text from a pasted LyricsTranslate song link into the text box. */
    private fun loadLink() {
        val url = linkBox.text.toString().trim()
        if (url.isEmpty()) return
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { LyricsEngine.repository.lyricsTranslateFromLink(title, artist, url) }.getOrNull()
            }
            if (text == null) {
                Toast.makeText(this@SyncEditorActivity, R.string.sync_editor_link_failed, Toast.LENGTH_LONG).show()
            } else {
                textBox.setText(text)
                linkBox.setText("")
            }
        }
    }

    private fun startSyncing() {
        lines = textBox.text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) {
            Toast.makeText(this, R.string.sync_editor_empty, Toast.LENGTH_SHORT).show()
            return
        }
        entries.clear()
        editStep.visibility = View.GONE
        tapStep.visibility = View.VISIBLE
        controls()?.seekTo(0)
        controls()?.play()
        render()
    }

    /** People tap a little after they hear something start; take that back off. */
    private fun stampNow() = (positionNow() - REACTION_MS).coerceAtLeast(entries.lastOrNull()?.timeMs ?: 0L)

    private fun tap() {
        val n = timedLines
        if (n >= lines.size) return
        entries += LyricLine(stampNow(), lines[n])
        render()
    }

    /** Marks where the singing stops for an instrumental part; shown as ♪ in the car. */
    private fun markBreak() {
        if (entries.isEmpty() || entries.last().text.isEmpty() || timedLines >= lines.size) return
        entries += LyricLine(stampNow(), "")
        render()
    }

    private fun undo() {
        if (entries.isEmpty()) return
        val removed = entries.removeAt(entries.lastIndex)
        // Replay from a few seconds before it so it can be tapped again.
        controls()?.seekTo((removed.timeMs - 3_000).coerceAtLeast(0))
        render()
    }

    private fun save() {
        LyricsEngine.repository.saveUserLyrics(title, artist, entries.toList())
        LyricsEngine.reload()
        Toast.makeText(this, R.string.sync_editor_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun render() {
        val n = timedLines
        progress.text = getString(R.string.sync_editor_progress, n, lines.size)
        previousLine.text = lines.getOrNull(n - 1).orEmpty()
        nextLine.text = lines.getOrNull(n) ?: getString(R.string.sync_editor_done)
        afterLine.text = lines.getOrNull(n + 1).orEmpty()
        tapButton.isEnabled = n < lines.size
        breakButton.isEnabled = n in 1 until lines.size && entries.last().text.isNotEmpty()
        breakState.visibility = if (entries.lastOrNull()?.text?.isEmpty() == true) View.VISIBLE else View.GONE
        saveButton.visibility = if (n == lines.size) View.VISIBLE else View.GONE
    }

    private fun controls() = NowPlayingTracker.state.value.controller?.transportControls

    private fun positionNow(): Long {
        val pb = NowPlayingTracker.state.value.playback ?: return 0L
        if (pb.state != PlaybackState.STATE_PLAYING) return pb.position
        return pb.position + ((SystemClock.elapsedRealtime() - pb.lastPositionUpdateTime) * pb.playbackSpeed).toLong()
    }

    private fun buildLayout(): View {
        val dp = resources.displayMetrics.density
        fun text(size: Float, bold: Boolean = false, alpha: Float = 1f) = TextView(this).apply {
            textSize = size
            if (bold) typeface = Typeface.DEFAULT_BOLD
            this.alpha = alpha
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt())
            fitsSystemWindows = true
        }
        root.addView(text(13f, alpha = 0.6f).apply { setText(R.string.sync_editor_title) })
        root.addView(text(20f, bold = true).apply { setText("$title · $artist") })

        // Step 1: the lyrics text, one line per row.
        editStep = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(14f, alpha = 0.7f).apply { setText(R.string.sync_editor_paste_hint); setPadding(0, (16 * dp).toInt(), 0, (8 * dp).toInt()) })
            textBox = EditText(context).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                gravity = Gravity.TOP or Gravity.START
                minLines = 10
                textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            }
            addView(textBox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            // Fallback when no source found the text: paste the song's LyricsTranslate link.
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                linkBox = EditText(context).apply {
                    setHint(R.string.sync_editor_link_hint)
                    inputType = InputType.TYPE_TEXT_VARIATION_URI
                    isSingleLine = true
                }
                addView(linkBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(Button(context).apply { setText(R.string.sync_editor_link_load); setOnClickListener { loadLink() } })
            })
            addView(Button(context).apply { setText(R.string.sync_editor_start); setOnClickListener { startSyncing() } })
            if (LyricsEngine.repository.hasUserLyrics(title, artist)) {
                addView(Button(context).apply {
                    setText(R.string.sync_editor_delete)
                    setOnClickListener {
                        LyricsEngine.repository.deleteUserLyrics(title, artist)
                        LyricsEngine.reload()
                        finish()
                    }
                })
            }
        }
        root.addView(editStep, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // Step 2: tap as each line starts.
        tapStep = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            progress = text(13f, alpha = 0.6f).apply { setPadding(0, (16 * dp).toInt(), 0, 0) }
            previousLine = text(16f, alpha = 0.4f).apply { setPadding(0, (24 * dp).toInt(), 0, 0) }
            addView(progress)
            addView(previousLine)
            addView(text(13f, alpha = 0.6f).apply { setText(R.string.sync_editor_tap_hint); setPadding(0, (16 * dp).toInt(), 0, 0) })
            nextLine = text(24f, bold = true)
            afterLine = text(16f, alpha = 0.5f).apply { setPadding(0, (8 * dp).toInt(), 0, 0) }
            addView(nextLine)
            addView(afterLine)
            addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))
            breakState = text(14f, alpha = 0.7f).apply { setText(R.string.sync_editor_break_on); visibility = View.GONE }
            addView(breakState)
            breakButton = Button(context).apply { setText(R.string.sync_editor_break); setOnClickListener { markBreak() } }
            addView(breakButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (56 * dp).toInt()))
            tapButton = Button(context).apply { setText(R.string.sync_editor_tap); textSize = 22f; setOnClickListener { tap() } }
            addView(tapButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (140 * dp).toInt()))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(Button(context).apply { setText(R.string.sync_editor_undo); setOnClickListener { undo() } },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                saveButton = Button(context).apply { setText(R.string.sync_editor_save); visibility = View.GONE; setOnClickListener { save() } }
                addView(saveButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            })
        }
        root.addView(tapStep, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return ScrollView(this).apply { isFillViewport = true; addView(root) }
    }

    private companion object {
        const val REACTION_MS = 200L
    }
}
