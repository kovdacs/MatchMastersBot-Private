package com.match3vision.analyzer.capture

/**
 * Intervals between emitted capture frames, in monotonic milliseconds.
 * VirtualDisplay often emits only when pixels change, so a long interval on
 * a static screen is a measurement, not a reason to loosen the freshness gate.
 */
class FrameCadence(
    private val maxSamples: Int = 32,
) {
    private val elapsedMs = ArrayDeque<Long>()

    fun record(elapsedRealtimeMs: Long) {
        if (elapsedRealtimeMs <= 0L) return
        elapsedMs.addLast(elapsedRealtimeMs)
        while (elapsedMs.size > maxSamples) elapsedMs.removeFirst()
    }

    fun samples(): List<Long> = elapsedMs.toList()

    fun intervalsMs(): List<Long> {
        if (elapsedMs.size < 2) return emptyList()
        val out = ArrayList<Long>(elapsedMs.size - 1)
        var prev = elapsedMs.first()
        for (t in elapsedMs.drop(1)) {
            out += (t - prev).coerceAtLeast(0L)
            prev = t
        }
        return out
    }

    fun summary(): String {
        val intervals = intervalsMs()
        if (intervals.isEmpty()) return "no interval yet (samples=${elapsedMs.size})"
        val sorted = intervals.sorted()
        val median = sorted[sorted.size / 2]
        return "samples=${elapsedMs.size} intervals=${intervals.size} " +
            "lastMs=${intervals.last()} medianMs=$median minMs=${sorted.first()} maxMs=${sorted.last()}"
    }

    fun reset() {
        elapsedMs.clear()
    }
}
