package com.orbisgpt.terminal.statistics

import com.orbisgpt.terminal.core.*

object StatisticsEngine {
    fun calculate(items: List<JournalItem>): DashboardSnapshot {
        if (items.isEmpty()) return DashboardSnapshot()
        val resolved = items.filter { it.outcome in setOf(JournalOutcome.WIN, JournalOutcome.LOSS, JournalOutcome.DRAW) }
        val wins = resolved.count { it.outcome == JournalOutcome.WIN }
        val losses = resolved.count { it.outcome == JournalOutcome.LOSS }
        val decided = wins + losses
        val returns = resolved.sortedBy(JournalItem::timestamp).map { item ->
            when (item.outcome) {
                JournalOutcome.WIN -> (item.payoutPercent ?: 0.0) / 100.0
                JournalOutcome.LOSS -> -1.0
                else -> 0.0
            }
        }
        var equity = 0.0
        var peak = 0.0
        var maxDrawdown = 0.0
        returns.forEach { value ->
            equity += value
            peak = maxOf(peak, equity)
            maxDrawdown = maxOf(maxDrawdown, peak - equity)
        }
        val grossWin = returns.filter { it > 0 }.sum()
        val grossLoss = -returns.filter { it < 0 }.sum()
        val followed = items.filter { it.followedPlan != null }
        val adherence = if (followed.isEmpty()) 0.0 else followed.count { it.followedPlan == true } * 100.0 / followed.size
        val bestPlaybook = items.groupBy(JournalItem::playbook)
            .mapValues { (_, rows) ->
                val marked = rows.filter { it.outcome in setOf(JournalOutcome.WIN, JournalOutcome.LOSS) }
                if (marked.size < 3) Double.NEGATIVE_INFINITY
                else marked.count { it.outcome == JournalOutcome.WIN }.toDouble() / marked.size
            }
            .maxByOrNull { it.value }
            ?.takeIf { it.value.isFinite() }
            ?.key
        val allowed = items.count(JournalItem::operationAllowed)
        val blocked = items.size - allowed
        val avoided = items.count { !it.operationAllowed && it.outcome == JournalOutcome.LOSS }
        val summary = when {
            decided < 30 -> "Amostra experimental: não alterar parâmetros"
            equity <= 0 -> "Expectativa observada ainda não positiva"
            else -> "Vantagem observada; confirmar fora da amostra"
        }
        return DashboardSnapshot(
            totalCandidates = items.size,
            resolved = resolved.size,
            wins = wins,
            losses = losses,
            winRate = if (decided == 0) 0.0 else wins * 100.0 / decided,
            expectedUnits = returns.sum(),
            profitFactor = if (grossLoss == 0.0) if (grossWin > 0) Double.POSITIVE_INFINITY else 0.0 else grossWin / grossLoss,
            maxDrawdownUnits = maxDrawdown,
            planAdherence = adherence,
            allowedCandidates = allowed,
            blockedCandidates = blocked,
            avoidedLosses = avoided,
            bestPlaybook = bestPlaybook,
            summary = summary
        )
    }
}
