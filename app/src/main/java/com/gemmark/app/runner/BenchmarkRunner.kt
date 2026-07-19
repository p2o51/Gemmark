package com.gemmark.app.runner

import com.gemmark.app.core.TokenCounter
import com.gemmark.app.core.model.Backend
import com.gemmark.app.core.model.BenchmarkConfig
import com.gemmark.app.core.model.LogEntry
import com.gemmark.app.core.model.PreflightSnapshot
import com.gemmark.app.core.model.PromptGroup
import com.gemmark.app.core.model.RoundResult
import com.gemmark.app.core.model.RoundStatus
import com.gemmark.app.core.model.RunConfigInfo
import com.gemmark.app.core.model.RunReport
import com.gemmark.app.core.model.RunStatus
import com.gemmark.app.core.model.SummaryStats
import com.gemmark.app.core.model.WorkloadSummary
import com.gemmark.app.core.prompts.PromptRepository
import com.gemmark.app.core.suite.StandardSuite
import com.gemmark.app.core.suite.SuitePhase
import com.gemmark.app.core.stats.ScoreCalculator
import com.gemmark.app.core.stats.Statistics
import com.gemmark.app.engine.EngineErrorCode
import com.gemmark.app.engine.EngineException
import com.gemmark.app.engine.GenerationEvent
import com.gemmark.app.engine.GenerationRequest
import com.gemmark.app.engine.InferenceEngine
import com.gemmark.app.telemetry.PowerSampler
import com.gemmark.app.telemetry.TelemetrySource
import kotlin.math.ceil
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

/** Retry policy for AICore BUSY errors: exponential backoff per the design doc. */
data class BackoffPolicy(
    val baseDelayMs: Long = 1_000,
    val factor: Double = 2.0,
    val maxDelayMs: Long = 16_000,
    val maxRetries: Int = 5,
) {
    fun delayFor(attempt: Int): Long =
        minOf(maxDelayMs, (baseDelayMs * Math.pow(factor, attempt.toDouble())).toLong())
}

/** Progress callbacks the runner reports while executing. */
interface RunnerListener {
    fun onPhase(phase: RunnerPhase)

    /** A suite workload begins. [index]/[total] are 1-based phase positions. */
    fun onWorkloadStarted(phase: SuitePhase, index: Int, total: Int) {}

    /** A suite workload finished; [summaryLine] is a human-readable live result. */
    fun onWorkloadFinished(phase: SuitePhase, summaryLine: String) {}

    fun onRoundStarted(index: Int, isWarmup: Boolean)
    fun onLiveMetrics(ttftMs: Double?, decodeTps: Double?)

    /**
     * Streams what the model is doing right now: the task, a prompt snippet,
     * the reasoning trace and the answer as they grow, plus how many test
     * images are attached — the run screen renders all of it live.
     */
    fun onGenerationUpdate(
        taskDescription: String,
        promptPreview: String,
        thoughtSoFar: String,
        answerSoFar: String,
        imageCount: Int,
    ) {}

    fun onRoundFinished(result: RoundResult)
    fun onLog(entry: LogEntry)
}

enum class RunnerPhase { PREPARING, WARMUP, MEASURING, PAUSED, SAVING, DONE }

/**
 * Executes one Gemmark Standard Test:
 * model load → warm-up → the fixed workload suite (see [StandardSuite]),
 * fixed inter-round interval, BUSY exponential backoff, per-round status
 * classification, per-workload rollups, summary statistics, composite score.
 *
 * Cancellation of the calling coroutine aborts the run; the caller decides what
 * to do with partial results via [buildReport].
 */
class BenchmarkRunner(
    private val engine: InferenceEngine,
    private val telemetry: TelemetrySource,
    private val sampler: PowerSampler,
    private val tokenCounter: TokenCounter,
    private val listener: RunnerListener,
    private val backoff: BackoffPolicy = BackoffPolicy(),
    /** Pause gate: runner suspends between rounds while false. */
    private val resumeGate: StateFlow<Boolean>,
    /** Injectable for tests; production always runs the standard suite. */
    private val suite: List<SuitePhase> = StandardSuite.phases,
    private val warmupRounds: Int = StandardSuite.WARMUP_ROUNDS,
    private val roundIntervalMs: Long = StandardSuite.ROUND_INTERVAL_MS,
    /** Supplies [count] bundled test images for multimodal phases. */
    private val imageProvider: ((count: Int) -> List<android.graphics.Bitmap>)? = null,
) {

    private val warmupResults = mutableListOf<RoundResult>()
    private val measuredResults = mutableListOf<RoundResult>()
    private val logs = mutableListOf<LogEntry>()
    private var modelLoadMs: Long = 0
    private var startSnapshotLevelPct: Int? = null
    private var startChargeUah: Long? = null

    /** True once any token count came from the engine's own tokenizer. */
    private var usedNativeTokenizer = false

    /** Backend requested for this run; rounds on any other backend are `fallback`. */
    private var requestedBackend: Backend = Backend.NPU

    private suspend fun countTokens(text: String): Int {
        val native = engine.countTokens(text)
        if (native != null) {
            usedNativeTokenizer = true
            return native
        }
        return tokenCounter.count(text)
    }

    suspend fun execute(config: BenchmarkConfig): RunOutcome {
        requestedBackend = config.backend
        val startSnap = telemetry.snapshot()
        startSnapshotLevelPct = startSnap.levelPct
        startChargeUah = startSnap.chargeCounterUah

        listener.onPhase(RunnerPhase.PREPARING)
        log("Preparing engine ${engine.id} (${config.backend.label})…")
        val prepareStart = System.nanoTime()
        engine.prepare(config.backend) { progress -> log(progress) }
        modelLoadMs = (System.nanoTime() - prepareStart) / 1_000_000
        log("Model ready in ${modelLoadMs} ms.")

        listener.onPhase(RunnerPhase.WARMUP)
        val warmupGroup = PromptGroup.FIXED_256
        for (i in 1..warmupRounds) {
            awaitResume()
            listener.onRoundStarted(i, isWarmup = true)
            val result = runRound(
                warmupGroup,
                index = i,
                isWarmup = true,
                workload = "warmup",
                applyShortRule = true,
                taskDescription = "Warm-up (not measured)",
            )
            warmupResults += result
            listener.onRoundFinished(result)
            log("Warm-up $i/$warmupRounds complete.")
            delay(roundIntervalMs)
        }

        listener.onPhase(RunnerPhase.MEASURING)
        var globalIndex = 0
        suite.forEachIndexed { phaseIdx, phase ->
            listener.onWorkloadStarted(phase, phaseIdx + 1, suite.size)
            log("Phase ${phaseIdx + 1}/${suite.size} — ${phase.description}")

            val phaseStartMs = sampler.nowMs()
            val phaseRounds = mutableListOf<RoundResult>()
            var roundInPhase = 0
            while (true) {
                val elapsed = sampler.nowMs() - phaseStartMs
                val needMoreForWall = phase.minWallMs > 0 && elapsed < phase.minWallMs
                val underTarget = roundInPhase < phase.rounds
                if (!underTarget && !needMoreForWall) break
                if (roundInPhase >= phase.maxRounds) break

                awaitResume()
                roundInPhase++
                globalIndex++
                listener.onRoundStarted(globalIndex, isWarmup = false)
                val result = runRound(
                    phase.group,
                    index = globalIndex,
                    isWarmup = false,
                    workload = phase.id,
                    applyShortRule = phase.applyShortRule,
                    taskDescription = phase.description,
                    imageCount = phase.imageCount,
                    thinking = phase.thinking,
                    structured = phase.structured,
                )
                phaseRounds += result
                measuredResults += result
                listener.onRoundFinished(result)

                // Optional phase whose very first round errors (e.g. no vision
                // support on this model): skip the phase, keep the run valid.
                // A BUSY-exhausted first round is NOT lack of support — during a
                // v4-full BUSY storm (seen on both Tensor and Dimensity) it would
                // wrongly erase STRUCTURED/IMAGE from the whole run; treat it as
                // an ordinary failed round instead.
                val busyExhausted = result.errorMessage?.startsWith("BUSY after") == true
                if (phase.optional && roundInPhase == 1 && result.status == RoundStatus.ERROR && !busyExhausted) {
                    measuredResults.removeAll(phaseRounds)
                    workloadSummaries += WorkloadSummary(
                        id = phase.id,
                        label = phase.label,
                        rounds = 0,
                        validRounds = 0,
                        metricName = "unsupported",
                        metricValue = 0.0,
                        metricUnit = "",
                    )
                    log("${phase.label} not supported by this model — skipped (${result.errorMessage}).", LogEntry.Level.WARN)
                    listener.onWorkloadFinished(phase, "not supported — skipped")
                    return@forEachIndexed
                }
                when (result.status) {
                    RoundStatus.OK, RoundStatus.BUSY_RETRIED ->
                        log("Round $globalIndex (${phase.id}) complete: ${"%.1f".format(result.decodeTps)} tok/s.")
                    RoundStatus.SHORT ->
                        log("Round $globalIndex short output (${result.outputTokens} tok) — excluded from stats.", LogEntry.Level.WARN)
                    RoundStatus.FALLBACK ->
                        log("Round $globalIndex ran on fallback backend ${result.backendUsed} — reported separately.", LogEntry.Level.WARN)
                    RoundStatus.ERROR ->
                        log("Round $globalIndex failed: ${result.errorMessage}", LogEntry.Level.ERROR)
                }
                delay(roundIntervalMs)
            }

            val rollup = workloadRollup(phase, phaseRounds)
            workloadSummaries += rollup
            val line = "${rollup.metricName} ${"%.1f".format(rollup.metricValue)} ${rollup.metricUnit}" +
                (rollup.jsonValidRate?.let { " · JSON valid ${"%.0f".format(it * 100)}%" } ?: "")
            listener.onWorkloadFinished(phase, line)
            log("Phase ${phase.label} done: $line")
        }

        listener.onPhase(RunnerPhase.SAVING)
        return RunOutcome.Completed
    }

    private val workloadSummaries = mutableListOf<WorkloadSummary>()

    /** Headline metric per workload for live display and the report breakdown. */
    private fun workloadRollup(phase: SuitePhase, rounds: List<RoundResult>): WorkloadSummary {
        val valid = rounds.filter { it.isValidForStats }
        val clean = rounds.filter { it.status == RoundStatus.OK && it.retries == 0 }
        val (name, value, unit) = when {
            phase.id == "prefill" -> Triple(
                "prefill",
                Statistics.median((clean.ifEmpty { valid }).map { it.prefillTps }),
                "tok/s",
            )
            // Constrained decoding is non-streaming: e2e is the honest metric.
            phase.structured -> Triple("e2e", Statistics.median(valid.map { it.e2eTps }), "tok/s")
            else -> Triple("decode", Statistics.median(valid.filter { it.decodeValid }.map { it.decodeTps }), "tok/s")
        }
        val jsonRate = if (phase.structured && rounds.isNotEmpty()) {
            rounds.count { it.jsonValid == true }.toDouble() / rounds.size
        } else {
            null
        }
        return WorkloadSummary(
            id = phase.id,
            label = phase.label,
            rounds = rounds.size,
            validRounds = valid.size,
            metricName = name,
            metricValue = value,
            metricUnit = unit,
            jsonValidRate = jsonRate,
        )
    }

    /** Waits while paused (gate false). */
    private suspend fun awaitResume() {
        if (!resumeGate.value) {
            listener.onPhase(RunnerPhase.PAUSED)
            resumeGate.first { it }
            listener.onPhase(RunnerPhase.MEASURING)
        }
    }

    private suspend fun runRound(
        group: PromptGroup,
        index: Int,
        isWarmup: Boolean,
        workload: String,
        applyShortRule: Boolean,
        taskDescription: String,
        imageCount: Int = 0,
        thinking: Boolean = false,
        structured: Boolean = false,
    ): RoundResult {
        val prompt = PromptRepository.promptForRound(group, index - 1)
            ?: return RoundResult(
                i = index, status = RoundStatus.ERROR, isWarmup = isWarmup, workload = workload,
                errorMessage = "Prompt group ${group.id} has no prompts yet",
            )

        val images = if (imageCount > 0) {
            val provided = imageProvider?.invoke(imageCount).orEmpty()
            if (provided.size < imageCount) {
                return RoundResult(
                    i = index, status = RoundStatus.ERROR, isWarmup = isWarmup, workload = workload,
                    errorMessage = "Test images unavailable for the multimodal workload",
                )
            }
            provided
        } else {
            emptyList()
        }

        val inputTokens = countTokens(prompt.text)
        val request = GenerationRequest(
            prompt = prompt.text,
            maxOutputTokens = group.maxOutputTokens,
            images = images,
            enableThinking = thinking,
        )

        if (structured) {
            return runStructuredRound(request, index, isWarmup, workload, taskDescription)
        }

        val roundStartMs = sampler.nowMs()
        val tempStart = telemetry.snapshot().tempC

        var retries = 0
        var emptyRetries = 0
        var lastError: EngineException? = null
        val promptPreview = prompt.text.take(140)

        while (retries <= backoff.maxRetries) {
            try {
                val outcome = collectGeneration(request) { thoughtSoFar, answerSoFar ->
                    listener.onGenerationUpdate(
                        taskDescription, promptPreview, thoughtSoFar, answerSoFar, imageCount,
                    )
                }
                val tempEnd = telemetry.snapshot().tempC
                val roundEndMs = sampler.nowMs()

                val answerTokens = countTokens(outcome.fullText)
                val thoughtTokens = if (outcome.thoughtText.isNotEmpty()) countTokens(outcome.thoughtText) else 0
                // Window throughput counts EVERYTHING the model produced
                // (thought + answer) — content-length ratios between the two
                // must not distort the hardware rate.
                val outputTokens = answerTokens
                val windowTokens = answerTokens + thoughtTokens
                val ttftMs = outcome.firstChunkNanos / 1e6
                val decodeSeconds = (outcome.lastChunkNanos - outcome.firstChunkNanos) / 1e9
                // Single-chunk outputs have no decode window: the rate is unmeasurable,
                // not zero — flag it so aggregates skip this round's decode_tps.
                val decodeMeasurable = windowTokens > 1 && decodeSeconds > 0
                val decodeTps = if (decodeMeasurable) (windowTokens - 1) / decodeSeconds else 0.0
                val totalSeconds = outcome.doneNanos / 1e9
                val e2eTps = if (totalSeconds > 0) outputTokens / totalSeconds else 0.0
                val prefillTps = if (ttftMs > 0) inputTokens / (ttftMs / 1000.0) else 0.0

                val jsonValid = if (prompt.validateJson) isValidJson(outcome.fullText) else null

                val fallback = outcome.backendUsed != requestedBackend
                val short = applyShortRule && outputTokens < group.maxOutputTokens *
                    BenchmarkConfig.SHORT_OUTPUT_THRESHOLD

                val status = when {
                    fallback -> RoundStatus.FALLBACK
                    short -> RoundStatus.SHORT
                    retries > 0 -> RoundStatus.BUSY_RETRIED
                    else -> RoundStatus.OK
                }

                return RoundResult(
                    i = index,
                    ttftMs = ttftMs,
                    decodeTps = decodeTps,
                    e2eTps = e2eTps,
                    outputTokens = outputTokens,
                    tempStartC = tempStart,
                    tempEndC = tempEnd,
                    thermalStatus = telemetry.thermalStatusName(),
                    avgCurrentMa = sampler.averageCurrentMa(roundStartMs, roundEndMs),
                    status = status,
                    retries = retries,
                    prefillTps = prefillTps,
                    decodeValid = decodeMeasurable,
                    totalMs = outcome.doneNanos / 1e6,
                    avgPowerW = sampler.averagePowerW(roundStartMs, roundEndMs),
                    backendUsed = outcome.backendUsed.name.lowercase(),
                    jsonValid = jsonValid,
                    isWarmup = isWarmup,
                    workload = workload,
                    thoughtTokens = thoughtTokens,
                    timeToAnswerMs = if (outcome.firstAnswerNanos > 0) outcome.firstAnswerNanos / 1e6 else 0.0,
                )
            } catch (e: EngineException) {
                when (e.code) {
                    EngineErrorCode.BUSY -> {
                        lastError = e
                        if (retries >= backoff.maxRetries) break
                        // Prefer the runtime's suggested delay — but once it has
                        // failed twice in a row it is demonstrably too short
                        // (MediaTek AICore hints 0.5 s throughout a BUSY storm),
                        // so from then on wait at least the exponential backoff.
                        val hint = e.retryDelayMs?.coerceIn(500, 20_000)
                        val wait = when {
                            hint == null -> backoff.delayFor(retries)
                            retries >= 2 -> maxOf(hint, backoff.delayFor(retries))
                            else -> hint
                        }
                        retries++
                        val source = when {
                            hint == null -> "exponential backoff"
                            retries - 1 >= 2 && wait > hint -> "hint overridden by backoff"
                            else -> "runtime hint"
                        }
                        log("AICore busy — retry $retries in ${wait / 1000.0}s ($source).", LogEntry.Level.WARN)
                        delay(wait)
                    }
                    EngineErrorCode.BATTERY_QUOTA_EXCEEDED -> throw QuotaExceededException(e)
                    else -> {
                        // Observed on-device (thinking phase): AICore occasionally
                        // returns an empty response. Retry once — the retried
                        // attempt is classed busy_retried, keeping it out of the
                        // clean-round TTFT basis.
                        val message = e.message.orEmpty()
                        val transientEmpty = emptyRetries == 0 &&
                            (message.contains("empty", ignoreCase = true) ||
                                message.contains("no output", ignoreCase = true))
                        if (transientEmpty) {
                            emptyRetries++
                            retries++
                            log("Empty response from AICore — retrying once.", LogEntry.Level.WARN)
                            delay(2_000)
                        } else {
                            return RoundResult(
                                i = index,
                                tempStartC = tempStart,
                                tempEndC = telemetry.snapshot().tempC,
                                thermalStatus = telemetry.thermalStatusName(),
                                status = RoundStatus.ERROR,
                                retries = retries,
                                errorMessage = e.message ?: e.code.name,
                                isWarmup = isWarmup,
                                workload = workload,
                            )
                        }
                    }
                }
            }
        }

        return RoundResult(
            i = index,
            tempStartC = tempStart,
            tempEndC = telemetry.snapshot().tempC,
            thermalStatus = telemetry.thermalStatusName(),
            status = RoundStatus.ERROR,
            retries = retries,
            errorMessage = "BUSY after ${backoff.maxRetries} retries: ${lastError?.message}",
            isWarmup = isWarmup,
            workload = workload,
        )
    }

    private class GenerationOutcome(
        val fullText: String,
        val thoughtText: String,
        val firstChunkNanos: Long,
        /** First non-thought chunk (answer starts); -1 when no thinking involved. */
        val firstAnswerNanos: Long,
        val lastChunkNanos: Long,
        val doneNanos: Long,
        val backendUsed: Backend,
    )

    private suspend fun collectGeneration(
        request: GenerationRequest,
        onOutput: (thought: String, answer: String) -> Unit = { _, _ -> },
    ): GenerationOutcome {
        var firstChunkNanos = -1L
        var firstAnswerNanos = -1L
        var lastChunkNanos = -1L
        var doneNanos = 0L
        var fullText = ""
        var backendUsed = Backend.NPU
        val answer = StringBuilder()
        val thought = StringBuilder()
        fun tail(sb: StringBuilder, limit: Int) =
            if (sb.length > limit) sb.substring(sb.length - limit) else sb.toString()

        engine.generate(request).collect { event ->
            when (event) {
                is GenerationEvent.Chunk -> {
                    if (event.text.isNotBlank()) {
                        // TTFT = first produced token of ANY kind (thought included):
                        // that is when the model demonstrably started producing.
                        if (firstChunkNanos < 0) {
                            firstChunkNanos = event.elapsedNanos
                            listener.onLiveMetrics(ttftMs = event.elapsedNanos / 1e6, decodeTps = null)
                        }
                        if (!event.isThought && firstAnswerNanos < 0) {
                            firstAnswerNanos = event.elapsedNanos
                        }
                        lastChunkNanos = event.elapsedNanos
                    }
                    if (event.isThought) thought.append(event.text) else answer.append(event.text)
                    // Keep the UI payload bounded; the tail is what matters live.
                    onOutput(tail(thought, 900), tail(answer, 1200))
                }
                is GenerationEvent.Done -> {
                    fullText = event.fullText
                    doneNanos = event.elapsedNanos
                    backendUsed = event.backendUsed
                    val tokens = tokenCounter.count(event.fullText) + tokenCounter.count(thought.toString())
                    val decodeSeconds = (lastChunkNanos - firstChunkNanos) / 1e9
                    val tps = if (tokens > 1 && decodeSeconds > 0) (tokens - 1) / decodeSeconds else null
                    listener.onLiveMetrics(ttftMs = firstChunkNanos / 1e6, decodeTps = tps)
                }
            }
        }
        if (firstChunkNanos < 0) {
            throw EngineException(EngineErrorCode.UNKNOWN, "Generation produced no output")
        }
        return GenerationOutcome(
            fullText, thought.toString(), firstChunkNanos,
            if (request.enableThinking) firstAnswerNanos else -1L,
            lastChunkNanos, doneNanos, backendUsed,
        )
    }

    /** Constrained-decoding round: non-streaming, e2e throughput only. */
    private suspend fun runStructuredRound(
        request: GenerationRequest,
        index: Int,
        isWarmup: Boolean,
        workload: String,
        taskDescription: String,
    ): RoundResult {
        val roundStartMs = sampler.nowMs()
        val tempStart = telemetry.snapshot().tempC
        var retries = 0

        while (true) {
            try {
                listener.onGenerationUpdate(taskDescription, request.prompt.take(140), "", "", 0)
                val result = engine.generateStructured(request)
                    ?: return RoundResult(
                        i = index, status = RoundStatus.ERROR, isWarmup = isWarmup, workload = workload,
                        tempStartC = tempStart, tempEndC = telemetry.snapshot().tempC,
                        errorMessage = "Structured output not supported by this model",
                    )
                val roundEndMs = sampler.nowMs()
                val outputTokens = countTokens(result.outputText)
                val totalSeconds = result.totalNanos / 1e9
                listener.onGenerationUpdate(taskDescription, request.prompt.take(140), "", result.outputText.take(1200), 0)
                return RoundResult(
                    i = index,
                    // Non-streaming API: no TTFT/decode window exists.
                    ttftMs = 0.0,
                    decodeTps = 0.0,
                    decodeValid = false,
                    e2eTps = if (totalSeconds > 0 && result.success) outputTokens / totalSeconds else 0.0,
                    outputTokens = outputTokens,
                    tempStartC = tempStart,
                    tempEndC = telemetry.snapshot().tempC,
                    thermalStatus = telemetry.thermalStatusName(),
                    avgCurrentMa = sampler.averageCurrentMa(roundStartMs, roundEndMs),
                    avgPowerW = sampler.averagePowerW(roundStartMs, roundEndMs),
                    totalMs = result.totalNanos / 1e6,
                    status = when {
                        !result.success -> RoundStatus.ERROR
                        retries > 0 -> RoundStatus.BUSY_RETRIED
                        else -> RoundStatus.OK
                    },
                    retries = retries,
                    jsonValid = result.success,
                    backendUsed = requestedBackend.name.lowercase(),
                    isWarmup = isWarmup,
                    workload = workload,
                )
            } catch (e: EngineException) {
                when (e.code) {
                    EngineErrorCode.BUSY -> {
                        if (retries >= backoff.maxRetries) {
                            return RoundResult(
                                i = index, status = RoundStatus.ERROR, isWarmup = isWarmup,
                                workload = workload, retries = retries,
                                tempStartC = tempStart, tempEndC = telemetry.snapshot().tempC,
                                errorMessage = "BUSY after ${backoff.maxRetries} retries: ${e.message}",
                            )
                        }
                        val wait = e.retryDelayMs?.coerceIn(500, 20_000) ?: backoff.delayFor(retries)
                        retries++
                        log("AICore busy — retry $retries in ${wait / 1000.0}s.", LogEntry.Level.WARN)
                        delay(wait)
                    }
                    EngineErrorCode.BATTERY_QUOTA_EXCEEDED -> throw QuotaExceededException(e)
                    else -> return RoundResult(
                        i = index, status = RoundStatus.ERROR, isWarmup = isWarmup, workload = workload,
                        retries = retries, tempStartC = tempStart,
                        tempEndC = telemetry.snapshot().tempC,
                        errorMessage = e.message ?: e.code.name,
                    )
                }
            }
        }
    }

    private fun isValidJson(text: String): Boolean = try {
        // Models often wrap JSON in code fences despite instructions; strip before validating.
        val cleaned = text.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()
        Json.parseToJsonElement(cleaned)
        true
    } catch (_: Exception) {
        false
    }

    fun buildReport(
        runId: String,
        timestamp: String,
        config: BenchmarkConfig,
        deviceInfo: com.gemmark.app.core.model.DeviceInfo,
        appVersion: String,
        preflight: PreflightSnapshot?,
        aborted: Boolean,
        failureMessage: String? = null,
    ): RunReport {
        val validRounds = measuredResults.filter { it.isValidForStats }
        // Decode/stability series: the 256/256 workloads only (main + sustained),
        // chronological, skipping degenerate single-chunk rounds. Legacy runs
        // (untagged rounds) fall back to all valid rounds.
        val decodeBasis = measuredResults
            .filter { it.workload in StandardSuite.DECODE_BASIS }
            .ifEmpty { measuredResults }
        val decode = decodeBasis.filter { it.isValidForStats && it.decodeValid }.map { it.decodeTps }

        val endSnap = telemetry.snapshot()
        val startLevel = startSnapshotLevelPct
        val endLevel = endSnap.levelPct
        val startCharge = startChargeUah
        val endCharge = endSnap.chargeCounterUah
        val summary = if (validRounds.isNotEmpty()) {
            SummaryStats(
                validRounds = validRounds.size,
                decodeTpsMedian = Statistics.median(decode),
                decodeTpsTrimmedMean = Statistics.trimmedMean(decode),
                decodeTpsStdDev = Statistics.stdDev(decode),
                decodeTpsP10 = Statistics.percentile(decode, 10.0),
                decodeTpsP90 = Statistics.percentile(decode, 90.0),
                decodeTpsMin = decode.minOrNull() ?: 0.0,
                ttftMsMedian = Statistics.median(validRounds.map { it.ttftMs }),
                e2eTpsMedian = Statistics.median(validRounds.map { it.e2eTps }),
                prefillTpsMedian = Statistics.median(validRounds.map { it.prefillTps }),
                thermalDrop = Statistics.thermalDrop(decode),
                tempPeakC = measuredResults.maxOfOrNull { maxOf(it.tempStartC, it.tempEndC) } ?: 0.0,
                batteryDropPct = if (startLevel != null && endLevel != null) {
                    (startLevel - endLevel).toDouble()
                } else {
                    null
                },
                chargeUsedMah = if (startCharge != null && endCharge != null) {
                    (startCharge - endCharge) / 1000.0
                } else {
                    null
                },
                ttftMsMedianClean = ScoreCalculator.cleanTtftMedian(measuredResults),
                prefillTpsMedianClean = ScoreCalculator.cleanPrefillMedian(measuredResults),
                cleanRounds = ScoreCalculator.cleanRounds(measuredResults).size,
                reasoningTpsMedian = ScoreCalculator.reasoningTpsMedian(measuredResults),
                timeToAnswerMsMedian = measuredResults
                    .filter { it.workload == StandardSuite.REASONING_BASIS && it.isValidForStats && it.timeToAnswerMs > 0 }
                    .map { it.timeToAnswerMs }
                    .takeIf { it.isNotEmpty() }
                    ?.let { Statistics.median(it) },
            )
        } else {
            null
        }

        val requiredValid = requiredValidRounds(measuredResults.size)
        val runStatus = when {
            failureMessage != null -> RunStatus.FAILED
            aborted -> RunStatus.ABORTED
            validRounds.size < requiredValid -> RunStatus.NEEDS_RETEST
            else -> RunStatus.COMPLETED
        }
        if (runStatus == RunStatus.NEEDS_RETEST) {
            log(
                "Only ${validRounds.size}/$requiredValid required valid rounds — spec requires a retest.",
                LogEntry.Level.WARN,
            )
        }
        failureMessage?.let { log(it, LogEntry.Level.ERROR) }

        val score = ScoreCalculator.compute(
            promptGroupId = config.promptGroup.id,
            runStatus = runStatus,
            rounds = measuredResults,
            summary = summary,
        )

        return RunReport(
            runId = runId,
            timestamp = timestamp,
            device = deviceInfo,
            model = engine.modelInfo.copy(backend = config.backend.name.lowercase()),
            config = RunConfigInfo(
                // Main-workload lineage for legacy tooling; the suite itself is
                // described by `mode` + the workload_summary block.
                promptGroup = PromptGroup.FIXED_256.id,
                promptVersion = PromptRepository.PROMPT_VERSION,
                inputTokens = PromptGroup.FIXED_256.approxInputTokens,
                maxOutputTokens = PromptGroup.FIXED_256.maxOutputTokens,
                mode = StandardSuite.VERSION,
                warmupRounds = warmupRounds,
                measuredRounds = measuredResults.size,
                roundIntervalMs = roundIntervalMs,
                engineId = engine.id,
                requestedBackend = config.backend,
                tokenCounter = if (usedNativeTokenizer) "${engine.id}:native" else tokenCounter.id,
            ),
            rounds = measuredResults.toList(),
            appVersion = appVersion,
            runStatus = runStatus,
            summary = summary,
            score = score,
            workloadSummary = workloadSummaries.toList(),
            warmupRounds = warmupResults.toList(),
            telemetry = sampler.samples.value,
            preflight = preflight,
            log = logs.toList(),
            modelLoadMs = modelLoadMs,
        )
    }

    private fun log(message: String, level: LogEntry.Level = LogEntry.Level.INFO) {
        val entry = LogEntry(tMs = sampler.nowMs(), level = level, message = message)
        logs += entry
        listener.onLog(entry)
    }

    companion object {
        /** A run needs ≥ 80 % valid rounds for its statistics to stand. */
        fun requiredValidRounds(measuredRounds: Int): Int =
            ceil(measuredRounds * 0.8).toInt()
    }
}

sealed interface RunOutcome {
    data object Completed : RunOutcome
}

/** Raised when AICore reports PER_APP_BATTERY_USE_QUOTA_EXCEEDED; aborts the run. */
class QuotaExceededException(cause: EngineException) :
    Exception("Battery usage quota exceeded — AICore refuses further inference", cause)
