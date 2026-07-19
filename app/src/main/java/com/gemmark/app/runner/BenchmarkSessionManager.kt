package com.gemmark.app.runner

import com.gemmark.app.core.TokenCounter
import com.gemmark.app.core.model.BenchmarkConfig
import com.gemmark.app.core.model.DeviceRun
import com.gemmark.app.core.model.LogEntry
import com.gemmark.app.core.model.RoundResult
import com.gemmark.app.core.model.RunReport
import com.gemmark.app.core.stats.ScoreCalculator
import com.gemmark.app.core.suite.StandardSuite
import com.gemmark.app.core.suite.SuitePhase
import com.gemmark.app.data.RunRepository
import com.gemmark.app.engine.EngineRegistry
import com.gemmark.app.telemetry.PowerSampler
import com.gemmark.app.telemetry.PreflightChecker
import com.gemmark.app.telemetry.TelemetryMonitor
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** UI-facing snapshot of the active (or last) run. */
data class SessionState(
    val phase: Phase = Phase.IDLE,
    val config: BenchmarkConfig? = null,
    val engineName: String = "",
    /** 1-based model position within the Standard Test sequence (Fast=1, Full=2). */
    val modelIndex: Int = 0,
    val modelCount: Int = 0,
    /** 1-based index of the round in progress within its stage. */
    val currentRound: Int = 0,
    val isWarmupStage: Boolean = false,
    /** 0..1 across ALL models' warmup + measured rounds. */
    val overallProgress: Float = 0f,
    /** Standard-suite phase info ("PREFILL", 1/5) while measuring. */
    val phaseLabel: String = "",
    val phaseIndex: Int = 0,
    val phaseCount: Int = 0,
    /** Live per-phase results, e.g. "PREFILL — prefill 1291.0 tok/s". */
    val liveResults: List<String> = emptyList(),
    /** What the model is doing right now (task, prompt snippet, streaming output). */
    val liveTask: String = "",
    val livePrompt: String = "",
    /** Reasoning trace when thinking mode is active (rendered distinctly). */
    val liveThought: String = "",
    val liveOutput: String = "",
    /** Number of bundled test images attached to the current request (0/1/2). */
    val liveImageCount: Int = 0,
    val lastTtftMs: Double? = null,
    val lastDecodeTps: Double? = null,
    val currentTempC: Double = 0.0,
    val currentPowerW: Double = 0.0,
    val thermalStatus: String = "",
    val tempSeries: List<Float> = emptyList(),
    val powerSeries: List<Float> = emptyList(),
    val logs: List<LogEntry> = emptyList(),
    val rounds: List<RoundResult> = emptyList(),
    /** Set when a single-model report has been persisted (legacy navigation). */
    val finishedRunId: String? = null,
    /** Set when the composite device run has been persisted; wins over finishedRunId. */
    val finishedDeviceRunId: String? = null,
    val fatalError: String? = null,
) {
    enum class Phase { IDLE, PREPARING, WARMUP, MEASURING, PAUSED, COOLDOWN, SAVING, FINISHED, ABORTED, FAILED }

    val isActive: Boolean
        get() = phase in listOf(Phase.PREPARING, Phase.WARMUP, Phase.MEASURING, Phase.PAUSED, Phase.COOLDOWN, Phase.SAVING)
}

/**
 * Owns the lifecycle of a benchmark session, decoupled from any single screen
 * so a run survives navigation. A Standard Test session executes the suite on
 * EVERY engine in [BenchmarkConfig.engineIds] (Preview·Fast, then Preview·Full)
 * with a thermal cooldown between models, persists one RunReport per model,
 * then a composite [DeviceRun] whose Device Score is the geometric mean of the
 * per-model totals.
 */
class BenchmarkSessionManager(
    private val appScope: CoroutineScope,
    private val engines: EngineRegistry,
    private val telemetry: TelemetryMonitor,
    private val preflightChecker: PreflightChecker,
    private val repository: RunRepository,
    private val tokenCounter: TokenCounter,
    private val appVersion: String,
    /** Supplies bundled multimodal test images by count (null in JVM tests). */
    private val imageProvider: ((count: Int) -> List<android.graphics.Bitmap>)? = null,
) {

    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val resumeGate = MutableStateFlow(true)
    private var runJob: Job? = null
    private var samplerJob: Job? = null

    fun start(config: BenchmarkConfig) {
        if (_state.value.isActive) return

        val engineIds = config.engineIds.ifEmpty { listOf(config.engineId) }
        val sampler = PowerSampler(telemetry)
        resumeGate.value = true
        _state.value = SessionState(
            phase = SessionState.Phase.PREPARING,
            config = config,
            engineName = engines.byId(engineIds.first()).displayName,
            modelIndex = 1,
            modelCount = engineIds.size,
        )

        val preflightChecks = preflightChecker.run()
        val preflightSnapshot = preflightChecker.snapshotFor(preflightChecks)

        samplerJob = appScope.launch {
            sampler.samples.collect { samples ->
                if (samples.isEmpty()) return@collect
                val latest = samples.last()
                _state.update {
                    it.copy(
                        currentTempC = latest.tempC,
                        currentPowerW = latest.powerW,
                        thermalStatus = latest.thermalStatus,
                        tempSeries = samples.map { s -> s.tempC.toFloat() },
                        powerSeries = samples.map { s -> s.powerW.toFloat() },
                    )
                }
            }
        }

        runJob = appScope.launch {
            sampler.start(this)
            val completed = mutableListOf<RunReport>()
            var currentRunner: BenchmarkRunner? = null
            var currentConfig = config.copy(engineId = engineIds.first())
            var currentRunId = ""
            var currentTimestamp = ""
            try {
                var switchLoads: List<Long> = emptyList()
                engineIds.forEachIndexed { index, engineId ->
                    val engine = engines.byId(engineId)
                    if (index > 0 && engineId != "mock") cooldownBetweenModels()
                    _state.update {
                        it.copy(
                            phase = SessionState.Phase.PREPARING,
                            modelIndex = index + 1,
                            engineName = engine.displayName,
                            liveResults = if (engineIds.size > 1) {
                                it.liveResults + "▸ ${engine.displayName}"
                            } else it.liveResults,
                            liveTask = "", livePrompt = "", liveThought = "",
                            liveOutput = "", liveImageCount = 0,
                            lastTtftMs = null, lastDecodeTps = null,
                        )
                    }
                    currentConfig = config.copy(engineId = engineId)
                    currentRunId = UUID.randomUUID().toString()
                    currentTimestamp = Instant.now().toString()
                    val runner = BenchmarkRunner(
                        engine = engine,
                        telemetry = telemetry,
                        sampler = sampler,
                        tokenCounter = tokenCounter,
                        listener = createListener(modelBase = index, modelCount = engineIds.size),
                        resumeGate = resumeGate,
                        imageProvider = imageProvider,
                    )
                    currentRunner = runner
                    try {
                        runner.execute(currentConfig)
                        val report = saveSubRun(
                            runner, currentRunId, currentTimestamp, currentConfig,
                            preflightSnapshot, aborted = false, failure = null,
                        )
                        completed += report
                        currentRunner = null
                    } finally {
                        // A real engine's suspend release() must run to completion
                        // (even through cancellation) or the model leaks.
                        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                            engine.release()
                        }
                    }
                }
                switchLoads = measureSwitchLoads(engineIds)
                finalize(engineIds, completed, aborted = false, failure = null, switchLoads)
            } catch (e: QuotaExceededException) {
                currentRunner?.let {
                    completed += saveSubRun(it, currentRunId, currentTimestamp, currentConfig, preflightSnapshot, aborted = false, failure = e.message)
                }
                finalize(engineIds, completed, aborted = false, failure = e.message ?: "Quota exceeded")
            } catch (e: kotlinx.coroutines.CancellationException) {
                // User stop: persist what we have as an aborted run.
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    currentRunner?.let {
                        completed += saveSubRun(it, currentRunId, currentTimestamp, currentConfig, preflightSnapshot, aborted = true, failure = null)
                    }
                    finalize(engineIds, completed, aborted = true, failure = null)
                }
                throw e
            } catch (e: Exception) {
                val message = e.message ?: "Unexpected error"
                currentRunner?.let {
                    completed += saveSubRun(it, currentRunId, currentTimestamp, currentConfig, preflightSnapshot, aborted = false, failure = message)
                }
                finalize(engineIds, completed, aborted = false, failure = message)
            } finally {
                sampler.stop()
                samplerJob?.cancel()
            }
        }
    }

    /** Builds + persists one model's report. Save failures surface via [finalize]. */
    private fun saveSubRun(
        runner: BenchmarkRunner,
        runId: String,
        timestamp: String,
        config: BenchmarkConfig,
        preflight: com.gemmark.app.core.model.PreflightSnapshot,
        aborted: Boolean,
        failure: String?,
    ): RunReport {
        val report = runner.buildReport(
            runId = runId,
            timestamp = timestamp,
            config = config,
            deviceInfo = telemetry.deviceInfo(),
            appVersion = appVersion,
            preflight = preflight,
            aborted = aborted,
            failureMessage = failure,
        )
        try {
            repository.save(report)
        } catch (e: Exception) {
            saveError = "Failed to save report: ${e.message ?: e.javaClass.simpleName}"
        }
        return report
    }

    private var saveError: String? = null

    /**
     * SWITCH LOAD phase: alternately load each model right after the other one
     * occupied AICore. Nano-4 Fast (4.2 GB) and Full (5.9 GB) cannot both stay
     * resident, so every load is a displacement load — the closest a third-party
     * app can get to a cold load (AICore has no eviction API; close() only
     * unbinds the client). Measures storage/memory throughput.
     */
    private suspend fun measureSwitchLoads(engineIds: List<String>): List<Long> {
        if (engineIds.size < 2) return emptyList()
        val sequence = listOf(engineIds[0], engineIds[1], engineIds[0], engineIds[1])
        val loads = mutableListOf<Long>()
        _state.update {
            it.copy(
                phase = SessionState.Phase.MEASURING,
                phaseLabel = "SWITCH",
                phaseIndex = 0,
                phaseCount = 0,
                liveTask = "Model switch load (displacement read)",
                livePrompt = "", liveThought = "", liveOutput = "", liveImageCount = 0,
                overallProgress = 0.98f,
            )
        }
        appendLog("Measuring model-switch load…", LogEntry.Level.INFO)
        // Let AICore settle after the sustained phase: on Dimensity 9500 the
        // service was still refusing requests (BUSY) when this phase started,
        // which killed all four loads.
        delay(SWITCH_SETTLE_MS)
        for (id in sequence) {
            val engine = engines.byId(id)
            var attempt = 0
            while (attempt < 2) {
                try {
                    val start = System.nanoTime()
                    engine.prepare(com.gemmark.app.core.model.Backend.NPU) { }
                    val ms = (System.nanoTime() - start) / 1_000_000
                    loads += ms
                    appendLog("Switch load ${engine.displayName}: $ms ms", LogEntry.Level.INFO)
                    break
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    attempt++
                    appendLog("Switch load failed (${engine.displayName}): ${e.message}", LogEntry.Level.WARN)
                    if (attempt < 2) delay(SWITCH_RETRY_DELAY_MS)
                } finally {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        engine.release()
                    }
                }
            }
        }
        return loads
    }

    /**
     * Ends the session. A composite DeviceRun is written only when every
     * requested model finished cleanly — a Device Score from a broken session
     * would not be comparable to anyone else's.
     */
    private fun finalize(
        engineIds: List<String>,
        completed: List<RunReport>,
        aborted: Boolean,
        failure: String?,
        switchLoads: List<Long> = emptyList(),
    ) {
        val clean = !aborted && failure == null && saveError == null && completed.size == engineIds.size
        var deviceRunId: String? = null
        if (clean && completed.isNotEmpty()) {
            val isMock = engineIds.all { it == "mock" }
            // Slots are matched by engine id; only the mock demo fills them
            // positionally (a Full-only partial run must NOT land in the Fast slot).
            val fast = completed.firstOrNull { it.config.engineId.contains("fast") }
                ?: if (isMock) completed.getOrNull(0) else null
            val full = completed.firstOrNull { it.config.engineId.contains("full") }
                ?: if (isMock) completed.getOrNull(1) else null
            // "Complete" requires a SCORE from both models, not just a report:
            // a needs_retest segment (observed: v4-full BUSY storm on Dimensity
            // 9500) must not let the other model's score pose as a device score.
            val allScored = completed.isNotEmpty() && completed.all { it.score != null }
            val coverage = when {
                isMock -> "mock"
                allScored && fast != null && full != null && fast !== full -> "complete"
                else -> "partial"
            }
            val loadScore = ScoreCalculator.loadScore(switchLoads)
            val deviceRun = DeviceRun(
                deviceRunId = UUID.randomUUID().toString(),
                timestamp = completed.first().timestamp,
                device = completed.first().device,
                deviceScore = if (coverage == "partial") {
                    null
                } else {
                    ScoreCalculator.deviceScore(completed.mapNotNull { it.score?.total }, loadScore)
                },
                fastRunId = fast?.runId,
                fastScore = fast?.score?.total,
                fastModel = fast?.model?.name.orEmpty(),
                fullRunId = full?.takeIf { it !== fast }?.runId,
                fullScore = full?.takeIf { it !== fast }?.score?.total,
                fullModel = full?.takeIf { it !== fast }?.model?.name.orEmpty(),
                suiteVersion = StandardSuite.VERSION,
                anchor = ScoreCalculator.REFERENCE_ID,
                coverage = coverage,
                switchLoadsMs = switchLoads,
                loadScore = loadScore,
                scoreFormula = ScoreCalculator.DEVICE_FORMULA,
            )
            try {
                repository.saveDeviceRun(deviceRun)
                deviceRunId = deviceRun.deviceRunId
            } catch (e: Exception) {
                saveError = "Failed to save device run: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        val error = failure ?: saveError
        _state.update {
            it.copy(
                phase = when {
                    error != null -> SessionState.Phase.FAILED
                    aborted -> SessionState.Phase.ABORTED
                    else -> SessionState.Phase.FINISHED
                },
                finishedDeviceRunId = deviceRunId,
                finishedRunId = if (saveError == null) completed.lastOrNull()?.runId else null,
                fatalError = error,
                overallProgress = if (error == null && !aborted) 1f else it.overallProgress,
            )
        }
        saveError = null
    }

    /**
     * Between models: let the SoC shed heat so Full isn't measured inside
     * Fast's thermal shadow (observed: SEVERE at 42 °C right after a suite).
     * Waits at least [COOLDOWN_MIN_S]; ends early once the battery reads
     * ≤ 38 °C, else gives up at [COOLDOWN_MAX_S] and presses on.
     */
    private suspend fun cooldownBetweenModels() {
        _state.update {
            it.copy(
                phase = SessionState.Phase.COOLDOWN,
                liveTask = "", livePrompt = "", liveThought = "", liveOutput = "",
                liveImageCount = 0,
            )
        }
        appendLog("Cooling down between models…", LogEntry.Level.INFO)
        var waited = 0
        while (waited < COOLDOWN_MAX_S) {
            delay(1_000)
            waited++
            val temp = _state.value.currentTempC
            if (waited >= COOLDOWN_MIN_S && (temp <= 38.0 || temp == 0.0)) break
        }
        appendLog("Cooldown done after ${waited}s (${_state.value.currentTempC} °C).", LogEntry.Level.INFO)
    }

    fun pause() {
        if (_state.value.phase in listOf(SessionState.Phase.WARMUP, SessionState.Phase.MEASURING)) {
            resumeGate.value = false
        }
    }

    fun resume() {
        autoPausedByBackground = false
        resumeGate.value = true
    }

    /**
     * AICore rejects background inference (BACKGROUND_USE_BLOCKED) and the
     * process may be killed once invisible. Pause between rounds when the app
     * leaves the foreground and note it in the run log; resume automatically
     * when the app returns (a user-initiated pause stays paused).
     */
    private var autoPausedByBackground = false

    fun onAppBackground() {
        if (_state.value.phase in listOf(SessionState.Phase.WARMUP, SessionState.Phase.MEASURING)) {
            autoPausedByBackground = true
            resumeGate.value = false
            appendLog("App left foreground — run auto-paused (AICore requires foreground).", LogEntry.Level.WARN)
        }
    }

    fun onAppForeground() {
        if (autoPausedByBackground) {
            autoPausedByBackground = false
            resumeGate.value = true
            appendLog("App back in foreground — resuming.", LogEntry.Level.INFO)
        }
    }

    private fun appendLog(message: String, level: LogEntry.Level) {
        _state.update {
            val t = it.logs.lastOrNull()?.tMs ?: 0
            it.copy(logs = (it.logs + LogEntry(tMs = t, level = level, message = message)).takeLast(200))
        }
    }

    fun stop() {
        // cancel() alone suffices: it interrupts a paused runner's gate wait too.
        // Releasing the gate first would let a paused runner start a fresh round
        // (and a real engine request) before cancellation lands.
        runJob?.cancel()
    }

    /** Clears a finished/aborted session so Home shows idle again. */
    fun reset() {
        if (!_state.value.isActive) {
            _state.value = SessionState()
        }
    }

    private fun createListener(modelBase: Int, modelCount: Int) = object : RunnerListener {
        // Sustained may overrun its planned rounds; progress caps below 100 %.
        val totalRounds = StandardSuite.WARMUP_ROUNDS + StandardSuite.plannedRounds

        /** Folds this model's 0..1 progress into the whole-session 0..1. */
        fun overall(innerCompleted: Int): Float {
            val inner = (innerCompleted.toFloat() / totalRounds).coerceAtMost(0.97f)
            return (modelBase + inner) / modelCount
        }

        override fun onWorkloadStarted(phase: SuitePhase, index: Int, total: Int) {
            _state.update {
                it.copy(phaseLabel = phase.label, phaseIndex = index, phaseCount = total)
            }
        }

        override fun onWorkloadFinished(phase: SuitePhase, summaryLine: String) {
            _state.update {
                it.copy(liveResults = it.liveResults + "${phase.label} · $summaryLine")
            }
        }

        override fun onPhase(phase: RunnerPhase) {
            _state.update {
                it.copy(
                    phase = when (phase) {
                        RunnerPhase.PREPARING -> SessionState.Phase.PREPARING
                        RunnerPhase.WARMUP -> SessionState.Phase.WARMUP
                        RunnerPhase.MEASURING -> SessionState.Phase.MEASURING
                        RunnerPhase.PAUSED -> SessionState.Phase.PAUSED
                        RunnerPhase.SAVING -> SessionState.Phase.SAVING
                        // A model finishing is not the session finishing; the
                        // orchestration loop owns the terminal states.
                        RunnerPhase.DONE -> SessionState.Phase.SAVING
                    },
                )
            }
        }

        override fun onRoundStarted(index: Int, isWarmup: Boolean) {
            _state.update {
                val completed = if (isWarmup) index - 1 else StandardSuite.WARMUP_ROUNDS + index - 1
                it.copy(
                    currentRound = index,
                    isWarmupStage = isWarmup,
                    overallProgress = overall(completed),
                    liveOutput = "",
                )
            }
        }

        override fun onGenerationUpdate(
            taskDescription: String,
            promptPreview: String,
            thoughtSoFar: String,
            answerSoFar: String,
            imageCount: Int,
        ) {
            _state.update {
                it.copy(
                    liveTask = taskDescription,
                    livePrompt = promptPreview,
                    liveThought = thoughtSoFar,
                    liveOutput = answerSoFar,
                    liveImageCount = imageCount,
                )
            }
        }

        override fun onLiveMetrics(ttftMs: Double?, decodeTps: Double?) {
            _state.update {
                it.copy(
                    lastTtftMs = ttftMs ?: it.lastTtftMs,
                    lastDecodeTps = decodeTps ?: it.lastDecodeTps,
                )
            }
        }

        override fun onRoundFinished(result: RoundResult) {
            _state.update {
                val completed = if (result.isWarmup) result.i else StandardSuite.WARMUP_ROUNDS + result.i
                it.copy(
                    rounds = if (result.isWarmup) it.rounds else it.rounds + result,
                    overallProgress = overall(completed),
                )
            }
        }

        override fun onLog(entry: LogEntry) {
            _state.update { it.copy(logs = (it.logs + entry).takeLast(200)) }
        }
    }

    private companion object {
        const val COOLDOWN_MIN_S = 20
        const val COOLDOWN_MAX_S = 75
        const val SWITCH_SETTLE_MS = 10_000L
        const val SWITCH_RETRY_DELAY_MS = 5_000L
    }
}
