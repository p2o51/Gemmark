package com.gemmark.app

import com.gemmark.app.core.stats.Statistics
import org.junit.Assert.assertEquals
import org.junit.Test

class StatisticsTest {

    @Test
    fun `median of odd count`() {
        assertEquals(3.0, Statistics.median(listOf(5.0, 1.0, 3.0)), 1e-9)
    }

    @Test
    fun `median of even count averages middle pair`() {
        assertEquals(2.5, Statistics.median(listOf(4.0, 1.0, 2.0, 3.0)), 1e-9)
    }

    @Test
    fun `median of empty list is zero`() {
        assertEquals(0.0, Statistics.median(emptyList()), 1e-9)
    }

    @Test
    fun `trimmed mean drops one from each end at 15 samples`() {
        // 15 values 1..15: trim floor(1.5)=1 from each end → mean of 2..14 = 8
        val values = (1..15).map { it.toDouble() }
        assertEquals(8.0, Statistics.trimmedMean(values, 0.1), 1e-9)
    }

    @Test
    fun `trimmed mean with fewer than 10 samples trims nothing`() {
        val values = listOf(1.0, 2.0, 3.0)
        assertEquals(2.0, Statistics.trimmedMean(values, 0.1), 1e-9)
    }

    @Test
    fun `stddev of known sample`() {
        // sample stddev of [2,4,4,4,5,5,7,9] = 2.138089935
        val values = listOf(2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0)
        assertEquals(2.13808993529939, Statistics.stdDev(values), 1e-9)
    }

    @Test
    fun `stddev of single value is zero`() {
        assertEquals(0.0, Statistics.stdDev(listOf(42.0)), 1e-9)
    }

    @Test
    fun `percentile interpolates linearly`() {
        val values = (1..10).map { it.toDouble() }
        // P10 of 1..10 with linear interpolation: rank = 0.9 → 1.9
        assertEquals(1.9, Statistics.percentile(values, 10.0), 1e-9)
        assertEquals(9.1, Statistics.percentile(values, 90.0), 1e-9)
        assertEquals(10.0, Statistics.percentile(values, 100.0), 1e-9)
        assertEquals(1.0, Statistics.percentile(values, 0.0), 1e-9)
    }

    @Test
    fun `thermal drop is tail median over head median`() {
        // first 3: 40,40,40 → median 40; last 5: 30,30,30,30,30 → median 30
        val series = listOf(40.0, 40.0, 40.0, 35.0, 34.0, 33.0, 32.0, 31.0, 30.0, 30.0,
            30.0, 30.0, 30.0)
        assertEquals(0.75, Statistics.thermalDrop(series), 1e-9)
    }

    @Test
    fun `thermal drop defaults to 1 when too few rounds`() {
        assertEquals(1.0, Statistics.thermalDrop(listOf(40.0, 39.0, 38.0)), 1e-9)
    }
}
