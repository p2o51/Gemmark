package com.gemmark.app.engine

import com.gemmark.app.core.model.Backend
import com.gemmark.app.core.model.ModelInfo
import kotlinx.coroutines.flow.Flow

/**
 * Abstraction over an on-device LLM runtime.
 *
 * Implementations planned per the design doc's three tracks:
 *  - Track A: AICore / Gemini Nano via NPU ([AiCoreInferenceEngine], wired on device)
 *  - Track B: LiteRT-LM Gemma (GPU/CPU) — added after the device session
 *  - [MockInferenceEngine]: simulated engine so the whole pipeline runs anywhere
 */
interface InferenceEngine {
    /** Stable id recorded in configs/reports, e.g. "aicore-nano", "mock". */
    val id: String
    val displayName: String

    /** Static description of the model this engine runs (refined at prepare time). */
    val modelInfo: ModelInfo

    /** Backends this engine can be asked to use. */
    val supportedBackends: List<Backend>

    suspend fun checkAvailability(): EngineAvailability

    /**
     * Loads/warms the model. The runner times this call and records it as
     * model_load_ms. Safe to call more than once. [onProgress] receives
     * human-readable progress lines (e.g. model download percentage).
     */
    suspend fun prepare(backend: Backend, onProgress: (String) -> Unit = {})

    /**
     * Counts tokens with the runtime's own tokenizer (spec: recount, never
     * trust streaming callbacks). Null when the engine has no tokenizer —
     * the runner then falls back to the app-level [com.gemmark.app.core.TokenCounter].
     */
    suspend fun countTokens(text: String): Int? = null

    /**
     * Resolves the concrete base model this engine maps to (e.g. "nano-v4-fast")
     * without loading it. Null when unknown before prepare(). Used by the UI so
     * variant rows show which actual model they select.
     */
    suspend fun resolvedModelName(): String? = null

    /**
     * Constrained generation into the fixed benchmark schema (R41 structured
     * output). Null when the engine/model doesn't support it — the STRUCTURED
     * workload is then skipped. Non-streaming by API design, so only end-to-end
     * throughput is measurable.
     */
    suspend fun generateStructured(request: GenerationRequest): StructuredResult? = null

    /**
     * Runs one generation. Emits [GenerationEvent.Chunk] as output arrives
     * (AICore callbacks are chunks, not single tokens — token counts are
     * recomputed by the runner afterwards) and ends with [GenerationEvent.Done].
     *
     * Failures are thrown as [EngineException]; BUSY must map to
     * [EngineErrorCode.BUSY] so the runner can apply exponential backoff.
     */
    fun generate(request: GenerationRequest): Flow<GenerationEvent>

    /** Releases model resources. */
    suspend fun release()
}

data class GenerationRequest(
    val prompt: String,
    val maxOutputTokens: Int,
    /** Spec: main leaderboard is always greedy — temperature 0, topK 1. */
    val temperature: Float = 0f,
    val topK: Int = 1,
    /** Multimodal inputs for vision workloads (0, 1 or 2 images). */
    val images: List<android.graphics.Bitmap> = emptyList(),
    /** R41 thinking mode (Nano v4+): extra compute for multi-step reasoning. */
    val enableThinking: Boolean = false,
)

/** Result of a constrained (typed) generation — non-streaming by design. */
data class StructuredResult(
    /** Serialized form of the decoded object, for token recounting. */
    val outputText: String,
    val totalNanos: Long,
    val success: Boolean,
)

sealed interface GenerationEvent {
    /**
     * A streamed piece of output.
     * [elapsedNanos] is the engine-reported monotonic time the chunk arrived,
     * measured from generation start; the runner derives TTFT and decode rate
     * from these timestamps. [isThought] marks reasoning-trace chunks emitted
     * before the answer when thinking mode is on.
     */
    data class Chunk(val text: String, val elapsedNanos: Long, val isThought: Boolean = false) : GenerationEvent

    /**
     * Terminal event. [backendUsed] reports what actually executed the request —
     * if it differs from the requested backend the round is marked `fallback`.
     */
    data class Done(
        val fullText: String,
        val elapsedNanos: Long,
        val backendUsed: Backend,
    ) : GenerationEvent
}

sealed class EngineAvailability {
    data object Available : EngineAvailability()

    /** Model/runtime present but needs a download first. */
    data class Downloadable(val detail: String) : EngineAvailability()

    data class Unavailable(val reason: String) : EngineAvailability()
}

enum class EngineErrorCode {
    /** Runtime is busy (AICore ErrorCode.BUSY): retry with exponential backoff. */
    BUSY,

    /** AICore PER_APP_BATTERY_USE_QUOTA_EXCEEDED: abort the run and record it. */
    BATTERY_QUOTA_EXCEEDED,

    NOT_AVAILABLE,
    TIMEOUT,
    UNKNOWN,
}

class EngineException(
    val code: EngineErrorCode,
    message: String,
    cause: Throwable? = null,
    /** Runtime-suggested retry delay (AICore provides one for BUSY). */
    val retryDelayMs: Long? = null,
) : Exception(message, cause)
