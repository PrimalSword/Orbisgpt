package com.orbisgpt.atlas

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.orbisgpt.atlas.data.EcbFxRepository
import com.orbisgpt.atlas.data.PaperTradeStore
import com.orbisgpt.atlas.data.SettingsStore
import com.orbisgpt.atlas.engine.MarketEngine
import com.orbisgpt.atlas.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { }
            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            MaterialTheme(colorScheme = darkColorScheme()) { AtlasApp() }
        }
    }
}

class AtlasViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = EcbFxRepository(application)
    private val settingsStore = SettingsStore(application)
    private val tradeStore = PaperTradeStore(application)
    private val _state = MutableStateFlow(
        AppState(settings = settingsStore.load(), paperTrades = tradeStore.list())
    )
    val state = _state.asStateFlow()

    init { refresh() }

    fun selectBase(code: String) = updateSettings { current ->
        val quote = if (code == current.quoteCode) current.baseCode else current.quoteCode
        current.copy(baseCode = code, quoteCode = quote)
    }

    fun selectQuote(code: String) = updateSettings { current ->
        val base = if (code == current.baseCode) current.quoteCode else current.baseCode
        current.copy(baseCode = base, quoteCode = code)
    }

    fun setBankroll(value: Double) = updateSettings { it.copy(virtualBankrollBrl = value.coerceIn(100.0, 10_000_000.0)) }
    fun setRisk(value: Double) = updateSettings { it.copy(riskPercent = value.coerceIn(0.1, 1.0)) }
    fun setNotifications(value: Boolean) = updateSettings { it.copy(notificationsEnabled = value) }

    private fun updateSettings(transform: (UserSettings) -> UserSettings) {
        val settings = transform(_state.value.settings)
        settingsStore.save(settings)
        _state.value = _state.value.copy(settings = settings)
        refresh()
    }

    fun refresh() {
        if (_state.value.loading) return
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            runCatching {
                val settings = _state.value.settings
                val pair = CurrencyPair(
                    CurrencyCatalog.byCode(settings.baseCode),
                    CurrencyCatalog.byCode(settings.quoteCode)
                )
                val data = repository.load(pair)
                val plan = MarketEngine.analyze(pair, data.points, data.brlPerQuote, settings)
                tradeStore.updateWithPrice(pair.symbol, plan.currentPrice)
                _state.value = _state.value.copy(
                    loading = false,
                    error = null,
                    points = data.points,
                    plan = plan,
                    paperTrades = tradeStore.list(),
                    lastUpdatedLabel = buildString {
                        append(data.sourceDate)
                        if (data.usedCache) append(" · cache local") else append(" · BCE")
                    }
                )
            }.onFailure { error ->
                _state.value = _state.value.copy(
                    loading = false,
                    error = error.message ?: "Falha ao atualizar a análise"
                )
            }
        }
    }

    fun simulateCurrentPlan() {
        val plan = _state.value.plan ?: return
        if (plan.action == DecisionAction.WAIT || plan.positionUnits == null) return
        tradeStore.open(plan)
        _state.value = _state.value.copy(paperTrades = tradeStore.list())
    }

    fun closeTrade(id: Long) {
        val price = _state.value.plan?.currentPrice ?: return
        tradeStore.close(id, price)
        _state.value = _state.value.copy(paperTrades = tradeStore.list())
    }

    fun cancelTrade(id: Long) {
        tradeStore.cancel(id)
        _state.value = _state.value.copy(paperTrades = tradeStore.list())
    }
}

private enum class Tab(val label: String, val icon: String) {
    HOME("Hoje", "◉"), PLAN("Plano", "◎"), PAPER("Simulador", "▤"), LEARN("Aprender", "?"), SETTINGS("Ajustes", "⚙")
}

@Composable
private fun AtlasApp(vm: AtlasViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    var tab by remember { mutableStateOf(Tab.HOME) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { Text(item.icon) },
                        label = { Text(item.label) }
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                Tab.HOME -> HomeScreen(state, vm)
                Tab.PLAN -> PlanScreen(state, vm)
                Tab.PAPER -> PaperScreen(state, vm)
                Tab.LEARN -> LearnScreen()
                Tab.SETTINGS -> SettingsScreen(state, vm)
            }
        }
    }
}

@Composable
private fun HomeScreen(state: AppState, vm: AtlasViewModel) {
    Page("Orbis Atlas") {
        Text("Escolha duas moedas. O Atlas cuida da análise diária, do risco e do plano de simulação.")
        PairSelector(state.settings, vm)
        Button(onClick = vm::refresh, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.loading) "Atualizando dados oficiais…" else "Atualizar análise")
        }
        state.error?.let { AlertCard("Não foi possível concluir", it) }
        val plan = state.plan
        if (plan == null) {
            LoadingCard(state.loading)
        } else {
            DecisionHero(plan)
            PriceChart(state.points)
            MetricRow(
                "Preço de referência" to price(plan.currentPrice),
                "Atualização" to state.lastUpdatedLabel
            )
            SectionTitle("O que fazer agora")
            plan.instructions.take(3).forEachIndexed { index, text -> StepCard(index + 1, text) }
            SectionTitle("Por que o Atlas decidiu isso")
            plan.reasons.forEach { BulletCard(it) }
        }
    }
}

@Composable
private fun PlanScreen(state: AppState, vm: AtlasViewModel) {
    Page("Plano simples") {
        val plan = state.plan
        if (plan == null) {
            LoadingCard(state.loading)
            return@Page
        }
        DecisionHero(plan)
        if (plan.action == DecisionAction.WAIT) {
            AlertCard("Sem operação hoje", "O plano não está incompleto: aguardar é a decisão calculada para este par.")
        } else {
            MetricRow(
                "Faixa de entrada" to "${price(plan.entryLow)} – ${price(plan.entryHigh)}",
                "Stop obrigatório" to price(plan.stopPrice)
            )
            MetricRow(
                "Primeiro alvo" to price(plan.target1),
                "Segundo alvo" to price(plan.target2)
            )
            MetricRow(
                "Risco virtual" to money(plan.riskAmountBrl),
                "Tamanho simulado" to "${formatUnits(plan.positionUnits)} ${plan.pair.base.code}"
            )
            MetricRow(
                "Risco/retorno" to "1 : ${"%.1f".format(plan.rewardRisk ?: 0.0)}",
                "Horizonte" to "${plan.horizonDays.first}–${plan.horizonDays.last} pregões"
            )
            Button(onClick = vm::simulateCurrentPlan, modifier = Modifier.fillMaxWidth()) {
                Text("Simular este plano")
            }
        }
        SectionTitle("Regras que não podem ser alteradas")
        plan.instructions.forEach { BulletCard(it) }
        SectionTitle("Avisos")
        plan.warnings.forEach { WarningCard(it) }
        IndicatorDetails(plan.indicators)
    }
}

@Composable
private fun PaperScreen(state: AppState, vm: AtlasViewModel) {
    Page("Simulador") {
        Text("Aqui o app acompanha decisões sem usar dinheiro real. As posições são avaliadas pela próxima referência diária disponível.")
        val open = state.paperTrades.filter { it.status == TradeStatus.OPEN }
        val closed = state.paperTrades.filter { it.status != TradeStatus.OPEN }
        MetricRow(
            "Posições abertas" to open.size.toString(),
            "Resultado acumulado" to money(closed.sumOf { it.resultBrl ?: 0.0 })
        )
        if (state.paperTrades.isEmpty()) {
            AlertCard("Nenhuma simulação", "Quando houver um plano de compra ou venda, use “Simular este plano”.")
        }
        state.paperTrades.forEach { trade ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${trade.pair} · ${actionLabel(trade.action)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Aberta em ${DateFormat.getDateTimeInstance().format(Date(trade.openedAt))}")
                    Text("Entrada ${price(trade.entryPrice)} · Stop ${price(trade.stopPrice)} · Alvo ${price(trade.targetPrice)}")
                    Text("Estado: ${statusLabel(trade.status)}")
                    trade.resultBrl?.let { Text("Resultado simulado: ${money(it)}") }
                    if (trade.status == TradeStatus.OPEN) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { vm.closeTrade(trade.id) }) { Text("Fechar agora") }
                            TextButton(onClick = { vm.cancelTrade(trade.id) }) { Text("Cancelar") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LearnScreen() {
    Page("Aprender sem complicação") {
        Text("Você não precisa interpretar dezenas de indicadores. Precisa entender as regras que protegem sua banca.")
        LessonCard("1. Comprar ou vender", "Comprar a moeda-base significa esperar que ela se valorize contra a segunda moeda. Vender significa esperar o contrário.")
        LessonCard("2. Stop", "É o preço que invalida a ideia. Ele existe antes da entrada e nunca deve ser afastado para evitar assumir uma perda.")
        LessonCard("3. Tamanho da posição", "O Atlas calcula o tamanho para que um stop completo custe no máximo 0,1% a 1% da banca virtual.")
        LessonCard("4. Aguardar", "Não operar não é fracasso. Em mercados sem direção, esperar preserva capital e evita decisões por ansiedade.")
        LessonCard("5. Dados diários", "Esta versão procura movimentos de vários dias. Ela não serve para opções binárias, scalping ou promessas de ganho rápido.")
        WarningCard("Nenhum aplicativo elimina risco ou garante lucro. O Atlas foi construído para simular decisões disciplinadas e ensinar gestão de risco antes de qualquer uso real.")
    }
}

@Composable
private fun SettingsScreen(state: AppState, vm: AtlasViewModel) {
    Page("Ajustes conservadores") {
        MetricCard("Banca virtual", money(state.settings.virtualBankrollBrl))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1_000.0, 5_000.0, 10_000.0, 50_000.0).forEach { value ->
                OutlinedButton(onClick = { vm.setBankroll(value) }, modifier = Modifier.weight(1f)) {
                    Text(if (value >= 1000) "${(value / 1000).toInt()}k" else value.toInt().toString())
                }
            }
        }
        MetricCard("Risco máximo por plano", "${"%.2f".format(state.settings.riskPercent)}% · ${money(state.settings.riskAmountBrl)}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0.25, 0.5, 0.75, 1.0).forEach { value ->
                OutlinedButton(onClick = { vm.setRisk(value) }, modifier = Modifier.weight(1f)) { Text("$value%") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Notificação diária", modifier = Modifier.weight(1f))
            Switch(checked = state.settings.notificationsEnabled, onCheckedChange = vm::setNotifications)
        }
        MetricCard("Modo de operação", "SOMENTE SIMULAÇÃO")
        WarningCard("O limite de 1% é absoluto nesta versão. Martingale, recuperação de perdas e execução automática não existem no produto.")
    }
}

@Composable
private fun PairSelector(settings: UserSettings, vm: AtlasViewModel) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CurrencyPicker("Moeda que você compra/vende", settings.baseCode, vm::selectBase, Modifier.weight(1f))
        CurrencyPicker("Moeda de comparação", settings.quoteCode, vm::selectQuote, Modifier.weight(1f))
    }
}

@Composable
private fun CurrencyPicker(label: String, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Column(horizontalAlignment = Alignment.Start) {
                Text(label, style = MaterialTheme.typography.labelSmall)
                Text(selected, fontWeight = FontWeight.Bold)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            CurrencyCatalog.supported.forEach { currency ->
                DropdownMenuItem(
                    text = { Text("${currency.code} · ${currency.name}") },
                    onClick = { expanded = false; onSelect(currency.code) }
                )
            }
        }
    }
}

@Composable
private fun DecisionHero(plan: TradePlan) {
    val decision = when (plan.action) {
        DecisionAction.BUY_BASE -> "COMPRAR ${plan.pair.base.code}"
        DecisionAction.SELL_BASE -> "VENDER ${plan.pair.base.code}"
        DecisionAction.WAIT -> "AGUARDAR"
    }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(plan.pair.symbol, style = MaterialTheme.typography.titleMedium)
            Text(decision, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
            Text(plan.headline, style = MaterialTheme.typography.titleMedium)
            Text(plan.plainExplanation)
            LinearProgressIndicator(progress = { plan.confidence / 100f }, modifier = Modifier.fillMaxWidth())
            Text("Clareza do cenário: ${plan.confidence}/100 · ${stateLabel(plan.marketState)}")
        }
    }
}

@Composable
private fun PriceChart(points: List<FxPoint>) {
    if (points.size < 2) return
    val values = points.takeLast(90).map(FxPoint::rate)
    val min = values.minOrNull() ?: return
    val max = values.maxOrNull() ?: return
    val span = (max - min).coerceAtLeast(max * 0.0001)
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Últimos 90 dias", style = MaterialTheme.typography.labelLarge)
            Canvas(Modifier.fillMaxWidth().height(180.dp)) {
                repeat(4) { index ->
                    val y = size.height * index / 3f
                    drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                }
                val path = Path()
                values.forEachIndexed { index, value ->
                    val x = size.width * index / (values.lastIndex.coerceAtLeast(1)).toFloat()
                    val y = size.height - ((value - min) / span).toFloat() * size.height
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, lineColor, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(price(min), style = MaterialTheme.typography.labelSmall)
                Text(price(max), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun IndicatorDetails(i: IndicatorSet) {
    SectionTitle("Leitura técnica resumida")
    MetricRow("Média 20 dias" to price(i.sma20), "Média 60 dias" to price(i.sma60))
    MetricRow("Média 120 dias" to price(i.sma120), "RSI" to "%.1f".format(i.rsi14))
    MetricRow("Variação em 5 dias" to percent(i.change5d), "Volatilidade anual" to percent(i.annualizedVolatility))
}

@Composable
private fun Page(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        content()
        Spacer(Modifier.height(12.dp))
    }
}

@Composable private fun SectionTitle(text: String) = Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

@Composable
private fun MetricRow(vararg metrics: Pair<String, String>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        metrics.forEach { (label, value) -> Box(Modifier.weight(1f)) { MetricCard(label, value) } }
    }
}

@Composable
private fun MetricCard(label: String, value: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable private fun StepCard(number: Int, text: String) = Card(Modifier.fillMaxWidth()) {
    Row(Modifier.padding(13.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(number.toString(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
        Text(text, modifier = Modifier.weight(1f))
    }
}

@Composable private fun BulletCard(text: String) = Card(Modifier.fillMaxWidth()) { Text("• $text", Modifier.padding(12.dp)) }
@Composable private fun WarningCard(text: String) = Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) { Text(text, Modifier.padding(12.dp)) }
@Composable private fun AlertCard(title: String, text: String) = Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) { Text(title, fontWeight = FontWeight.Bold); Text(text) } }
@Composable private fun LoadingCard(loading: Boolean) = AlertCard(if (loading) "Montando a análise" else "Sem dados", if (loading) "Baixando a série diária e calculando o plano." else "Toque em atualizar para tentar novamente.")
@Composable private fun LessonCard(title: String, text: String) = Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(title, fontWeight = FontWeight.Bold); Text(text) } }

private fun actionLabel(action: DecisionAction) = when (action) {
    DecisionAction.BUY_BASE -> "COMPRA"
    DecisionAction.SELL_BASE -> "VENDA"
    DecisionAction.WAIT -> "AGUARDAR"
}
private fun statusLabel(status: TradeStatus) = when (status) {
    TradeStatus.OPEN -> "ABERTA"
    TradeStatus.TARGET -> "ALVO ATINGIDO"
    TradeStatus.STOPPED -> "STOP ATINGIDO"
    TradeStatus.CLOSED -> "FECHADA MANUALMENTE"
    TradeStatus.CANCELLED -> "CANCELADA"
}
private fun stateLabel(state: MarketState) = when (state) {
    MarketState.UPTREND -> "tendência de alta"
    MarketState.DOWNTREND -> "tendência de baixa"
    MarketState.RANGE -> "mercado sem direção"
    MarketState.HIGH_RISK -> "risco elevado"
    MarketState.INSUFFICIENT_DATA -> "dados insuficientes"
}
private fun money(value: Double): String = String.format(Locale("pt", "BR"), "R$ %,.2f", value)
private fun percent(value: Double): String = String.format(Locale.US, "%+.2f%%", value * 100.0)
private fun price(value: Double?): String = value?.let {
    when {
        kotlin.math.abs(it) >= 100 -> String.format(Locale.US, "%.2f", it)
        kotlin.math.abs(it) >= 10 -> String.format(Locale.US, "%.3f", it)
        else -> String.format(Locale.US, "%.5f", it)
    }
} ?: "—"
private fun formatUnits(value: Double?): String = value?.let { String.format(Locale.US, "%,.0f", it) } ?: "—"
