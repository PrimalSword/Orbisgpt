package com.orbisgpt.atlas.model

import java.time.LocalDate

/** A daily reference-rate observation. Rate means quote currency units per one base unit. */
data class FxPoint(val date: LocalDate, val rate: Double)

data class CurrencyOption(val code: String, val name: String)

data class CurrencyPair(val base: CurrencyOption, val quote: CurrencyOption) {
    val symbol: String get() = "${base.code}/${quote.code}"
}

enum class DecisionAction { BUY_BASE, SELL_BASE, WAIT }
enum class MarketState { UPTREND, DOWNTREND, RANGE, HIGH_RISK, INSUFFICIENT_DATA }
enum class TradeStatus { OPEN, TARGET, STOPPED, CLOSED, CANCELLED }

data class IndicatorSet(
    val last: Double,
    val change1d: Double,
    val change5d: Double,
    val sma20: Double,
    val sma60: Double,
    val sma120: Double,
    val rsi14: Double,
    val dailyVolatility: Double,
    val annualizedVolatility: Double,
    val high60: Double,
    val low60: Double,
    val zScore20: Double,
    val freshnessDays: Long
)

data class TradePlan(
    val pair: CurrencyPair,
    val action: DecisionAction,
    val marketState: MarketState,
    val confidence: Int,
    val currentPrice: Double,
    val entryLow: Double?,
    val entryHigh: Double?,
    val stopPrice: Double?,
    val target1: Double?,
    val target2: Double?,
    val horizonDays: IntRange,
    val riskAmountBrl: Double,
    val positionUnits: Double?,
    val rewardRisk: Double?,
    val headline: String,
    val plainExplanation: String,
    val instructions: List<String>,
    val reasons: List<String>,
    val warnings: List<String>,
    val indicators: IndicatorSet,
    val generatedAt: Long = System.currentTimeMillis()
)

data class UserSettings(
    val baseCode: String = "EUR",
    val quoteCode: String = "USD",
    val virtualBankrollBrl: Double = 10_000.0,
    val riskPercent: Double = 0.5,
    val notificationsEnabled: Boolean = true,
    val paperOnly: Boolean = true
) {
    val riskAmountBrl: Double get() = virtualBankrollBrl * riskPercent / 100.0
}

data class PaperTrade(
    val id: Long = 0,
    val pair: String,
    val action: DecisionAction,
    val openedAt: Long,
    val entryPrice: Double,
    val stopPrice: Double,
    val targetPrice: Double,
    val units: Double,
    val riskAmountBrl: Double,
    val status: TradeStatus = TradeStatus.OPEN,
    val closedAt: Long? = null,
    val exitPrice: Double? = null,
    val resultBrl: Double? = null
)

data class AppState(
    val settings: UserSettings = UserSettings(),
    val loading: Boolean = false,
    val error: String? = null,
    val points: List<FxPoint> = emptyList(),
    val plan: TradePlan? = null,
    val paperTrades: List<PaperTrade> = emptyList(),
    val lastUpdatedLabel: String = "Nunca atualizado"
)

object CurrencyCatalog {
    val supported = listOf(
        CurrencyOption("EUR", "Euro"),
        CurrencyOption("USD", "Dólar americano"),
        CurrencyOption("BRL", "Real brasileiro"),
        CurrencyOption("GBP", "Libra esterlina"),
        CurrencyOption("JPY", "Iene japonês"),
        CurrencyOption("CHF", "Franco suíço"),
        CurrencyOption("AUD", "Dólar australiano"),
        CurrencyOption("CAD", "Dólar canadense"),
        CurrencyOption("NZD", "Dólar neozelandês"),
        CurrencyOption("CNY", "Yuan chinês"),
        CurrencyOption("SEK", "Coroa sueca"),
        CurrencyOption("NOK", "Coroa norueguesa"),
        CurrencyOption("DKK", "Coroa dinamarquesa"),
        CurrencyOption("PLN", "Zlóti polonês"),
        CurrencyOption("CZK", "Coroa tcheca"),
        CurrencyOption("HUF", "Florim húngaro"),
        CurrencyOption("RON", "Leu romeno"),
        CurrencyOption("TRY", "Lira turca"),
        CurrencyOption("ZAR", "Rand sul-africano"),
        CurrencyOption("MXN", "Peso mexicano"),
        CurrencyOption("SGD", "Dólar de Singapura"),
        CurrencyOption("HKD", "Dólar de Hong Kong")
    )

    fun byCode(code: String): CurrencyOption = supported.firstOrNull { it.code == code } ?: supported.first()
}
