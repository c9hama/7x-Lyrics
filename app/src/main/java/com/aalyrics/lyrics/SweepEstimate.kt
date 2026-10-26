package com.aalyrics.lyrics

import kotlin.math.min

/**
 * Estimates how far through a line the singer is, for a karaoke-style highlight. LRCLIB rarely
 * has word timing, so the line is assumed to be sung evenly by character count, finishing a bit
 * before the next line (or sooner for short lines followed by a long instrumental gap).
 */
object SweepEstimate {
    /** Typical singing pace: roughly 12-13 characters per second. */
    private const val MS_PER_CHAR = 80L
    private const val BASE_MS = 500L
    private const val MIN_MS = 600L
    /** Lines usually end with a breath before the next one starts. */
    private const val GAP_USE = 0.9f
    /** Assumed gap when this is the last line. */
    private const val LAST_LINE_GAP_MS = 4_000L

    /** Fraction (0..1) of [text] sung at [positionMs] for a line starting at [startMs]. */
    fun progress(text: String, startMs: Long, nextStartMs: Long?, positionMs: Long): Float {
        val gap = (nextStartMs ?: (startMs + LAST_LINE_GAP_MS)) - startMs
        val estimate = MS_PER_CHAR * text.trim().length + BASE_MS
        val sungMs = min((gap * GAP_USE).toLong(), estimate).coerceAtLeast(MIN_MS)
        return ((positionMs - startMs).toFloat() / sungMs).coerceIn(0f, 1f)
    }
}
