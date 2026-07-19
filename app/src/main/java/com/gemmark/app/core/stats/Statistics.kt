package com.gemmark.app.core.stats

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Statistics used by the leaderboard, per the v1 spec:
 * median, 10% trimmed mean, sample std dev, P10/P90, min,
 * and thermal drop = median(last 5) / median(first 3).
 */
object Statistics {

    fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }

    /**
     * Trimmed mean dropping [fraction] of samples from each end
     * (spec: 去最高/最低各10% → fraction = 0.1).
     * The trim count is floor(n * fraction); with n < 1/fraction nothing is trimmed.
     */
    fun trimmedMean(values: List<Double>, fraction: Double = 0.1): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val trim = floor(sorted.size * fraction).toInt()
        val kept = sorted.subList(trim, sorted.size - trim)
        return kept.average()
    }

    /** Sample standard deviation (n − 1 denominator); 0 when n < 2. */
    fun stdDev(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        val sumSq = values.sumOf { (it - mean) * (it - mean) }
        return sqrt(sumSq / (values.size - 1))
    }

    /**
     * Percentile with linear interpolation between closest ranks
     * ([p] in 0..100, e.g. 10.0 for P10).
     */
    fun percentile(values: List<Double>, p: Double): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        if (sorted.size == 1) return sorted[0]
        val rank = (p / 100.0) * (sorted.size - 1)
        val low = floor(rank).toInt()
        val high = ceil(rank).toInt()
        if (low == high) return sorted[low]
        val weight = rank - low
        return sorted[low] * (1 - weight) + sorted[high] * weight
    }

    /**
     * Thermal drop per spec: median of the last 5 values ÷ median of the first 3,
     * computed over chronologically ordered valid-round decode speeds.
     * Returns 1.0 when there are not enough rounds to compare.
     */
    fun thermalDrop(chronological: List<Double>): Double {
        if (chronological.size < 8) return 1.0
        val head = median(chronological.take(3))
        val tail = median(chronological.takeLast(5))
        if (head == 0.0) return 1.0
        return tail / head
    }
}
