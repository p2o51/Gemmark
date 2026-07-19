package com.gemmark.app.core.stats

import com.gemmark.app.core.model.PromptGroup
import com.gemmark.app.core.model.RoundResult
import com.gemmark.app.core.model.RoundStatus
import com.gemmark.app.core.model.RunReport
import com.gemmark.app.core.model.RunStatus
import com.gemmark.app.core.model.ScoreCard
import com.gemmark.app.core.model.SummaryStats
import com.gemmark.app.core.suite.StandardSuite
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Gemmark Score — a single number for the on-device AI performance a
 * device+engine pair delivers on the G3 workload, plus four sub-scores.
 *
 * Anchor design: the 1000-point baseline is a SPEC, not a device. It is a
 * published vector of round physical constants describing a defined
 * "reference on-device assistant experience" on the G3 workload
 * (256-token prompt → 256-token greedy generation):
 *
 *      decode 10 tok/s · prefill 500 tok/s · TTFT 500 ms · zero decay
 *
 * Anchoring to constants (Geekbench anchors to a fixed reference machine;
 * we go one step further) keeps scores meaningful across devices, models,
 * and time: 2000 means "twice the reference experience" on any phone, and
 * the anchor never dies when a particular model (e.g. nano-v3) is retired.
 * A device's headline score is the best score among its available engines.
 *
 *  - Only G3 (the spec's main leaderboard group) completed runs are scored —
 *    the workload must be identical for scores to be comparable.
 *  - Sub-score = 1000 × (measured ÷ anchor); total = weighted geometric mean
 *    (decode 40%, prefill/response/stability 20% each). Geometric mean is
 *    scale-invariant and punishes unbalanced profiles.
 *  - TTFT/prefill use CLEAN rounds only (status == ok, no BUSY retries):
 *    on-device measurement showed AICore may evict the model while BUSY, so a
 *    retried round's TTFT measures a cold start, not responsiveness.
 *  - Raw sub-metrics all remain in the report — the spec's pure decode-median
 *    leaderboard is unaffected by this score.
 */
object ScoreCalculator {

    const val REFERENCE_ID = "gemmark-anchor-v2"

    /** Device Score composition: fast 45% · full 45% · switch-load 10%. */
    const val DEVICE_FORMULA = "device-v2"
    const val W_DEVICE_MODELS = 0.90
    const val W_DEVICE_LOAD = 0.10

    /** Anchor: swapping a multi-GB model into AICore in 5 s ≡ 1000 pts. */
    const val REF_SWITCH_LOAD_MS = 5_000.0

    /**
     * If AICore turns out to keep both models resident (switch ≈ warm attach,
     * ~200 ms), an uncapped ratio would inflate the Device Score through pure
     * IPC overhead. Cap until real-device data justifies more headroom.
     */
    const val LOAD_RATIO_CAP = 4.0

    /**
     * A displacement load reads gigabytes; anything faster than this is a
     * non-blocking warmup (observed: MediaTek AICore returns from warmup() in
     * ~35-51 ms and loads lazily on first inference) — not a measurement.
     */
    const val MIN_VALID_SWITCH_MS = 500L

    /** Load sub-score from displacement-load samples; null when none valid. */
    fun loadScore(switchLoadsMs: List<Long>): Int? {
        val valid = switchLoadsMs.filter { it >= MIN_VALID_SWITCH_MS }
        if (valid.isEmpty()) return null
        val median = Statistics.median(valid.map { it.toDouble() })
        if (median <= 0.0) return null
        val ratio = (REF_SWITCH_LOAD_MS / median).coerceAtMost(LOAD_RATIO_CAP)
        return kotlin.math.round(1000.0 * ratio).toInt()
    }

    /**
     * Device Score: weighted geometric mean of the per-model anchor-v2 totals
     * (equal weight — neither model is "the" device) and the Load sub-score.
     * A missing dimension renormalizes, mirroring the per-model score.
     */
    fun deviceScore(totals: List<Int>, loadScore: Int? = null): Int? {
        val positive = totals.filter { it > 0 }
        if (positive.isEmpty()) return null
        val terms = buildList {
            val perModel = W_DEVICE_MODELS / positive.size
            positive.forEach { add(perModel to it.toDouble()) }
            if (loadScore != null && loadScore > 0) add(W_DEVICE_LOAD to loadScore.toDouble())
        }
        val totalWeight = terms.sumOf { it.first }
        val logSum = terms.sumOf { (w, v) -> w * ln(v) }
        return kotlin.math.round(exp(logSum / totalWeight)).toInt()
    }

    // The published anchor-v2 constants (see class KDoc). Round numbers by
    // design: the anchor is a definition, not a measurement.
    // v2 adds Reasoning (thinking-mode window throughput, same physical unit
    // as decode). Weights renormalize when a dimension is unmeasured.
    private const val REF_DECODE_TPS = 10.0
    private const val REF_PREFILL_TPS = 500.0
    private const val REF_TTFT_MS = 500.0
    private const val REF_REASONING_TPS = 10.0
    private const val REF_STABILITY = 1.0

    private const val W_DECODE = 0.35
    private const val W_PREFILL = 0.15
    private const val W_RESPONSE = 0.15
    private const val W_REASONING = 0.20
    private const val W_STABILITY = 0.15

    /** Clean rounds: fully successful with no BUSY retries and a measurable decode. */
    fun cleanRounds(rounds: List<RoundResult>): List<RoundResult> =
        rounds.filter { it.status == RoundStatus.OK && it.retries == 0 }

    /**
     * Restricts to the given suite workloads; legacy runs whose rounds carry no
     * workload tag fall back to the full list.
     */
    private fun List<RoundResult>.basis(workloads: Set<String>): List<RoundResult> {
        val tagged = filter { it.workload in workloads }
        return tagged.ifEmpty { this }
    }

    /** Response basis: clean-round TTFT over the short-input workloads. */
    fun cleanTtftMedian(rounds: List<RoundResult>): Double? =
        cleanRounds(rounds.basis(StandardSuite.RESPONSE_BASIS))
            .takeIf { it.isNotEmpty() }
            ?.let { list -> Statistics.median(list.map { it.ttftMs }) }

    /** Prefill basis: the long-context workload (real 2048-token ingestion). */
    fun cleanPrefillMedian(rounds: List<RoundResult>): Double? {
        val basis = rounds.basis(setOf(StandardSuite.PREFILL_BASIS))
        val clean = cleanRounds(basis).ifEmpty { basis.filter { it.isValidForStats } }
        return clean.takeIf { it.isNotEmpty() }
            ?.let { list -> Statistics.median(list.map { it.prefillTps }) }
    }

    /**
     * Reasoning basis: thinking-mode rounds' WINDOW throughput (decode_tps
     * counts thought + answer tokens there), median over valid rounds with a
     * measurable window. Null when the dimension wasn't run.
     */
    fun reasoningTpsMedian(rounds: List<RoundResult>): Double? =
        rounds.filter {
            it.workload == StandardSuite.REASONING_BASIS && it.isValidForStats && it.decodeValid
        }
            .map { it.decodeTps }
            .takeIf { it.isNotEmpty() }
            ?.let { Statistics.median(it) }

    /**
     * Computes the score card, or null when the run is not scoreable
     * (wrong prompt group, not completed, or no usable summary).
     */
    fun compute(
        promptGroupId: Int,
        runStatus: RunStatus,
        rounds: List<RoundResult>,
        summary: SummaryStats?,
    ): ScoreCard? {
        if (promptGroupId != PromptGroup.FIXED_256.id) return null
        if (runStatus != RunStatus.COMPLETED) return null
        if (summary == null || summary.decodeTpsMedian <= 0) return null

        val clean = cleanRounds(rounds.basis(StandardSuite.RESPONSE_BASIS))
        val ttftBasisClean = clean.isNotEmpty()
        val validRounds = rounds.filter { it.isValidForStats }
        val ttftMedian = cleanTtftMedian(rounds)
            ?: Statistics.median(validRounds.map { it.ttftMs })
        val prefillMedian = cleanPrefillMedian(rounds)
            ?: Statistics.median(validRounds.map { it.prefillTps })
        if (ttftMedian <= 0 || prefillMedian <= 0) return null

        // Stability: sustained throughput (thermal drop capped at 1) × run
        // consistency (1 − coefficient of variation of decode speed).
        val sustain = summary.thermalDrop.coerceAtMost(1.0).coerceAtLeast(0.0)
        val cv = (summary.decodeTpsStdDev / summary.decodeTpsMedian).coerceIn(0.0, 1.0)
        val stabilityRaw = sustain * (1.0 - cv)

        val reasoningMedian = reasoningTpsMedian(rounds)

        val decodeRatio = summary.decodeTpsMedian / REF_DECODE_TPS
        val prefillRatio = prefillMedian / REF_PREFILL_TPS
        val responseRatio = REF_TTFT_MS / ttftMedian
        val reasoningRatio = reasoningMedian?.let { it / REF_REASONING_TPS }
        val stabilityRatio = stabilityRaw / REF_STABILITY

        if (listOfNotNull(decodeRatio, prefillRatio, responseRatio, reasoningRatio, stabilityRatio)
                .any { it <= 0 }
        ) {
            return null
        }

        // Weighted geometric mean; an unmeasured dimension drops out and the
        // remaining weights renormalize, so totals stay on the same scale.
        val terms = buildList {
            add(W_DECODE to decodeRatio)
            add(W_PREFILL to prefillRatio)
            add(W_RESPONSE to responseRatio)
            reasoningRatio?.let { add(W_REASONING to it) }
            add(W_STABILITY to stabilityRatio)
        }
        val weightSum = terms.sumOf { it.first }
        val total = 1000.0 * exp(terms.sumOf { (w, r) -> w * ln(r) } / weightSum)

        return ScoreCard(
            total = total.roundToInt(),
            decode = (1000.0 * decodeRatio).roundToInt(),
            prefill = (1000.0 * prefillRatio).roundToInt(),
            response = (1000.0 * responseRatio).roundToInt(),
            stability = (1000.0 * stabilityRatio).roundToInt(),
            reasoning = reasoningRatio?.let { (1000.0 * it).roundToInt() },
            referenceId = REFERENCE_ID,
            ttftBasis = if (ttftBasisClean) "clean" else "all_valid",
            cleanRoundCount = clean.size,
        )
    }

    /** Score for a stored report; computes on the fly for legacy files without one. */
    fun forReport(report: RunReport): ScoreCard? =
        report.score ?: compute(
            promptGroupId = report.config.promptGroup,
            runStatus = report.runStatus,
            rounds = report.rounds,
            summary = report.summary,
        )
}
