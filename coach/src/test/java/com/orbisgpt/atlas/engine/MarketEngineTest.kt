package com.orbisgpt.atlas.engine

import com.orbisgpt.atlas.model.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class MarketEngineTest {
    private val pair = CurrencyPair(CurrencyCatalog.byCode("EUR"), CurrencyCatalog.byCode("USD"))
    private val settings = UserSettings(virtualBankrollBrl = 10_000.0, riskPercent = 0.5)

    @Test
    fun flatMarketReturnsWait() {
        val points = (0 until 160).map { index ->
            FxPoint(LocalDate.now().minusDays((159 - index).toLong()), 1.10 + (index % 2) * 0.00001)
        }
        val plan = MarketEngine.analyze(pair, points, brlPerQuote = 5.0, settings)
        assertEquals(DecisionAction.WAIT, plan.action)
        assertEquals(MarketState.RANGE, plan.marketState)
    }

    @Test
    fun orderlyUptrendProducesRiskDefinedBuyPlan() {
        val values = buildList {
            repeat(120) { add(1.0 + it * 0.001) }
            repeat(20) { add(1.1200 + (it % 5) * 0.0003) }
        }
        val points = values.mapIndexed { index, value ->
            FxPoint(LocalDate.now().minusDays((values.lastIndex - index).toLong()), value)
        }
        val plan = MarketEngine.analyze(pair, points, brlPerQuote = 5.0, settings)
        assertEquals(MarketState.UPTREND, plan.marketState)
        assertEquals(DecisionAction.BUY_BASE, plan.action)
        assertNotNull(plan.stopPrice)
        assertNotNull(plan.target1)
        assertTrue(plan.stopPrice!! < plan.currentPrice)
        assertTrue(plan.target1!! > plan.currentPrice)
        assertEquals(50.0, plan.riskAmountBrl, 0.001)
        assertTrue((plan.rewardRisk ?: 0.0) >= 2.0)
    }

    @Test
    fun rsiAndVolatilityAreBounded() {
        val values = listOf(1.0, 1.1, 1.05, 1.12, 1.08, 1.15, 1.13, 1.18, 1.16, 1.20, 1.19, 1.23, 1.21, 1.25, 1.24, 1.27)
        val rsi = MarketEngine.rsi(values, 14)
        assertTrue(rsi in 0.0..100.0)
        assertTrue(MarketEngine.standardDeviation(values) > 0.0)
    }
}
