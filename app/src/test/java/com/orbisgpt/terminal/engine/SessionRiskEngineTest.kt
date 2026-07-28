package com.orbisgpt.terminal.engine

import com.orbisgpt.terminal.core.*
import org.junit.Assert.*
import org.junit.Test

class SessionRiskEngineTest {
    @Test
    fun threeConsecutiveLossesLockSession() {
        val settings = TerminalSettings(pauseAfterLosses = 3, maxTradesPerSession = 10)
        val rows = (1L..3L).map { id -> sample(id, JournalOutcome.LOSS) }
        val session = SessionRiskEngine.rebuild(rows, settings)
        assertTrue(session.locked)
        assertEquals(3, session.consecutiveLosses)
        assertTrue(session.lockReason!!.contains("perdas consecutivas"))
    }

    @Test
    fun winUsesPayoutWhileLossCostsOneRiskUnit() {
        val settings = TerminalSettings(virtualBankroll = 1000.0, riskPerTradePercent = 1.0, maxTradesPerSession = 10)
        val rows = listOf(sample(1, JournalOutcome.WIN, 90.0), sample(2, JournalOutcome.LOSS, 90.0))
        val session = SessionRiskEngine.rebuild(rows, settings)
        assertEquals(-1.0, session.pnlAmount, 1e-9)
    }

    private fun sample(id: Long, outcome: JournalOutcome, payout: Double = 88.0) = JournalItem(
        id = id,
        timestamp = id,
        marketMode = MarketMode.OTC,
        timeframe = CaptureTimeframe.ENTRY_1M,
        operatingMode = OperatingMode.ASSISTED,
        asset = "EUR/USD (OTC)",
        payoutPercent = payout,
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
