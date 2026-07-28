package com.orbisgpt.terminal.engine

import com.orbisgpt.terminal.core.Candle
import kotlin.math.abs

class TemporalCandleTracker {
    private data class Track(var candle: Candle, var seen: Int = 1, var missed: Int = 0)
    private val tracks = mutableListOf<Track>()

    fun update(detected: List<Candle>, graphWidth: Int): List<Candle> {
        if (detected.isEmpty()) {
            tracks.forEach { it.missed++ }
            tracks.removeAll { it.missed > MAX_MISSES }
            return stable()
        }
        val tolerance = (graphWidth / 80).coerceAtLeast(3)
        tracks.forEach { it.missed++ }
        detected.sortedBy(Candle::x).forEach { candidate ->
            val match = tracks
                .filter { abs(it.candle.x - candidate.x) <= tolerance }
                .minByOrNull { abs(it.candle.x - candidate.x) }
            if (match == null) {
                tracks += Track(candidate)
            } else {
                match.candle = blend(match.candle, candidate, match.seen)
                match.seen++
                match.missed = 0
            }
        }
        tracks.removeAll { it.missed > MAX_MISSES }
        return stable()
    }

    fun reset() = tracks.clear()

    private fun stable(): List<Candle> = tracks
        .filter { it.seen >= MIN_CONFIRMATIONS && it.missed <= MAX_MISSES }
        .sortedBy { it.candle.x }
        .map { it.candle.copy(confirmed = it.seen >= MIN_CONFIRMATIONS) }
        .takeLast(MAX_TRACKS)

    private fun blend(old: Candle, fresh: Candle, seen: Int): Candle {
        val alpha = if (seen < 3) 0.55 else 0.32
        fun mix(a: Double, b: Double) = a * (1.0 - alpha) + b * alpha
        return Candle(
            open = mix(old.open, fresh.open),
            high = maxOf(old.high, fresh.high),
            low = minOf(old.low, fresh.low),
            close = mix(old.close, fresh.close),
            x = fresh.x,
            timestamp = fresh.timestamp,
            confirmed = seen + 1 >= MIN_CONFIRMATIONS
        )
    }

    companion object {
        private const val MIN_CONFIRMATIONS = 2
        private const val MAX_MISSES = 4
        private const val MAX_TRACKS = 240
    }
}
