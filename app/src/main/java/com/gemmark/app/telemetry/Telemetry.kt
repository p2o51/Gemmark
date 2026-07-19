package com.gemmark.app.telemetry

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.gemmark.app.core.model.DeviceInfo
import com.gemmark.app.core.model.TelemetrySample
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Point-in-time battery/thermal reading.
 * Fields are null when the device's fuel gauge does not expose the property
 * (BatteryManager returns Int/Long.MIN_VALUE sentinels — never propagate those).
 */
data class BatterySnapshot(
    val levelPct: Int?,
    /** True when a cable/dock is attached OR the OS reports charging (spec: must be unplugged). */
    val isCharging: Boolean,
    /** Battery temperature in °C (from EXTRA_TEMPERATURE, tenths of a degree). */
    val tempC: Double,
    val voltageMv: Int,
    /** Instantaneous current in µA; sign convention varies by device — negative usually = discharging. */
    val currentNowUa: Long?,
    /** Remaining battery capacity in µAh (CHARGE_COUNTER); used for whole-run cross-check. */
    val chargeCounterUah: Long?,
    /** PowerManager thermal status name (NONE/LIGHT/MODERATE/SEVERE/...). */
    val thermalStatus: String,
    /** Instantaneous power draw in watts, |current| × voltage; null when current is unavailable. */
    val powerW: Double?,
)

/** Minimal telemetry surface the runner depends on (fakeable in unit tests). */
interface TelemetrySource {
    fun snapshot(): BatterySnapshot
    fun thermalStatusName(): String
}

class TelemetryMonitor(private val context: Context) : TelemetrySource {

    private val batteryManager =
        context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    private val powerManager =
        context.getSystemService(Context.POWER_SERVICE) as PowerManager

    override fun snapshot(): BatterySnapshot {
        val intent: Intent? = context.registerReceiver(
            null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val tempTenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val voltageMv = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        // Plugged-but-not-charging (Adaptive Charging pause, 80% limit) still powers
        // the device from AC and invalidates discharge measurement — treat as charging.
        val charging = plugged != 0 ||
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        // MIN_VALUE sentinels mean "unsupported on this fuel gauge".
        val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            .takeIf { it != Int.MIN_VALUE }
            ?: stickyIntentLevelPct(intent)
        val currentRaw = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            .takeIf { it != Long.MIN_VALUE && it != Int.MIN_VALUE.toLong() }
        // Spec says CURRENT_NOW is µA, but some vendors report mA (observed on
        // OPPO/MediaTek: |raw| ≈ 0.5–2.3 during inference — three orders of
        // magnitude off). A real inference draw is ≥100 mA = 100,000 µA; any
        // nonzero reading under 10,000 can only be mA. Normalize to µA.
        val currentUa = currentRaw?.let { raw ->
            if (raw != 0L && abs(raw) < 10_000L) raw * 1_000L else raw
        }
        val chargeUah = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            .takeIf { it != Long.MIN_VALUE }

        // CURRENT_NOW in µA, VOLTAGE in mV → W = (µA / 1e6) × (mV / 1e3)
        val powerW = if (currentUa != null && voltageMv > 0) {
            abs(currentUa.toDouble()) / 1_000_000.0 * voltageMv / 1_000.0
        } else {
            null
        }

        return BatterySnapshot(
            levelPct = level,
            isCharging = charging,
            tempC = tempTenths / 10.0,
            voltageMv = voltageMv,
            currentNowUa = currentUa,
            chargeCounterUah = chargeUah,
            thermalStatus = thermalStatusName(),
            powerW = powerW,
        )
    }

    private fun stickyIntentLevelPct(intent: Intent?): Int? {
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) level * 100 / scale else null
    }

    override fun thermalStatusName(): String = when (powerManager.currentThermalStatus) {
        PowerManager.THERMAL_STATUS_NONE -> "NONE"
        PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
        PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
        PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
        PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
        else -> "UNKNOWN"
    }

    /** Forecast headroom (0..1, 1 = throttling imminent); NaN when unsupported. */
    fun thermalHeadroom(forecastSeconds: Int = 10): Float =
        powerManager.getThermalHeadroom(forecastSeconds)

    val isPowerSaveMode: Boolean
        get() = powerManager.isPowerSaveMode

    fun deviceInfo(): DeviceInfo = DeviceInfo(
        model = "${Build.MANUFACTURER} ${Build.MODEL}",
        build = Build.DISPLAY,
        // Spec: record AICore version with every run; readable via
        // PackageManager("com.google.android.aicore") once verified on device.
        aicoreVersion = aicoreVersionOrEmpty(),
        manufacturer = Build.MANUFACTURER,
        soc = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else "",
        androidSdk = Build.VERSION.SDK_INT,
    )

    private fun aicoreVersionOrEmpty(): String = try {
        val info = context.packageManager.getPackageInfo("com.google.android.aicore", 0)
        info.versionName ?: info.longVersionCode.toString()
    } catch (_: Exception) {
        ""
    }
}

/**
 * 1 Hz sampler per spec: integrate CURRENT_NOW × VOLTAGE while discharging;
 * CHARGE_COUNTER delta over the whole run is the cross-check.
 */
class PowerSampler(
    private val monitor: TelemetrySource,
    private val intervalMs: Long = 1_000,
    private val elapsedMs: () -> Long = android.os.SystemClock::elapsedRealtime,
) {
    private val _samples = MutableStateFlow<List<TelemetrySample>>(emptyList())
    val samples: StateFlow<List<TelemetrySample>> = _samples

    private var job: Job? = null
    private var startElapsedMs: Long = 0

    fun start(scope: CoroutineScope) {
        stop()
        _samples.value = emptyList()
        startElapsedMs = elapsedMs()
        job = scope.launch {
            while (isActive) {
                val snap = monitor.snapshot()
                val t = elapsedMs() - startElapsedMs
                val currentUa = snap.currentNowUa
                val powerW = snap.powerW
                // Skip samples when the fuel gauge exposes no current reading —
                // an empty telemetry series is honest; zeros would be quiet garbage.
                if (currentUa != null && powerW != null) {
                    _samples.value = _samples.value + TelemetrySample(
                        tMs = t,
                        tempC = snap.tempC,
                        powerW = powerW,
                        currentMa = currentUa / 1_000.0,
                        thermalStatus = snap.thermalStatus,
                    )
                }
                delay(intervalMs)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Elapsed ms on the sampler clock; aligns round windows with sample timestamps. */
    fun nowMs(): Long = elapsedMs() - startElapsedMs

    /** Average |current| (mA) over samples inside [fromMs, toMs]. */
    fun averageCurrentMa(fromMs: Long, toMs: Long): Double {
        val window = _samples.value.filter { it.tMs in fromMs..toMs }
        if (window.isEmpty()) return 0.0
        return window.map { abs(it.currentMa) }.average()
    }

    /** Average power (W) over samples inside [fromMs, toMs]. */
    fun averagePowerW(fromMs: Long, toMs: Long): Double {
        val window = _samples.value.filter { it.tMs in fromMs..toMs }
        if (window.isEmpty()) return 0.0
        return window.map { it.powerW }.average()
    }
}
