package com.gemmark.app.ui.welcome

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gemmark.app.core.model.Backend
import com.gemmark.app.di.AppContainer
import com.gemmark.app.engine.EngineAvailability
import com.gemmark.app.ui.home.EngineStatusUi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WelcomeUiState(
    val engines: List<EngineStatusUi> = emptyList(),
    /** Engine currently downloading its model, with the latest progress line. */
    val downloadingId: String? = null,
    val downloadProgress: String = "",
    val loading: Boolean = true,
)

class WelcomeViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(WelcomeUiState())
    val state: StateFlow<WelcomeUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val engines = container.engineRegistry.engines
                .filter { it.id != "mock" } // the guide is about real models
                .map { engine ->
                    val availability = engine.checkAvailability()
                    val available = availability is EngineAvailability.Available
                    EngineStatusUi(
                        id = engine.id,
                        name = engine.displayName,
                        available = available,
                        detail = when (availability) {
                            is EngineAvailability.Available -> "Ready"
                            is EngineAvailability.Downloadable -> "Tap to download"
                            is EngineAvailability.Unavailable -> availability.reason
                        },
                        modelName = if (available) engine.resolvedModelName() else null,
                    )
                }
            _state.update { it.copy(engines = engines, loading = false) }
        }
    }

    /** Triggers the on-device model download for a DOWNLOADABLE engine. */
    fun download(engineId: String) {
        if (_state.value.downloadingId != null) return
        _state.update { it.copy(downloadingId = engineId, downloadProgress = "Starting…") }
        viewModelScope.launch {
            try {
                container.engineRegistry.byId(engineId).prepare(Backend.NPU) { progress ->
                    _state.update { it.copy(downloadProgress = progress) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(downloadProgress = "Failed: ${e.message}") }
            } finally {
                container.engineRegistry.byId(engineId).release()
                _state.update { it.copy(downloadingId = null) }
                refresh()
            }
        }
    }
}
