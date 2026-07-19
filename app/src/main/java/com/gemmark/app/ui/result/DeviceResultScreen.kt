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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gemmark.app.R
import com.gemmark.app.core.model.DeviceRun
import com.gemmark.app.core.model.DeviceRunExport
import com.gemmark.app.core.model.RunReport
import com.gemmark.app.core.model.ScoreCard
import com.gemmark.app.di.AppContainer
import com.gemmark.app.ui.appViewModel
import com.gemmark.app.ui.components.EmptyState
import com.gemmark.app.ui.components.GemmarkCardShape
import com.gemmark.app.ui.theme.GoogleSansCode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch


data class DeviceResultUiState(
    val deviceRun: DeviceRun? = null,
    val fastReport: RunReport? = null,
    val fullReport: RunReport? = null,
    val loading: Boolean = true,
)

class DeviceResultViewModel(
    container: AppContainer,
    deviceRunId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(DeviceResultUiState())
    val state: StateFlow<DeviceResultUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val run = container.runRepository.loadDeviceRun(deviceRunId)
            _state.value = DeviceResultUiState(
                deviceRun = run,
                fastReport = run?.fastRunId?.let { container.runRepository.load(it) },
                fullReport = run?.fullRunId?.let { container.runRepository.load(it) },
                loading = false,
            )
        }
    }
}

/** Composite Standard Test result: Device Score hero + one card per model. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceResultScreen(
    deviceRunId: String,
    onOpenRun: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: DeviceResultViewModel = appViewModel(key = deviceRunId) {
        DeviceResultViewModel(it, deviceRunId)
    },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var showExportDialog by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }
    fun buildExport(): DeviceRunExport? = state.deviceRun?.let { dr ->
        DeviceRunExport(deviceRun = dr, fastReport = state.fastReport, fullReport = state.fullReport)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.result_back),
                        )
                    }
                },
                actions = {
                    if (state.deviceRun != null) {
                        IconButton(onClick = { showExportDialog = true }) {
                            Icon(
                                Icons.Filled.FileDownload,
                                contentDescription = stringResource(R.string.device_result_export),
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        val run = state.deviceRun
        when {
            state.loading -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
            }

            run == null -> Column(Modifier.padding(padding)) {
                EmptyState(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    title = stringResource(R.string.device_result_missing),
                    caption = "",
                )
            }

            else -> {
                if (showExportDialog) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { showExportDialog = false },
                        title = { Text(stringResource(R.string.device_result_export)) },
                        text = { Text(stringResource(R.string.device_result_export_desc)) },
                        confirmButton = {
                            androidx.compose.material3.TextButton(onClick = {
                                showExportDialog = false
                                buildExport()?.let { e ->
                                    scope.launch {
                                        com.gemmark.app.data.Exporters.shareDeviceRun(
                                            context, e, com.gemmark.app.data.Exporters.Format.JSON,
                                        )
                                    }
                                }
                            }) { Text("JSON") }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(onClick = {
                                showExportDialog = false
                                buildExport()?.let { e ->
                                    scope.launch {
                                        com.gemmark.app.data.Exporters.shareDeviceRun(
                                            context, e, com.gemmark.app.data.Exporters.Format.CSV,
                                        )
                                    }
                                }
                            }) { Text("CSV") }
                        },
                    )
                }
                LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding() + 4.dp,
                    bottom = padding.calculateBottomPadding() + 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { DeviceScoreHero(run) }
                item {
                    ModelResultCard(
                        title = stringResource(
                            if (run.coverage == "mock") R.string.device_result_simulated else R.string.device_result_fast,
                        ),
                        modelName = run.fastModel,
                        total = run.fastScore,
                        score = state.fastReport?.score,
                        onClick = run.fastRunId?.let { id -> { onOpenRun(id) } },
                    )
                }
                if (run.fullRunId != null || run.fullModel.isNotEmpty()) {
                    item {
                        ModelResultCard(
                            title = stringResource(R.string.device_result_full),
                            modelName = run.fullModel,
                            total = run.fullScore,
                            score = state.fullReport?.score,
                            onClick = run.fullRunId?.let { id -> { onOpenRun(id) } },
                        )
                    }
                }
                if (run.switchLoadsMs.isNotEmpty()) {
                    item { SwitchLoadCard(run) }
                }
                item {
                    Text(
                        stringResource(R.string.device_result_formula) + "\n" +
                            stringResource(R.string.result_anchor_footnote),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
                }
            }
        }
    }
}

@Composable
private fun DeviceScoreHero(run: DeviceRun) {
    Card(
        shape = GemmarkCardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.home_device_score),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f),
            )
            Text(
                run.deviceScore?.let { "%,d".format(it) } ?: "—",
                style = MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.Bold),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                (deviceLabel(run) + " · " + formatDeviceTimestamp(run.timestamp)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f),
            )
            if (run.coverage != "complete") {
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(
                        if (run.coverage == "mock") R.string.home_coverage_mock else R.string.home_coverage_partial,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.75f),
                )
            }
        }
    }
}

/** Displacement-load result: median switch time + the Load sub-score. */
@Composable
private fun SwitchLoadCard(run: DeviceRun) {
    val sorted = run.switchLoadsMs.sorted()
    val median = if (sorted.size % 2 == 1) {
        sorted[sorted.size / 2].toDouble()
    } else {
        (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
    }
    Card(
        shape = GemmarkCardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.device_result_switch_load),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    stringResource(
                        R.string.device_result_switch_load_detail,
                        median / 1000.0,
                        run.switchLoadsMs.size,
                    ),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = GoogleSansCode),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (run.loadScore == null) {
                    Text(
                        stringResource(R.string.device_result_switch_not_scored),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                run.loadScore?.let { "%,d".format(it) } ?: "—",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun ModelResultCard(
    title: String,
    modelName: String,
    total: Int?,
    score: ScoreCard?,
    onClick: (() -> Unit)?,
) {
    Card(
        onClick = onClick ?: {},
        enabled = onClick != null,
        shape = GemmarkCardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    if (modelName.isNotEmpty()) {
                        Text(
                            modelName,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = GoogleSansCode),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    total?.let { "%,d".format(it) } ?: "—",
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                )
                if (onClick != null) {
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (score != null) {
                Spacer(Modifier.height(12.dp))
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
                            .padding(vertical = 3.dp),
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
            }
        }
    }
}

/** Some builds put the brand inside Build.MODEL already — avoid "Google Google …". */
private fun deviceLabel(run: DeviceRun): String =
    if (run.device.model.startsWith(run.device.manufacturer, ignoreCase = true)) {
        run.device.model
    } else {
        "${run.device.manufacturer} ${run.device.model}".trim()
    }

private fun formatDeviceTimestamp(iso: String): String = try {
    val instant = Instant.parse(iso)
    DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault()).format(instant)
} catch (_: Exception) {
    iso.take(16)
}
