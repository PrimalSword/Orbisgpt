package com.orbisgpt.terminal.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object RuntimeState {
    private val _overlayRunning = MutableStateFlow(false)
    val overlayRunning = _overlayRunning.asStateFlow()

    private val _captureRunning = MutableStateFlow(false)
    val captureRunning = _captureRunning.asStateFlow()

    private val _capturedFrames = MutableStateFlow(0L)
    val capturedFrames = _capturedFrames.asStateFlow()

    private val _settings = MutableStateFlow(TerminalSettings())
    val settings = _settings.asStateFlow()

    private val _session = MutableStateFlow(SessionState())
    val session = _session.asStateFlow()

    private val _terminal = MutableStateFlow(TerminalSnapshot())
    val terminal = _terminal.asStateFlow()

    private val _journal = MutableStateFlow<List<JournalItem>>(emptyList())
    val journal = _journal.asStateFlow()

    fun setOverlayRunning(value: Boolean) { _overlayRunning.value = value }
    fun setCaptureRunning(value: Boolean) { _captureRunning.value = value }
    fun registerFrame() { _capturedFrames.value += 1 }

    fun updateSettings(transform: (TerminalSettings) -> TerminalSettings) {
        _settings.value = transform(_settings.value)
        _terminal.value = _terminal.value.copy(settings = _settings.value)
    }

    fun setSession(value: SessionState) {
        _session.value = value
        _terminal.value = _terminal.value.copy(session = value)
    }

    fun resetSession() = setSession(SessionState())

    fun updateTerminal(value: TerminalSnapshot) {
        _terminal.value = value.copy(settings = _settings.value, session = _session.value)
    }

    fun updateJournal(items: List<JournalItem>) { _journal.value = items }

    fun remember(snapshot: TimeframeSnapshot) {
        val current = _terminal.value
        _terminal.value = when (snapshot.timeframe) {
            CaptureTimeframe.CONTEXT_15M -> current.copy(contextMemory = snapshot)
            CaptureTimeframe.STRUCTURE_5M -> current.copy(structureMemory = snapshot)
            CaptureTimeframe.ENTRY_1M -> current.copy(entryMemory = snapshot)
        }
    }
}
