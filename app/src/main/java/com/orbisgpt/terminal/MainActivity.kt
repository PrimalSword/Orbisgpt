package com.orbisgpt.terminal

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.*
import com.orbisgpt.terminal.capture.ScreenCaptureService
import com.orbisgpt.terminal.core.*
import com.orbisgpt.terminal.data.JournalStore
import com.orbisgpt.terminal.engine.SessionRiskEngine
import com.orbisgpt.terminal.overlay.OverlayService
import com.orbisgpt.terminal.statistics.StatisticsEngine
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 4101)
        JournalStore(this).use { store ->
            val rows = store.recent()
            RuntimeState.updateJournal(rows)
            RuntimeState.setSession(SessionRiskEngine.rebuild(rows, RuntimeState.settings.value))
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                OrbisTerminalApp()
            }
        }
    }
}

class TerminalViewModel : ViewModel() {
    val overlayRunning = RuntimeState.overlayRunning
    val captureRunning = RuntimeState.captureRunning
    val capturedFrames = RuntimeState.capturedFrames
    val settings = RuntimeState.settings
    val session = RuntimeState.session
    val terminal = RuntimeState.terminal
    val journal = RuntimeState.journal

    fun marketMode(value: MarketMode) = RuntimeState.updateSettings { it.copy(marketMode = value) }
    fun timeframe(value: CaptureTimeframe) = RuntimeState.updateSettings { it.copy(captureTimeframe = value) }
    fun operatingMode(value: OperatingMode) = RuntimeState.updateSettings { it.copy(operatingMode = value) }
    fun risk(delta: Double) = RuntimeState.updateSettings { it.copy(riskPerTradePercent = (it.riskPerTradePercent + delta).coerceIn(0.1, 1.0)) }
    fun payout(delta: Double) = RuntimeState.updateSettings { it.copy(minimumPayoutPercent = (it.minimumPayoutPercent + delta).coerceIn(60.0, 95.0)) }
    fun sample(delta: Int) = RuntimeState.updateSettings { it.copy(minimumSample = (it.minimumSample + delta).coerceIn(10, 500)) }
}

@Composable
private fun OrbisTerminalApp(vm: TerminalViewModel = viewModel()) {
    val nav = rememberNavController()
    val current = nav.currentBackStackEntryAsState().value?.destination?.route
    val routes = listOf("sessao", "decisao", "diario", "dados", "ajustes")
    Scaffold(bottomBar = {
        NavigationBar {
            routes.forEach { route ->
                NavigationBarItem(
                    selected = current == route,
                    onClick = { nav.navigate(route) { launchSingleTop = true } },
                    icon = { Text(when (route) {
                        "sessao" -> "◉"; "decisao" -> "◇"; "diario" -> "≡"; "dados" -> "▦"; else -> "⚙"
                    }) },
                    label = { Text(route.replaceFirstChar(Char::uppercase)) }
                )
            }
        }
    }) { padding ->
        NavHost(nav, "sessao", Modifier.padding(padding)) {
            composable("sessao") { SessionScreen(vm) }
            composable("decisao") { DecisionScreen(vm) }
            composable("diario") { JournalScreen(vm) }
            composable("dados") { DashboardScreen(vm) }
            composable("ajustes") { SettingsScreen(vm) }
        }
    }
}

@Composable
private fun SessionScreen(vm: TerminalViewModel) {
    val activity = androidx.compose.ui.platform.LocalContext.current as Activity
    val overlay by vm.overlayRunning.collectAsState()
    val capture by vm.captureRunning.collectAsState()
    val frames by vm.capturedFrames.collectAsState()
    val settings by vm.settings.collectAsState()
    val session by vm.session.collectAsState()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.takeIf { result.resultCode == Activity.RESULT_OK }?.let { data ->
            ContextCompat.startForegroundService(activity, Intent(activity, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            })
        }
    }
    Page("Orbis Decision Terminal") {
        Text("Assistência de decisão e risco — validação em conta demo")
        Text("Mercado", style = MaterialTheme.typography.titleMedium)
        ChoiceRow {
            ChoiceButton("Mercado aberto", settings.marketMode == MarketMode.OPEN_MARKET) { vm.marketMode(MarketMode.OPEN_MARKET) }
            ChoiceButton("OTC", settings.marketMode == MarketMode.OTC) { vm.marketMode(MarketMode.OTC) }
        }
        Text("Timeframe que está visível", style = MaterialTheme.typography.titleMedium)
        CaptureTimeframe.entries.forEach { timeframe ->
            ChoiceButton(timeframe.label, settings.captureTimeframe == timeframe) { vm.timeframe(timeframe) }
        }
        Text("Modo operacional", style = MaterialTheme.typography.titleMedium)
        OperatingMode.entries.forEach { mode ->
            ChoiceButton(mode.name, settings.operatingMode == mode) { vm.operatingMode(mode) }
        }
        MetricCard("Overlay", if (overlay) "ATIVO" else "INATIVO")
        MetricCard("Captura", if (capture) "ATIVA · $frames frames" else "INATIVA")
        Button(modifier = Modifier.fillMaxWidth(), onClick = {
            if (!Settings.canDrawOverlays(activity)) {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${activity.packageName}")))
            } else ContextCompat.startForegroundService(activity, Intent(activity, OverlayService::class.java))
        }) { Text(if (overlay) "Overlay ativo" else "Autorizar e iniciar overlay") }
        Button(modifier = Modifier.fillMaxWidth(), onClick = {
            launcher.launch(activity.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
        }) { Text(if (capture) "Reiniciar captura" else "Iniciar captura e análise") }
        ChoiceRow {
            Button(onClick = { activity.stopService(Intent(activity, OverlayService::class.java)) }) { Text("Parar overlay") }
            Button(onClick = { activity.stopService(Intent(activity, ScreenCaptureService::class.java)) }) { Text("Parar captura") }
        }
        HorizontalDivider()
        Text("Sessão", style = MaterialTheme.typography.titleLarge)
        MetricCard("Resultado", money(session.pnlAmount))
        MetricCard("Operações", "${session.trades}/${settings.maxTradesPerSession} · ${session.wins}W/${session.losses}L/${session.draws}D")
        MetricCard("Drawdown", money(session.drawdownAmount))
        MetricCard("Risco por entrada", "${pct(settings.riskPerTradePercent / 100.0)} · ${money(settings.riskAmount)}")
        MetricCard("Estado", if (session.locked) "BLOQUEADA · ${session.lockReason}" else "LIBERADA PELO RISCO")
        Button(modifier = Modifier.fillMaxWidth(), onClick = { RuntimeState.resetSession() }) { Text("Iniciar nova sessão") }
    }
}

@Composable
private fun DecisionScreen(vm: TerminalViewModel) {
    val terminal by vm.terminal.collectAsState()
    val settings by vm.settings.collectAsState()
    var revealBlind by remember { mutableStateOf(false) }
    val d = terminal.decision
    Page("Decisão atual") {
        if (settings.operatingMode == OperatingMode.BLIND && !revealBlind) {
            MetricCard("Modo cego", "Registre primeiro sua escolha no diário. A leitura do sistema está ocultada.")
            Button(modifier = Modifier.fillMaxWidth(), onClick = { revealBlind = true }) { Text("Revelar leitura do Orbis") }
        } else {
            MetricCard("Tela", if (d.context.screenValidated) "VALIDADA · ${(d.context.visualConfidence * 100).toInt()}%" else "NÃO VALIDADA")
            MetricCard("Ativo", d.context.asset)
            MetricCard("Payout e vencimento", "${d.context.payoutPercent?.let { "%.1f%%".format(Locale.US, it) } ?: "—"} · ${d.context.expirySeconds?.let { "$it s" } ?: "—"}")
            MetricCard("Memória multi-timeframe", "15m ${memoryStatus(terminal.contextMemory)} · 5m ${memoryStatus(terminal.structureMemory)} · 1m ${memoryStatus(terminal.entryMemory)}")
            MetricCard("Regime", d.regime.name)
            MetricCard("Estrutura", "${d.structure.bias.name} · ${d.structure.description}")
            MetricCard("Playbook", d.playbook.name)
            MetricCard("Etapa", d.stage.name)
            MetricCard("Direção candidata", d.direction.name)
            MetricCard("Confluência", "${d.confluenceScore}/100")
            MetricCard("Qualidade da entrada", "${d.entryQuality.score}/100")
            MetricCard("Probabilidade condicionada", "${pct(d.probability.posteriorProbability)} · ${d.probability.label} · n=${d.probability.sampleSize}")
            MetricCard("Break-even", pct(d.probability.breakEven))
            MetricCard("Expectativa", signed(d.probability.expectedValue))
            MetricCard("Decisão final", if (d.operationAllowed) "OPERAÇÃO PERMITIDA" else "OPERAÇÃO BLOQUEADA")
            MetricCard("Vetos", if (d.blockers.isEmpty()) "Nenhum" else d.blockers.joinToString("\n• ", prefix = "• "))
            MetricCard("Razões favoráveis", if (d.reasons.isEmpty()) "Nenhuma" else d.reasons.joinToString("\n• ", prefix = "• "))
            MetricCard("Próxima condição", d.nextCondition)
            MetricCard("OCR", terminal.lastOcrText.ifBlank { "Aguardando leitura" })
            terminal.error?.let { MetricCard("Erro", it) }
        }
    }
}

@Composable
private fun JournalScreen(vm: TerminalViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val rows by vm.journal.collectAsState()
    Page("Diário auditável") {
        if (rows.isEmpty()) Text("Nenhum candidato registrado. O Orbis registra setups ARMADOS ou VÁLIDOS, inclusive quando o motor de veto os bloqueia.")
        rows.forEach { item ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("${item.direction.name} · ${item.playbook.name} · ${item.stage.name}", style = MaterialTheme.typography.titleMedium)
                    Text("${item.asset} · ${item.marketMode.name} · ${item.timeframe.label}\n${DateFormat.getDateTimeInstance().format(Date(item.timestamp))}")
                    Text("Confluência ${item.confluenceScore}/100 · Entrada ${item.entryQuality}/100")
                    Text("Prob. ${pct(item.probability)} · BE ${pct(item.breakEven)} · EV ${signed(item.expectedValue)}")
                    Text(if (item.operationAllowed) "Sistema: PERMITIU" else "Sistema: BLOQUEOU · ${item.blocker ?: "sem motivo"}")
                    Text("Humano: ${item.humanChoice.name} · Resultado: ${item.outcome.name} · Plano: ${item.followedPlan ?: "—"}")
                    ChoiceRow {
                        listOf(HumanChoice.CALL, HumanChoice.PUT, HumanChoice.SKIP).forEach { choice ->
                            Button(onClick = { setHumanChoice(context, item.id, choice) }) { Text(choice.name) }
                        }
                    }
                    ChoiceRow {
                        listOf(JournalOutcome.WIN, JournalOutcome.LOSS, JournalOutcome.DRAW).forEach { outcome ->
                            Button(onClick = { setOutcome(context, item.id, outcome, item.followedPlan) }) { Text(outcome.name) }
                        }
                    }
                    ChoiceRow {
                        Button(onClick = { setOutcome(context, item.id, item.outcome, true) }) { Text("Plano SIM") }
                        Button(onClick = { setOutcome(context, item.id, item.outcome, false) }) { Text("Plano NÃO") }
                        Button(onClick = { setOutcome(context, item.id, JournalOutcome.INVALIDATED, item.followedPlan) }) { Text("Invalidar") }
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardScreen(vm: TerminalViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val rows by vm.journal.collectAsState()
    val dashboard = StatisticsEngine.calculate(rows)
    Page("Performance e auditoria") {
        MetricCard("Candidatos", "${dashboard.totalCandidates} · resolvidos ${dashboard.resolved}")
        MetricCard("Resultado", "${dashboard.wins}W/${dashboard.losses}L · ${"%.1f%%".format(Locale.US, dashboard.winRate)}")
        MetricCard("Retorno simulado", "${signed(dashboard.expectedUnits)} unidades")
        MetricCard("Profit factor", if (dashboard.profitFactor.isInfinite()) "∞" else "%.2f".format(Locale.US, dashboard.profitFactor))
        MetricCard("Drawdown máximo", "%.2f unidades".format(Locale.US, dashboard.maxDrawdownUnits))
        MetricCard("Aderência ao plano", "%.1f%%".format(Locale.US, dashboard.planAdherence))
        MetricCard("Motor de veto", "Permitidos ${dashboard.allowedCandidates} · Bloqueados ${dashboard.blockedCandidates}")
        MetricCard("Perdas bloqueadas", dashboard.avoidedLosses.toString())
        MetricCard("Melhor playbook", dashboard.bestPlaybook?.name ?: "DADOS INSUFICIENTES")
        MetricCard("Leitura", dashboard.summary)
        val grouped = rows.groupBy { "${it.marketMode.name} · ${it.playbook.name}" }
        grouped.forEach { (label, segment) ->
            val marked = segment.filter { it.outcome in setOf(JournalOutcome.WIN, JournalOutcome.LOSS) }
            val wins = marked.count { it.outcome == JournalOutcome.WIN }
            MetricCard(label, "n=${marked.size} · ${if (marked.isEmpty()) "—" else "%.1f%%".format(Locale.US, wins * 100.0 / marked.size)}")
        }
        Button(modifier = Modifier.fillMaxWidth(), onClick = { exportCsv(context) }) { Text("Exportar diário completo em CSV") }
        Text("Resultados históricos não garantem desempenho futuro. Mantenha a validação em conta demo.")
    }
}

@Composable
private fun SettingsScreen(vm: TerminalViewModel) {
    val settings by vm.settings.collectAsState()
    Page("Política de decisão e risco") {
        MetricCard("Banca virtual", money(settings.virtualBankroll))
        MetricCard("Risco por operação", "${pct(settings.riskPerTradePercent / 100.0)} · ${money(settings.riskAmount)}")
        ChoiceRow {
            Button(onClick = { vm.risk(-0.05) }) { Text("−0,05%") }
            Button(onClick = { vm.risk(0.05) }) { Text("+0,05%") }
        }
        MetricCard("Stop diário", "${settings.dailyStopPercent}% · ${money(settings.stopAmount)}")
        MetricCard("Meta de encerramento", "${settings.dailyTargetPercent}% · ${money(settings.targetAmount)}")
        MetricCard("Limites", "${settings.maxTradesPerSession} operações · pausa após ${settings.pauseAfterLosses} perdas")
        MetricCard("Payout mínimo", "${settings.minimumPayoutPercent.toInt()}%")
        ChoiceRow {
            Button(onClick = { vm.payout(-1.0) }) { Text("−1") }
            Button(onClick = { vm.payout(1.0) }) { Text("+1") }
        }
        MetricCard("Amostra mínima", settings.minimumSample.toString())
        ChoiceRow {
            Button(onClick = { vm.sample(-10) }) { Text("−10") }
            Button(onClick = { vm.sample(10) }) { Text("+10") }
        }
        MetricCard("Qualidade mínima", "${settings.minimumEntryQuality}/100")
        MetricCard("Margem mínima", "+${settings.minimumEdgePoints} p.p. sobre o break-even")
        MetricCard("Martingale", "PROIBIDO")
        Text("Não altere os parâmetros durante uma janela de validação. Mudanças reiniciam a comparabilidade estatística.")
    }
}

@Composable
private fun Page(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        content()
    }
}

@Composable
private fun ChoiceRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), content = content)
}

@Composable
private fun ChoiceButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) FilledTonalButton(modifier = Modifier.fillMaxWidth(), onClick = onClick) { Text("✓ $label") }
    else OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = onClick) { Text(label) }
}

@Composable
private fun MetricCard(label: String, value: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

private fun setOutcome(context: Context, id: Long, outcome: JournalOutcome, followedPlan: Boolean?) {
    JournalStore(context).use { store ->
        store.setOutcome(id, outcome, followedPlan)
        val rows = store.recent()
        RuntimeState.updateJournal(rows)
        RuntimeState.setSession(SessionRiskEngine.rebuild(rows, RuntimeState.settings.value))
    }
}

private fun setHumanChoice(context: Context, id: Long, choice: HumanChoice) {
    JournalStore(context).use { store ->
        store.setHumanChoice(id, choice)
        RuntimeState.updateJournal(store.recent())
    }
}

private fun exportCsv(context: Context) {
    val file = File(context.cacheDir, "orbis_decision_terminal_journal.csv")
    file.writeText(JournalStore(context).use(JournalStore::csv))
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }, "Exportar diário do Orbis"))
}

private fun memoryStatus(snapshot: TimeframeSnapshot?): String = when {
    snapshot == null -> "AUSENTE"
    snapshot.isFresh() -> "OK"
    else -> "VENCIDA"
}
private fun money(value: Double): String = String.format(Locale("pt", "BR"), "R$ %,.2f", value)
private fun pct(value: Double?): String = value?.let { String.format(Locale.US, "%.1f%%", it * 100.0) } ?: "—"
private fun signed(value: Double?): String = value?.let { String.format(Locale.US, "%+.3f", it) } ?: "—"
