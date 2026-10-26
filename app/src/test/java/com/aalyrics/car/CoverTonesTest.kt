package com.aalyrics.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverTonesTest {
    private fun hue(argb: Int) = CoverTones.rgbToHsl(argb).first
    private fun lightness(argb: Int) = CoverTones.rgbToHsl(argb).third

    @Test
    fun blueCoverGivesLightBlueAndDarkBlue() {
        val pixels = IntArray(400) { 0xFF1E5AC8.toInt() } // mid blue
        val tones = CoverTones.fromPixels(pixels)
        assertEquals(hue(0xFF1E5AC8.toInt()), hue(tones.light), 3f)
        assertTrue(lightness(tones.light) > 0.8f)
        assertTrue(lightness(tones.onLight) < 0.2f)
    }

    @Test
    fun greyCoverIsNeutral() {
        val pixels = IntArray(400) { i -> if (i % 2 == 0) 0xFF808080.toInt() else 0xFF404040.toInt() }
        assertEquals(CoverTones.NEUTRAL, CoverTones.fromPixels(pixels))
    }

    @Test
    fun ignoresBlackBackgroundAndFindsTheColour() {
        // Mostly black cover with a red title, like many album covers.
        val pixels = IntArray(1000) { i -> if (i < 150) 0xFFE01020.toInt() else 0xFF050505.toInt() }
        val h = hue(CoverTones.fromPixels(pixels).light)
        assertTrue("hue $h should be red", h < 15f || h > 345f)
    }

    @Test
    fun picksTheDominantHue() {
        val pixels = IntArray(1000) { i -> if (i < 700) 0xFF20A040.toInt() else 0xFFC03080.toInt() }
        assertEquals(hue(0xFF20A040.toInt()), hue(CoverTones.fromPixels(pixels).light), 5f)
    }

    @Test
    fun hslRoundTrips() {
        val c = CoverTones.hsl(210f, 0.6f, 0.5f)
        val (h, s, l) = CoverTones.rgbToHsl(c)
        assertEquals(210f, h, 1.5f)
        assertEquals(0.6f, s, 0.02f)
        assertEquals(0.5f, l, 0.02f)
    }
}
