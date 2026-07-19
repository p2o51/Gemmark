package com.gemmark.app.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Data model for a benchmark run.
 *
 * Field names follow the JSON schema frozen in "Gemmark 测试规范 v1" (Notion).
 * Fields beyond the v1 schema are additive extensions; the v1 fields must never
 * be renamed so exported files stay comparable across app versions.
 */

/** Inference backend requested for a run. */
@Serializable
enum class Backend(val label: String) {
    @SerialName("npu") NPU("NPU (AICore)"),
    @SerialName("gpu") GPU("GPU"),
    @SerialName("cpu") CPU("CPU"),
}

/** Per-round outcome, as defined by the v1 spec's 判定规则. */
@Serializable
enum class RoundStatus(val csvValue: String) {
    /** Clean success. */
    @SerialName("ok") OK("ok"),

    /** Succeeded after one or more BUSY retries; still a valid round. */
    @SerialName("busy_retried") BUSY_RETRIED("busy_retried"),

    /** Output tokens < 80% of target; excluded from leaderboard stats. */
    @SerialName("short") SHORT("short"),

    /** Executed on a fallback backend (e.g. CPU); kept but reported separately. */
    @SerialName("fallback") FALLBACK("fallback"),

    /** Failed; round is dropped entirely. */
    @SerialName("error") ERROR("error"),
}

/** Whole-run status. */
@Serializable
enum class RunStatus {
    /** Completed with >= MIN_VALID_ROUNDS valid rounds. */
    @SerialName("completed") COMPLETED,

    /** Completed but with < MIN_VALID_ROUNDS valid rounds — the spec requires a retest. */
    @SerialName("needs_retest") NEEDS_RETEST,

    /** Stopped by the user before all rounds finished. */
    @SerialName("aborted") ABORTED,

    /** Aborted by the runner (e.g. battery quota exceeded). */
    @SerialName("failed") FAILED,
}

/** Prompt groups from the v1 spec (Prompt 集与 token 口径). */
@Serializable
enum class PromptGroup(
    val id: Int,
    val title: String,
    val purpose: String,
    val approxInputTokens: Int,
    val maxOutputTokens: Int,
    val note: String,
    val implemented: Boolean = true,
) {
    @SerialName("1")
    SHORT_IN_LONG_OUT(1, "Short in / Long out", "Decode", 32, 512, "Open-ended writing instruction"),

    @SerialName("2")
    LONG_IN_SHORT_OUT(2, "Long in / Short out", "Prefill", 2048, 32, "Document + one-line summary"),

    @SerialName("3")
    FIXED_256(3, "Fixed 256 / 256", "Main leaderboard", 256, 256, "All leaderboard numbers come from this group"),

    @SerialName("4")
    JSON_TASK(4, "JSON extraction", "Structured output", 128, 128, "Schema extraction; output validated for legality"),

    @SerialName("5")
    TRILINGUAL(5, "ZH / EN / JA", "Multilingual", 64, 128, "Same instruction in three languages, round-robin"),

    @SerialName("6")
    IMAGE(6, "Image understanding", "Multimodal", 16, 128, "Fixed 512×512 image + short question"),

    @SerialName("7")
    THINKING(7, "Multi-step reasoning", "Thinking mode (Nano v4+)", 64, 512, "Deterministic logic puzzles, enableThinking on"),

    @SerialName("8")
    COMPARE(8, "Image comparison", "Multimodal, two images", 16, 128, "Two fixed 512×512 scenes, spot the differences"),
}

/** Device block of the v1 schema. */
@Serializable
data class DeviceInfo(
    val model: String,
    val build: String,
    @SerialName("aicore_version") val aicoreVersion: String,
    // extensions
    @SerialName("manufacturer") val manufacturer: String = "",
    @SerialName("soc") val soc: String = "",
    @SerialName("android_sdk") val androidSdk: Int = 0,
)

/** Model block of the v1 schema. */
@Serializable
data class ModelInfo(
    val name: String,
    @SerialName("base_model_name") val baseModelName: String = "",
    @SerialName("release_track") val releaseTrack: String = "",
    val quant: String = "",
    val backend: String = "",
)

/** Config block of the v1 schema. */
@Serializable
data class RunConfigInfo(
    @SerialName("prompt_group") val promptGroup: Int,
    @SerialName("prompt_version") val promptVersion: String = "v1",
    @SerialName("input_tokens") val inputTokens: Int,
    @SerialName("max_output_tokens") val maxOutputTokens: Int,
    val sampling: String = "greedy",
    // extensions
    /** "standard-v1" for suite runs; empty for legacy single-group runs. */
    val mode: String = "",
    @SerialName("warmup_rounds") val warmupRounds: Int = 3,
    @SerialName("measured_rounds") val measuredRounds: Int = 15,
    @SerialName("round_interval_ms") val roundIntervalMs: Long = 3_000,
    @SerialName("engine_id") val engineId: String = "",
    @SerialName("requested_backend") val requestedBackend: Backend = Backend.NPU,
    @SerialName("token_counter") val tokenCounter: String = "",
)

/** One measured round, matching the v1 `rounds[]` entry. */
@Serializable
data class RoundResult(
    val i: Int,
    @SerialName("ttft_ms") val ttftMs: Double = 0.0,
    @SerialName("decode_tps") val decodeTps: Double = 0.0,
    @SerialName("e2e_tps") val e2eTps: Double = 0.0,
    @SerialName("output_tokens") val outputTokens: Int = 0,
    @SerialName("temp_start_c") val tempStartC: Double = 0.0,
    @SerialName("temp_end_c") val tempEndC: Double = 0.0,
    @SerialName("thermal_status") val thermalStatus: String = "",
    @SerialName("avg_current_ma") val avgCurrentMa: Double = 0.0,
    val status: RoundStatus = RoundStatus.OK,
    val retries: Int = 0,
    // extensions
    @SerialName("prefill_tps") val prefillTps: Double = 0.0,
    /**
     * False when the whole output arrived in one chunk (decode window = 0):
     * decode_tps is then meaningless and excluded from decode aggregates,
     * while TTFT/e2e stay valid and the round still counts toward validity.
     */
    @SerialName("decode_valid") val decodeValid: Boolean = true,
    @SerialName("total_ms") val totalMs: Double = 0.0,
    @SerialName("avg_power_w") val avgPowerW: Double = 0.0,
    @SerialName("backend_used") val backendUsed: String = "",
    @SerialName("json_valid") val jsonValid: Boolean? = null,
    @SerialName("error_message") val errorMessage: String = "",
    @SerialName("is_warmup") val isWarmup: Boolean = false,
    /** Standard-suite workload this round belongs to (prefill/decode/main/…). */
    val workload: String = "",
    /**
     * Thinking-mode extras. For THINKING rounds, decode_tps is the WINDOW
     * throughput over thought+answer tokens (content-length independent);
     * these fields expose the split for analysis only and never feed scores.
     */
    @SerialName("thought_tokens") val thoughtTokens: Int = 0,
    @SerialName("time_to_answer_ms") val timeToAnswerMs: Double = 0.0,
) {
    /** Valid rounds per spec: ok and busy_retried count toward leaderboard stats. */
    val isValidForStats: Boolean
        get() = status == RoundStatus.OK || status == RoundStatus.BUSY_RETRIED
}

/** Per-workload rollup for the standard suite (extension block in the report). */
@Serializable
data class WorkloadSummary(
    val id: String,
    val label: String,
    val rounds: Int,
    @SerialName("valid_rounds") val validRounds: Int,
    /** Headline metric for this workload (meaning depends on the workload). */
    @SerialName("metric_name") val metricName: String,
    @SerialName("metric_value") val metricValue: Double,
    @SerialName("metric_unit") val metricUnit: String,
    @SerialName("json_valid_rate") val jsonValidRate: Double? = null,
)

/** Aggregates computed from the valid rounds only. */
@Serializable
data class SummaryStats(
    @SerialName("valid_rounds") val validRounds: Int,
    @SerialName("decode_tps_median") val decodeTpsMedian: Double,
    @SerialName("decode_tps_trimmed_mean") val decodeTpsTrimmedMean: Double,
    @SerialName("decode_tps_stddev") val decodeTpsStdDev: Double,
    @SerialName("decode_tps_p10") val decodeTpsP10: Double,
    @SerialName("decode_tps_p90") val decodeTpsP90: Double,
    @SerialName("decode_tps_min") val decodeTpsMin: Double,
    @SerialName("ttft_ms_median") val ttftMsMedian: Double,
    @SerialName("e2e_tps_median") val e2eTpsMedian: Double,
    @SerialName("prefill_tps_median") val prefillTpsMedian: Double,
    /** median(last 5 rounds) / median(first 3 rounds); < 1.0 means decay. */
    @SerialName("thermal_drop") val thermalDrop: Double,
    @SerialName("temp_peak_c") val tempPeakC: Double,
    /** Null when the platform exposes no usable battery level / charge counter. */
    @SerialName("battery_drop_pct") val batteryDropPct: Double? = null,
    @SerialName("charge_used_mah") val chargeUsedMah: Double? = null,
    /**
     * TTFT/prefill medians over CLEAN rounds only (ok, zero retries).
     * BUSY-retried rounds can measure a cold restart, not responsiveness —
     * observed on-device with nano-v4-full. Null when no clean rounds exist.
     */
    @SerialName("ttft_ms_median_clean") val ttftMsMedianClean: Double? = null,
    @SerialName("prefill_tps_median_clean") val prefillTpsMedianClean: Double? = null,
    @SerialName("clean_rounds") val cleanRounds: Int = 0,
    /** Thinking-mode window throughput median (thought+answer tokens); null when unmeasured. */
    @SerialName("reasoning_tps_median") val reasoningTpsMedian: Double? = null,
    @SerialName("time_to_answer_ms_median") val timeToAnswerMsMedian: Double? = null,
)

/**
 * Gemmark Score: composite device-AI score for G3 runs. 1000 = the frozen v1
 * reference (nano-v3 · Pixel 10 Pro). See ScoreCalculator for the formula.
 */
@Serializable
data class ScoreCard(
    val total: Int,
    val decode: Int,
    val prefill: Int,
    val response: Int,
    val stability: Int,
    /** Thinking-mode throughput sub-score; null when the dimension was not measured. */
    val reasoning: Int? = null,
    @SerialName("reference_id") val referenceId: String,
    /** "clean" when TTFT/prefill came from retry-free rounds, else "all_valid". */
    @SerialName("ttft_basis") val ttftBasis: String,
    @SerialName("clean_round_count") val cleanRoundCount: Int,
)

/** 1 Hz telemetry sample captured during the run (extension). */
@Serializable
data class TelemetrySample(
    @SerialName("t_ms") val tMs: Long,
    @SerialName("temp_c") val tempC: Double,
    @SerialName("power_w") val powerW: Double,
    @SerialName("current_ma") val currentMa: Double,
    @SerialName("thermal_status") val thermalStatus: String,
)

@Serializable
data class LogEntry(
    @SerialName("t_ms") val tMs: Long,
    val level: Level,
    val message: String,
) {
    @Serializable
    enum class Level {
        @SerialName("info") INFO,
        @SerialName("warn") WARN,
        @SerialName("error") ERROR,
    }
}

/** Preflight condition snapshot recorded with the run (固定条件 traceability). */
@Serializable
data class PreflightSnapshot(
    @SerialName("battery_pct") val batteryPct: Int,
    @SerialName("charging") val charging: Boolean,
    @SerialName("thermal_status") val thermalStatus: String,
    @SerialName("power_save") val powerSave: Boolean,
    @SerialName("passed") val passed: Boolean,
)

/** The exported document: v1 schema fields + additive extensions. */
@Serializable
data class RunReport(
    @SerialName("run_id") val runId: String,
    val timestamp: String,
    val device: DeviceInfo,
    val model: ModelInfo,
    val config: RunConfigInfo,
    val rounds: List<RoundResult>,
    // extensions
    @SerialName("schema_version") val schemaVersion: String = "v1",
    @SerialName("app_version") val appVersion: String = "",
    @SerialName("run_status") val runStatus: RunStatus = RunStatus.COMPLETED,
    val summary: SummaryStats? = null,
    /** Present on scoreable (standard suite / legacy G3, completed) runs. */
    val score: ScoreCard? = null,
    /** Per-workload rollups for standard-suite runs. */
    @SerialName("workload_summary") val workloadSummary: List<WorkloadSummary> = emptyList(),
    @SerialName("warmup_rounds") val warmupRounds: List<RoundResult> = emptyList(),
    val telemetry: List<TelemetrySample> = emptyList(),
    val preflight: PreflightSnapshot? = null,
    val log: List<LogEntry> = emptyList(),
    @SerialName("model_load_ms") val modelLoadMs: Long = 0,
) {
    /** Short human-facing id, e.g. GMK-992A. */
    val reportCode: String
        get() = "GMK-" + runId.takeLast(4).uppercase()
}

/**
 * The device-level result: one tap runs BOTH Gemma-4 targets (Fast, then
 * Full); the Device Score is the geometric mean of the two anchor-v2 totals.
 * Sub-runs stay stored as ordinary RunReports and are referenced by id.
 */
@Serializable
data class DeviceRun(
    @SerialName("device_run_id") val deviceRunId: String,
    val timestamp: String,
    val device: DeviceInfo,
    /** Geometric mean of the two model totals; null if either run is unscored. */
    @SerialName("device_score") val deviceScore: Int? = null,
    @SerialName("fast_run_id") val fastRunId: String? = null,
    @SerialName("fast_score") val fastScore: Int? = null,
    @SerialName("fast_model") val fastModel: String = "",
    @SerialName("full_run_id") val fullRunId: String? = null,
    @SerialName("full_score") val fullScore: Int? = null,
    @SerialName("full_model") val fullModel: String = "",
    @SerialName("suite_version") val suiteVersion: String = "",
    @SerialName("anchor") val anchor: String = "",
    /** "complete" when both models ran; "partial" when only one was available. */
    val coverage: String = "complete",
    /**
     * Model-switch load times (ms) from the SWITCH LOAD phase: each load happens
     * right after the OTHER multi-GB model occupied AICore, so it is a
     * displacement load — the closest a third-party app can get to a cold load
     * (AICore exposes no eviction API; verified against genai-prompt beta3).
     */
    @SerialName("switch_loads_ms") val switchLoadsMs: List<Long> = emptyList(),
    /** 1000 × anchor ÷ median switch load; null when the phase didn't run. */
    @SerialName("load_score") val loadScore: Int? = null,
    /** Device Score composition version (weights + inputs). */
    @SerialName("score_formula") val scoreFormula: String = "",
) {
    val reportCode: String
        get() = "GMK-" + deviceRunId.takeLast(4).uppercase()
}

/**
 * The single-file export of a dual-model session: the composite verdict plus
 * BOTH full sub-reports. One Standard Test → one document.
 */
@Serializable
data class DeviceRunExport(
    @SerialName("device_run") val deviceRun: DeviceRun,
    @SerialName("fast_report") val fastReport: RunReport? = null,
    @SerialName("full_report") val fullReport: RunReport? = null,
)

/** User-facing benchmark configuration assembled on the setup screen. */
@Serializable
data class BenchmarkConfig(
    val engineId: String,
    /**
     * Standard Test model sequence. The device benchmark runs every engine in
     * order (Fast, then Full) inside one session; empty = legacy single-engine
     * run of [engineId] only.
     */
    val engineIds: List<String> = emptyList(),
    val backend: Backend = Backend.NPU,
    val promptGroup: PromptGroup = PromptGroup.FIXED_256,
    val warmupRounds: Int = DEFAULT_WARMUP_ROUNDS,
    val measuredRounds: Int = DEFAULT_MEASURED_ROUNDS,
    val roundIntervalMs: Long = DEFAULT_ROUND_INTERVAL_MS,
) {
    companion object {
        const val DEFAULT_WARMUP_ROUNDS = 3
        const val DEFAULT_MEASURED_ROUNDS = 15
        const val DEFAULT_ROUND_INTERVAL_MS = 3_000L

        /** Spec: statistics require at least 12 valid rounds. */
        const val MIN_VALID_ROUNDS = 12

        /** Spec: output below 80% of target marks the round `short`. */
        const val SHORT_OUTPUT_THRESHOLD = 0.8
    }
}
