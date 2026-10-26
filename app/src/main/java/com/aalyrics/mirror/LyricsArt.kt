package com.aalyrics.mirror

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import kotlin.math.min

/**
 * Renders the lyrics as a square "album art" image for the media player.
 *
 * Android Auto only lets sideloaded apps into real cars as media apps, and a media app's one
 * free-form visual is its album art. So the art becomes a small lyrics view: the current line
 * big, its neighbours faded, over a soft darkened copy of the cover.
 */
object LyricsArt {
    /** Kept modest: metadata (with this bitmap) is re-sent on every line change. */
    const val SIZE = 320

    private val bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    private val currentPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = bold }
    private val sidePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = bold }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    fun render(cover: Bitmap?, previous: String?, current: String, next: String?): Bitmap {
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        drawBackground(canvas, cover)

        val pad = 18f
        val width = (SIZE - 2 * pad).toInt()
        currentPaint.textSize = fitSize(current, width)
        val main = layout(current, currentPaint, width, maxLines = 4)

        sidePaint.textSize = 17f
        val gap = 12f
        val above = previous?.let { layout(it, sidePaint, width, maxLines = 2) }
        val below = next?.let { layout(it, sidePaint, width, maxLines = 2) }

        // Current line centred; neighbours stacked above and below it.
        val mainTop = (SIZE - main.height) / 2f
        above?.let {
            sidePaint.alpha = 115
            draw(canvas, it, pad, mainTop - gap - it.height)
        }
        draw(canvas, main, pad, mainTop)
        below?.let {
            sidePaint.alpha = 115
            draw(canvas, it, pad, mainTop + main.height + gap)
        }
        return out.copy(Bitmap.Config.RGB_565, false)
    }

    /** Large for short lines, smaller for long ones, so most lines fit in two or three rows. */
    private fun fitSize(text: String, width: Int): Float {
        for (size in floatArrayOf(34f, 30f, 26f, 23f)) {
            currentPaint.textSize = size
            if (layout(text, currentPaint, width, maxLines = Int.MAX_VALUE).lineCount <= 3) return size
        }
        return 23f
    }

    private fun layout(text: String, paint: TextPaint, width: Int, maxLines: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1.05f)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()

    private fun draw(canvas: Canvas, layout: StaticLayout, x: Float, y: Float) {
        canvas.save()
        canvas.translate(x, y)
        layout.draw(canvas)
        canvas.restore()
    }

    /** Cover shrunk to a few pixels and stretched back (a cheap soft blur), then darkened. */
    private fun drawBackground(canvas: Canvas, cover: Bitmap?) {
        canvas.drawColor(Color.rgb(0x1E, 0x1B, 0x2E))
        if (cover != null && cover.width > 0 && cover.height > 0) {
            val side = min(cover.width, cover.height)
            val src = Rect((cover.width - side) / 2, (cover.height - side) / 2, (cover.width + side) / 2, (cover.height + side) / 2)
            val tiny = Bitmap.createBitmap(18, 18, Bitmap.Config.ARGB_8888)
            Canvas(tiny).drawBitmap(cover, src, Rect(0, 0, 18, 18), bitmapPaint)
            canvas.drawBitmap(tiny, null, RectF(0f, 0f, SIZE.toFloat(), SIZE.toFloat()), bitmapPaint)
        }
        canvas.drawColor(Color.argb(150, 0, 0, 0))
    }
}
