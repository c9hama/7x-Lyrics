package com.aalyrics.lyrics

data class LyricLine(val timeMs: Long, val text: String)

object LrcParser {
    private val TIME_TAG = Regex("""^\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    /** Parses LRC text into lines sorted by time. A line may carry several time tags. */
    fun parse(lrc: String): List<LyricLine> {
        val out = mutableListOf<LyricLine>()
        for (raw in lrc.lineSequence()) {
            var rest = raw.trim()
            val times = mutableListOf<Long>()
            while (true) {
                val m = TIME_TAG.find(rest) ?: break
                val (min, sec, frac) = m.destructured
                val fracMs = if (frac.isEmpty()) 0 else frac.padEnd(3, '0').take(3).toInt()
                times += min.toLong() * 60_000 + sec.toLong() * 1_000 + fracMs
                rest = rest.substring(m.range.last + 1)
            }
            // Some LRCLIB entries store apostrophes SQL-escaped ("don''t").
            val text = rest.trim().replace("''", "'")
            times.forEach { out += LyricLine(it, text) }
        }
        return out.sortedBy { it.timeMs }
    }

    /** Index of the line active at [positionMs], or -1 before the first line. */
    fun indexAt(lines: List<LyricLine>, positionMs: Long): Int {
        var lo = 0
        var hi = lines.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= positionMs) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found
    }
}
