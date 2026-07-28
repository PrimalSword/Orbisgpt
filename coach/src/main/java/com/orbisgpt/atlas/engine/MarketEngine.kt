package com.orbisgpt.atlas.engine

import com.orbisgpt.atlas.data.EcbFxRepository
import com.orbisgpt.atlas.model.*
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Conservative daily swing engine for beginners.
 * It deliberately returns WAIT when the market is mixed, stale, very volatile or extended.
 */
object MarketEngine {

    fun analyze(
        pair: CurrencyPair,
        points: List<FxPoint>,
        brlPerQuote: Double,
        settings: UserSettings
    ): TradePlan {
        require(points.size >= 130) { "São necessárias pelo menos 130 observações diárias" }
        val closes = points.sortedBy { it.date }.map(FxPoint::rate)
        val last = closes.last()
        val sma20 = closes.takeLast(20).average()
        val sma60 = closes.takeLast(60).average()
        val sma120 = closes.takeLast(120).average()
        val sd20 = standardDeviation(closes.takeLast(20)).coerceAtLeast(last * 0.0001)
        val returns = closes.zipWithNext { a, b -> ln(b / a) }
        val dailyVol = standardDeviation(returns.takeLast(20)).coerceAtLeast(0.0001)
        val annualVol = dailyVol * sqrt(252.0)
        val rsi = rsi(closes, 14)
        val change1d = last / closes[closes.lastIndex - 1] - 1.0
        val change5d = last / closes[closes.lastIndex - 5] - 1.0
        val high60 = closes.takeLast(60).maxOrNull() ?: last
        val low60 = closes.takeLast(60).minOrNull() ?: last
        val zScore = (last - sma20) / sd20
        val freshness = EcbFxRepository.freshnessDays(points.last().date)

        val indicators = IndicatorSet(
            last = last,
            change1d = change1d,
            change5d = change5d,
            sma20 = sma20,
            sma60 = sma60,
            sma120 = sma120,
            rsi14 = rsi,
            dailyVolatility = dailyVol,
            annualizedVolatility = annualVol,
            high60 = high60,
            low60 = low60,
            zScore20 = zScore,
            freshnessDays = freshness
        )

        val state = when {
            freshness > 7 -> MarketState.INSUFFICIENT_DATA
            annualVol > 0.28 -> MarketState.HIGH_RISK
            last > sma120 && sma20 > sma60 && sma60 > sma120 -> MarketState.UPTREND
            last < sma120 && sma20 < sma60 && sma60 < sma120 -> MarketState.DOWNTREND
            else -> MarketState.RANGE
        }

        val longScore = scoreLong(state, indicators)
        val shortScore = scoreShort(state, indicators)
        val action = when {
            state == MarketState.UPTREND && longScore >= 70 && zScore <= 1.55 -> DecisionAction.BUY_BASE
            state == MarketState.DOWNTREND && shortScore >= 70 && zScore >= -1.55 -> DecisionAction.SELL_BASE
            else -> DecisionAction.WAIT
        }
        val confidence = when (action) {
            DecisionAction.BUY_BASE -> longScore
            DecisionAction.SELL_BASE -> shortScore
            DecisionAction.WAIT -> maxOf(longScore, shortScore).coerceAtMost(69)
        }

        val move = maxOf(last * dailyVol, sd20 * 0.55, last * 0.001)
        val levels = when (action) {
            DecisionAction.BUY_BASE -> Levels(
                entryLow = last - move * 0.35,
                entryHigh = last + move * 0.10,
                stop = last - move * 1.60,
                target1 = last + move * 3.20,
                target2 = last + move * 4.80
            )
            DecisionAction.SELL_BASE -> Levels(
                entryLow = last - move * 0.10,
                entryHigh = last + move * 0.35,
                stop = last + move * 1.60,
                target1 = last - move * 3.20,
                target2 = last - move * 4.80
            )
            DecisionAction.WAIT -> null
        }

        val stopDistanceQuote = levels?.let { abs(last - it.stop) }
        val positionUnits = if (stopDistanceQuote != null && brlPerQuote > 0.0) {
            settings.riskAmountBrl / (stopDistanceQuote * brlPerQuote)
        } else null

        val reasons = buildReasons(state, indicators, action)
        val warnings = buildList {
            add("Dados diários de referência do BCE; não são uma cotação executável de corretora.")
            add("Plano destinado a simulação e horizonte de 5 a 20 pregões, não a operações intradiárias.")
            if (freshness > 3) add("A última observação tem $freshness dias; confirme feriados e disponibilidade do mercado.")
            if (annualVol > 0.20) add("Volatilidade elevada: o plano usa posição menor e pode oscilar bastante.")
        }

        return TradePlan(
            pair = pair,
            action = action,
            marketState = state,
            confidence = confidence,
            currentPrice = last,
            entryLow = levels?.entryLow,
            entryHigh = levels?.entryHigh,
            stopPrice = levels?.stop,
            target1 = levels?.target1,
            target2 = levels?.target2,
            horizonDays = 5..20,
            riskAmountBrl = settings.riskAmountBrl,
            positionUnits = positionUnits,
            rewardRisk = levels?.let { abs(it.target1 - last) / abs(last - it.stop) },
            headline = headline(action, pair, state),
            plainExplanation = explanation(action, pair, state, indicators),
            instructions = instructions(action, pair, levels, settings),
            reasons = reasons,
            warnings = warnings,
            indicators = indicators
        )
    }

    private fun scoreLong(state: MarketState, i: IndicatorSet): Int {
        var score = 0
        if (state == MarketState.UPTREND) score += 35
        if (i.sma20 > i.sma60) score += 15
        if (i.sma60 > i.sma120) score += 10
        if (i.change5d > 0) score += 12
        if (i.rsi14 in 48.0..68.0) score += 12
        if (i.zScore20 in -0.6..1.2) score += 8
        if (i.annualizedVolatility in 0.04..0.20) score += 5
        if (i.freshnessDays <= 3) score += 3
        return score.coerceIn(0, 100)
    }

    private fun scoreShort(state: MarketState, i: IndicatorSet): Int {
        var score = 0
        if (state == MarketState.DOWNTREND) score += 35
        if (i.sma20 < i.sma60) score += 15
        if (i.sma60 < i.sma120) score += 10
        if (i.change5d < 0) score += 12
        if (i.rsi14 in 32.0..52.0) score += 12
        if (i.zScore20 in -1.2..0.6) score += 8
        if (i.annualizedVolatility in 0.04..0.20) score += 5
        if (i.freshnessDays <= 3) score += 3
        return score.coerceIn(0, 100)
    }

    private fun buildReasons(state: MarketState, i: IndicatorSet, action: DecisionAction): List<String> = buildList {
        add(when (state) {
            MarketState.UPTREND -> "As médias de 20, 60 e 120 dias estão alinhadas para cima."
            MarketState.DOWNTREND -> "As médias de 20, 60 e 120 dias estão alinhadas para baixo."
            MarketState.RANGE -> "As médias estão misturadas; não há direção suficientemente limpa."
            MarketState.HIGH_RISK -> "A volatilidade anualizada está acima do limite conservador."
            MarketState.INSUFFICIENT_DATA -> "A série está desatualizada para uma decisão prudente."
        })
        add("Movimento de 5 dias: ${percent(i.change5d)}; RSI: ${"%.1f".format(i.rsi14)}.")
        if (abs(i.zScore20) > 1.55) add("O preço está distante demais da média de 20 dias; entrar agora seria perseguir o movimento.")
        if (action == DecisionAction.WAIT) add("O Atlas prefere perder uma oportunidade a sugerir uma entrada sem vantagem clara.")
    }

    private fun headline(action: DecisionAction, pair: CurrencyPair, state: MarketState): String = when (action) {
        DecisionAction.BUY_BASE -> "Comprar ${pair.base.code} apenas dentro da faixa planejada"
        DecisionAction.SELL_BASE -> "Vender ${pair.base.code} apenas dentro da faixa planejada"
        DecisionAction.WAIT -> when (state) {
            MarketState.HIGH_RISK -> "Aguardar: risco acima do aceitável"
            MarketState.INSUFFICIENT_DATA -> "Aguardar: dados precisam ser atualizados"
            else -> "Aguardar: nenhuma operação simples e limpa hoje"
        }
    }

    private fun explanation(action: DecisionAction, pair: CurrencyPair, state: MarketState, i: IndicatorSet): String = when (action) {
        DecisionAction.BUY_BASE -> "A tendência favorece ${pair.base.code}, mas a entrada só faz sentido perto do preço atual e com perda limitada pelo stop."
        DecisionAction.SELL_BASE -> "A tendência favorece ${pair.quote.code} contra ${pair.base.code}; o plano simula uma venda da moeda-base com risco previamente limitado."
        DecisionAction.WAIT -> when (state) {
            MarketState.RANGE -> "O mercado está indeciso. Para um iniciante, não operar é a decisão com melhor relação entre simplicidade e risco."
            MarketState.HIGH_RISK -> "Os movimentos recentes estão grandes demais. Mesmo uma análise correta pode sofrer oscilações difíceis de suportar."
            else -> "Os dados ainda não oferecem uma combinação suficientemente forte de tendência, momentum e preço de entrada."
        }
    }

    private fun instructions(
        action: DecisionAction,
        pair: CurrencyPair,
        levels: Levels?,
        settings: UserSettings
    ): List<String> = when (action) {
        DecisionAction.WAIT -> listOf(
            "Não abrir uma nova simulação neste par hoje.",
            "Atualizar novamente após a próxima publicação diária.",
            "Escolher outro par somente se quiser comparar, não para forçar uma operação."
        )
        else -> listOf(
            "Usar primeiro o botão “Simular este plano”; não executar com dinheiro real nesta versão.",
            "Aceitar entrada somente entre ${format(levels!!.entryLow)} e ${format(levels.entryHigh)}.",
            "Encerrar a ideia se o preço atingir ${format(levels.stop)}; não afastar o stop.",
            "Realizar parcialmente em ${format(levels.target1)} e encerrar o restante em ${format(levels.target2)}.",
            "Risco virtual máximo: R$ ${"%.2f".format(settings.riskAmountBrl)}."
        )
    }

    fun rsi(values: List<Double>, period: Int): Double {
        require(values.size > period)
        val changes = values.takeLast(period + 1).zipWithNext { a, b -> b - a }
        val gains = changes.filter { it > 0 }.sum() / period
        val losses = -changes.filter { it < 0 }.sum() / period
        if (losses == 0.0) return 100.0
        if (gains == 0.0) return 0.0
        val rs = gains / losses
        return 100.0 - 100.0 / (1.0 + rs)
    }

    fun standardDeviation(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean).pow(2) } / (values.size - 1))
    }

    private data class Levels(
        val entryLow: Double,
        val entryHigh: Double,
        val stop: Double,
        val target1: Double,
        val target2: Double
    )

    private fun percent(value: Double): String = "%+.2f%%".format(value * 100.0)
    private fun format(value: Double): String = when {
        abs(value) >= 100 -> "%.2f".format(value)
        abs(value) >= 10 -> "%.3f".format(value)
        else -> "%.5f".format(value)
    }
}
