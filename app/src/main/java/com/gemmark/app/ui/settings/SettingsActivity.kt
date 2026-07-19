package com.gemmark.app.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.WavingHand
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.core.net.toUri
import com.gemmark.app.BuildConfig
import com.gemmark.app.R
import com.gemmark.app.core.stats.ScoreCalculator
import com.gemmark.app.ui.theme.GemmarkTheme
import com.gemmark.app.ui.theme.GoogleSansCode
import com.gemmark.app.ui.welcome.WelcomeActivity

class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GemmarkTheme {
                SettingsScreen(onBack = { finish() })
            }
        }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, SettingsActivity::class.java)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
        ) {
            item {
                SettingsItem(
                    icon = { Icon(Icons.Filled.WavingHand, contentDescription = null, Modifier.size(22.dp)) },
                    title = stringResource(R.string.settings_setup_guide),
                    subtitle = stringResource(R.string.settings_setup_guide_subtitle),
                    onClick = { context.startActivity(WelcomeActivity.intent(context)) },
                )
            }
            item {
                SettingsItem(
                    icon = { Icon(Icons.Filled.Groups, contentDescription = null, Modifier.size(22.dp)) },
                    title = stringResource(R.string.settings_group_title),
                    subtitle = stringResource(R.string.settings_group_subtitle),
                    trailingExternal = true,
                    onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, "https://groups.google.com/g/aicore-experimental".toUri()),
                        )
                    },
                )
            }
            item {
                SettingsItem(
                    icon = { Icon(Icons.Filled.Science, contentDescription = null, Modifier.size(22.dp)) },
                    title = stringResource(R.string.settings_testing_title),
                    subtitle = stringResource(R.string.settings_testing_subtitle),
                    trailingExternal = true,
                    onClick = {
                        context.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                "https://play.google.com/apps/testing/com.google.android.aicore".toUri(),
                            ),
                        )
                    },
                )
            }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        "Gemmark ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.settings_anchor_info, ScoreCalculator.REFERENCE_ID),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = GoogleSansCode),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsItem(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    trailingExternal: Boolean = false,
    onClick: () -> Unit,
) {
    ListItem(
        leadingContent = icon,
        headlineContent = { Text(title) },
        supportingContent = {
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        },
        trailingContent = if (trailingExternal) {
            {
                Icon(
                    Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    Modifier.size(16.dp),
                )
            }
        } else {
            null
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.clickable(onClick = onClick),
    )
}
