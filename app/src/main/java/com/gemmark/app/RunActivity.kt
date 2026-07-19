package com.gemmark.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.gemmark.app.core.model.BenchmarkConfig
import com.gemmark.app.ui.run.RunScreen
import com.gemmark.app.ui.theme.GemmarkTheme
import kotlinx.serialization.json.Json

class RunActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // A benchmark must survive an accidental power-button press: keep the
        // run visible above the keyguard and wake the screen when launched.
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        val config = intent.getStringExtra(EXTRA_CONFIG)
            ?.let { runCatching { Json.decodeFromString<BenchmarkConfig>(it) }.getOrNull() }
        if (config == null) {
            finish()
            return
        }

        setContent {
            GemmarkTheme {
                RunScreen(
                    config = config,
                    onFinished = { runId ->
                        startActivity(ResultActivity.intent(this, runId))
                        finish()
                    },
                    onFinishedDeviceRun = { deviceRunId ->
                        startActivity(DeviceResultActivity.intent(this, deviceRunId))
                        finish()
                    },
                    onExit = { finish() },
                )
            }
        }
    }

    companion object {
        private const val EXTRA_CONFIG = "config_json"

        fun intent(context: Context, config: BenchmarkConfig): Intent =
            Intent(context, RunActivity::class.java)
                .putExtra(EXTRA_CONFIG, Json.encodeToString(BenchmarkConfig.serializer(), config))
    }
}
