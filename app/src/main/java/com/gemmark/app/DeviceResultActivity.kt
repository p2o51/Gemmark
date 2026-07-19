package com.gemmark.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.gemmark.app.ui.result.DeviceResultScreen
import com.gemmark.app.ui.theme.GemmarkTheme

/** Composite result of one Standard Test session: both models + Device Score. */
class DeviceResultActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val deviceRunId = intent.getStringExtra(EXTRA_DEVICE_RUN_ID)
        if (deviceRunId == null) {
            finish()
            return
        }

        setContent {
            GemmarkTheme {
                DeviceResultScreen(
                    deviceRunId = deviceRunId,
                    onOpenRun = { runId -> startActivity(ResultActivity.intent(this, runId)) },
                    onBack = { finish() },
                )
            }
        }
    }

    companion object {
        private const val EXTRA_DEVICE_RUN_ID = "device_run_id"

        fun intent(context: Context, deviceRunId: String): Intent =
            Intent(context, DeviceResultActivity::class.java).putExtra(EXTRA_DEVICE_RUN_ID, deviceRunId)
    }
}
