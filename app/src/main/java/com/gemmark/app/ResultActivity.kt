package com.gemmark.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.gemmark.app.ui.result.ResultScreen
import com.gemmark.app.ui.theme.GemmarkTheme

class ResultActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val runId = intent.getStringExtra(EXTRA_RUN_ID)
        if (runId == null) {
            finish()
            return
        }

        setContent {
            GemmarkTheme {
                ResultScreen(
                    runId = runId,
                    onBack = { finish() },
                )
            }
        }
    }

    companion object {
        private const val EXTRA_RUN_ID = "run_id"

        fun intent(context: Context, runId: String): Intent =
            Intent(context, ResultActivity::class.java).putExtra(EXTRA_RUN_ID, runId)
    }
}
