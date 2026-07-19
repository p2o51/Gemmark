package com.gemmark.app.engine

import android.content.Context
import com.gemmark.app.core.model.Backend
import com.gemmark.app.core.model.ModelInfo
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.gemmark.app.engine.schema.OrderInfo
import com.google.mlkit.genai.prompt.Content
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.ModelReleaseStage
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.genai.prompt.generateTypedContentRequest
import com.google.mlkit.genai.prompt.generationConfig
import com.google.mlkit.genai.prompt.modelConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Track A engine: Gemini Nano on the device NPU via AICore, accessed through
 * the ML Kit GenAI Prompt API (com.google.mlkit:genai-prompt).
 *
 * Verified against genai-prompt 1.0.0-beta3 / genai-common 1.0.0-beta4 on a
 * Pixel 10 Pro running an AICore `thirdpartyexperimental` build.
 *
 * Spec mappings:
 *  - ErrorCode.BUSY → [EngineErrorCode.BUSY] (runner applies exponential
 *    backoff; AICore also suggests a delay via GenAiException.retryDelay)
 *  - ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED → [EngineErrorCode.BATTERY_QUOTA_EXCEEDED]
 *  - token counts come from [countTokens] (the runtime's own tokenizer),
 *    satisfying "生成结束后重新计数，不信任平台回调"
 *  - streaming chunks are deltas, not tokens; the runner only uses their
 *    arrival timestamps
 */
class AiCoreInferenceEngine(
    private val context: Context,
    private val variant: Variant = Variant.STABLE,
) : InferenceEngine {

    /**
     * Model selection knobs of the Prompt API (ModelConfig). STABLE resolves to
     * the default model (nano-v3 on the current AICore build); PREVIEW is the
     * pre-release track — the design doc's "Preview Nano 4 Fast / Full".
     */
    enum class Variant(val idSuffix: String, val label: String, val trackName: String) {
        STABLE("", "Stable", "stable"),
        PREVIEW_FAST("-preview-fast", "Preview · Fast", "preview"),
        PREVIEW_FULL("-preview-full", "Preview · Full", "preview"),
    }

    override val id: String = "aicore-nano${variant.idSuffix}"
    override val displayName: String = "Gemini Nano · ${variant.label}"

    private var model: GenerativeModel? = null
    private var baseModelName: String = ""
    private var tokenLimit: Int = 0

    override val modelInfo: ModelInfo
        get() = ModelInfo(
            name = if (baseModelName.isNotEmpty()) {
                "$baseModelName (${variant.label})"
            } else {
                "Gemini Nano (${variant.label})"
            },
            baseModelName = baseModelName,
            releaseTrack = "${variant.trackName} · aicore ${aicoreReleaseTrack()}",
            quant = "", // not exposed by the Prompt API; leave empty rather than guess
            backend = "npu",
        )

    override val supportedBackends: List<Backend> = listOf(Backend.NPU)

    private fun client(): GenerativeModel =
        model ?: Generation.getClient(
            generationConfig {
                modelConfig = when (variant) {
                    Variant.STABLE -> modelConfig {
                        releaseStage = ModelReleaseStage.STABLE
                    }
                    Variant.PREVIEW_FAST -> modelConfig {
                        releaseStage = ModelReleaseStage.PREVIEW
                        preference = ModelPreference.FAST
                    }
                    Variant.PREVIEW_FULL -> modelConfig {
                        releaseStage = ModelReleaseStage.PREVIEW
                        preference = ModelPreference.FULL
                    }
                }
            }
        ).also { model = it }

    override suspend fun checkAvailability(): EngineAvailability = try {
        when (client().checkStatus()) {
            FeatureStatus.AVAILABLE -> EngineAvailability.Available
            FeatureStatus.DOWNLOADABLE -> EngineAvailability.Downloadable("Model download required on first run")
            FeatureStatus.DOWNLOADING -> EngineAvailability.Downloadable("Model download in progress")
            else -> EngineAvailability.Unavailable("AICore reports UNAVAILABLE on this device")
        }
    } catch (e: Exception) {
        EngineAvailability.Unavailable("AICore check failed: ${e.message ?: e.javaClass.simpleName}")
    }

    override suspend fun prepare(backend: Backend, onProgress: (String) -> Unit) {
        require(backend == Backend.NPU) { "AICore engine only supports the NPU backend" }
        val m = client()

        when (m.checkStatus()) {
            FeatureStatus.AVAILABLE -> Unit
            FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> {
                onProgress("Downloading Gemini Nano…")
                var totalBytes = 0L
                m.download().collect { status ->
                    when (status) {
                        is DownloadStatus.DownloadStarted -> {
                            totalBytes = status.bytesToDownload
                            onProgress("Download started: ${totalBytes / 1_000_000} MB")
                        }
                        is DownloadStatus.DownloadProgress -> {
                            if (totalBytes > 0) {
                                val pct = status.totalBytesDownloaded * 100 / totalBytes
                                onProgress("Downloading model… $pct%")
                            }
                        }
                        is DownloadStatus.DownloadFailed -> throw mapException(status.e)
                        is DownloadStatus.DownloadCompleted -> onProgress("Model download complete.")
                    }
                }
            }
            else -> throw EngineException(
                EngineErrorCode.NOT_AVAILABLE,
                "Gemini Nano unavailable on this device (AICore UNAVAILABLE)",
            )
        }

        // warmup() loads the model into the AICore runtime — this is the part
        // the runner times as model_load_ms.
        try {
            m.warmup()
        } catch (e: GenAiException) {
            throw mapException(e)
        }

        baseModelName = runCatching { m.getBaseModelName() }.getOrDefault("")
        tokenLimit = runCatching { m.getTokenLimit() }.getOrDefault(0)
        onProgress(
            "AICore ready: ${baseModelName.ifEmpty { "(base model name unavailable)" }}" +
                if (tokenLimit > 0) " · context $tokenLimit tok" else "",
        )
    }

    override suspend fun countTokens(text: String): Int? = try {
        model?.countTokens(generateContentRequest(TextPart(text)) {})?.totalTokens
    } catch (_: Exception) {
        null
    }

    private fun buildRequest(request: GenerationRequest) =
        if (request.images.isNotEmpty()) {
            val content = Content.builder().apply {
                request.images.forEach { addPart(ImagePart(it)) }
                addPart(TextPart(request.prompt))
            }.build()
            generateContentRequest(content) {
                temperature = request.temperature
                topK = request.topK
                candidateCount = 1
                maxOutputTokens = request.maxOutputTokens
                enableThinking = request.enableThinking
            }
        } else {
            generateContentRequest(TextPart(request.prompt)) {
                temperature = request.temperature
                topK = request.topK
                candidateCount = 1
                maxOutputTokens = request.maxOutputTokens
                enableThinking = request.enableThinking
            }
        }

    /** Constrained decoding into [OrderInfo] (R41 structured output). */
    override suspend fun generateStructured(request: GenerationRequest): StructuredResult? {
        val m = model ?: throw EngineException(EngineErrorCode.NOT_AVAILABLE, "prepare() not called")
        val supported = runCatching { m.isStructuredOutputFeatureAvailable() }.getOrDefault(false)
        if (!supported) return null

        val typedRequest = generateTypedContentRequest(
            generateContentRequest = buildRequest(request),
            outputClass = OrderInfo::class,
            includeSchemaInPrompt = true,
        )
        val start = System.nanoTime()
        try {
            val response = m.generateContent(typedRequest)
            val elapsed = System.nanoTime() - start
            val order: OrderInfo? = response.candidates.firstOrNull()?.response
            return StructuredResult(
                outputText = order?.toString() ?: "",
                totalNanos = elapsed,
                success = order != null,
            )
        } catch (e: GenAiException) {
            throw mapException(e)
        }
    }

    private var resolvedName: String? = null

    override suspend fun resolvedModelName(): String? {
        resolvedName?.let { return it }
        return try {
            client().getBaseModelName().also { resolvedName = it }
        } catch (_: Exception) {
            null
        }
    }

    override fun generate(request: GenerationRequest): Flow<GenerationEvent> = flow {
        val m = model ?: throw EngineException(EngineErrorCode.NOT_AVAILABLE, "prepare() not called")

        // Spec: greedy decoding — temperature 0, topK 1. If AICore rejects
        // these values the run fails visibly and the actual constraint gets
        // recorded, which is exactly what the spec's ⚠️ rule wants.
        val genRequest = buildRequest(request)

        val start = System.nanoTime()
        val sb = StringBuilder()
        var emittedThought = false
        try {
            m.generateContentStream(genRequest).collect { response ->
                val now = System.nanoTime() - start
                // Thinking mode surfaces reasoning chunks separately (R41).
                response.thoughtProcess.firstOrNull()?.text?.takeIf { it.isNotEmpty() }?.let {
                    emittedThought = true
                    emit(GenerationEvent.Chunk(it, now, isThought = true))
                }
                // Chunks are text deltas (possibly several tokens each).
                val text = response.candidates.firstOrNull()?.text ?: return@collect
                if (text.isNotEmpty()) {
                    sb.append(text)
                    emit(GenerationEvent.Chunk(text, now))
                }
            }
        } catch (e: GenAiException) {
            throw mapException(e)
        }

        // Thought-only completions are real generations (observed on v4-fast:
        // greedy path thinks ~343 tok then EOS with no answer — a model-quality
        // outcome, not an engine failure; the decode work is measurable).
        if (sb.isEmpty() && !emittedThought) {
            throw EngineException(EngineErrorCode.UNKNOWN, "AICore returned an empty response")
        }
        // The Prompt API has no CPU-fallback signal; NPU is reported as-is and
        // any future fallback detection would come from AICore-side metadata.
        emit(GenerationEvent.Done(sb.toString(), System.nanoTime() - start, Backend.NPU))
    }

    override suspend fun release() {
        runCatching { model?.close() }
        model = null
        baseModelName = ""
        tokenLimit = 0
    }

    private fun mapException(e: GenAiException): EngineException {
        val code = when (e.errorCode) {
            GenAiException.ErrorCode.BUSY -> EngineErrorCode.BUSY
            GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED ->
                EngineErrorCode.BATTERY_QUOTA_EXCEEDED
            GenAiException.ErrorCode.NOT_AVAILABLE,
            GenAiException.ErrorCode.NOT_SUPPORTED,
            GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE,
            GenAiException.ErrorCode.AICORE_INCOMPATIBLE,
            GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED,
            -> EngineErrorCode.NOT_AVAILABLE
            else -> EngineErrorCode.UNKNOWN
        }
        val retryHint = runCatching { e.retryDelay?.toMillis() }.getOrNull()
        return EngineException(
            code,
            "AICore error ${e.errorCode}: ${e.message ?: "no message"}" +
                (retryHint?.let { " (suggested retry in ${it}ms)" } ?: ""),
            e,
            retryDelayMs = retryHint,
        )
    }

    /** Release track derived from the AICore package version (spec: record per run). */
    private fun aicoreReleaseTrack(): String = try {
        val name = context.packageManager
            .getPackageInfo("com.google.android.aicore", 0).versionName.orEmpty()
        when {
            name.contains("thirdpartyexperimental") -> "thirdpartyexperimental"
            name.contains("experimental") -> "experimental"
            name.contains("beta") -> "beta"
            name.isNotEmpty() -> "stable"
            else -> ""
        }
    } catch (_: Exception) {
        ""
    }
}
