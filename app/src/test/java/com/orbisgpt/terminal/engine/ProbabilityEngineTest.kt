package com.orbisgpt.terminal.engine

import com.orbisgpt.terminal.core.HistoricalPerformance
import org.junit.Assert.*
import org.junit.Test

class ProbabilityEngineTest {
    @Test
    fun positiveSampleProducesPositiveEdgeAboveBreakEven() {
        val result = ProbabilityEngine.calculate(HistoricalPerformance(wins = 60, losses = 40), 90.0)
        assertEquals(100, result.sampleSize)
        assertNotNull(result.posteriorProbability)
        assertNotNull(result.breakEven)
        assertTrue(result.posteriorProbability!! > result.breakEven!!)
        assertTrue(result.expectedValue!! > 0.0)
        assertTrue(result.edgePoints!! > 0.0)
    }

    @Test
    fun noSampleDoesNotInventProbability() {
        val result = ProbabilityEngine.calculate(HistoricalPerformance(), 88.0)
        assertNull(result.posteriorProbability)
        assertEquals(1.0 / 1.88, result.breakEven!!, 1e-9)
    }
}
