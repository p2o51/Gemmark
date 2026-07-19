package com.gemmark.app.ui.result

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gemmark.app.core.model.RunReport
import com.gemmark.app.core.stats.ScoreCalculator
import com.gemmark.app.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ResultUiState(
    val report: RunReport? = null,
    val loading: Boolean = true,
    val notFound: Boolean = false,
)

class ResultViewModel(
    private val container: AppContainer,
    private val runId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(ResultUiState())
    val state: StateFlow<ResultUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val loaded = container.runRepository.load(runId)
            // Legacy reports (saved before scoring existed) get scored on the fly.
            val report = loaded?.let {
                if (it.score == null) it.copy(score = ScoreCalculator.forReport(it)) else it
            }
            _state.value = ResultUiState(
                report = report,
                loading = false,
                notFound = report == null,
            )
        }
    }

}
