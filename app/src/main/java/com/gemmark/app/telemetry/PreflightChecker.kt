package com.gemmark.app.telemetry

import com.gemmark.app.core.model.PreflightSnapshot

/**
 * Fixed test conditions from the design doc (固定条件). Automatic checks are
 * verified against system state; manual items are shown as reminders the
 * operator confirms by eye (room temperature, brightness/refresh lock,
 * background apps, model pre-downloaded).
 */
data class PreflightCheck(
    val id: String,
    val label: String,
    val detail: String,
    val state: State,
    val automatic: Boolean,
) {
    enum class State { PASS, WARN, FAIL, MANUAL }
}

class PreflightChecker(private val monitor: TelemetryMonitor) {

    companion object {
        const val BATTERY_MIN = 70
    }

    fun run(): List<PreflightCheck> {
        val snap = monitor.snapshot()
        val checks = mutableListOf<PreflightCheck>()

        // Enough charge to finish a run without the battery-saver kicking in.
        // No upper bound: a full battery is a perfectly good test condition.
        val level = snap.levelPct
        checks += PreflightCheck(
            id = "battery",
            label = "Battery ≥ $BATTERY_MIN%",
            detail = if (level != null) "Now $level%" else "Battery level unavailable",
            state = when {
                level == null -> PreflightCheck.State.FAIL
                level >= BATTERY_MIN -> PreflightCheck.State.PASS
                else -> PreflightCheck.State.FAIL
            },
            automatic = true,
        )

        checks += PreflightCheck(
            id = "charging",
            label = "Not charging",
            detail = if (snap.isCharging) "Unplug the cable" else "Unplugged",
            state = if (snap.isCharging) PreflightCheck.State.FAIL else PreflightCheck.State.PASS,
            automatic = true,
        )

        val thermal = snap.thermalStatus
        checks += PreflightCheck(
            id = "thermal",
            label = "Device cool",
            detail = "Thermal $thermal · battery ${"%.1f".format(snap.tempC)}°C",
            state = when (thermal) {
                "NONE" -> PreflightCheck.State.PASS
                "LIGHT" -> PreflightCheck.State.WARN
                else -> PreflightCheck.State.FAIL
            },
            automatic = true,
        )

        checks += PreflightCheck(
            id = "powersave",
            label = "Battery saver off",
            detail = if (monitor.isPowerSaveMode) "Turn off battery saver" else "Off",
            state = if (monitor.isPowerSaveMode) PreflightCheck.State.FAIL else PreflightCheck.State.PASS,
            automatic = true,
        )

        checks += PreflightCheck(
            id = "brightness",
            label = "Fixed brightness & refresh rate",
            detail = "Set manually, confirm before recording",
            state = PreflightCheck.State.MANUAL,
            automatic = false,
        )

        checks += PreflightCheck(
            id = "background",
            label = "Background apps closed",
            detail = "Clear recents, disable notifications",
            state = PreflightCheck.State.MANUAL,
            automatic = false,
        )

        checks += PreflightCheck(
            id = "room",
            label = "Consistent room temperature",
            detail = "Same environment across all runs",
            state = PreflightCheck.State.MANUAL,
            automatic = false,
        )

        return checks
    }

    /** Automatic checks all pass (WARN counts as pass, FAIL does not). */
    fun automaticChecksPass(checks: List<PreflightCheck>): Boolean =
        checks.filter { it.automatic }.none { it.state == PreflightCheck.State.FAIL }

    fun snapshotFor(checks: List<PreflightCheck>): PreflightSnapshot {
        val snap = monitor.snapshot()
        return PreflightSnapshot(
            batteryPct = snap.levelPct ?: -1,
            charging = snap.isCharging,
            thermalStatus = snap.thermalStatus,
            powerSave = monitor.isPowerSaveMode,
            passed = automaticChecksPass(checks),
        )
    }
}
