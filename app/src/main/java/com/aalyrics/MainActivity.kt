package com.aalyrics

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.aalyrics.lyrics.Lyrics
import com.aalyrics.source.NowPlayingTracker
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Phone-side setup screen and a preview of what the car will show. */
class MainActivity : ComponentActivity() {
    private lateinit var accessCard: View
    private lateinit var source: TextView
    private lateinit var track: TextView
    private lateinit var status: TextView
    private lateinit var current: TextView
    private lateinit var next: TextView
    private lateinit var warningRow: View
    private lateinit var syncSection: View
    private lateinit var syncValue: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        accessCard = findViewById(R.id.access_card)
        source = findViewById(R.id.source)
        track = findViewById(R.id.track)
        status = findViewById(R.id.status)
        current = findViewById(R.id.current_line)
        next = findViewById(R.id.next_line)
        findViewById<Button>(R.id.grant_access).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        CrashLog.install(this)
        findViewById<TextView>(R.id.brand_link).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://7xdevs.com")))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        AppSettings.load(this)
        findViewById<Button>(R.id.aa_install).setOnClickListener { AutoInstaller.reinstallForAndroidAuto(this) }
        findViewById<Button>(R.id.clear_error).setOnClickListener {
            CrashLog.clear()
            showLastError()
        }
        findViewById<Switch>(R.id.karaoke_switch).apply {
            isChecked = AppSettings.karaoke.value
            setOnCheckedChangeListener { _, checked -> AppSettings.setKaraoke(this@MainActivity, checked) }
        }
        // Car-screen look, shared with the renderer's preferences (it applies changes live).
        val viewPrefs = getSharedPreferences("lyrics_view", MODE_PRIVATE)
        val sizes = mapOf(R.id.size_small to 0.85f, R.id.size_medium to 1f, R.id.size_large to 1.2f)
        findViewById<RadioGroup>(R.id.lyrics_size).apply {
            val current = viewPrefs.getFloat("text_scale", 1f)
            check(sizes.minByOrNull { kotlin.math.abs(it.value - current) }!!.key)
            setOnCheckedChangeListener { _, id -> sizes[id]?.let { viewPrefs.edit().putFloat("text_scale", it).apply() } }
        }
        findViewById<Switch>(R.id.moving_background_switch).apply {
            isChecked = viewPrefs.getBoolean("moving_background", true)
            setOnCheckedChangeListener { _, on -> viewPrefs.edit().putBoolean("moving_background", on).apply() }
        }
        findViewById<Button>(R.id.open_sync_editor).setOnClickListener {
            startActivity(Intent(this, SyncEditorActivity::class.java))
        }
        warningRow = findViewById(R.id.warning_row)
        syncSection = findViewById(R.id.sync_section)
        syncValue = findViewById(R.id.sync_value)
        findViewById<Button>(R.id.dismiss_warning).setOnClickListener { LyricsEngine.dismissTimingWarning() }
        findViewById<Button>(R.id.sync_earlier).setOnClickListener { LyricsEngine.nudge(LyricsEngine.SYNC_STEP_MS) }
        findViewById<Button>(R.id.sync_later).setOnClickListener { LyricsEngine.nudge(-LyricsEngine.SYNC_STEP_MS) }
        findViewById<Button>(R.id.sync_reset).setOnClickListener { LyricsEngine.resetOffset() }

        LyricsEngine.start(this)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(NowPlayingTracker.state, LyricsEngine.display, ::Pair).collect { (src, display) ->
                    render(src, display)
                }
            }
        }
    }

    private fun showLastError() {
        val text = CrashLog.read()
        findViewById<View>(R.id.error_section).visibility = if (text == null) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.error_text).text = text.orEmpty()
    }

    override fun onResume() {
        super.onResume()
        showLastError()
        val granted = NowPlayingTracker.ensureStarted(this)
        accessCard.visibility = if (granted) View.GONE else View.VISIBLE
    }

    private fun render(src: NowPlayingTracker.Source, display: LyricsEngine.Display) {
        source.text = src.controller?.packageName?.let { getString(R.string.source_format, it) }
            ?: getString(R.string.waiting_subtitle)
        track.text = display.track?.let { listOf(it.title, it.artist).filter(String::isNotBlank).joinToString(" · ") }.orEmpty()
        status.text = when (val s = display.status) {
            LyricsEngine.Status.Idle -> ""
            LyricsEngine.Status.Loading -> getString(R.string.status_loading)
            is LyricsEngine.Status.Failed -> getString(R.string.status_failed) + " (${s.message})"
            is LyricsEngine.Status.Ready -> when (s.lyrics) {
                is Lyrics.Synced -> getString(R.string.status_synced, s.lyrics.lines.size)
                is Lyrics.Plain -> getString(R.string.status_unsynced)
                Lyrics.Instrumental -> getString(R.string.status_instrumental)
                Lyrics.NotFound -> getString(R.string.status_not_found)
            }
        }
        current.text = display.currentLine.orEmpty()
        next.text = display.nextLine.orEmpty()

        val hasSynced = (display.status as? LyricsEngine.Status.Ready)?.lyrics is Lyrics.Synced
        warningRow.visibility = if (display.timingMayBeOff) View.VISIBLE else View.GONE
        syncSection.visibility = if (hasSynced) View.VISIBLE else View.GONE
        val seconds = "%.1f".format(kotlin.math.abs(display.offsetMs) / 1000f)
        syncValue.text = when {
            display.offsetMs == 0L -> getString(R.string.sync_in_sync)
            display.offsetMs > 0 -> getString(R.string.sync_shown_earlier, seconds)
            else -> getString(R.string.sync_shown_later, seconds)
        }
    }
}
