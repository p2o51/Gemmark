package com.gemmark.app.ui.run

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DeviceThermostat
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.Image
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gemmark.app.R
import com.gemmark.app.core.model.BenchmarkConfig
import com.gemmark.app.ui.appViewModel
import com.gemmark.app.runner.SessionState
import com.gemmark.app.ui.components.DotPill
import com.gemmark.app.ui.components.GemmarkWordmark
import com.gemmark.app.ui.components.LogConsole
import com.gemmark.app.ui.components.ProgressRing
import com.gemmark.app.ui.components.SectionCard
import com.gemmark.app.ui.components.Sparkline
import com.gemmark.app.ui.components.StatCard
import com.gemmark.app.ui.theme.ConsoleTextStyle
import com.gemmark.app.ui.theme.GemmarkTheme
import com.gemmark.app.ui.theme.MetricHeroStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunScreen(
    config: BenchmarkConfig,
    onFinished: (String) -> Unit,
    onFinishedDeviceRun: (String) -> Unit,
    onExit: () -> Unit,
    viewModel: RunViewModel = appViewModel { RunViewModel(it) },
) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    var showStopDialog by remember { mutableStateOf(false) }

    LaunchedEffect(config) { viewModel.ensureStarted(config) }

    // Keep the screen on for the duration of the run (spec: app must stay foreground).
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // Navigate to the report as soon as the run is persisted. A composite
    // device run (both models) wins over the last single-model report.
    LaunchedEffect(session.finishedRunId, session.finishedDeviceRunId) {
        val deviceRunId = session.finishedDeviceRunId
        val runId = session.finishedRunId
        when {
            deviceRunId != null -> {
                viewModel.acknowledgeFinished()
                onFinishedDeviceRun(deviceRunId)
            }
            runId != null -> {
                viewModel.acknowledgeFinished()
                onFinished(runId)
            }
        }
    }

    // Terminal state without a saved report (engine failure and/or save failure):
    // capture the message before reset() wipes it, and show it in a dialog.
    var terminalError by remember { mutableStateOf<String?>(null) }
    val noReportMessage = stringResource(R.string.run_no_report_saved)
    LaunchedEffect(session.phase) {
        val terminalWithoutReport = !session.isActive &&
            session.phase != SessionState.Phase.IDLE &&
            session.finishedRunId == null
        if (terminalWithoutReport) {
            terminalError = session.fatalError ?: noReportMessage
        }
    }

    val isActive = session.isActive
    BackHandler(enabled = isActive) { showStopDialog = true }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { GemmarkWordmark() },
            )
        },
    ) { padding ->
        LazyColumn(
            // True edge-to-edge: content scrolls under the system bars; the
            // insets only pad the scroll range.
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        when (session.phase) {
                            SessionState.Phase.PREPARING -> stringResource(R.string.run_headline_loading)
                            SessionState.Phase.WARMUP -> stringResource(R.string.run_headline_warmup)
                            SessionState.Phase.MEASURING -> stringResource(R.string.run_headline_testing)
                            SessionState.Phase.PAUSED -> stringResource(R.string.run_headline_paused)
                            SessionState.Phase.COOLDOWN -> stringResource(R.string.run_headline_cooldown)
                            SessionState.Phase.SAVING -> stringResource(R.string.run_headline_saving)
                            else -> stringResource(R.string.run_headline_default)
                        },
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        (if (session.modelCount > 1) {
                            stringResource(R.string.run_model_step, session.modelIndex, session.modelCount) + " · "
                        } else {
                            ""
                        }) + session.engineName +
                            if (!session.isWarmupStage && session.phaseCount > 0) {
                                " · " + stringResource(R.string.run_phase, session.phaseIndex, session.phaseCount)
                            } else {
                                ""
                            },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))

                    // Hero: the live generation IS the "testing in progress" visual —
                    // model output streams behind the ring, auto-scrolling, with a
                    // translucent disc keeping the ring legible.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(300.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        LiveOutputBackdrop(
                            thought = session.liveThought,
                            answer = session.liveOutput,
                            modifier = Modifier.matchParentSize(),
                        )
                        Box(
                            Modifier
                                .size(212.dp)
                                .background(
                                    MaterialTheme.colorScheme.background.copy(alpha = 0.78f),
                                    CircleShape,
                                ),
                        )
                        ProgressRing(
                            progress = session.overallProgress,
                            modifier = Modifier.size(220.dp),
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    if (session.isWarmupStage) {
                                        stringResource(R.string.run_ring_warmup, session.currentRound)
                                    } else {
                                        session.phaseLabel.ifEmpty {
                                            stringResource(R.string.run_ring_round, session.currentRound)
                                        }
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "${(session.overallProgress * 100).toInt()}%",
                                    style = MetricHeroStyle,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.height(6.dp))
                                PhaseChip(session.phase)
                            }
                        }
                    }

                    if (session.liveTask.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (session.liveThought.isNotEmpty()) {
                                Icon(
                                    Icons.Filled.AutoAwesome,
                                    contentDescription = stringResource(R.string.run_thinking_indicator),
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.tertiary,
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(
                                session.liveTask,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }

                    // Vision rounds: show exactly what the model is looking at.
                    if (session.liveImageCount > 0) {
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Image(
                                painter = painterResource(R.drawable.gemmark_test_scene),
                                contentDescription = stringResource(R.string.run_test_image_desc),
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(RoundedCornerShape(12.dp)),
                            )
                            if (session.liveImageCount > 1) {
                                Image(
                                    painter = painterResource(R.drawable.gemmark_test_scene_b),
                                    contentDescription = stringResource(R.string.run_test_image_desc),
                                    modifier = Modifier
                                        .size(64.dp)
                                        .clip(RoundedCornerShape(12.dp)),
                                )
                            }
                        }
                    }
                }
            }

            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCard(
                        icon = Icons.Filled.Timer,
                        label = stringResource(R.string.run_stat_ttft),
                        value = session.lastTtftMs?.let { "%.0f".format(it) } ?: "—",
                        unit = stringResource(R.string.unit_ms),
                        modifier = Modifier.weight(1f),
                    )
                    StatCard(
                        icon = Icons.Filled.Speed,
                        label = stringResource(R.string.run_stat_decode),
                        value = session.lastDecodeTps?.let { "%.1f".format(it) } ?: "—",
                        unit = stringResource(R.string.unit_tok_s),
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (session.liveResults.isNotEmpty()) {
                item {
                    SectionCard(title = stringResource(R.string.run_live_results), icon = Icons.Filled.Speed) {
                        session.liveResults.forEach { line ->
                            Text(
                                line,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(vertical = 3.dp),
                            )
                        }
                    }
                }
            }

            item {
                SectionCard(
                    title = stringResource(R.string.run_thermal),
                    icon = Icons.Filled.DeviceThermostat,
                    trailing = {
                        TrailingValuePill(
                            text = "%.1f°C".format(session.currentTempC),
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    },
                ) {
                    Sparkline(
                        values = session.tempSeries,
                        color = GemmarkTheme.extended.chartThermal,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(72.dp),
                    )
                    if (session.thermalStatus.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.run_thermal_status, session.thermalStatus),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                SectionCard(
                    title = stringResource(R.string.run_power_draw),
                    icon = Icons.Filled.Bolt,
                    trailing = {
                        TrailingValuePill(
                            text = "%.1f W".format(session.currentPowerW),
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    },
                ) {
                    Sparkline(
                        values = session.powerSeries,
                        color = GemmarkTheme.extended.chartPower,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(72.dp),
                    )
                }
            }

            item {
                SectionCard(title = stringResource(R.string.run_activity_log)) {
                    LogConsole(
                        entries = session.logs.takeLast(30),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp),
                    )
                }
            }

            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    val paused = session.phase == SessionState.Phase.PAUSED
                    OutlinedButton(
                        onClick = { if (paused) viewModel.resume() else viewModel.pause() },
                        enabled = isActive && session.phase != SessionState.Phase.PREPARING,
                        shape = RoundedCornerShape(50),
                    ) {
                        Icon(
                            if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(if (paused) stringResource(R.string.run_resume) else stringResource(R.string.run_pause))
                    }
                    Spacer(Modifier.width(16.dp))
                    OutlinedButton(
                        onClick = { showStopDialog = true },
                        enabled = isActive,
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                        border = BorderStroke(
                            1.dp,
                            if (isActive) {
                                MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                        ),
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.run_stop))
                    }
                }
            }
        }
    }

    terminalError?.let { message ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text(stringResource(R.string.dialog_run_failed_title)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = {
                    terminalError = null
                    viewModel.acknowledgeFinished()
                    onExit()
                }) { Text(stringResource(R.string.dialog_ok)) }
            },
        )
    }

    if (showStopDialog) {
        AlertDialog(
            onDismissRequest = { showStopDialog = false },
            title = { Text(stringResource(R.string.dialog_stop_title)) },
            text = { Text(stringResource(R.string.dialog_stop_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showStopDialog = false
                    viewModel.stop()
                }) { Text(stringResource(R.string.dialog_stop_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showStopDialog = false }) { Text(stringResource(R.string.dialog_stop_keep)) }
            },
        )
    }
}

/** Small mono value pill used as a SectionCard trailing badge (e.g. "78.0°C"). */
@Composable
private fun TrailingValuePill(
    text: String,
    containerColor: Color,
    contentColor: Color,
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

private data class PillStyle(
    val dot: Color,
    val container: Color,
    val content: Color,
    val label: String,
)

/**
 * The model's live output as an animated backdrop: Google Sans Code text
 * auto-crawling upward as tokens stream in, fading out at the top and bottom
 * edges so the progress ring floats over a river of generated text.
 * Thinking-mode reasoning renders as a visually distinct italic tertiary
 * stream ahead of the answer.
 */
@Composable
private fun LiveOutputBackdrop(thought: String, answer: String, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    LaunchedEffect(thought.length + answer.length) {
        scroll.animateScrollTo(
            scroll.maxValue,
            animationSpec = tween(durationMillis = 350, easing = LinearEasing),
        )
    }
    val bg = MaterialTheme.colorScheme.background
    val thoughtColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.65f)
    val answerColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    val streamText = buildAnnotatedString {
        if (thought.isNotEmpty()) {
            withStyle(SpanStyle(color = thoughtColor, fontStyle = FontStyle.Italic)) {
                append("✦ ")
                append(thought)
            }
            if (answer.isNotEmpty()) append("\n\n")
        }
        withStyle(SpanStyle(color = answerColor)) {
            append(answer)
        }
    }
    Box(modifier) {
        Column(
            Modifier
                .matchParentSize()
                .verticalScroll(scroll, enabled = false)
                .padding(horizontal = 6.dp),
        ) {
            Spacer(Modifier.height(120.dp))
            Text(
                streamText,
                style = ConsoleTextStyle,
            )
            Spacer(Modifier.height(120.dp))
        }
        // Fade the text into the page background at both edges.
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0.00f to bg,
                        0.22f to bg.copy(alpha = 0f),
                        0.78f to bg.copy(alpha = 0f),
                        1.00f to bg,
                    ),
                ),
        )
    }
}

@Composable
private fun PhaseChip(phase: SessionState.Phase) {
    val ext = GemmarkTheme.extended
    val neutral = PillStyle(
        dot = MaterialTheme.colorScheme.outline,
        container = MaterialTheme.colorScheme.surfaceContainerHighest,
        content = MaterialTheme.colorScheme.onSurfaceVariant,
        label = phase.name,
    )
    val style = when (phase) {
        SessionState.Phase.PREPARING -> neutral.copy(label = stringResource(R.string.chip_phase_loading))
        SessionState.Phase.WARMUP -> PillStyle(
            ext.warning,
            ext.warningContainer,
            ext.onWarningContainer,
            stringResource(R.string.chip_phase_warmup),
        )
        SessionState.Phase.MEASURING -> PillStyle(
            ext.success,
            ext.successContainer,
            ext.onSuccessContainer,
            stringResource(R.string.chip_phase_measuring),
        )
        SessionState.Phase.PAUSED -> neutral.copy(label = stringResource(R.string.chip_phase_paused))
        SessionState.Phase.SAVING -> PillStyle(
            ext.success,
            ext.successContainer,
            ext.onSuccessContainer,
            stringResource(R.string.chip_phase_saving),
        )
        else -> neutral
    }
    DotPill(
        label = style.label,
        dotColor = style.dot,
        containerColor = style.container,
        contentColor = style.content,
    )
}
