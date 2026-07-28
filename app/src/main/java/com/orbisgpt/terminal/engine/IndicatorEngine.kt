package com.orbisgpt.terminal.engine

import com.orbisgpt.terminal.core.Candle
import com.orbisgpt.terminal.core.IndicatorSnapshot
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

object IndicatorEngine {
    fun calculate(candles: List<Candle>): IndicatorSnapshot {
        if (candles.isEmpty()) return IndicatorSnapshot()
        val closes = candles.map(Candle::close)
        val ema12 = ema(closes, 12)
        val ema60 = ema(closes, 60)
        val bb = bollinger(closes, 12, 1.5)
        val atr = atr(candles, 14)
        val rsi = rsi(closes, 14)
        val slope = if (closes.size >= 6) closes.last() - closes[closes.lastIndex - 5] else 0.0
        val atrRatio = if (atr != null && closes.last() != 0.0) atr / abs(closes.last()) else 0.0
        val trend = when {
            closes.size < 12 || ema12 == null || ema60 == null -> "AQUECENDO"
            ema12 > ema60 && slope > 0 -> "ALTA"
            ema12 < ema60 && slope < 0 -> "BAIXA"
            else -> "NEUTRA"
        }
        val volatility = when {
            atr == null -> "AQUECENDO"
            atrRatio > 0.08 -> "ALTA"
            atrRatio > 0.035 -> "MÉDIA"
            else -> "BAIXA"
        }
        val lateral = bb != null && ema12 != null && ema60 != null && atr != null &&
            abs(ema12 - ema60) < atr * 0.35 &&
            (bb.first - bb.third) < atr * 3.0
        return IndicatorSnapshot(
            candleCount = candles.size,
            lastClose = closes.last(),
            ema12 = ema12,
            ema60 = ema60,
            bollingerUpper = bb?.first,
            bollingerMiddle = bb?.second,
            bollingerLower = bb?.third,
            atr14 = atr,
            rsi14 = rsi,
            trend = trend,
            volatility = volatility,
            lateral = lateral
        )
    }

    fun ema(values: List<Double>, period: Int): Double? {
        if (values.isEmpty() || period <= 0) return null
        val multiplier = 2.0 / (period + 1.0)
        var result = values.first()
        values.drop(1).forEach { result = (it - result) * multiplier + result }
        return result
    }

    fun bollinger(values: List<Double>, period: Int, deviation: Double): Triple<Double, Double, Double>? {
        if (values.size < period) return null
        val window = values.takeLast(period)
        val mean = window.average()
        val sd = sqrt(window.sumOf { (it - mean).pow(2) } / period)
        return Triple(mean + deviation * sd, mean, mean - deviation * sd)
    }

    fun atr(candles: List<Candle>, period: Int): Double? {
        if (candles.size < period + 1) return null
        val ranges = candles.zipWithNext { previous, current ->
            maxOf(current.high - current.low, abs(current.high - previous.close), abs(current.low - previous.close))
        }
        return ranges.takeLast(period).average()
    }

    fun rsi(values: List<Double>, period: Int): Double? {
        if (values.size < period + 1) return null
        val changes = values.zipWithNext { a, b -> b - a }.takeLast(period)
        val gains = changes.filter { it > 0 }.sum() / period
        val losses = -changes.filter { it < 0 }.sum() / period
        if (losses == 0.0) return 100.0
        val rs = gains / losses
        return 100.0 - 100.0 / (1.0 + rs)
    }
}
