package com.orbisgpt.terminal.engine

import com.orbisgpt.terminal.core.*
import org.junit.Assert.*
import org.junit.Test

class TerminalDecisionEngineTest {
    @Test
    fun invalidScreenCanNeverAuthorizeOperation() {
        val decision = TerminalDecisionEngine.evaluate(
            DecisionInput(
                context = MarketContext(screenValidated = false, visualConfidence = 0.2),
                candles = emptyList(),
                indicators = IndicatorSnapshot(),
                settings = TerminalSettings(),
                session = SessionState(),
                contextMemory = null,
                structureMemory = null,
                history = HistoricalPerformance()
            )
        )
        assertFalse(decision.operationAllowed)
        assertTrue(decision.blockers.any { it.contains("Tela atual não validada") })
        assertEquals(SignalDirection.WAIT, decision.direction)
    }

    @Test
    fun observerModeAddsHardVeto() {
        val settings = TerminalSettings(operatingMode = OperatingMode.OBSERVER)
        val decision = TerminalDecisionEngine.evaluate(
            DecisionInput(
                context = MarketContext(screenValidated = true, visualConfidence = 0.9, asset = "EUR/USD", payoutPercent = 90.0, expirySeconds = 60),
                candles = risingCandles(),
                indicators = IndicatorEngine.calculate(risingCandles()),
                settings = settings,
                session = SessionState(),
                contextMemory = null,
                structureMemory = null,
                history = HistoricalPerformance(60, 40)
            )
        )
        assertFalse(decision.operationAllowed)
        assertTrue(decision.blockers.any { it.contains("Modo observador") })
    }

    private fun risingCandles(): List<Candle> = (0 until 80).map { index ->
        val base = 100.0 + index * 0.5
        Candle(base, base + 1.0, base - 0.3, base + 0.7, index)
    }
}
