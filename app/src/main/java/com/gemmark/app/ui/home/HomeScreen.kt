package com.gemmark.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Assignment
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import com.gemmark.app.ui.theme.GoogleSansCode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gemmark.app.R
import com.gemmark.app.core.model.Backend
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.gemmark.app.ui.appViewModel
import com.gemmark.app.core.model.BenchmarkConfig
import com.gemmark.app.core.model.RunReport
import com.gemmark.app.core.model.RunStatus
import com.gemmark.app.runner.SessionState
import com.gemmark.app.telemetry.PreflightCheck
import com.gemmark.app.telemetry.PreflightChecker
import com.gemmark.app.ui.components.EmptyState
import com.gemmark.app.ui.components.GemmarkCardShape
import com.gemmark.app.ui.components.GemmarkWordmark
import com.gemmark.app.ui.components.SectionCard
import com.gemmark.app.ui.theme.GemmarkTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onStartBenchmark: (BenchmarkConfig) -> Unit,
    onOpenRun: (String) -> Unit,
    onOpenDeviceRun: (String) -> Unit,
    onResumeActiveRun: (BenchmarkConfig) -> Unit,
    onOpenSettings: () -> Unit = {},
    viewModel: HomeViewModel = appViewModel { HomeViewModel(it) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var showStartSheet by remember { mutableStateOf(false) }

    // Refresh history/preflight every time this activity returns to the front
    // (multi-activity: Home stays composed underneath Run/Result).
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { GemmarkWordmark() },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            Icons.Outlined.Settings,
                            contentDescription = stringResource(R.string.home_settings),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                // Engine availability must be known before the sheet decides the
                // model sequence — a tap during the initial refresh would see an
                // empty engine list and wrongly fall back to the mock demo.
                onClick = { if (!state.loading) showStartSheet = true },
                icon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                text = { Text(stringResource(R.string.home_run_benchmark)) },
            )
        },
    ) { padding ->
        if (state.loading) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        LazyColumn(
            // True edge-to-edge: content scrolls under the system bars.
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (session.isActive) {
                item { ActiveRunBanner(session, onResumeActiveRun) }
            }

            val bestDevice = state.bestDeviceScore
            if (bestDevice != null) {
                item { BestDeviceScoreCard(bestDevice, onClick = { onOpenDeviceRun(bestDevice.deviceRunId) }) }
            } else {
                state.bestScore?.let { best ->
                    item { BestScoreCard(best, onClick = { onOpenRun(best.runId) }) }
                }
            }

            item { DeviceCard(state) }
            item { PreflightCard(state.preflight) }

            item {
                Row(
                    Modifier.padding(top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.History,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.home_recent_runs), style = MaterialTheme.typography.titleMedium)
                }
            }

            if (state.runs.isEmpty() && state.deviceRuns.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Outlined.Assignment,
                        title = stringResource(R.string.home_no_runs_title),
                        caption = stringResource(R.string.home_no_runs_caption),
                    )
                }
            } else {
                items(state.deviceRuns, key = { it.deviceRunId }) { run ->
                    DeviceRunHistoryCard(run, onClick = { onOpenDeviceRun(run.deviceRunId) })
                }
                items(state.runs, key = { it.runId }) { run ->
                    RunHistoryCard(run, onClick = { onOpenRun(run.runId) })
                }
            }
        }
    }

    if (showStartSheet) {
        StartBenchmarkSheet(
            engines = state.engines,
            preflight = state.preflight,
            onDismiss = { showStartSheet = false },
            onStart = { engineIds ->
                showStartSheet = false
                onStartBenchmark(
                    BenchmarkConfig(
                        engineId = engineIds.first(),
                        engineIds = engineIds,
                        backend = Backend.NPU,
                    ),
                )
            },
        )
    }
}

/**
 * No choices: the Standard Test runs BOTH Gemma-4 targets (Preview·Fast, then
 * Preview·Full) and combines them into one Device Score. The sheet only shows
 * what will run and whether the models are ready.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StartBenchmarkSheet(
    engines: List<EngineStatusUi>,
    preflight: List<PreflightCheck>,
    onDismiss: () -> Unit,
    onStart: (List<String>) -> Unit,
) {
    val fast = engines.firstOrNull { it.id == "aicore-nano-preview-fast" }
    val full = engines.firstOrNull { it.id == "aicore-nano-preview-full" }
    val sequence = listOfNotNull(fast, full).filter { it.available }.map { it.id }
    // Emulators / non-AICore devices: fall back to a simulated demo run.
    val runIds = sequence.ifEmpty { listOf("mock") }
    val isMockFallback = sequence.isEmpty()
    var showPreflightDialog by remember { mutableStateOf(false) }
    val failing = preflight.filter { it.automatic && it.state == PreflightCheck.State.FAIL }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp)) {
            Text(stringResource(R.string.sheet_standard_test), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.sheet_standard_test_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            listOfNotNull(fast, full).forEach { engine ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AvailabilityDot(engine.available)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            engine.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (engine.available) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                        )
                        Text(
                            engine.modelName?.let { "$it · ${engine.detail}" } ?: engine.detail,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = if (engine.modelName != null) {
                                    GoogleSansCode
                                } else {
                                    FontFamily.Default
                                },
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            when {
                isMockFallback -> Text(
                    stringResource(R.string.sheet_mock_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                sequence.size == 1 -> Text(
                    stringResource(R.string.sheet_partial_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    if (failing.isEmpty()) onStart(runIds) else showPreflightDialog = true
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp),
            ) {
                Text(stringResource(R.string.sheet_start))
            }
        }
    }

    if (showPreflightDialog) {
        AlertDialog(
            onDismissRequest = { showPreflightDialog = false },
            icon = { Icon(Icons.Filled.Warning, contentDescription = null) },
            title = { Text(stringResource(R.string.dialog_preflight_title)) },
            text = {
                Column {
                    failing.forEach {
                        Text(
                            stringResource(R.string.dialog_preflight_item, preflightLabel(it), it.detail),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.dialog_preflight_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showPreflightDialog = false
                    onStart(runIds)
                }) { Text(stringResource(R.string.dialog_preflight_run_anyway)) }
            },
            dismissButton = {
                TextButton(onClick = { showPreflightDialog = false }) {
                    Text(stringResource(R.string.dialog_preflight_cancel))
                }
            },
        )
    }
}

@Composable
private fun ActiveRunBanner(session: SessionState, onResume: (BenchmarkConfig) -> Unit) {
    val config = session.config ?: return
    Card(
        onClick = { onResume(config) },
        shape = GemmarkCardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.home_benchmark_in_progress), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.home_active_run_detail, session.engineName, session.currentRound),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(stringResource(R.string.home_open), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Device-AI hero: best Gemmark Score achieved on this device (G3 leaderboard runs). */
@Composable
private fun BestScoreCard(best: BestScoreUi, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = GemmarkCardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.home_gemmark_score),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    best.modelName,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = GoogleSansCode),
                )
            }
            Text(
                "%,d".format(best.score),
                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
            )
        }
    }
}

@Composable
private fun DeviceCard(state: HomeUiState) {
    SectionCard(title = stringResource(R.string.home_device), icon = Icons.Outlined.PhoneAndroid) {
        val device = state.device
        if (device != null) {
            Text(device.model, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (device.aicoreVersion.isNotEmpty()) {
                    stringResource(
                        R.string.home_device_build_aicore,
                        device.build,
                        device.androidSdk,
                        device.aicoreVersion,
                    )
                } else {
                    stringResource(R.string.home_device_build, device.build, device.androidSdk)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(12.dp))
        state.engines.forEach { engine ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Memory,
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
                        // Surface the concrete model each variant resolves to —
                        // Stable vs Preview is meaningless without nano-v3/v4 names.
                        engine.modelName?.let { "$it · ${engine.detail}" } ?: engine.detail,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = if (engine.modelName != null) GoogleSansCode else FontFamily.Default,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AvailabilityDot(engine.available)
            }
        }
    }
}

@Composable
private fun AvailabilityDot(available: Boolean) {
    val color = if (available) GemmarkTheme.extended.success else MaterialTheme.colorScheme.outlineVariant
    Surface(
        modifier = Modifier.size(10.dp),
        shape = MaterialTheme.shapes.small,
        color = color,
    ) {}
}

/**
 * Localized display label for a preflight check, keyed by the stable check id.
 * The model-provided [PreflightCheck.detail] stays as-is (technical detail with
 * live numbers, English by design so it matches saved reports).
 */
@Composable
private fun preflightLabel(check: PreflightCheck): String = when (check.id) {
    "battery" -> stringResource(R.string.preflight_battery, PreflightChecker.BATTERY_MIN)
    "charging" -> stringResource(R.string.preflight_charging)
    "thermal" -> stringResource(R.string.preflight_thermal)
    "powersave" -> stringResource(R.string.preflight_powersave)
    "brightness" -> stringResource(R.string.preflight_brightness)
    "background" -> stringResource(R.string.preflight_background)
    "room" -> stringResource(R.string.preflight_room)
    else -> check.label
}

@Composable
private fun PreflightCard(checks: List<PreflightCheck>) {
    SectionCard(title = stringResource(R.string.home_test_conditions), icon = Icons.Filled.CheckCircle) {
        checks.forEach { check ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val (icon, tint) = when (check.state) {
                    PreflightCheck.State.PASS ->
                        Icons.Filled.CheckCircle to GemmarkTheme.extended.success
                    PreflightCheck.State.WARN ->
                        Icons.Filled.Warning to GemmarkTheme.extended.warning
                    PreflightCheck.State.FAIL ->
                        Icons.Filled.Error to MaterialTheme.colorScheme.error
                    PreflightCheck.State.MANUAL ->
                        Icons.Filled.RadioButtonUnchecked to MaterialTheme.colorScheme.outline
                }
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = tint)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(preflightLabel(check), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        check.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun BestDeviceScoreCard(best: BestDeviceScoreUi, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = GemmarkCardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.home_device_score),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    listOfNotNull(
                        best.fastScore?.let { "Fast %,d".format(it) },
                        best.fullScore?.let { "Full %,d".format(it) },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = GoogleSansCode),
                )
                if (best.coverage != "complete") {
                    Text(
                        stringResource(
                            if (best.coverage == "mock") R.string.home_coverage_mock else R.string.home_coverage_partial,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f),
                    )
                }
            }
            Text(
                "%,d".format(best.score),
                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
            )
        }
    }
}

@Composable
private fun DeviceRunHistoryCard(run: com.gemmark.app.core.model.DeviceRun, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = GemmarkCardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        ),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.home_device_run_title), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(2.dp))
                Text(
                    listOfNotNull(
                        run.fastModel.takeIf { it.isNotEmpty() },
                        run.fullModel.takeIf { it.isNotEmpty() },
                    ).joinToString(" + ").ifEmpty { run.suiteVersion } +
                        " · " + formatTimestamp(run.timestamp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (run.coverage != "complete") {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(
                            if (run.coverage == "mock") R.string.home_coverage_mock else R.string.home_coverage_partial,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = GemmarkTheme.extended.warning,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    run.deviceScore?.let { "%,d".format(it) } ?: "—",
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    stringResource(R.string.home_device_score),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RunHistoryCard(run: RunReport, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = GemmarkCardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        ),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(run.model.name, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(2.dp))
                Text(
                    "G${run.config.promptGroup} · ${run.config.requestedBackend.name} · ${formatTimestamp(run.timestamp)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (run.runStatus != RunStatus.COMPLETED) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        when (run.runStatus) {
                            RunStatus.NEEDS_RETEST -> stringResource(R.string.home_status_needs_retest)
                            RunStatus.ABORTED -> stringResource(R.string.home_status_aborted)
                            RunStatus.FAILED -> stringResource(R.string.home_status_failed)
                            RunStatus.COMPLETED -> ""
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = GemmarkTheme.extended.warning,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    run.summary?.let { "%.1f".format(it.decodeTpsMedian) } ?: "—",
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    stringResource(R.string.home_toks_median),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun formatTimestamp(iso: String): String = try {
    val instant = Instant.parse(iso)
    DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault()).format(instant)
} catch (_: Exception) {
    iso.take(16)
}
