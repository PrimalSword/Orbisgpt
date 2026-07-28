package com.orbisgpt.terminal.engine

import com.orbisgpt.terminal.core.*
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionWindowEngineTest {
    @Test
    fun oldOutcomesDoNotCountAfterSessionRestart() {
        val startedAt = 1_000L
        val oldLoss = sample(timestamp = 500L, outcome = JournalOutcome.LOSS)
        val newWin = sample(timestamp = 1_500L, outcome = JournalOutcome.WIN)
        val session = SessionWindowEngine.rebuild(listOf(oldLoss, newWin), TerminalSettings(), startedAt)
        assertEquals(1, session.trades)
        assertEquals(1, session.wins)
        assertEquals(0, session.losses)
        assertEquals(startedAt, session.startedAt)
    }

    private fun sample(timestamp: Long, outcome: JournalOutcome) = JournalItem(
        id = timestamp,
        timestamp = timestamp,
        marketMode = MarketMode.OTC,
        timeframe = CaptureTimeframe.ENTRY_1M,
        operatingMode = OperatingMode.ASSISTED,
        asset = "EUR/USD (OTC)",
        payoutPercent = 88.0,
        expirySeconds = 60,
        playbook = Playbook.TREND_PULLBACK,
        regime = MarketRegime.STRONG_TREND,
        stage = SetupStage.VALID,
        direction = SignalDirection.CALL,
        confluenceScore = 80,
        entryQuality = 75,
        probability = 0.58,
        breakEven = 0.53,
        expectedValue = 0.09,
        operationAllowed = true,
        blocker = null,
        outcome = outcome
    )
}
