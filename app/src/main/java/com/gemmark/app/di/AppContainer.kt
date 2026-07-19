package com.gemmark.app.di

import android.content.Context
import com.gemmark.app.BuildConfig
import com.gemmark.app.core.ApproxTokenCounter
import com.gemmark.app.core.TokenCounter
import com.gemmark.app.data.RunRepository
import com.gemmark.app.engine.EngineRegistry
import com.gemmark.app.runner.BenchmarkSessionManager
import com.gemmark.app.telemetry.PreflightChecker
import com.gemmark.app.telemetry.TelemetryMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Manual DI — deliberate: the object graph is small and build stays lean. */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val telemetryMonitor = TelemetryMonitor(appContext)
    val preflightChecker = PreflightChecker(telemetryMonitor)
    val engineRegistry = EngineRegistry(appContext)
    val runRepository = RunRepository(appContext)
    val tokenCounter: TokenCounter = ApproxTokenCounter()

    val sessionManager = BenchmarkSessionManager(
        appScope = appScope,
        engines = engineRegistry,
        telemetry = telemetryMonitor,
        preflightChecker = preflightChecker,
        repository = runRepository,
        tokenCounter = tokenCounter,
        appVersion = BuildConfig.VERSION_NAME,
        imageProvider = { count ->
            listOf(
                com.gemmark.app.R.drawable.gemmark_test_scene,
                com.gemmark.app.R.drawable.gemmark_test_scene_b,
            )
                .take(count)
                .mapNotNull { android.graphics.BitmapFactory.decodeResource(appContext.resources, it) }
        },
    )
}
