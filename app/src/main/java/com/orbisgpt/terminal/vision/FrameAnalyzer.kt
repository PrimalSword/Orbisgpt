package com.orbisgpt.terminal.vision

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.orbisgpt.terminal.OrbisTerminalApplication
import com.orbisgpt.terminal.core.*
import com.orbisgpt.terminal.data.JournalStore
import com.orbisgpt.terminal.engine.*
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max

class FrameAnalyzer {
    private data class ColoredRect(val rect: Rect, val bullish: Boolean)

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val journal = JournalStore(OrbisTerminalApplication.instance)
    private val tracker = TemporalCandleTracker()
    private val openCvReady = OpenCVLoader.initLocal()
    private var analyzedFrames = 0L
    private var lastOcrText = ""
    private var lastGraph: Rect? = null
    private var graphMisses = 0
    private var lastStoredKey = ""
    private var lastStoredAt = 0L

    fun analyze(bitmap: Bitmap) {
        val started = System.currentTimeMillis()
        analyzedFrames++
        if (!openCvReady) {
            RuntimeState.updateTerminal(RuntimeState.terminal.value.copy(error = "OpenCV não inicializado", analyzedFrames = analyzedFrames))
            return
        }

        val source = Mat()
        val rgb = Mat()
        val hsv = Mat()
        val gray = Mat()
        val edges = Mat()
        val greenMask = Mat()
        val redLowMask = Mat()
        val redHighMask = Mat()
        val redMask = Mat()
        val edgeContours = mutableListOf<MatOfPoint>()
        val greenContours = mutableListOf<MatOfPoint>()
        val redContours = mutableListOf<MatOfPoint>()
        val edgeHierarchy = Mat()
        val greenHierarchy = Mat()
        val redHierarchy = Mat()

        try {
            Utils.bitmapToMat(bitmap, source)
            Imgproc.cvtColor(source, rgb, Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
            Imgproc.cvtColor(source, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(gray, gray, Size(3.0, 3.0), 0.0)
            Imgproc.Canny(gray, edges, 35.0, 125.0)
            Imgproc.findContours(edges, edgeContours, edgeHierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)

            Core.inRange(hsv, Scalar(30.0, 65.0, 48.0), Scalar(105.0, 255.0, 255.0), greenMask)
            Core.inRange(hsv, Scalar(0.0, 70.0, 48.0), Scalar(16.0, 255.0, 255.0), redLowMask)
            Core.inRange(hsv, Scalar(162.0, 70.0, 48.0), Scalar(180.0, 255.0, 255.0), redHighMask)
            Core.bitwise_or(redLowMask, redHighMask, redMask)
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(2.0, 3.0))
            Imgproc.morphologyEx(greenMask, greenMask, Imgproc.MORPH_CLOSE, kernel)
            Imgproc.morphologyEx(redMask, redMask, Imgproc.MORPH_CLOSE, kernel)
            kernel.release()
            Imgproc.findContours(greenMask, greenContours, greenHierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            Imgproc.findContours(redMask, redContours, redHierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

            val colored = buildList {
                greenContours.mapTo(this) { ColoredRect(Imgproc.boundingRect(it), true) }
                redContours.mapTo(this) { ColoredRect(Imgproc.boundingRect(it), false) }
            }
            val edgeRects = edgeContours.map(Imgproc::boundingRect)
            val detected = detectGraph(edgeRects, colored, source.width(), source.height())
            if (detected != null && graphChangedMaterially(lastGraph, detected)) tracker.reset()
            val graph = when {
                detected != null -> detected.also { lastGraph = it; graphMisses = 0 }
                lastGraph != null && graphMisses < GRAPH_GRACE_FRAMES && hasEvidence(colored, lastGraph!!) ->
                    lastGraph.also { graphMisses++ }
                else -> null.also { lastGraph = null; graphMisses = 0 }
            }
            val currentEvidence = graph != null && hasEvidence(colored, graph)
            val rawCandles = graph?.let { reconstructCandles(colored, it) }.orEmpty()
            val candles = tracker.update(if (currentEvidence) rawCandles else emptyList(), graph?.width ?: source.width())
            val screenArea = max(1.0, source.width().toDouble() * source.height())
            val confidence = if (currentEvidence && graph != null) {
                val areaScore = (graph.area() / screenArea).coerceIn(0.0, 1.0)
                val candleScore = (candles.size / 40.0).coerceIn(0.0, 1.0)
                (areaScore * 0.25 + candleScore * 0.75).coerceIn(0.0, 1.0)
            } else 0.0

            val settings = RuntimeState.settings.value
            val indicators = IndicatorEngine.calculate(candles)
            val preliminaryStructure = MarketAnalysisEngine.structure(candles, indicators)
            val preliminaryRegime = MarketAnalysisEngine.regime(candles, indicators, preliminaryStructure, confidence)
            val preliminaryPlaybook = MarketAnalysisEngine.playbook(preliminaryRegime)
            val context = parseContext(lastOcrText, currentEvidence, confidence)
            val snapshot = TimeframeSnapshot(
                timeframe = settings.captureTimeframe,
                timestamp = System.currentTimeMillis(),
                candles = candles,
                indicators = indicators,
                structure = preliminaryStructure,
                regime = preliminaryRegime,
                visualConfidence = confidence
            )
            if (context.screenValidated && candles.size >= MIN_CANDLES) RuntimeState.remember(snapshot)
            val memory = RuntimeState.terminal.value
            val performance = journal.performance(settings.marketMode, preliminaryPlaybook, preliminaryRegime)
            val decision = TerminalDecisionEngine.evaluate(
                DecisionInput(
                    context = context,
                    candles = candles,
                    indicators = indicators,
                    settings = settings,
                    session = RuntimeState.session.value,
                    contextMemory = memory.contextMemory,
                    structureMemory = memory.structureMemory,
                    history = performance
                )
            )
            RuntimeState.updateTerminal(
                memory.copy(
                    settings = settings,
                    session = RuntimeState.session.value,
                    decision = decision,
                    contextMemory = RuntimeState.terminal.value.contextMemory,
                    structureMemory = RuntimeState.terminal.value.structureMemory,
                    entryMemory = RuntimeState.terminal.value.entryMemory,
                    trackedCandles = candles.size,
                    analyzedFrames = analyzedFrames,
                    lastOcrText = lastOcrText,
                    processingMs = System.currentTimeMillis() - started,
                    error = null
                )
            )
            persistCandidate(decision, settings)
            if (analyzedFrames % OCR_INTERVAL == 0L) runOcr(bitmap)
        } catch (error: Throwable) {
            RuntimeState.updateTerminal(
                RuntimeState.terminal.value.copy(
                    analyzedFrames = analyzedFrames,
                    processingMs = System.currentTimeMillis() - started,
                    error = error.message ?: error.javaClass.simpleName
                )
            )
        } finally {
            edgeContours.forEach(MatOfPoint::release)
            greenContours.forEach(MatOfPoint::release)
            redContours.forEach(MatOfPoint::release)
            edgeHierarchy.release(); greenHierarchy.release(); redHierarchy.release()
            redMask.release(); redHighMask.release(); redLowMask.release(); greenMask.release()
            edges.release(); gray.release(); hsv.release(); rgb.release(); source.release()
        }
    }

    fun close() {
        recognizer.close()
        journal.close()
    }

    private fun persistCandidate(decision: TerminalDecision, settings: TerminalSettings) {
        if (!decision.context.screenValidated) return
        if (decision.direction == SignalDirection.WAIT) return
        if (decision.stage !in setOf(SetupStage.ARMED, SetupStage.VALID)) return
        if (decision.entryQuality.score < 58) return
        val key = listOf(settings.marketMode, decision.context.asset, decision.playbook, decision.direction, decision.stage).joinToString("|")
        val now = System.currentTimeMillis()
        if (key == lastStoredKey && now - lastStoredAt < CANDIDATE_COOLDOWN_MS) return
        journal.insert(decision, settings)
        lastStoredKey = key
        lastStoredAt = now
        RuntimeState.updateJournal(journal.recent())
    }

    private fun runOcr(bitmap: Bitmap) {
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                lastOcrText = result.text.lineSequence().map(String::trim).filter(String::isNotBlank).take(18).joinToString(" | ")
                RuntimeState.updateTerminal(RuntimeState.terminal.value.copy(lastOcrText = lastOcrText, error = null))
            }
            .addOnFailureListener { error ->
                RuntimeState.updateTerminal(RuntimeState.terminal.value.copy(error = "OCR: ${error.message}"))
            }
    }

    private fun parseContext(text: String, graphEvidence: Boolean, visualConfidence: Double): MarketContext {
        val normalized = text.uppercase().replace(',', '.')
        val assetRegex = Regex("[A-Z]{3}\\s*/\\s*[A-Z]{3}(?:\\s*\\(OTC\\))?")
        val asset = assetRegex.find(normalized)?.value?.replace(" ", "")
            ?: normalized.split("|").map(String::trim).firstOrNull { it.contains("OTC") && it.length < 30 }
            ?: "NÃO IDENTIFICADO"
        val payouts = Regex("(\\d{2,3}(?:\\.\\d+)?)\\s*%").findAll(normalized)
            .mapNotNull { it.groupValues[1].toDoubleOrNull() }
            .filter { it in 40.0..100.0 }
            .toList()
        val payout = payouts.maxOrNull()
        val expiry = parseExpiry(normalized)
        val brokerMarker = payout != null && (asset != "NÃO IDENTIFICADO" || normalized.contains("OTC"))
        val ocrConfidence = when {
            payout != null && asset != "NÃO IDENTIFICADO" && expiry != null -> 1.0
            payout != null && asset != "NÃO IDENTIFICADO" -> 0.8
            brokerMarker -> 0.6
            else -> 0.0
        }
        return MarketContext(
            asset = asset,
            payoutPercent = payout,
            expirySeconds = expiry,
            screenValidated = graphEvidence && visualConfidence >= 0.50 && brokerMarker,
            visualConfidence = visualConfidence,
            ocrConfidence = ocrConfidence
        )
    }

    private fun parseExpiry(text: String): Int? {
        val hms = Regex("(?<!\\d)(\\d{1,2}):(\\d{2}):(\\d{2})(?!\\d)").findAll(text).mapNotNull { match ->
            val h = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val m = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            val s = match.groupValues[3].toIntOrNull() ?: return@mapNotNull null
            (h * 3600 + m * 60 + s).takeIf { it in 15..86_400 }
        }.toList()
        if (hms.isNotEmpty()) return hms.minOrNull()
        return Regex("(?<!\\d)(\\d{1,2}):(\\d{2})(?!\\d)").findAll(text).mapNotNull { match ->
            val m = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val s = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            (m * 60 + s).takeIf { it in 15..3600 }
        }.minOrNull()
    }

    private fun detectGraph(edges: List<Rect>, colored: List<ColoredRect>, width: Int, height: Int): Rect? {
        val edgeCandidate = edges.asSequence()
            .filter { it.width > width * 0.34 && it.height > height * 0.16 }
            .filter { it.width < width * 0.99 && it.height < height * 0.90 }
            .filter { it.y < height * 0.82 }
            .filter { hasEvidence(colored, it) }
            .maxByOrNull { it.area() }
        if (edgeCandidate != null) return edgeCandidate
        val usable = colored.filter { rect ->
            rect.rect.width in 1..max(14, width / 15) &&
                rect.rect.height in 3..max(10, height * 3 / 5) &&
                rect.rect.y < height * 0.85
        }
        if (usable.size < MIN_COLOR_CANDLES) return null
        val minX = usable.minOf { it.rect.x }
        val maxX = usable.maxOf { it.rect.x + it.rect.width }
        val minY = usable.minOf { it.rect.y }
        val maxY = usable.maxOf { it.rect.y + it.rect.height }
        if (maxX - minX < width * MIN_HORIZONTAL_COVERAGE) return null
        val marginX = width / 30
        val marginY = height / 20
        return Rect(
            (minX - marginX).coerceAtLeast(0),
            (minY - marginY).coerceAtLeast(0),
            (maxX - minX + marginX * 2).coerceAtMost(width),
            (maxY - minY + marginY * 2).coerceAtMost(height)
        )
    }

    private fun hasEvidence(colored: List<ColoredRect>, graph: Rect): Boolean {
        val candidates = colored.filter { isCandle(it.rect, graph) }
        if (candidates.size < MIN_COLOR_CANDLES) return false
        if (candidates.none(ColoredRect::bullish) || candidates.all(ColoredRect::bullish)) return false
        val minX = candidates.minOf { it.rect.x }
        val maxX = candidates.maxOf { it.rect.x + it.rect.width }
        return maxX - minX >= graph.width * MIN_HORIZONTAL_COVERAGE
    }

    private fun reconstructCandles(rectangles: List<ColoredRect>, graph: Rect): List<Candle> = rectangles
        .filter { isCandle(it.rect, graph) }
        .sortedBy { it.rect.x }
        .distinctBy { it.rect.x / max(1, graph.width / 180) }
        .map { colored ->
            val rect = colored.rect
            val high = (graph.y + graph.height - rect.y).toDouble()
            val low = (graph.y + graph.height - rect.y - rect.height).toDouble()
            val padding = max(1.0, rect.height * 0.18)
            val open = if (colored.bullish) low + padding else high - padding
            val close = if (colored.bullish) high - padding else low + padding
            Candle(open, high, low, close, rect.x)
        }

    private fun isCandle(candidate: Rect, graph: Rect): Boolean {
        val marginX = max(2, graph.width / 100)
        val marginY = max(2, graph.height / 100)
        val inside = candidate.x >= graph.x - marginX && candidate.y >= graph.y - marginY &&
            candidate.x + candidate.width <= graph.x + graph.width + marginX &&
            candidate.y + candidate.height <= graph.y + graph.height + marginY
        val narrow = candidate.width in 1..max(14, graph.width / 16)
        val usefulHeight = candidate.height in max(3, graph.height / 140)..max(9, graph.height * 4 / 5)
        val bodyShape = candidate.height >= max(2, candidate.width / 2)
        return inside && narrow && usefulHeight && bodyShape
    }

    private fun graphChangedMaterially(old: Rect?, fresh: Rect): Boolean {
        if (old == null) return false
        val delta = abs(old.x - fresh.x) + abs(old.y - fresh.y) + abs(old.width - fresh.width) + abs(old.height - fresh.height)
        return delta > (old.width + old.height) * 0.25
    }

    companion object {
        private const val OCR_INTERVAL = 10L
        private const val MIN_CANDLES = 8
        private const val MIN_COLOR_CANDLES = 7
        private const val MIN_HORIZONTAL_COVERAGE = 0.22
        private const val GRAPH_GRACE_FRAMES = 2
        private const val CANDIDATE_COOLDOWN_MS = 60_000L
    }
}
