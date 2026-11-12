package dev.lumbina.tv.core.domain.health

import dev.lumbina.tv.core.domain.access.LocalAdb

/**
 * [AccessibilityRestart] over Lumbina's local adb. The crashed list in `dumpsys accessibility`
 * names services by label only, hence [ourLabel].
 */
class AdbAccessibilityRestart(
    private val adb: LocalAdb,
    private val ourService: String,
    private val ourLabel: String,
) : AccessibilityRestart {

    override suspend fun isCrashed(): Boolean? {
        val dump = adb.shellQuietly("dumpsys accessibility") ?: return null
        if (!dump.contains(CRASHED)) return null
        return crashedSection(dump).contains("label=$ourLabel,")
    }

    override suspend fun restartClean(services: String): Boolean {
        // Stopping the app also takes it off the list (the system forgets a force-stopped
        // service), so the list is written back after. In the background and immune to hangup:
        // the stop ends our process, and with it this adb connection.
        val script = listOf(
            "am force-stop ${ourService.substringBefore('/')}",
            "sleep 1",
            "settings put secure enabled_accessibility_services ${quote(services)}",
            "settings put secure accessibility_enabled 1",
        ).joinToString(" ; ")
        return adb.shell("nohup sh -c ${quote(script)} >/dev/null 2>&1 &") != null
    }

    internal companion object {
        const val CRASHED = "Crashed services:"

        fun crashedSection(dump: String): String = dump.substringAfter(CRASHED).substringBefore("Client list info")

        fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
    }
}
