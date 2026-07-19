package com.gemmark.app.ui.welcome

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gemmark.app.R
import com.gemmark.app.ui.appViewModel
import com.gemmark.app.ui.components.GemmarkCardShape
import com.gemmark.app.ui.components.GemmarkWordmark
import com.gemmark.app.ui.theme.GemmarkTheme
import com.gemmark.app.ui.theme.GoogleSansCode

/**
 * Onboarding: how to get Gemini Nano preview models onto this device.
 * Enrollment steps follow Google's AICore Developer Preview program:
 * https://developers.google.com/ml-kit/genai/aicore-dev-preview
 */
class WelcomeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GemmarkTheme {
                WelcomeScreen(
                    onDone = {
                        markWelcomeDone(this)
                        finish()
                    },
                )
            }
        }
    }

    companion object {
        private const val PREFS = "gemmark"
        private const val KEY_WELCOME_DONE = "welcome_done"

        fun intent(context: Context): Intent = Intent(context, WelcomeActivity::class.java)

        fun isWelcomeDone(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_WELCOME_DONE, false)

        fun markWelcomeDone(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_WELCOME_DONE, true).apply()
        }
    }
}

private const val GROUP_URL = "https://groups.google.com/g/aicore-experimental"
private const val TESTING_URL = "https://play.google.com/apps/testing/com.google.android.aicore"

@Composable
private fun WelcomeScreen(
    onDone: () -> Unit,
    viewModel: WelcomeViewModel = appViewModel { WelcomeViewModel(it) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 24.dp,
                end = 24.dp,
                top = padding.calculateTopPadding() + 40.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    GemmarkWordmark()
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.welcome_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(18.dp))
                }
            }

            item {
                StepCard(
                    step = "1",
                    icon = Icons.Filled.Groups,
                    title = stringResource(R.string.welcome_step1_title),
                    body = stringResource(R.string.welcome_step1_body),
                ) {
                    val context = androidx.compose.ui.platform.LocalContext.current
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, GROUP_URL.toUri()))
                        }) {
                            Text(stringResource(R.string.welcome_google_group))
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, Modifier.size(14.dp))
                        }
                        OutlinedButton(onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, TESTING_URL.toUri()))
                        }) {
                            Text(stringResource(R.string.welcome_become_tester))
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, Modifier.size(14.dp))
                        }
                    }
                }
            }

            item {
                StepCard(
                    step = "2",
                    icon = Icons.Filled.CloudDownload,
                    title = stringResource(R.string.welcome_step2_title),
                    body = stringResource(R.string.welcome_step2_body),
                ) {
                    if (state.loading) {
                        CircularProgressIndicator(Modifier.size(22.dp))
                    }
                    state.engines.forEach { engine ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (engine.available) Icons.Filled.CheckCircle else Icons.Filled.Memory,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = if (engine.available) {
                                    GemmarkTheme.extended.success
                                } else {
                                    MaterialTheme.colorScheme.outline
                                },
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(engine.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    if (state.downloadingId == engine.id) {
                                        state.downloadProgress
                                    } else {
                                        engine.modelName?.let { "$it · ${engine.detail}" } ?: engine.detail
                                    },
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = GoogleSansCode),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (!engine.available && engine.detail.contains("download", ignoreCase = true)) {
                                TextButton(
                                    onClick = { viewModel.download(engine.id) },
                                    enabled = state.downloadingId == null,
                                ) { Text(stringResource(R.string.welcome_download)) }
                            }
                        }
                    }
                }
            }

            item {
                StepCard(
                    step = "3",
                    icon = Icons.Filled.Speed,
                    title = stringResource(R.string.welcome_step3_title),
                    body = stringResource(R.string.welcome_step3_body),
                    content = null,
                )
            }

            item {
                Spacer(Modifier.height(6.dp))
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.welcome_get_started))
                }
            }
        }
    }
}

@Composable
private fun StepCard(
    step: String,
    icon: ImageVector,
    title: String,
    body: String,
    content: (@Composable () -> Unit)?,
) {
    Card(
        shape = GemmarkCardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    step,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontFamily = GoogleSansCode,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (content != null) {
                Spacer(Modifier.height(12.dp))
                content()
            }
        }
    }
}
