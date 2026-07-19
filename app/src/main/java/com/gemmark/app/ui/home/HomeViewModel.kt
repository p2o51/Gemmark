package com.gemmark.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gemmark.app.core.model.DeviceInfo
import com.gemmark.app.core.model.DeviceRun
import com.gemmark.app.core.model.RunReport
import com.gemmark.app.core.stats.ScoreCalculator
import com.gemmark.app.di.AppContainer
import com.gemmark.app.engine.EngineAvailability
import com.gemmark.app.runner.SessionState
import com.gemmark.app.telemetry.PreflightCheck
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EngineStatusUi(
    val id: String,
    val name: String,
    val available: Boolean,
    val detail: String,
    /** Concrete base model this engine resolves to (e.g. "nano-v4-fast"). */
    val modelName: String? = null,
)

/** Best Gemmark Score achieved on this device (G3 runs only). */
data class BestScoreUi(
    val score: Int,
    val modelName: String,
    val runId: String,
)

/** Best composite Device Score (both models in one session). */
data class BestDeviceScoreUi(
    val score: Int,
    val fastScore: Int?,
    val fullScore: Int?,
    val deviceRunId: String,
    val coverage: String,
)

data class HomeUiState(
    val device: DeviceInfo? = null,
    val engines: List<EngineStatusUi> = emptyList(),
    val preflight: List<PreflightCheck> = emptyList(),
    /** Standalone model runs NOT belonging to a device run (legacy/partial). */
    val runs: List<RunReport> = emptyList(),
    /** Composite dual-model sessions, newest first. */
    val deviceRuns: List<DeviceRun> = emptyList(),
    val bestScore: BestScoreUi? = null,
    val bestDeviceScore: BestDeviceScoreUi? = null,
    val loading: Boolean = true,
)

class HomeViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    /** Active-run banner state. */
    val session: StateFlow<SessionState> = container.sessionManager.state

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val device = container.telemetryMonitor.deviceInfo()
            val engines = container.engineRegistry.engines.map { engine ->
                val availability = engine.checkAvailability()
                val available = availability is EngineAvailability.Available
                EngineStatusUi(
                    id = engine.id,
                    name = engine.displayName,
                    available = available,
                    detail = when (availability) {
                        is EngineAvailability.Available -> "Ready"
                        is EngineAvailability.Downloadable -> "Needs download — ${availability.detail}"
                        is EngineAvailability.Unavailable -> availability.reason
                    },
                    modelName = if (available) engine.resolvedModelName() else null,
                )
            }
            val preflight = container.preflightChecker.run()
            val allRuns = container.runRepository.listAll()
            val deviceRuns = container.runRepository.listDeviceRuns()
            // Sub-runs live inside their device run's card; don't double-list them.
            val referenced = deviceRuns.flatMap { listOfNotNull(it.fastRunId, it.fullRunId) }.toSet()
            val runs = allRuns.filterNot { it.runId in referenced }
            val best = allRuns
                .mapNotNull { run ->
                    ScoreCalculator.forReport(run)?.let { card ->
                        BestScoreUi(card.total, run.model.baseModelName.ifEmpty { run.model.name }, run.runId)
                    }
                }
                .maxByOrNull { it.score }
            val bestDevice = deviceRuns
                .mapNotNull { dr ->
                    dr.deviceScore?.let {
                        BestDeviceScoreUi(it, dr.fastScore, dr.fullScore, dr.deviceRunId, dr.coverage)
                    }
                }
                .maxByOrNull { it.score }
            _state.update {
                HomeUiState(
                    device = device,
                    engines = engines,
                    preflight = preflight,
                    runs = runs,
                    deviceRuns = deviceRuns,
                    bestScore = best,
                    bestDeviceScore = bestDevice,
                    loading = false,
                )
            }
        }
    }

}
