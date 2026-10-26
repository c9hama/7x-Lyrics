package com.aalyrics.car

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Picks the main hue of a cover and derives two colours from it, like Android Auto's media
 * player (Material You) does: a light pastel for buttons and the slider, and a dark shade for
 * icons drawn on that pastel. Mostly grey covers get neutral greys.
 *
 * Plain math on ARGB ints (no Android classes) so it can be unit tested.
 */
object CoverTones {
    data class Tones(val light: Int, val onLight: Int)

    val NEUTRAL = Tones(light = hsl(0f, 0f, 0.86f), onLight = hsl(0f, 0f, 0.14f))

    private const val BINS = 24
    /** Below this average colourfulness the cover counts as black-and-white. */
    private const val MIN_AVG_CHROMA = 0.06f

    fun fromPixels(pixels: IntArray): Tones {
        val weight = FloatArray(BINS)
        val hueX = FloatArray(BINS)
        val hueY = FloatArray(BINS)
        val satSum = FloatArray(BINS)
        var chromaTotal = 0f

        for (p in pixels) {
            val (h, s, l) = rgbToHsl(p)
            // Near-black and near-white pixels carry no usable colour.
            if (l < 0.1f || l > 0.95f) continue
            val chroma = s * (1f - abs(2f * l - 1f))
            chromaTotal += chroma
            val bin = ((h / 360f) * BINS).toInt().coerceIn(0, BINS - 1)
            val rad = Math.toRadians(h.toDouble())
            weight[bin] += chroma
            hueX[bin] += (cos(rad) * chroma).toFloat()
            hueY[bin] += (sin(rad) * chroma).toFloat()
            satSum[bin] += s * chroma
        }
        if (pixels.isEmpty() || chromaTotal / pixels.size < MIN_AVG_CHROMA) return NEUTRAL

        // Strongest hue, smoothed over neighbouring bins so a hue on a bin edge isn't split.
        var best = 0
        var bestScore = -1f
        for (i in 0 until BINS) {
            val score = weight[(i + BINS - 1) % BINS] + weight[i] + weight[(i + 1) % BINS]
            if (score > bestScore) {
                bestScore = score
                best = i
            }
        }
        var x = 0f
        var y = 0f
        var sat = 0f
        var w = 0f
        for (d in -1..1) {
            val i = (best + d + BINS) % BINS
            x += hueX[i]
            y += hueY[i]
            sat += satSum[i]
            w += weight[i]
        }
        val hue = ((Math.toDegrees(atan2(y, x).toDouble()) + 360.0) % 360.0).toFloat()
        val s = if (w > 0f) sat / w else 0f
        return Tones(
            light = hsl(hue, s.coerceIn(0.3f, 0.75f), 0.84f),
            onLight = hsl(hue, s.coerceIn(0.25f, 0.6f), 0.15f),
        )
    }

    /** Hue in degrees, saturation and lightness in 0..1. */
    fun rgbToHsl(argb: Int): Triple<Float, Float, Float> {
        val r = (argb shr 16 and 0xFF) / 255f
        val g = (argb shr 8 and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val l = (max + min) / 2f
        val d = max - min
        if (d == 0f) return Triple(0f, 0f, l)
        val s = d / (1f - abs(2f * l - 1f))
        val h = when (max) {
            r -> 60f * (((g - b) / d) % 6f)
            g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }
        return Triple((h + 360f) % 360f, s.coerceIn(0f, 1f), l)
    }

    fun hsl(h: Float, s: Float, l: Float): Int {
        val c = (1f - abs(2f * l - 1f)) * s
        val hp = (h % 360f) / 60f
        val x = c * (1f - abs(hp % 2f - 1f))
        val (r1, g1, b1) = when (hp.toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val m = l - c / 2f
        fun ch(v: Float) = ((v + m) * 255f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(r1) shl 16) or (ch(g1) shl 8) or ch(b1)
    }

    /** Linear blend of two ARGB colours. */
    fun blend(from: Int, to: Int, t: Float): Int {
        fun mix(shift: Int) = (((from shr shift and 0xFF) * (1f - t)) + ((to shr shift and 0xFF) * t)).toInt()
        return (mix(24) shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }
}
