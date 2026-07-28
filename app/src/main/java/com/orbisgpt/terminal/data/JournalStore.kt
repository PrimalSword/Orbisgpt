package com.orbisgpt.terminal.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.orbisgpt.terminal.core.*

class JournalStore(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE journal (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                created_at INTEGER NOT NULL,
                market_mode TEXT NOT NULL,
                timeframe TEXT NOT NULL,
                operating_mode TEXT NOT NULL,
                asset TEXT NOT NULL,
                payout REAL,
                expiry_seconds INTEGER,
                playbook TEXT NOT NULL,
                regime TEXT NOT NULL,
                stage TEXT NOT NULL,
                direction TEXT NOT NULL,
                confluence INTEGER NOT NULL,
                entry_quality INTEGER NOT NULL,
                probability REAL,
                break_even REAL,
                expected_value REAL,
                operation_allowed INTEGER NOT NULL,
                blocker TEXT,
                human_choice TEXT NOT NULL DEFAULT 'NONE',
                outcome TEXT NOT NULL DEFAULT 'PENDING',
                followed_plan INTEGER,
                audit_note TEXT
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX idx_journal_segment ON journal(market_mode, playbook, regime, outcome)")
        db.execSQL("CREATE INDEX idx_journal_time ON journal(created_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun insert(decision: TerminalDecision, settings: TerminalSettings): Long {
        val probability = decision.probability
        return writableDatabase.insert("journal", null, ContentValues().apply {
            put("created_at", decision.timestamp)
            put("market_mode", settings.marketMode.name)
            put("timeframe", settings.captureTimeframe.name)
            put("operating_mode", settings.operatingMode.name)
            put("asset", decision.context.asset)
            decision.context.payoutPercent?.let { put("payout", it) }
            decision.context.expirySeconds?.let { put("expiry_seconds", it) }
            put("playbook", decision.playbook.name)
            put("regime", decision.regime.name)
            put("stage", decision.stage.name)
            put("direction", decision.direction.name)
            put("confluence", decision.confluenceScore)
            put("entry_quality", decision.entryQuality.score)
            probability.posteriorProbability?.let { put("probability", it) }
            probability.breakEven?.let { put("break_even", it) }
            probability.expectedValue?.let { put("expected_value", it) }
            put("operation_allowed", if (decision.operationAllowed) 1 else 0)
            decision.blockers.firstOrNull()?.let { put("blocker", it) }
            put("human_choice", HumanChoice.NONE.name)
            put("outcome", JournalOutcome.PENDING.name)
        })
    }

    fun setOutcome(id: Long, outcome: JournalOutcome, followedPlan: Boolean? = null, auditNote: String? = null) {
        writableDatabase.update("journal", ContentValues().apply {
            put("outcome", outcome.name)
            if (followedPlan == null) putNull("followed_plan") else put("followed_plan", if (followedPlan) 1 else 0)
            if (auditNote == null) putNull("audit_note") else put("audit_note", auditNote)
        }, "id = ?", arrayOf(id.toString()))
    }

    fun setHumanChoice(id: Long, choice: HumanChoice) {
        writableDatabase.update("journal", ContentValues().apply {
            put("human_choice", choice.name)
        }, "id = ?", arrayOf(id.toString()))
    }

    fun recent(limit: Int = 1000): List<JournalItem> {
        val result = mutableListOf<JournalItem>()
        readableDatabase.query(
            "journal",
            COLUMNS,
            null, null, null, null,
            "created_at DESC",
            limit.coerceIn(1, 10_000).toString()
        ).use { c ->
            while (c.moveToNext()) {
                result += JournalItem(
                    id = c.getLong(0),
                    timestamp = c.getLong(1),
                    marketMode = enumOrDefault(c.getString(2), MarketMode.OTC),
                    timeframe = enumOrDefault(c.getString(3), CaptureTimeframe.ENTRY_1M),
                    operatingMode = enumOrDefault(c.getString(4), OperatingMode.ASSISTED),
                    asset = c.getString(5),
                    payoutPercent = nullableDouble(c, 6),
                    expirySeconds = if (c.isNull(7)) null else c.getInt(7),
                    playbook = enumOrDefault(c.getString(8), Playbook.NONE),
                    regime = enumOrDefault(c.getString(9), MarketRegime.LOW_QUALITY),
                    stage = enumOrDefault(c.getString(10), SetupStage.CONTEXT),
                    direction = enumOrDefault(c.getString(11), SignalDirection.WAIT),
                    confluenceScore = c.getInt(12),
                    entryQuality = c.getInt(13),
                    probability = nullableDouble(c, 14),
                    breakEven = nullableDouble(c, 15),
                    expectedValue = nullableDouble(c, 16),
                    operationAllowed = c.getInt(17) == 1,
                    blocker = if (c.isNull(18)) null else c.getString(18),
                    humanChoice = enumOrDefault(c.getString(19), HumanChoice.NONE),
                    outcome = enumOrDefault(c.getString(20), JournalOutcome.PENDING),
                    followedPlan = if (c.isNull(21)) null else c.getInt(21) == 1,
                    auditNote = if (c.isNull(22)) null else c.getString(22)
                )
            }
        }
        return result
    }

    fun performance(mode: MarketMode, playbook: Playbook, regime: MarketRegime): HistoricalPerformance {
        val rows = recent(10_000).filter {
            it.marketMode == mode && it.playbook == playbook && it.regime == regime
        }
        return HistoricalPerformance(
            wins = rows.count { it.outcome == JournalOutcome.WIN },
            losses = rows.count { it.outcome == JournalOutcome.LOSS }
        )
    }

    fun csv(): String = buildString {
        appendLine("id;data;mercado;timeframe;modo;ativo;payout;vencimento_s;playbook;regime;etapa;direcao;confluencia;qualidade;probabilidade;break_even;ev;permitida;bloqueio;escolha_humana;resultado;seguiu_plano;nota")
        recent(10_000).asReversed().forEach { i ->
            fun clean(value: Any?): String = value?.toString().orEmpty().replace(";", ",").replace("\n", " ")
            appendLine(listOf(
                i.id, i.timestamp, i.marketMode, i.timeframe, i.operatingMode, clean(i.asset),
                i.payoutPercent ?: "", i.expirySeconds ?: "", i.playbook, i.regime, i.stage,
                i.direction, i.confluenceScore, i.entryQuality, i.probability ?: "", i.breakEven ?: "",
                i.expectedValue ?: "", i.operationAllowed, clean(i.blocker), i.humanChoice, i.outcome,
                i.followedPlan ?: "", clean(i.auditNote)
            ).joinToString(";"))
        }
    }

    private fun nullableDouble(cursor: android.database.Cursor, index: Int): Double? =
        if (cursor.isNull(index)) null else cursor.getDouble(index)

    private inline fun <reified T : Enum<T>> enumOrDefault(value: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: fallback

    companion object {
        private const val DB_NAME = "orbis_decision_terminal.db"
        private const val DB_VERSION = 1
        private val COLUMNS = arrayOf(
            "id", "created_at", "market_mode", "timeframe", "operating_mode", "asset",
            "payout", "expiry_seconds", "playbook", "regime", "stage", "direction",
            "confluence", "entry_quality", "probability", "break_even", "expected_value",
            "operation_allowed", "blocker", "human_choice", "outcome", "followed_plan", "audit_note"
        )
    }
}
