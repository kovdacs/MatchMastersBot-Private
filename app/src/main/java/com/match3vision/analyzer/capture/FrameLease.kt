package com.match3vision.analyzer.capture

/**
 * Immutable pixel snapshots. The producer may free a frame only after every
 * consumer has released it. A frozen frame stays readable while a newer frame
 * is published.
 */
class FrameLease {
    private val pixels = HashMap<Long, IntArray>()
    private val retains = HashMap<Long, Int>()
    private var current: Long? = null

    fun publish(sequence: Long, argb: IntArray): List<Long> {
        pixels[sequence] = argb.copyOf()
        retains.putIfAbsent(sequence, 0)
        current = sequence
        return freeable()
    }

    fun retain(sequence: Long) {
        check(pixels.containsKey(sequence)) { "unknown frame $sequence" }
        retains[sequence] = (retains[sequence] ?: 0) + 1
    }

    fun read(sequence: Long): IntArray? = pixels[sequence]

    /** True when this frame is no longer current and nobody holds it. */
    fun release(sequence: Long): Boolean {
        val left = ((retains[sequence] ?: 0) - 1).coerceAtLeast(0)
        retains[sequence] = left
        return sequence != current && left == 0
    }

    fun held(sequence: Long): Boolean = (retains[sequence] ?: 0) > 0

    fun canFree(sequence: Long): Boolean =
        sequence != current && (retains[sequence] ?: 0) == 0 && pixels.containsKey(sequence)

    fun free(sequence: Long) {
        if (!canFree(sequence)) return
        pixels.remove(sequence)
        retains.remove(sequence)
    }

    private fun freeable(): List<Long> =
        pixels.keys.filter { canFree(it) }
}
