package com.aalyrics.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class SweepEstimateTest {
    private val line = "paper boats on a quiet river" // 28 chars -> ~2.74s estimate

    @Test
    fun startsEmptyAndEndsFull() {
        assertEquals(0f, SweepEstimate.progress(line, 10_000, 15_000, 9_000), 0f)
        assertEquals(0f, SweepEstimate.progress(line, 10_000, 15_000, 10_000), 0f)
        assertEquals(1f, SweepEstimate.progress(line, 10_000, 15_000, 14_000), 0f)
    }

    @Test
    fun longGapUsesTheLengthEstimateNotTheWholeGap() {
        // Next line is 20s away (instrumental), but the line itself takes ~2.7s to sing.
        assertEquals(1f, SweepEstimate.progress(line, 0, 20_000, 3_000), 0f)
    }

    @Test
    fun shortGapFinishesJustBeforeTheNextLine() {
        // 2s to the next line: sweep must complete within 90% of it.
        assertEquals(1f, SweepEstimate.progress(line, 0, 2_000, 1_800), 0.001f)
        assertEquals(0.5f, SweepEstimate.progress(line, 0, 2_000, 900), 0.001f)
    }

    @Test
    fun lastLineStillSweeps() {
        val p = SweepEstimate.progress(line, 0, null, 1_000)
        assert(p > 0f && p < 1f)
    }
}
