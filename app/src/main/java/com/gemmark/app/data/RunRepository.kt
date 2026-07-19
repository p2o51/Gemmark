package com.gemmark.app.data

import android.content.Context
import com.gemmark.app.core.model.RunReport
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Persistence: one pretty-printed JSON document per run in filesDir/runs.
 * The stored file IS the export format (spec: 每次 run 导出一个 JSON), so
 * export == share the stored document.
 */
class RunRepository(context: Context) {

    private val dir = File(context.filesDir, "runs").apply { mkdirs() }

    val json: Json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun save(report: RunReport) {
        val file = File(dir, "${report.runId}.json")
        file.writeText(json.encodeToString(RunReport.serializer(), report))
    }

    suspend fun load(runId: String): RunReport? = withContext(Dispatchers.IO) {
        val file = File(dir, "$runId.json")
        if (!file.exists()) return@withContext null
        runCatching {
            json.decodeFromString(RunReport.serializer(), file.readText())
        }.getOrNull()
    }

    /** All runs, newest first. Corrupt files are skipped. */
    suspend fun listAll(): List<RunReport> = withContext(Dispatchers.IO) {
        (dir.listFiles { f -> f.extension == "json" } ?: emptyArray())
            .mapNotNull { file ->
                runCatching {
                    json.decodeFromString(RunReport.serializer(), file.readText())
                }.getOrNull()
            }
            .sortedByDescending { it.timestamp }
    }

    suspend fun delete(runId: String): Boolean = withContext(Dispatchers.IO) {
        File(dir, "$runId.json").delete()
    }

    fun rawFile(runId: String): File = File(dir, "$runId.json")

    // --- Device runs (dual-model composite results) ---

    private val deviceDir = File(dir.parentFile, "device_runs").apply { mkdirs() }

    fun saveDeviceRun(run: com.gemmark.app.core.model.DeviceRun) {
        File(deviceDir, "${run.deviceRunId}.json")
            .writeText(json.encodeToString(com.gemmark.app.core.model.DeviceRun.serializer(), run))
    }

    suspend fun loadDeviceRun(id: String): com.gemmark.app.core.model.DeviceRun? =
        withContext(Dispatchers.IO) {
            val f = File(deviceDir, "$id.json")
            if (!f.exists()) return@withContext null
            runCatching {
                json.decodeFromString(com.gemmark.app.core.model.DeviceRun.serializer(), f.readText())
            }.getOrNull()
        }

    suspend fun listDeviceRuns(): List<com.gemmark.app.core.model.DeviceRun> =
        withContext(Dispatchers.IO) {
            (deviceDir.listFiles { f -> f.extension == "json" } ?: emptyArray())
                .mapNotNull { file ->
                    runCatching {
                        json.decodeFromString(com.gemmark.app.core.model.DeviceRun.serializer(), file.readText())
                    }.getOrNull()
                }
                .sortedByDescending { it.timestamp }
        }
}
