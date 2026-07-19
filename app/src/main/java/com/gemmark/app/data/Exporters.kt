package com.gemmark.app.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.gemmark.app.core.model.RunReport
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * JSON / CSV export via the system share sheet (save to Files/Drive, AirDrop,
 * send to a computer, …). CSV covers the per-round table; JSON is the full
 * stored document.
 */
object Exporters {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    /** Machine-readable output must not follow the device locale (12,3 breaks CSV). */
    private fun Double.csv(digits: Int): String = "%.${digits}f".format(Locale.ROOT, this)

    fun csv(report: RunReport): String = buildString {
        appendLine(
            "round,ttft_ms,decode_tps,e2e_tps,prefill_tps,output_tokens," +
                "temp_start_c,temp_end_c,thermal_status,avg_current_ma,avg_power_w," +
                "status,retries,backend_used,json_valid"
        )
        for (r in report.rounds) {
            appendLine(
                listOf(
                    r.i,
                    r.ttftMs.csv(1),
                    r.decodeTps.csv(2),
                    r.e2eTps.csv(2),
                    r.prefillTps.csv(2),
                    r.outputTokens,
                    r.tempStartC.csv(1),
                    r.tempEndC.csv(1),
                    r.thermalStatus,
                    r.avgCurrentMa.csv(1),
                    r.avgPowerW.csv(2),
                    r.status.csvValue,
                    r.retries,
                    r.backendUsed,
                    r.jsonValid ?: "",
                ).joinToString(",")
            )
        }
    }

    /**
     * Writes the export into cacheDir/exports (off the main thread) and opens
     * the share sheet. Call from a coroutine launched on the main dispatcher.
     */
    suspend fun share(context: Context, report: RunReport, format: Format) {
        val stamp = report.timestamp.replace(":", "-").take(19)
        val (file, mime) = withContext(Dispatchers.IO) {
            val exportsDir = File(context.cacheDir, "exports").apply { mkdirs() }
            when (format) {
                Format.JSON -> {
                    val f = File(exportsDir, "gemmark_${report.reportCode}_$stamp.json")
                    f.writeText(json.encodeToString(RunReport.serializer(), report))
                    f to "application/json"
                }
                Format.CSV -> {
                    val f = File(exportsDir, "gemmark_${report.reportCode}_$stamp.csv")
                    f.writeText(csv(report))
                    f to "text/csv"
                }
            }
        }

        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Export ${format.name}"))
    }

    /**
     * Unified export of a dual-model session: ONE document carrying the
     * composite DeviceRun plus both full sub-reports (JSON), or one combined
     * per-round table with a leading model column (CSV).
     */
    suspend fun shareDeviceRun(
        context: Context,
        export: com.gemmark.app.core.model.DeviceRunExport,
        format: Format,
    ) {
        val stamp = export.deviceRun.timestamp.replace(":", "-").take(19)
        val code = export.deviceRun.reportCode
        val (file, mime) = withContext(Dispatchers.IO) {
            val exportsDir = File(context.cacheDir, "exports").apply { mkdirs() }
            when (format) {
                Format.JSON -> {
                    val f = File(exportsDir, "gemmark_device_${code}_$stamp.json")
                    f.writeText(
                        json.encodeToString(
                            com.gemmark.app.core.model.DeviceRunExport.serializer(), export,
                        ),
                    )
                    f to "application/json"
                }
                Format.CSV -> {
                    val f = File(exportsDir, "gemmark_device_${code}_$stamp.csv")
                    f.writeText(deviceCsv(export))
                    f to "text/csv"
                }
            }
        }
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file,
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Export ${format.name}"))
    }

    fun deviceCsv(export: com.gemmark.app.core.model.DeviceRunExport): String = buildString {
        appendLine(
            "model,round,ttft_ms,decode_tps,e2e_tps,prefill_tps,output_tokens," +
                "temp_start_c,temp_end_c,thermal_status,avg_current_ma,avg_power_w," +
                "status,retries,backend_used,json_valid"
        )
        for (report in listOfNotNull(export.fastReport, export.fullReport)) {
            val model = report.model.baseModelName.ifEmpty { report.model.name }
            for (r in report.rounds) {
                appendLine(
                    listOf(
                        model,
                        r.i,
                        r.ttftMs.csv(1),
                        r.decodeTps.csv(2),
                        r.e2eTps.csv(2),
                        r.prefillTps.csv(2),
                        r.outputTokens,
                        r.tempStartC.csv(1),
                        r.tempEndC.csv(1),
                        r.thermalStatus,
                        r.avgCurrentMa.csv(1),
                        r.avgPowerW.csv(2),
                        r.status.csvValue,
                        r.retries,
                        r.backendUsed,
                        r.jsonValid ?: "",
                    ).joinToString(",")
                )
            }
        }
    }

    /** Plain-text summary for the Share button (paste into notes/chat). */
    fun shareSummary(context: Context, report: RunReport) {
        val s = report.summary
        val text = buildString {
            appendLine("Gemmark ${report.reportCode} — ${report.model.name}")
            appendLine("${report.device.model} · ${report.device.build}")
            appendLine("Prompt group ${report.config.promptGroup} · ${report.config.sampling}")
            if (s != null) {
                appendLine("Decode median: ${"%.1f".format(s.decodeTpsMedian)} tok/s (${s.validRounds} valid rounds)")
                appendLine("Trimmed mean: ${"%.1f".format(s.decodeTpsTrimmedMean)} tok/s · σ ${"%.2f".format(s.decodeTpsStdDev)}")
                appendLine("P10/P90: ${"%.1f".format(s.decodeTpsP10)} / ${"%.1f".format(s.decodeTpsP90)} tok/s")
                appendLine("TTFT median: ${"%.0f".format(s.ttftMsMedian)} ms")
                appendLine("Thermal drop: ${"%.0f".format(s.thermalDrop * 100)}% · peak ${"%.1f".format(s.tempPeakC)}°C")
            } else {
                appendLine("No valid rounds recorded.")
            }
            append(report.timestamp)
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(send, "Share result"))
    }

    enum class Format { JSON, CSV }
}
