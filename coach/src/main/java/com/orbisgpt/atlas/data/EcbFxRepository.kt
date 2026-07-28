package com.orbisgpt.atlas.data

import android.content.Context
import com.orbisgpt.atlas.model.CurrencyPair
import com.orbisgpt.atlas.model.FxPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Uses the official ECB Data Portal daily reference-rate series.
 * ECB publishes currency units per EUR. Cross-rates are calculated locally.
 */
class EcbFxRepository(private val context: Context) {

    data class PairData(
        val points: List<FxPoint>,
        val brlPerQuote: Double,
        val sourceDate: LocalDate,
        val usedCache: Boolean
    )

    suspend fun load(pair: CurrencyPair): PairData = withContext(Dispatchers.IO) {
        require(pair.base.code != pair.quote.code) { "Escolha moedas diferentes" }
        var usedCache = false

        fun series(code: String): Map<LocalDate, Double> {
            if (code == "EUR") return emptyMap()
            return try {
                fetchSeries(code).also { saveCache(code, it) }
            } catch (error: Exception) {
                usedCache = true
                loadCache(code).takeIf { it.isNotEmpty() }
                    ?: throw IllegalStateException("Não foi possível obter dados do BCE para $code", error)
            }
        }

        val baseSeries = series(pair.base.code)
        val quoteSeries = series(pair.quote.code)
        val brlSeries = if (pair.quote.code == "BRL") quoteSeries else series("BRL")

        val availableDates = when {
            pair.base.code == "EUR" -> quoteSeries.keys
            pair.quote.code == "EUR" -> baseSeries.keys
            else -> baseSeries.keys.intersect(quoteSeries.keys)
        }.sorted()

        val points = availableDates.mapNotNull { date ->
            val basePerEur = if (pair.base.code == "EUR") 1.0 else baseSeries[date]
            val quotePerEur = if (pair.quote.code == "EUR") 1.0 else quoteSeries[date]
            if (basePerEur == null || quotePerEur == null || basePerEur <= 0.0) null
            else FxPoint(date, quotePerEur / basePerEur)
        }.takeLast(220)

        require(points.size >= 130) {
            "O BCE retornou apenas ${points.size} observações alinhadas; são necessárias pelo menos 130"
        }

        val latestDate = points.last().date
        val quotePerEur = if (pair.quote.code == "EUR") 1.0 else quoteSeries[latestDate]
            ?: quoteSeries.entries.lastOrNull { it.key <= latestDate }?.value
            ?: error("Cotação da moeda de referência indisponível")
        val brlPerEur = if (pair.quote.code == "BRL") quotePerEur else brlSeries[latestDate]
            ?: brlSeries.entries.lastOrNull { it.key <= latestDate }?.value
            ?: error("Cotação BRL indisponível")
        val brlPerQuote = brlPerEur / quotePerEur

        PairData(points, brlPerQuote, latestDate, usedCache)
    }

    private fun fetchSeries(code: String): Map<LocalDate, Double> {
        val endpoint = "https://data-api.ecb.europa.eu/service/data/EXR/D.$code.EUR.SP00.A" +
            "?lastNObservations=260&format=csvdata&detail=dataonly"
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            requestMethod = "GET"
            setRequestProperty("Accept", "text/csv")
            setRequestProperty("User-Agent", "OrbisAtlas/1.0 Android")
        }
        try {
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("BCE respondeu HTTP ${connection.responseCode}")
            }
            val lines = connection.inputStream.bufferedReader().use { it.readLines() }
            if (lines.isEmpty()) error("Resposta vazia do BCE")
            val header = parseCsvLine(lines.first())
            val dateIndex = header.indexOfFirst { it.equals("TIME_PERIOD", true) }
            val valueIndex = header.indexOfFirst { it.equals("OBS_VALUE", true) }
            require(dateIndex >= 0 && valueIndex >= 0) { "Formato CSV do BCE não reconhecido" }
            val parsed: List<Pair<LocalDate, Double>> = lines.drop(1).mapNotNull { line ->
                val cells = parseCsvLine(line)
                val date = cells.getOrNull(dateIndex)?.let { raw -> runCatching { LocalDate.parse(raw) }.getOrNull() }
                val value = cells.getOrNull(valueIndex)?.replace(',', '.')?.toDoubleOrNull()
                if (date == null || value == null || value <= 0.0) null else Pair(date, value)
            }
            return parsed.toMap().toSortedMap()
        } finally {
            connection.disconnect()
        }
    }

    private fun cacheFile(code: String): File = File(context.filesDir, "ecb_${code.lowercase()}.csv")

    private fun saveCache(code: String, values: Map<LocalDate, Double>) {
        cacheFile(code).writeText(values.entries.joinToString("\n") { "${it.key};${it.value}" })
    }

    private fun loadCache(code: String): Map<LocalDate, Double> {
        val file = cacheFile(code)
        if (!file.exists()) return emptyMap()
        val parsed: List<Pair<LocalDate, Double>> = file.readLines().mapNotNull { line ->
            val cells = line.split(';')
            val date = cells.getOrNull(0)?.let { raw -> runCatching { LocalDate.parse(raw) }.getOrNull() }
            val value = cells.getOrNull(1)?.toDoubleOrNull()
            if (date == null || value == null) null else Pair(date, value)
        }
        return parsed.toMap().toSortedMap()
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                char == '"' && quoted && index + 1 < line.length && line[index + 1] == '"' -> {
                    current.append('"')
                    index++
                }
                char == '"' -> quoted = !quoted
                char == ',' && !quoted -> {
                    result += current.toString()
                    current.clear()
                }
                else -> current.append(char)
            }
            index++
        }
        result += current.toString()
        return result
    }

    companion object {
        fun freshnessDays(sourceDate: LocalDate): Long =
            ChronoUnit.DAYS.between(sourceDate, LocalDate.now()).coerceAtLeast(0)
    }
}
