package com.orbisgpt.atlas.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.orbisgpt.atlas.model.*

class SettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("orbis_atlas_settings", Context.MODE_PRIVATE)

    fun load(): UserSettings = UserSettings(
        baseCode = preferences.getString("base", "EUR") ?: "EUR",
        quoteCode = preferences.getString("quote", "USD") ?: "USD",
        virtualBankrollBrl = preferences.getFloat("bankroll", 10_000f).toDouble(),
        riskPercent = preferences.getFloat("risk", 0.5f).toDouble(),
        notificationsEnabled = preferences.getBoolean("notifications", true),
        paperOnly = true
    )

    fun save(settings: UserSettings) {
        preferences.edit()
            .putString("base", settings.baseCode)
            .putString("quote", settings.quoteCode)
            .putFloat("bankroll", settings.virtualBankrollBrl.toFloat())
            .putFloat("risk", settings.riskPercent.toFloat())
            .putBoolean("notifications", settings.notificationsEnabled)
            .apply()
    }
}

class PaperTradeStore(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE trades (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                pair TEXT NOT NULL,
                action TEXT NOT NULL,
                opened_at INTEGER NOT NULL,
                entry_price REAL NOT NULL,
                stop_price REAL NOT NULL,
                target_price REAL NOT NULL,
                units REAL NOT NULL,
                risk_brl REAL NOT NULL,
                status TEXT NOT NULL,
                closed_at INTEGER,
                exit_price REAL,
                result_brl REAL
            )""".trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun open(plan: TradePlan): Long {
        require(plan.action != DecisionAction.WAIT)
        return writableDatabase.insert("trades", null, ContentValues().apply {
            put("pair", plan.pair.symbol)
            put("action", plan.action.name)
            put("opened_at", System.currentTimeMillis())
            put("entry_price", plan.currentPrice)
            put("stop_price", requireNotNull(plan.stopPrice))
            put("target_price", requireNotNull(plan.target1))
            put("units", requireNotNull(plan.positionUnits))
            put("risk_brl", plan.riskAmountBrl)
            put("status", TradeStatus.OPEN.name)
        })
    }

    fun updateWithPrice(pair: String, currentPrice: Double) {
        list().filter { it.pair == pair && it.status == TradeStatus.OPEN }.forEach { trade ->
            val status = when (trade.action) {
                DecisionAction.BUY_BASE -> when {
                    currentPrice <= trade.stopPrice -> TradeStatus.STOPPED
                    currentPrice >= trade.targetPrice -> TradeStatus.TARGET
                    else -> null
                }
                DecisionAction.SELL_BASE -> when {
                    currentPrice >= trade.stopPrice -> TradeStatus.STOPPED
                    currentPrice <= trade.targetPrice -> TradeStatus.TARGET
                    else -> null
                }
                DecisionAction.WAIT -> null
            }
            if (status != null) finish(trade, currentPrice, status)
        }
    }

    fun close(id: Long, currentPrice: Double) {
        val trade = list().firstOrNull { it.id == id && it.status == TradeStatus.OPEN } ?: return
        finish(trade, currentPrice, TradeStatus.CLOSED)
    }

    fun cancel(id: Long) {
        writableDatabase.update("trades", ContentValues().apply {
            put("status", TradeStatus.CANCELLED.name)
            put("closed_at", System.currentTimeMillis())
        }, "id = ?", arrayOf(id.toString()))
    }

    fun list(limit: Int = 500): List<PaperTrade> {
        val result = mutableListOf<PaperTrade>()
        readableDatabase.query("trades", COLUMNS, null, null, null, null, "opened_at DESC", limit.toString()).use { c ->
            while (c.moveToNext()) {
                result += PaperTrade(
                    id = c.getLong(0),
                    pair = c.getString(1),
                    action = enumValueOf(c.getString(2)),
                    openedAt = c.getLong(3),
                    entryPrice = c.getDouble(4),
                    stopPrice = c.getDouble(5),
                    targetPrice = c.getDouble(6),
                    units = c.getDouble(7),
                    riskAmountBrl = c.getDouble(8),
                    status = enumValueOf(c.getString(9)),
                    closedAt = if (c.isNull(10)) null else c.getLong(10),
                    exitPrice = if (c.isNull(11)) null else c.getDouble(11),
                    resultBrl = if (c.isNull(12)) null else c.getDouble(12)
                )
            }
        }
        return result
    }

    private fun finish(trade: PaperTrade, exitPrice: Double, status: TradeStatus) {
        val direction = if (trade.action == DecisionAction.BUY_BASE) 1.0 else -1.0
        val priceMove = (exitPrice - trade.entryPrice) * direction
        val plannedStopDistance = kotlin.math.abs(trade.entryPrice - trade.stopPrice).coerceAtLeast(1e-12)
        val result = trade.riskAmountBrl * (priceMove / plannedStopDistance)
        writableDatabase.update("trades", ContentValues().apply {
            put("status", status.name)
            put("closed_at", System.currentTimeMillis())
            put("exit_price", exitPrice)
            put("result_brl", result)
        }, "id = ?", arrayOf(trade.id.toString()))
    }

    companion object {
        private const val DB_NAME = "orbis_atlas.db"
        private const val DB_VERSION = 1
        private val COLUMNS = arrayOf(
            "id", "pair", "action", "opened_at", "entry_price", "stop_price", "target_price",
            "units", "risk_brl", "status", "closed_at", "exit_price", "result_brl"
        )
    }
}
