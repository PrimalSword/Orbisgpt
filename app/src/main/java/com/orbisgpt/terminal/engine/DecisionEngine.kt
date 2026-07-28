package com.orbisgpt.terminal.engine

import com.orbisgpt.terminal.core.*
import kotlin.math.pow
import kotlin.math.sqrt

data class DecisionInput(
    val context: MarketContext,
    val candles: List<Candle>,
    val indicators: IndicatorSnapshot,
    val settings: TerminalSettings,
    val session: SessionState,
    val contextMemory: TimeframeSnapshot?,
    val structureMemory: TimeframeSnapshot?,
    val history: HistoricalPerformance
)

object ProbabilityEngine {
    fun calculate(performance: HistoricalPerformance, payoutPercent: Double?): ProbabilitySnapshot {
        val payout = payoutPercent?.div(100.0)?.takeIf { it > 0.0 }
        val breakEven = payout?.let { 1.0 / (1.0 + it) }
        val n = performance.sampleSize
        if (n == 0) return ProbabilitySnapshot(breakEven = breakEven)
        val posterior = (performance.wins + 2.0) / (n + 4.0)
        val lower = wilsonLower(performance.wins, n)
        val ev = payout?.let { posterior * it - (1.0 - posterior) }
        val edge = if (breakEven != null) (posterior - breakEven) * 100.0 else null
        val label = when {
            n < 30 -> "EXPERIMENTAL"
            n < 100 -> "EVIDÊNCIA FRACA"
            n < 500 -> "AVALIAÇÃO PRELIMINAR"
            else -> "AMOSTRA ROBUSTA"
        }
        return ProbabilitySnapshot(n, performance.wins, performance.losses, posterior, lower, breakEven, ev, edge, label)
    }

    private fun wilsonLower(wins: Int, total: Int, z: Double = 1.96): Double {
        if (total <= 0) return 0.0
        val p = wins.toDouble() / total
        val denominator = 1.0 + z.pow(2) / total
        val centre = p + z.pow(2) / (2.0 * total)
        val margin = z * sqrt((p * (1.0 - p) + z.pow(2) / (4.0 * total)) / total)
        return ((centre - margin) / denominator).coerceIn(0.0, 1.0)
    }
}

object SessionRiskEngine {
    fun rebuild(items: List<JournalItem>, settings: TerminalSettings): SessionState {
        var wins = 0
        var losses = 0
        var draws = 0
        var consecutiveLosses = 0
        var pnl = 0.0
        var peak = 0.0
        var maxDrawdown = 0.0
        items.sortedBy(JournalItem::timestamp).forEach { item ->
            when (item.outcome) {
                JournalOutcome.WIN -> {
                    wins++
                    consecutiveLosses = 0
                    pnl += settings.riskAmount * ((item.payoutPercent ?: 0.0) / 100.0)
                }
                JournalOutcome.LOSS -> {
                    losses++
                    consecutiveLosses++
                    pnl -= settings.riskAmount
                }
                JournalOutcome.DRAW -> {
                    draws++
                    consecutiveLosses = 0
                }
                else -> Unit
            }
            peak = maxOf(peak, pnl)
            maxDrawdown = maxOf(maxDrawdown, peak - pnl)
        }
        val trades = wins + losses + draws
        val lockReason = when {
            -pnl >= settings.stopAmount -> "Stop diário atingido"
            pnl >= settings.targetAmount -> "Meta diária atingida; preservar resultado"
            trades >= settings.maxTradesPerSession -> "Limite de operações atingido"
            consecutiveLosses >= settings.pauseAfterLosses -> "Pausa após perdas consecutivas"
            else -> null
        }
        return SessionState(
            trades = trades,
            wins = wins,
            losses = losses,
            draws = draws,
            consecutiveLosses = consecutiveLosses,
            pnlAmount = pnl,
            peakPnlAmount = peak,
            drawdownAmount = maxDrawdown,
            locked = lockReason != null,
            lockReason = lockReason
        )
    }
}

object TerminalDecisionEngine {
    fun evaluate(input: DecisionInput): TerminalDecision {
        val structure = MarketAnalysisEngine.structure(input.candles, input.indicators)
        val regime = MarketAnalysisEngine.regime(input.candles, input.indicators, structure, input.context.visualConfidence)
        val playbook = MarketAnalysisEngine.playbook(regime)
        val direction = MarketAnalysisEngine.candidateDirection(playbook, structure)
        val quality = MarketAnalysisEngine.entryQuality(playbook, direction, input.candles, input.indicators, structure)
        val stage = MarketAnalysisEngine.stage(playbook, direction, quality, structure)
        val probability = ProbabilityEngine.calculate(input.history, input.context.payoutPercent)
        val reasons = mutableListOf<String>()
        val blockers = mutableListOf<String>()

        if (structure.bias != StructureBias.UNDEFINED) reasons += structure.description
        if (playbook != Playbook.NONE) reasons += "Playbook ${playbook.name} compatível com ${regime.name}"
        reasons += quality.positives

        if (!input.context.screenValidated) blockers += "Tela atual não validada"
        if (input.context.visualConfidence < 0.65) blockers += "Confiança visual abaixo de 65%"
        if (input.context.asset == "NÃO IDENTIFICADO") blockers += "Ativo não identificado"
        val payout = input.context.payoutPercent
        if (payout == null) blockers += "Payout não identificado"
        else if (payout < input.settings.minimumPayoutPercent) blockers += "Payout abaixo de ${input.settings.minimumPayoutPercent.toInt()}%"
        if (input.context.expirySeconds == null) blockers += "Vencimento não identificado"
        if (direction == SignalDirection.WAIT) blockers += "Nenhuma direção técnica válida"
        if (stage != SetupStage.VALID) blockers += when (stage) {
            SetupStage.MISSED -> "Entrada já passou"
            SetupStage.ARMED -> "Setup armado, aguardando confirmação"
            SetupStage.FORMING -> "Setup ainda em formação"
            SetupStage.INVALIDATED -> "Setup invalidado"
            else -> "Contexto incompleto"
        }
        if (quality.score < input.settings.minimumEntryQuality) blockers += "Qualidade de entrada ${quality.score}/100"
        if (regime in setOf(MarketRegime.ERRATIC, MarketRegime.LOW_QUALITY)) blockers += "Regime inadequado: ${regime.name}"
        if (input.settings.operatingMode == OperatingMode.OBSERVER) blockers += "Modo observador não autoriza operação"
        if (input.session.locked) blockers += (input.session.lockReason ?: "Sessão bloqueada")

        val contextMemory = input.contextMemory
        val structureMemory = input.structureMemory
        if (contextMemory == null || !contextMemory.isFresh()) blockers += "Contexto 15m ausente ou vencido"
        if (structureMemory == null || !structureMemory.isFresh()) blockers += "Estrutura 5m ausente ou vencida"
        if (contextMemory != null && direction != SignalDirection.WAIT) {
            val conflict = (direction == SignalDirection.CALL && contextMemory.structure.bias == StructureBias.BEARISH) ||
                (direction == SignalDirection.PUT && contextMemory.structure.bias == StructureBias.BULLISH)
            if (conflict) blockers += "Direção contrária ao contexto 15m"
        }

        if (probability.sampleSize < input.settings.minimumSample) blockers += "Amostra ${probability.sampleSize}/${input.settings.minimumSample}"
        val lower = probability.lowerBound
        val breakEven = probability.breakEven
        if (lower != null && breakEven != null && lower <= breakEven) blockers += "Limite estatístico ainda não supera o break-even"
        val edge = probability.edgePoints
        if (edge != null && edge < input.settings.minimumEdgePoints) blockers += "Margem estatística insuficiente"
        if (probability.expectedValue != null && probability.expectedValue <= 0.0) blockers += "Expectativa não positiva"

        val confluence = confluenceScore(regime, structure, direction, quality, input.context, contextMemory, structureMemory)
        val operationAllowed = blockers.isEmpty()
        val nextCondition = nextCondition(stage, quality, blockers)
        return TerminalDecision(
            context = input.context,
            regime = regime,
            structure = structure,
            playbook = playbook,
            stage = stage,
            direction = direction,
            confluenceScore = confluence,
            entryQuality = quality,
            probability = probability,
            operationAllowed = operationAllowed,
            blockers = blockers,
            reasons = reasons.distinct(),
            nextCondition = nextCondition,
            avoidedLossCandidate = !operationAllowed && direction != SignalDirection.WAIT && stage in setOf(SetupStage.ARMED, SetupStage.VALID)
        )
    }

    private fun confluenceScore(
        regime: MarketRegime,
        structure: MarketStructure,
        direction: SignalDirection,
        quality: EntryQuality,
        context: MarketContext,
        contextMemory: TimeframeSnapshot?,
        structureMemory: TimeframeSnapshot?
    ): Int {
        if (direction == SignalDirection.WAIT) return 0
        var score = quality.score / 2
        if (regime in setOf(MarketRegime.STRONG_TREND, MarketRegime.EXPANSION)) score += 15
        if (structure.bias != StructureBias.UNDEFINED) score += 10
        if (context.screenValidated) score += 10
        if ((context.payoutPercent ?: 0.0) >= 85.0) score += 5
        if (contextMemory?.isFresh() == true) score += 5
        if (structureMemory?.isFresh() == true) score += 5
        return score.coerceIn(0, 100)
    }

    private fun nextCondition(stage: SetupStage, quality: EntryQuality, blockers: List<String>): String = when {
        blockers.any { it.contains("Contexto 15m") } -> "Capturar primeiro o gráfico de contexto 15m"
        blockers.any { it.contains("Estrutura 5m") } -> "Capturar o gráfico de estrutura 5m"
        stage == SetupStage.MISSED -> "Aguardar novo pullback; não perseguir o preço"
        stage == SetupStage.ARMED -> "Aguardar candle de confirmação"
        stage == SetupStage.FORMING -> quality.negatives.firstOrNull() ?: "Aguardar a formação do setup"
        stage == SetupStage.VALID && blockers.isNotEmpty() -> blockers.first()
        stage == SetupStage.VALID -> "Condições completas; respeitar o risco indicado"
        else -> "Aguardar novo contexto válido"
    }
}
