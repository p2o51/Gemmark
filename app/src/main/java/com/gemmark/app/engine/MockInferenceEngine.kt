package com.gemmark.app.engine

import com.gemmark.app.core.model.Backend
import com.gemmark.app.core.model.ModelInfo
import kotlin.math.max
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Simulated engine so the full benchmark pipeline (runner, telemetry, stats,
 * persistence, UI) can be exercised without a device that has AICore.
 *
 * Behavior modeled on the failure modes the spec calls out:
 *  - chunked streaming (a few tokens per callback, like AICore)
 *  - occasional BUSY errors (runner must retry with exponential backoff)
 *  - occasional short outputs (< 80% of target → `short`)
 *  - throughput decays over consecutive rounds to imitate thermal throttling
 *  - rare CPU fallback rounds
 */
class MockInferenceEngine(
    private val random: Random = Random(System.nanoTime()),
    /** Probability of a BUSY rejection per attempt. */
    private val busyProbability: Double = 0.06,
    private val shortOutputProbability: Double = 0.05,
    private val fallbackProbability: Double = 0.03,
    /** Base decode speed in tokens/sec before decay. */
    private val baseDecodeTps: Double = 32.0,
) : InferenceEngine {

    override val id: String = "mock"
    override val displayName: String = "Mock Engine (simulated)"

    override val modelInfo: ModelInfo = ModelInfo(
        name = "Simulated Nano (mock)",
        baseModelName = "mock-nano-sim",
        releaseTrack = "simulated",
        quant = "int4-sim",
        backend = "sim",
    )

    override val supportedBackends: List<Backend> = listOf(Backend.NPU, Backend.GPU, Backend.CPU)

    /** Rounds completed since prepare(); drives the simulated thermal decay. */
    private var completedGenerations = 0
    private var prepared = false
    private var requestedBackend: Backend = Backend.NPU

    override suspend fun checkAvailability(): EngineAvailability = EngineAvailability.Available

    override suspend fun resolvedModelName(): String = modelInfo.baseModelName

    override suspend fun prepare(backend: Backend, onProgress: (String) -> Unit) {
        // Always record the backend, even when already prepared: the engine is a
        // singleton and a later run may request a different backend.
        requestedBackend = backend
        if (!prepared) {
            delay(600 + random.nextLong(400)) // simulated model load
            prepared = true
            completedGenerations = 0
        }
    }

    override fun generate(request: GenerationRequest): Flow<GenerationEvent> = flow {
        check(prepared) { "prepare() must be called before generate()" }

        if (random.nextDouble() < busyProbability) {
            delay(30 + random.nextLong(50))
            throw EngineException(EngineErrorCode.BUSY, "Simulated BUSY: runtime serving another request")
        }

        // A CPU run cannot "fall back to CPU" — only simulate fallback otherwise.
        val fallback = requestedBackend != Backend.CPU && random.nextDouble() < fallbackProbability
        val backendUsed = if (fallback) Backend.CPU else requestedBackend

        // TTFT: base latency + prefill proportional to input size.
        val inputChars = request.prompt.length
        val prefillMs = inputChars / 28.0 // ~28 chars/ms simulated prefill
        val ttftMs = 90 + random.nextDouble() * 60 + prefillMs
        delay(ttftMs.toLong())

        val start = System.nanoTime() - (ttftMs * 1_000_000).toLong()

        // Thermal decay: each completed round slows decode a little, floor at 55%.
        val decayFactor = max(0.55, 1.0 - completedGenerations * 0.018)
        val speedFactor = if (fallback) 0.35 else 1.0
        val tps = baseDecodeTps * decayFactor * speedFactor * (0.95 + random.nextDouble() * 0.1)

        val short = random.nextDouble() < shortOutputProbability
        val targetTokens = if (short) {
            (request.maxOutputTokens * (0.3 + random.nextDouble() * 0.4)).toInt()
        } else {
            // Greedy decoding against a max-token cap: usually hits the cap.
            (request.maxOutputTokens * (0.97 + random.nextDouble() * 0.03)).toInt()
        }

        val sb = StringBuilder()
        if (request.enableThinking) {
            // Simulated reasoning trace before the answer.
            repeat(5) { i ->
                val thought = "step ${i + 1}: considering the constraints... "
                delay((4 / tps * 1000).toLong())
                emit(GenerationEvent.Chunk(thought, System.nanoTime() - start, isThought = true))
            }
        }
        if (request.images.isNotEmpty()) {
            val intro = "The image shows "
            sb.append(intro)
            emit(GenerationEvent.Chunk(intro, System.nanoTime() - start))
        }
        var emitted = 0
        while (emitted < targetTokens) {
            // AICore-like behavior: each callback carries a few tokens, not one.
            val chunkTokens = 1 + random.nextInt(4)
            val take = minOf(chunkTokens, targetTokens - emitted)
            val text = buildString {
                repeat(take) { append(WORDS[random.nextInt(WORDS.size)]).append(' ') }
            }
            delay((take / tps * 1000).toLong())
            emitted += take
            sb.append(text)
            emit(GenerationEvent.Chunk(text, System.nanoTime() - start))
        }

        completedGenerations++
        emit(GenerationEvent.Done(sb.toString().trim(), System.nanoTime() - start, backendUsed))
    }

    override suspend fun generateStructured(request: GenerationRequest): StructuredResult {
        check(prepared) { "prepare() must be called before generateStructured()" }
        val start = System.nanoTime()
        delay(900 + random.nextLong(400)) // simulated constrained decode
        return StructuredResult(
            outputText = "OrderInfo(customer=Ren Okabe, items=[OrderItem(name=jasmine tea, quantity=3, " +
                "unitPrice=12.5), OrderItem(name=ceramic teapot, quantity=2, unitPrice=34.0)], " +
                "currency=euros, deliveryDate=March 14, express=true)",
            totalNanos = System.nanoTime() - start,
            success = true,
        )
    }

    override suspend fun release() {
        prepared = false
        completedGenerations = 0
    }

    private companion object {
        val WORDS = listOf(
            "the", "wren", "nests", "among", "pale", "grasses", "near", "shifting", "dunes",
            "its", "copper", "crest", "catches", "morning", "light", "while", "it", "sings",
            "a", "low", "rolling", "song", "that", "carries", "far", "across", "open", "sand",
        )
    }
}
