package com.gemmark.app

import com.gemmark.app.core.ApproxTokenCounter
import com.gemmark.app.core.model.Backend
import com.gemmark.app.core.model.BenchmarkConfig
import com.gemmark.app.core.model.DeviceInfo
import com.gemmark.app.core.model.LogEntry
import com.gemmark.app.core.model.ModelInfo
import com.gemmark.app.core.model.PromptGroup
import com.gemmark.app.core.model.RoundResult
import com.gemmark.app.core.model.RoundStatus
import com.gemmark.app.core.model.RunStatus
import com.gemmark.app.core.suite.SuitePhase
import com.gemmark.app.engine.EngineAvailability
import com.gemmark.app.engine.EngineErrorCode
import com.gemmark.app.engine.EngineException
import com.gemmark.app.engine.GenerationEvent
import com.gemmark.app.engine.GenerationRequest
import com.gemmark.app.engine.InferenceEngine
import com.gemmark.app.runner.BackoffPolicy
import com.gemmark.app.runner.BenchmarkRunner
import com.gemmark.app.runner.RunnerListener
import com.gemmark.app.runner.RunnerPhase
import com.gemmark.app.telemetry.BatterySnapshot
import com.gemmark.app.telemetry.PowerSampler
import com.gemmark.app.telemetry.TelemetrySource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeTelemetry : TelemetrySource {
    override fun snapshot() = BatterySnapshot(
        levelPct = 80,
        isCharging = false,
        tempC = 30.0,
        voltageMv = 4000,
        currentNowUa = -500_000,
        chargeCounterUah = 4_000_000,
        thermalStatus = "NONE",
        powerW = 2.0,
    )

    override fun thermalStatusName() = "NONE"
}

/**
 * Scripted engine: [script] decides per (round, attempt) what happens.
 * Emits [tokens] words with deterministic timestamps: first chunk at 100ms,
 * last chunk at 100ms + (tokens-1) * 10ms, done at +50ms after that.
 */
private class ScriptedEngine(
    private val script: (attemptIndex: Int) -> Behavior,
) : InferenceEngine {
    sealed interface Behavior {
        data class Emit(val tokens: Int, val backend: Backend = Backend.NPU) : Behavior
        data object Busy : Behavior
        data class Fail(val code: EngineErrorCode) : Behavior
    }

    var attempts = 0
        private set

    override val id = "scripted"
    override val displayName = "Scripted"
    override val modelInfo = ModelInfo(name = "scripted-model")
    override val supportedBackends = listOf(Backend.NPU)
    override suspend fun checkAvailability() = EngineAvailability.Available
    override suspend fun prepare(backend: Backend, onProgress: (String) -> Unit) = Unit
    override suspend fun release() = Unit

    override fun generate(request: GenerationRequest): Flow<GenerationEvent> = flow {
        val behavior = script(attempts++)
        when (behavior) {
            is Behavior.Busy -> throw EngineException(EngineErrorCode.BUSY, "busy")
            is Behavior.Fail -> throw EngineException(behavior.code, "fail")
            is Behavior.Emit -> {
                val words = List(behavior.tokens) { "word" }
                val text = words.joinToString(" ")
                var t = 100_000_000L // first chunk at 100 ms
                emit(GenerationEvent.Chunk(words.first(), t))
                for (i in 1 until behavior.tokens) {
                    t += 10_000_000L
                    emit(GenerationEvent.Chunk(words[i], t))
                }
                emit(GenerationEvent.Done(text, t + 50_000_000L, behavior.backend))
            }
        }
    }
}

private class NoopListener : RunnerListener {
    val finishedRounds = mutableListOf<RoundResult>()
    override fun onPhase(phase: RunnerPhase) = Unit
    override fun onRoundStarted(index: Int, isWarmup: Boolean) = Unit
    override fun onLiveMetrics(ttftMs: Double?, decodeTps: Double?) = Unit
    override fun onRoundFinished(result: RoundResult) {
        finishedRounds += result
    }

    override fun onLog(entry: LogEntry) = Unit
}

class BenchmarkRunnerTest {

    private val device = DeviceInfo(model = "Test", build = "test-build", aicoreVersion = "")

    /** Single-workload test suite: N main rounds, no wall-time extension. */
    private fun testSuite(measured: Int) = listOf(
        SuitePhase(
            id = "main",
            label = "MAIN",
            description = "test main workload",
            group = PromptGroup.FIXED_256,
            rounds = measured,
        ),
    )

    private fun runnerFor(
        engine: InferenceEngine,
        listener: RunnerListener = NoopListener(),
        measured: Int = 15,
        warmup: Int = 1,
    ): BenchmarkRunner {
        val telemetry = FakeTelemetry()
        var fakeMs = 0L
        val sampler = PowerSampler(telemetry, elapsedMs = { fakeMs++ })
        return BenchmarkRunner(
            engine = engine,
            telemetry = telemetry,
            sampler = sampler,
            tokenCounter = ApproxTokenCounter(),
            listener = listener,
            backoff = BackoffPolicy(baseDelayMs = 10, maxRetries = 3),
            resumeGate = MutableStateFlow(true),
            suite = testSuite(measured),
            warmupRounds = warmup,
            roundIntervalMs = 10,
        )
    }

    private fun config() = BenchmarkConfig(
        engineId = "scripted",
        backend = Backend.NPU,
    )

    // 256-token target; ApproxTokenCounter counts 256 words as round(256*1.3)=333 tokens,
    // so emitting 256 words is far above the 80% threshold of 256 → OK.
    private val fullOutput = 256

    @Test
    fun `all clean rounds produce a completed report`() = runTest {
        val engine = ScriptedEngine { ScriptedEngine.Behavior.Emit(fullOutput) }
        val listener = NoopListener()
        val runner = runnerFor(engine, listener, measured = 15, warmup = 1)
        runner.execute(config())

        val report = runner.buildReport(
            runId = "test-run", timestamp = "2026-07-16T00:00:00Z",
            config = config(), deviceInfo = device, appVersion = "1.0",
            preflight = null, aborted = false,
        )

        assertEquals(15, report.rounds.size)
        assertEquals(1, report.warmupRounds.size)
        assertTrue(report.rounds.all { it.status == RoundStatus.OK })
        assertEquals(RunStatus.COMPLETED, report.runStatus)
        assertEquals(15, report.summary?.validRounds)
        // decode tps from scripted timestamps: (333-1)/((100+2550-100)ms) ≈ 130.2 tok/s
        val expectedTps = 332 / 2.550
        assertEquals(expectedTps, report.rounds.first().decodeTps, 0.5)
        // TTFT is the first chunk timestamp: 100 ms
        assertEquals(100.0, report.rounds.first().ttftMs, 0.01)
    }

    @Test
    fun `busy then success marks round busy_retried with retry count`() = runTest {
        // Every round: first attempt BUSY, second succeeds.
        var attemptInRound = 0
        val engine = ScriptedEngine {
            if (attemptInRound++ % 2 == 0) ScriptedEngine.Behavior.Busy
            else ScriptedEngine.Behavior.Emit(fullOutput)
        }
        val runner = runnerFor(engine, measured = 3, warmup = 0)
        runner.execute(config())

        val report = runner.buildReport(
            runId = "r", timestamp = "t", config = config(),
            deviceInfo = device, appVersion = "1.0", preflight = null, aborted = false,
        )
        assertTrue(report.rounds.all { it.status == RoundStatus.BUSY_RETRIED })
        assertTrue(report.rounds.all { it.retries == 1 })
        // busy_retried rounds still count as valid
        assertEquals(3, report.summary?.validRounds)
    }

    @Test
    fun `short output is excluded from stats`() = runTest {
        var round = 0
        val engine = ScriptedEngine {
            round++
            // Round 2 emits 100 words → ~130 approx-tokens < 80% of 256 → short
            if (round == 2) ScriptedEngine.Behavior.Emit(100)
            else ScriptedEngine.Behavior.Emit(fullOutput)
        }
        val runner = runnerFor(engine, measured = 3, warmup = 0)
        runner.execute(config())

        val report = runner.buildReport(
            runId = "r", timestamp = "t", config = config(),
            deviceInfo = device, appVersion = "1.0", preflight = null, aborted = false,
        )
        assertEquals(RoundStatus.SHORT, report.rounds[1].status)
        assertEquals(2, report.summary?.validRounds)
    }

    @Test
    fun `cpu fallback is flagged and kept out of stats`() = runTest {
        var round = 0
        val engine = ScriptedEngine {
            round++
            if (round == 1) ScriptedEngine.Behavior.Emit(fullOutput, backend = Backend.CPU)
            else ScriptedEngine.Behavior.Emit(fullOutput)
        }
        val runner = runnerFor(engine, measured = 3, warmup = 0)
        runner.execute(config())

        val report = runner.buildReport(
            runId = "r", timestamp = "t", config = config(),
            deviceInfo = device, appVersion = "1.0", preflight = null, aborted = false,
        )
        assertEquals(RoundStatus.FALLBACK, report.rounds[0].status)
        assertEquals("cpu", report.rounds[0].backendUsed)
        assertEquals(2, report.summary?.validRounds)
    }

    @Test
    fun `too many errors yields needs_retest`() = runTest {
        var round = 0
        val engine = ScriptedEngine {
            round++
            // 5 of 15 rounds error → 10 valid < 12 required
            if (round % 3 == 0) ScriptedEngine.Behavior.Fail(EngineErrorCode.UNKNOWN)
            else ScriptedEngine.Behavior.Emit(fullOutput)
        }
        val runner = runnerFor(engine, measured = 15, warmup = 0)
        runner.execute(config())

        val report = runner.buildReport(
            runId = "r", timestamp = "t", config = config(),
            deviceInfo = device, appVersion = "1.0", preflight = null, aborted = false,
        )
        assertEquals(RunStatus.NEEDS_RETEST, report.runStatus)
        assertEquals(10, report.summary?.validRounds)
    }

    @Test
    fun `busy exhaustion becomes an error round`() = runTest {
        var attempts = 0
        val engine = ScriptedEngine {
            attempts++
            if (attempts <= 4) ScriptedEngine.Behavior.Busy // 1 try + 3 retries all busy
            else ScriptedEngine.Behavior.Emit(fullOutput)
        }
        val runner = runnerFor(engine, measured = 2, warmup = 0)
        runner.execute(config())

        val report = runner.buildReport(
            runId = "r", timestamp = "t", config = config(),
            deviceInfo = device, appVersion = "1.0", preflight = null, aborted = false,
        )
        assertEquals(RoundStatus.ERROR, report.rounds[0].status)
        assertEquals(3, report.rounds[0].retries)
        assertEquals(RoundStatus.OK, report.rounds[1].status)
    }

    @Test
    fun `optional phase is skipped when its first round errors`() = runTest {
        var attempts = 0
        val engine = ScriptedEngine {
            attempts++
            // First 3 attempts = main phase (3 rounds ok); 4th = image round fails.
            if (attempts <= 3) ScriptedEngine.Behavior.Emit(fullOutput)
            else ScriptedEngine.Behavior.Fail(EngineErrorCode.NOT_AVAILABLE)
        }
        val telemetry = FakeTelemetry()
        var fakeMs = 0L
        val sampler = PowerSampler(telemetry, elapsedMs = { fakeMs++ })
        val suite = listOf(
            SuitePhase("main", "MAIN", "main", PromptGroup.FIXED_256, rounds = 3),
            SuitePhase(
                "image", "IMAGE", "vision", PromptGroup.IMAGE, rounds = 3,
                applyShortRule = false, imageCount = 0, optional = true,
            ),
        )
        val runner = BenchmarkRunner(
            engine = engine,
            telemetry = telemetry,
            sampler = sampler,
            tokenCounter = ApproxTokenCounter(),
            listener = NoopListener(),
            backoff = BackoffPolicy(baseDelayMs = 10, maxRetries = 3),
            resumeGate = MutableStateFlow(true),
            suite = suite,
            warmupRounds = 0,
            roundIntervalMs = 10,
        )
        runner.execute(config())

        val report = runner.buildReport(
            runId = "r", timestamp = "t", config = config(),
            deviceInfo = device, appVersion = "1.0", preflight = null, aborted = false,
        )
        // Image rounds removed entirely; validity computed over the 3 main rounds.
        assertEquals(3, report.rounds.size)
        assertEquals(RunStatus.COMPLETED, report.runStatus)
        val imageSummary = report.workloadSummary.first { it.id == "image" }
        assertEquals("unsupported", imageSummary.metricName)
        assertEquals(0, imageSummary.rounds)
    }

    @Test
    fun `busy-exhausted first round does not mark optional phase unsupported`() = runTest {
        // attempt 0: main round ok. attempts 1-4: first image round exhausts
        // BUSY retries (maxRetries=3). attempt 5: second image round succeeds.
        val engine = ScriptedEngine { attempt ->
            when {
                attempt == 0 -> ScriptedEngine.Behavior.Emit(256)
                attempt in 1..4 -> ScriptedEngine.Behavior.Busy
                else -> ScriptedEngine.Behavior.Emit(256)
            }
        }
        val telemetry = FakeTelemetry()
        var fakeMs = 0L
        val sampler = PowerSampler(telemetry, elapsedMs = { fakeMs++ })
        val suite = listOf(
            SuitePhase("main", "MAIN", "main", PromptGroup.FIXED_256, rounds = 1),
            SuitePhase(
                "image", "IMAGE", "vision", PromptGroup.IMAGE, rounds = 2,
                applyShortRule = false, imageCount = 0, optional = true,
            ),
        )
        val runner = BenchmarkRunner(
            engine = engine,
            telemetry = telemetry,
            sampler = sampler,
            tokenCounter = ApproxTokenCounter(),
            listener = NoopListener(),
            backoff = BackoffPolicy(baseDelayMs = 10, maxRetries = 3),
            resumeGate = MutableStateFlow(true),
            suite = suite,
            warmupRounds = 0,
            roundIntervalMs = 10,
        )
        runner.execute(config())
        val report = runner.buildReport(
            runId = "r", timestamp = "t", config = config(),
            deviceInfo = device, appVersion = "1.0", preflight = null, aborted = false,
        )
        val imageSummary = report.workloadSummary.first { it.id == "image" }
        // Phase must survive: one error round dropped, one valid round kept.
        assertTrue(imageSummary.metricName != "unsupported")
        assertEquals(1, imageSummary.validRounds)
        val imageRounds = report.rounds.filter { it.workload == "image" }
        assertEquals(2, imageRounds.size)
        assertEquals(RoundStatus.ERROR, imageRounds[0].status)
        assertEquals(RoundStatus.OK, imageRounds[1].status)
    }

    @Test
    fun `thought-only thinking round stays valid with measurable decode`() = runTest {
        // Observed on nano-v4-fast: the greedy path thinks ~343 tok then hits
        // EOS with no answer. Model-quality outcome, not an engine failure —
        // the decode work is real and must feed the Reasoning basis.
        val engine = object : InferenceEngine {
            override val id = "thoughtonly"
            override val displayName = "ThoughtOnly"
            override val modelInfo = ModelInfo(name = "thoughtonly")
            override val supportedBackends = listOf(Backend.NPU)
            override suspend fun checkAvailability() = EngineAvailability.Available
            override suspend fun prepare(backend: Backend, onProgress: (String) -> Unit) = Unit
            override suspend fun release() = Unit
            override fun generate(request: GenerationRequest) = flow<GenerationEvent> {
                // Trailing spaces: the approx counter tokenizes on whitespace.
                val words = List(300) { "think " }
                var t = 100_000_000L
                emit(GenerationEvent.Chunk(words.first(), t, isThought = true))
                for (i in 1 until words.size) {
                    t += 10_000_000L
                    emit(GenerationEvent.Chunk(words[i], t, isThought = true))
                }
                // What AiCoreInferenceEngine emits when sb stayed empty.
                emit(GenerationEvent.Done("", t + 50_000_000L, Backend.NPU))
            }
        }
        val telemetry = FakeTelemetry()
        var fakeMs = 0L
        val sampler = PowerSampler(telemetry, elapsedMs = { fakeMs++ })
        val suite = listOf(
            SuitePhase(
                "thinking", "THINKING", "reasoning", PromptGroup.THINKING, rounds = 1,
                applyShortRule = false, thinking = true, optional = true,
            ),
        )
        val runner = BenchmarkRunner(
            engine = engine,
            telemetry = telemetry,
            sampler = sampler,
            tokenCounter = ApproxTokenCounter(),
            listener = NoopListener(),
            backoff = BackoffPolicy(baseDelayMs = 10, maxRetries = 3),
            resumeGate = MutableStateFlow(true),
            suite = suite,
            warmupRounds = 0,
            roundIntervalMs = 10,
        )
        runner.execute(config())
        val report = runner.buildReport(
            runId = "r", timestamp = "t", config = config(),
            deviceInfo = device, appVersion = "1.0", preflight = null, aborted = false,
        )
        val round = report.rounds.single()
        assertEquals(RoundStatus.OK, round.status)
        assertEquals(0, round.outputTokens)
        assertTrue(round.thoughtTokens > 0)
        assertTrue(round.decodeValid)
        assertTrue(round.decodeTps > 0.0)
        assertEquals(0.0, round.timeToAnswerMs, 0.0)
    }

    @Test
    fun `busy retry uses runtime hint when provided`() = runTest {
        var attempts = 0
        val engine = object : InferenceEngine {
            override val id = "hinting"
            override val displayName = "Hinting"
            override val modelInfo = ModelInfo(name = "hinting")
            override val supportedBackends = listOf(Backend.NPU)
            override suspend fun checkAvailability() = EngineAvailability.Available
            override suspend fun prepare(backend: Backend, onProgress: (String) -> Unit) = Unit
            override suspend fun release() = Unit
            override fun generate(request: GenerationRequest) = flow<GenerationEvent> {
                if (attempts++ == 0) {
                    throw EngineException(
                        EngineErrorCode.BUSY, "busy", retryDelayMs = 700,
                    )
                }
                val words = List(256) { "word" }
                var t = 100_000_000L
                emit(GenerationEvent.Chunk(words.first(), t))
                for (i in 1 until words.size) {
                    t += 10_000_000L
                    emit(GenerationEvent.Chunk(words[i], t))
                }
                emit(GenerationEvent.Done(words.joinToString(" "), t + 50_000_000L, Backend.NPU))
            }
        }
        val runner = runnerFor(engine, measured = 1, warmup = 0)
        runner.execute(config())
        val report = runner.buildReport(
            runId = "r", timestamp = "t", config = config(),
            deviceInfo = device, appVersion = "1.0", preflight = null, aborted = false,
        )
        assertEquals(RoundStatus.BUSY_RETRIED, report.rounds[0].status)
        assertTrue(report.log.any { it.message.contains("runtime hint") })
    }

    @Test
    fun `required valid rounds scales for short configs`() {
        assertEquals(12, BenchmarkRunner.requiredValidRounds(15))
        assertEquals(16, BenchmarkRunner.requiredValidRounds(20))
        assertEquals(4, BenchmarkRunner.requiredValidRounds(5))
        assertEquals(3, BenchmarkRunner.requiredValidRounds(3))
    }
}
