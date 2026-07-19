package com.gemmark.app.engine

import android.content.Context

/**
 * All engines the app knows about. Track B (LiteRT-LM Gemma) joins this list
 * after the device session decides the exact runtime artifacts.
 */
class EngineRegistry(context: Context) {

    // Gemma-4 generation only (owner decision): the benchmark's two fixed
    // targets are Preview·Fast and Preview·Full. Mock stays for emulators.
    val engines: List<InferenceEngine> = listOf(
        AiCoreInferenceEngine(context.applicationContext, AiCoreInferenceEngine.Variant.PREVIEW_FAST),
        AiCoreInferenceEngine(context.applicationContext, AiCoreInferenceEngine.Variant.PREVIEW_FULL),
        MockInferenceEngine(),
    )

    fun byId(id: String): InferenceEngine =
        engines.firstOrNull { it.id == id }
            ?: error("Unknown engine id: $id")

    /** Default selection for the setup screen: first available engine wins. */
    suspend fun defaultEngineId(): String {
        for (engine in engines) {
            if (engine.checkAvailability() is EngineAvailability.Available) return engine.id
        }
        return engines.last().id
    }
}
