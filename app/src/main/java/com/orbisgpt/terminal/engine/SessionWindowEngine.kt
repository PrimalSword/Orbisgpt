package com.orbisgpt.terminal.engine

import com.orbisgpt.terminal.core.JournalItem
import com.orbisgpt.terminal.core.SessionState
import com.orbisgpt.terminal.core.TerminalSettings

object SessionWindowEngine {
    fun rebuild(
        items: List<JournalItem>,
        settings: TerminalSettings,
        startedAt: Long
    ): SessionState = SessionRiskEngine
        .rebuild(items.filter { it.timestamp >= startedAt }, settings)
        .copy(startedAt = startedAt)
}
