package com.gemmark.app.ui.run

import androidx.lifecycle.ViewModel
import com.gemmark.app.core.model.BenchmarkConfig
import com.gemmark.app.di.AppContainer
import com.gemmark.app.runner.SessionState
import kotlinx.coroutines.flow.StateFlow

class RunViewModel(private val container: AppContainer) : ViewModel() {

    val session: StateFlow<SessionState> = container.sessionManager.state

    /** Starts the run once; re-entering the screen attaches to the running session. */
    fun ensureStarted(config: BenchmarkConfig) {
        val current = session.value
        if (!current.isActive && current.finishedRunId == null) {
            container.sessionManager.start(config)
        }
    }

    fun pause() = container.sessionManager.pause()
    fun resume() = container.sessionManager.resume()
    fun stop() = container.sessionManager.stop()

    /** Called after navigating away from a finished run. */
    fun acknowledgeFinished() = container.sessionManager.reset()
}
