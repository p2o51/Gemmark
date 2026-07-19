package com.gemmark.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.gemmark.app.ui.home.HomeScreen
import com.gemmark.app.ui.settings.SettingsActivity
import com.gemmark.app.ui.theme.GemmarkTheme
import com.gemmark.app.ui.welcome.WelcomeActivity

/**
 * One activity per destination (Home / Run / Result) so the system's native
 * predictive back gesture and cross-activity animations apply.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (savedInstanceState == null && !WelcomeActivity.isWelcomeDone(this)) {
            startActivity(WelcomeActivity.intent(this))
        }

        setContent {
            GemmarkTheme {
                HomeScreen(
                    onStartBenchmark = { config -> startActivity(RunActivity.intent(this, config)) },
                    onOpenRun = { runId -> startActivity(ResultActivity.intent(this, runId)) },
                    onOpenDeviceRun = { id -> startActivity(DeviceResultActivity.intent(this, id)) },
                    onResumeActiveRun = { config -> startActivity(RunActivity.intent(this, config)) },
                    onOpenSettings = { startActivity(SettingsActivity.intent(this)) },
                )
            }
        }
    }
}
