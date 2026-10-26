package com.aalyrics.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import com.aalyrics.R

/**
 * The template around the lyrics surface. Playback controls are drawn by
 * [LyricsRenderer]; the action strip (required by the template) holds the
 * lyric sync, background and text size toggles.
 */
class LyricsScreen(carContext: CarContext, private val renderer: LyricsRenderer) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val textSize = Action.Builder()
            .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_text_size)).build())
            .setOnClickListener {
                val label = renderer.cycleTextSize()
                CarToast.makeText(carContext, carContext.getString(R.string.text_size_toast, label), CarToast.LENGTH_SHORT).show()
            }
            .build()
        val background = Action.Builder()
            .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_background)).build())
            .setOnClickListener {
                val label = renderer.toggleBackground()
                CarToast.makeText(carContext, carContext.getString(R.string.background_toast, label), CarToast.LENGTH_SHORT).show()
            }
            .build()
        val sync = Action.Builder()
            .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_sync_lyrics)).build())
            .setOnClickListener { renderer.toggleSyncMode() }
            .build()
        return NavigationTemplate.Builder()
            .setActionStrip(ActionStrip.Builder().addAction(sync).addAction(background).addAction(textSize).build())
            .build()
    }
}
