package com.gemmark.app

import com.gemmark.app.core.model.RoundResult
import com.gemmark.app.core.model.RoundStatus
import com.gemmark.app.core.model.RunStatus
import com.gemmark.app.core.model.SummaryStats
import com.gemmark.app.core.stats.ScoreCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoreCalculatorTest {

    private fun round(
        i: Int,
        ttft: Double,
        decode: Double,
        status: RoundStatus = RoundStatus.OK,
        retries: Int = 0,
    ) = RoundResult(
        i = i,
        ttftMs = ttft,
        decodeTps = decode,
        prefillTps = 256.0 / (ttft / 1000.0),
        outputTokens = 256,
        status = status,
        retries = retries,
    )

    private fun summary(
        median: Double,
        stddev: Double,
        thermalDrop: Double,
        rounds: List<RoundResult>,
    ) = SummaryStats(
        validRounds = rounds.count { it.isValidForStats },
        decodeTpsMedian = median,
        decodeTpsTrimmedMean = median,
        decodeTpsStdDev = stddev,
        decodeTpsP10 = median,
        decodeTpsP90 = median,
        decodeTpsMin = median,
        ttftMsMedian = 0.0,
        e2eTpsMedian = median,
        prefillTpsMedian = 0.0,
        thermalDrop = thermalDrop,
        tempPeakC = 38.0,
        ttftMsMedianClean = ScoreCalculator.cleanTtftMedian(rounds),
        prefillTpsMedianClean = ScoreCalculator.cleanPrefillMedian(rounds),
        cleanRounds = ScoreCalculator.cleanRounds(rounds).size,
    )

    /**
     * Golden case from the real nano-v3 run (GMK-4A8B): lands close to the
     * anchor by coincidence — the anchor itself is a constant spec, not this run.
     */
    @Test
    fun `nano-v3 profile scores near 1000 against anchor v1`() {
        // clean ttft median 581, decode 11.67, drop 0.977, cv 0.106/11.67
        val rounds = listOf(
            round(1, 561.0, 11.98),
            round(2, 581.0, 11.92),
            round(3, 584.0, 11.72),
        ) + (4..15).map { round(it, 650.0, 11.65, RoundStatus.BUSY_RETRIED, retries = 2) }
        val s = summary(11.67, 0.106, 0.977, rounds)

        val card = ScoreCalculator.compute(3, RunStatus.COMPLETED, rounds, s)
        assertNotNull(card)
        assertTrue("total=${card!!.total}", card.total in 985..1015)
        assertEquals("clean", card.ttftBasis)
        assertEquals(3, card.cleanRoundCount)
        // Sub-scores carry absolute meaning now: 11.67 tok/s vs 10 anchor ≈ 1167.
        assertTrue("decode=${card.decode}", card.decode in 1160..1175)
    }

    /** nano-v4-fast profile from run 91922cfe: all clean, ~3x decode. */
    @Test
    fun `v4-fast profile scores near 2400`() {
        val rounds = (1..15).map { round(it, 209.0, 37.0) }
        val s = summary(37.02, 0.56, 0.978, rounds)

        val card = ScoreCalculator.compute(3, RunStatus.COMPLETED, rounds, s)
        assertNotNull(card)
        assertTrue("total=${card!!.total}", card.total in 2250..2550)
        assertTrue("decode=${card.decode}", card.decode > 3500)
    }

    /** TTFT pollution by BUSY retries must not leak into the response score. */
    @Test
    fun `retried rounds excluded from response basis`() {
        val rounds = (1..5).map { round(it, 430.0, 17.5) } +
            (6..15).map { round(it, 4400.0, 17.4, RoundStatus.BUSY_RETRIED, retries = 2) }
        val s = summary(17.45, 1.22, 1.0, rounds)

        val card = ScoreCalculator.compute(3, RunStatus.COMPLETED, rounds, s)
        assertNotNull(card)
        assertEquals("clean", card!!.ttftBasis)
        assertEquals(5, card.cleanRoundCount)
        // Response basis 430ms → 500/430 ≈ 1163, NOT ~128 (which the polluted
        // 3922ms median would give).
        assertTrue("response=${card.response}", card.response in 1100..1230)
    }

    @Test
    fun `reasoning dimension joins the composite when thinking rounds exist`() {
        val mainRounds = (1..8).map { round(it, 250.0, 38.0) }
        // Thinking rounds: window throughput (thought+answer) carried in decodeTps.
        val thinkingRounds = (9..11).map {
            round(it, 400.0, 36.0).copy(workload = "thinking", thoughtTokens = 300, timeToAnswerMs = 5000.0)
        }
        val rounds = mainRounds.map { it.copy(workload = "main") } + thinkingRounds
        val s = summary(38.0, 0.5, 1.0, rounds)

        val card = ScoreCalculator.compute(3, RunStatus.COMPLETED, rounds, s)
        assertNotNull(card)
        // reasoning ratio = 36/10 → 3600
        assertEquals(3600, card!!.reasoning)
        // Full five-dimension weight sum = 1.0; total must reflect reasoning's 20%.
        assertTrue("total=${card.total}", card.total in 2400..3400)
    }

    @Test
    fun `missing reasoning renormalizes weights instead of zeroing`() {
        val rounds = (1..10).map { round(it, 250.0, 38.0).copy(workload = "main") }
        val s = summary(38.0, 0.5, 1.0, rounds)
        val card = ScoreCalculator.compute(3, RunStatus.COMPLETED, rounds, s)
        assertNotNull(card)
        assertEquals(null, card!!.reasoning)
        // Same profile WITH reasoning at exactly the anchor ratio of the other
        // dims would land near the renormalized value — sanity: total is in the
        // same ballpark as the four-dimension geometric mean, not dragged to 0.
        assertTrue("total=${card.total}", card.total > 1500)
    }

    @Test
    fun `non-G3 runs are not scored`() {
        val rounds = (1..15).map { round(it, 400.0, 20.0) }
        val s = summary(20.0, 0.5, 1.0, rounds)
        assertNull(ScoreCalculator.compute(1, RunStatus.COMPLETED, rounds, s))
    }

    @Test
    fun `incomplete runs are not scored`() {
        val rounds = (1..15).map { round(it, 400.0, 20.0) }
        val s = summary(20.0, 0.5, 1.0, rounds)
        assertNull(ScoreCalculator.compute(3, RunStatus.NEEDS_RETEST, rounds, s))
        assertNull(ScoreCalculator.compute(3, RunStatus.ABORTED, rounds, s))
    }

    @Test
    fun `all-retried run falls back to all_valid basis`() {
        val rounds = (1..15).map { round(it, 900.0, 15.0, RoundStatus.BUSY_RETRIED, retries = 1) }
        val s = summary(15.0, 0.3, 0.99, rounds)
        val card = ScoreCalculator.compute(3, RunStatus.COMPLETED, rounds, s)
        assertNotNull(card)
        assertEquals("all_valid", card!!.ttftBasis)
        assertEquals(0, card.cleanRoundCount)
    }

    @Test
    fun `device score is the geometric mean of both model totals`() {
        // geomean(3267, 2000) = sqrt(3267 * 2000) ≈ 2556
        assertEquals(2556, ScoreCalculator.deviceScore(listOf(3267, 2000)))
        // Symmetric: model order must not matter.
        assertEquals(2556, ScoreCalculator.deviceScore(listOf(2000, 3267)))
        // Single-model (partial coverage) degenerates to that model's score.
        assertEquals(3267, ScoreCalculator.deviceScore(listOf(3267)))
        // Equal scores are a fixed point.
        assertEquals(1000, ScoreCalculator.deviceScore(listOf(1000, 1000)))
        // No scored models -> no device score.
        assertNull(ScoreCalculator.deviceScore(emptyList()))
        assertNull(ScoreCalculator.deviceScore(listOf(0)))
    }

    @Test
    fun `load sub-score anchors at 5s and caps hot-attach inflation`() {
        // Exactly at anchor: 5 s median switch load -> 1000.
        assertEquals(1000, ScoreCalculator.loadScore(listOf(5000L, 5000L, 5000L, 5000L)))
        // Median of mixed fast/full displacement loads: (4200+5900)/2 = 5050 ms.
        assertEquals(990, ScoreCalculator.loadScore(listOf(4200L, 5900L, 4100L, 6000L)))
        // Fast-but-real loads must not inflate: ratio capped at 4x.
        assertEquals(4000, ScoreCalculator.loadScore(listOf(900L, 910L, 890L, 905L)))
        // Non-blocking warmup (MediaTek: ~35-51 ms lazy load) is not a
        // measurement — filtered out entirely.
        assertNull(ScoreCalculator.loadScore(listOf(35L, 51L, 37L, 40L)))
        // Mixed: only the real displacement loads count.
        assertEquals(833, ScoreCalculator.loadScore(listOf(35L, 6000L, 40L, 6000L)))
        // No samples -> no dimension.
        assertNull(ScoreCalculator.loadScore(emptyList()))
        assertNull(ScoreCalculator.loadScore(listOf(0L)))
    }

    @Test
    fun `device score folds load at 10 percent and renormalizes without it`() {
        // exp(0.45*ln(3267) + 0.45*ln(2000) + 0.10*ln(800)) = 2276
        assertEquals(2276, ScoreCalculator.deviceScore(listOf(3267, 2000), loadScore = 800))
        // Missing load renormalizes back to the plain geometric mean.
        assertEquals(2556, ScoreCalculator.deviceScore(listOf(3267, 2000), loadScore = null))
        // Load equal to the model geomean is a fixed point.
        assertEquals(1000, ScoreCalculator.deviceScore(listOf(1000, 1000), loadScore = 1000))
    }
}
