package com.gemmark.app.ui.result

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.DeviceThermostat
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SsidChart
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import com.gemmark.app.ui.theme.GoogleSansCode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gemmark.app.R
import com.gemmark.app.ui.appViewModel
import kotlinx.coroutines.launch
import com.gemmark.app.core.model.RoundResult
import com.gemmark.app.core.model.RunReport
import com.gemmark.app.core.model.RunStatus
import com.gemmark.app.data.Exporters
import com.gemmark.app.ui.components.DecayLineChart
import com.gemmark.app.ui.components.GemmarkCardShape
import com.gemmark.app.ui.components.RoundBarsChart
import com.gemmark.app.ui.components.StatCard
import com.gemmark.app.ui.components.StatusChip
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultScreen(
    runId: String,
    onBack: () -> Unit,
    viewModel: ResultViewModel = appViewModel(key = runId) { ResultViewModel(it, runId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var showExportDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.result_back))
                    }
                },
            )
        },
    ) { padding ->
        val report = state.report
        when {
            state.loading -> {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(padding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator() }
            }

            report == null -> {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(padding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(stringResource(R.string.result_report_not_found), style = MaterialTheme.typography.titleMedium)
                }
            }

            else -> {
                ResultContent(
                    report = report,
                    padding = padding,
                    onExport = { showExportDialog = true },
                    onShare = { Exporters.shareSummary(context, report) },
                )
            }
        }
    }

    if (showExportDialog) {
        val report = state.report ?: return
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text(stringResource(R.string.dialog_export_title)) },
            text = { Text(stringResource(R.string.dialog_export_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showExportDialog = false
                    scope.launch { Exporters.share(context, report, Exporters.Format.JSON) }
                }) { Text(stringResource(R.string.dialog_export_json)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showExportDialog = false
                    scope.launch { Exporters.share(context, report, Exporters.Format.CSV) }
                }) { Text(stringResource(R.string.dialog_export_csv)) }
            },
        )
    }
}

@Composable
private fun ResultContent(
    report: RunReport,
    padding: androidx.compose.foundation.layout.PaddingValues,
    onExport: () -> Unit,
    onShare: () -> Unit,
) {
    LazyColumn(
        // True edge-to-edge: content scrolls under the system bars.
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
            Column {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ) {
                    Text(
                        stringResource(R.string.result_report_id, report.reportCode),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.result_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    report.model.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "${formatTimestamp(report.timestamp)} · ${report.device.model} · " +
                        (
                            if (report.config.mode.isNotEmpty()) {
                                stringResource(R.string.result_standard_test)
                            } else {
                                "G${report.config.promptGroup}"
                            }
                            ) +
                        " · ${report.config.requestedBackend.name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (report.runStatus != RunStatus.COMPLETED) {
            item { RunStatusBanner(report) }
        }

        report.score?.let { score ->
            item { ScoreHeroCard(score) }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onExport, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.FileDownload, contentDescription = null, Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.result_export))
                }
                Button(onClick = onShare, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Share, contentDescription = null, Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.result_share))
                }
            }
        }

        val summary = report.summary
        if (summary != null) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatCard(
                            icon = Icons.Filled.Speed,
                            label = stringResource(R.string.result_median_decode),
                            value = "%.1f".format(summary.decodeTpsMedian),
                            unit = "tok/s",
                            modifier = Modifier.weight(1f),
                        )
                        StatCard(
                            icon = Icons.Filled.Functions,
                            label = stringResource(R.string.result_trimmed_mean),
                            value = "%.1f".format(summary.decodeTpsTrimmedMean),
                            unit = "tok/s",
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatCard(
                            icon = Icons.Filled.BarChart,
                            label = stringResource(R.string.result_std_dev),
                            value = "%.2f".format(summary.decodeTpsStdDev),
                            unit = "±",
                            modifier = Modifier.weight(1f),
                        )
                        StatCard(
                            icon = Icons.Filled.SsidChart,
                            label = stringResource(R.string.result_p10_p90),
                            value = "%.0f / %.0f".format(summary.decodeTpsP10, summary.decodeTpsP90),
                            unit = "tok/s",
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatCard(
                            icon = Icons.Filled.Timer,
                            // Clean rounds only: a BUSY-retried round's TTFT measures a
                            // cold restart (observed with nano-v4-full), not responsiveness.
                            label = if (summary.ttftMsMedianClean != null &&
                                summary.cleanRounds < summary.validRounds
                            ) {
                                stringResource(R.string.result_ttft_clean, summary.cleanRounds)
                            } else {
                                stringResource(R.string.result_ttft_median)
                            },
                            value = "%.0f".format(summary.ttftMsMedianClean ?: summary.ttftMsMedian),
                            unit = "ms",
                            modifier = Modifier.weight(1f),
                        )
                        StatCard(
                            icon = Icons.Filled.Thermostat,
                            label = stringResource(R.string.result_thermal_drop),
                            value = "%.0f".format(summary.thermalDrop * 100),
                            unit = "%",
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        if (report.workloadSummary.isNotEmpty()) {
            item {
                Card(
                    shape = GemmarkCardShape,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.result_workload_breakdown), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        report.workloadSummary.forEach { w ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    w.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.width(96.dp),
                                )
                                Text(
                                    "%.1f %s".format(w.metricValue, w.metricUnit) +
                                        (w.jsonValidRate?.let { " · JSON ${"%.0f".format(it * 100)}%" } ?: ""),
                                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = GoogleSansCode),
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    stringResource(R.string.result_rounds_ok, w.validRounds, w.rounds),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            // Decay series = the 256/256 workloads only; mixing prefill/json rounds
            // (different output lengths) into one line makes fake valleys.
            val decaySeries = report.rounds
                .filter { it.workload == "main" || it.workload == "sustained" }
                .ifEmpty { report.rounds }
            ChartCard(
                title = stringResource(R.string.result_chart_decay),
                badge = stringResource(R.string.result_rounds_count, decaySeries.size),
            ) {
                DecayLineChart(
                    values = decaySeries.map { it.decodeTps.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        item {
            val peak = report.rounds.maxOfOrNull { maxOf(it.tempStartC, it.tempEndC) }
            ChartCard(
                title = stringResource(R.string.result_chart_temp),
                badge = peak?.let { stringResource(R.string.result_max_value, "%.1f°C".format(it)) } ?: "",
                badgeIcon = Icons.Filled.DeviceThermostat,
                badgeColor = MaterialTheme.colorScheme.errorContainer,
                badgeContentColor = MaterialTheme.colorScheme.onErrorContainer,
            ) {
                RoundBarsChart(
                    values = report.rounds.map { it.tempEndC.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        item {
            RoundTable(rounds = report.rounds)
        }

        item {
            Column(Modifier.padding(top = 4.dp, bottom = 24.dp)) {
                MonoDetail(stringResource(R.string.result_detail_run_id), report.runId)
                MonoDetail(stringResource(R.string.result_detail_app), report.appVersion)
                MonoDetail(stringResource(R.string.result_detail_token_counter), report.config.tokenCounter)
                MonoDetail(stringResource(R.string.result_detail_model_load), "${report.modelLoadMs} ms")
                MonoDetail(stringResource(R.string.result_detail_sampling), report.config.sampling)
                if (report.summary != null) {
                    MonoDetail(
                        stringResource(R.string.result_detail_valid_rounds),
                        "${report.summary.validRounds}/${report.config.measuredRounds}",
                    )
                    val drop = report.summary.batteryDropPct
                    val charge = report.summary.chargeUsedMah
                    if (drop != null || charge != null) {
                        MonoDetail(
                            stringResource(R.string.result_detail_battery_drop),
                            listOfNotNull(
                                drop?.let { "%.0f%%".format(it) },
                                charge?.let { "%.0f mAh".format(it) },
                            ).joinToString(" · "),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Composite Gemmark Score hero: total + the four sub-scores as bars.
 * 1000 = frozen v1 reference (nano-v3 · Pixel 10 Pro · G3).
 */
@Composable
private fun ScoreHeroCard(score: com.gemmark.app.core.model.ScoreCard) {
    Card(
        shape = GemmarkCardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                stringResource(R.string.result_gemmark_score),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "%,d".format(score.total),
                style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(14.dp))

            val subs = buildList {
                add(stringResource(R.string.result_sub_decode) to score.decode)
                add(stringResource(R.string.result_sub_prefill) to score.prefill)
                add(stringResource(R.string.result_sub_response) to score.response)
                score.reasoning?.let { add(stringResource(R.string.result_sub_reasoning) to it) }
                add(stringResource(R.string.result_sub_stability) to score.stability)
            }
            val maxSub = subs.maxOf { it.second }.coerceAtLeast(1)
            subs.forEach { (label, value) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(84.dp),
                    )
                    Box(
                        Modifier
                            .weight(1f)
                            .height(6.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceContainerHighest,
                                RoundedCornerShape(3.dp),
                            ),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(value.toFloat() / maxSub)
                                .height(6.dp)
                                .background(
                                    MaterialTheme.colorScheme.primary,
                                    RoundedCornerShape(3.dp),
                                ),
                        )
                    }
                    Text(
                        "%,d".format(value),
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = GoogleSansCode),
                        modifier = Modifier
                            .width(56.dp)
                            .padding(start = 8.dp),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.result_weights_footnote) + "\n" +
                    stringResource(R.string.result_anchor_footnote) +
                    if (score.ttftBasis != "clean") {
                        "\n" + stringResource(R.string.result_ttft_retried_warning)
                    } else {
                        ""
                    },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RunStatusBanner(report: RunReport) {
    val (label, detail) = when (report.runStatus) {
        RunStatus.NEEDS_RETEST -> stringResource(R.string.result_status_needs_retest) to
            stringResource(R.string.result_status_needs_retest_detail)
        RunStatus.ABORTED -> stringResource(R.string.result_status_aborted) to
            stringResource(R.string.result_status_aborted_detail)
        RunStatus.FAILED -> stringResource(R.string.result_status_failed) to
            (
                report.log.lastOrNull { it.level == com.gemmark.app.core.model.LogEntry.Level.ERROR }?.message
                    ?: stringResource(R.string.result_status_failed_detail)
                )
        RunStatus.COMPLETED -> return
    }
    Card(
        shape = GemmarkCardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(label, style = MaterialTheme.typography.titleSmall)
                Text(detail, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ChartCard(
    title: String,
    badge: String,
    badgeIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    badgeColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.secondaryContainer,
    badgeContentColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSecondaryContainer,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = GemmarkCardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (badge.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = badgeColor,
                        contentColor = badgeContentColor,
                    ) {
                        Row(
                            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (badgeIcon != null) {
                                Icon(badgeIcon, contentDescription = null, Modifier.size(12.dp))
                                Spacer(Modifier.width(4.dp))
                            }
                            Text(badge, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            content()
        }
    }
}

@Composable
private fun RoundTable(rounds: List<RoundResult>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = GemmarkCardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        ),
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                TableHeader(stringResource(R.string.result_table_round), 0.9f)
                TableHeader(stringResource(R.string.result_table_ttft), 1f)
                TableHeader(stringResource(R.string.result_table_decode), 1f)
                TableHeader(stringResource(R.string.result_table_status), 1.3f)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            rounds.forEach { round ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TableCell("R%02d".format(round.i), 0.9f)
                    TableCell("%.0f".format(round.ttftMs), 1f)
                    TableCell("%.1f".format(round.decodeTps), 1f)
                    Row(Modifier.weight(1.3f)) {
                        StatusChip(round.status)
                    }
                }
                if (round != rounds.last()) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                }
            }
            if (rounds.isEmpty()) {
                Text(
                    stringResource(R.string.result_no_rounds),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.TableHeader(text: String, weight: Float) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.weight(weight),
    )
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.TableCell(text: String, weight: Float) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = GoogleSansCode),
        modifier = Modifier.weight(weight),
    )
}

@Composable
private fun MonoDetail(label: String, value: String) {
    if (value.isEmpty()) return
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(110.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = GoogleSansCode),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun formatTimestamp(iso: String): String = try {
    val instant = Instant.parse(iso)
    DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm").withZone(ZoneId.systemDefault()).format(instant)
} catch (_: Exception) {
    iso.take(16)
}
