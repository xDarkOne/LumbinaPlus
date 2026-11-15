package dev.lumbina.tv.core.domain.health

import dev.lumbina.tv.core.domain.access.LocalAdb

/**
 * [AccessibilityRestart] over Lumbina's local adb. Connections are counted in the activity
 * manager's record of our service, the one place that shows a second one. A restart is refused
 * within [restartGapMillis] of the last, which [lastRestart] and [rememberRestart] keep across
 * our processes and their restarts.
 */
class AdbAccessibilityRestart(
    private val adb: LocalAdb,
    private val ourService: String,
    private val lastRestart: () -> Long,
    private val rememberRestart: (Long) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val restartGapMillis: Long = RESTART_GAP_MILLIS,
) : AccessibilityRestart {

    private val ourPackage = ourService.substringBefore('/')

    override suspend fun connections(): Int? {
        val dump = adb.shellQuietly("dumpsys activity services $ourPackage") ?: return null
        return connectionsOf(dump, ourService)
    }

    override suspend fun restartClean(services: String): Boolean {
        val at = now()
        if (at - lastRestart() in 0 until restartGapMillis) return false
        // Stopping the app also takes it off the list (the system forgets a force-stopped
        // service), so the list is written back after. In its own session and immune to hangup:
        // the stop ends our process, and with it this adb connection. The shell lingers a second
        // so the child has left its process group before adbd ends the group with the shell.
        val script = listOf(
            "am force-stop $ourPackage",
            "sleep 1",
            "settings put secure enabled_accessibility_services ${quote(services)}",
            "settings put secure accessibility_enabled 1",
        ).joinToString(" ; ")
        rememberRestart(at)
        return adb.shell("setsid nohup sh -c ${quote(script)} </dev/null >/dev/null 2>&1 & sleep 1") != null
    }

    internal companion object {
        const val RESTART_GAP_MILLIS = 600_000L
        private val CONNECTION = Regex("""ConnectionRecord\{([0-9a-f]+)""")

        /** Distinct connections in [service]'s record of `dumpsys activity services`; 0 without one. */
        fun connectionsOf(dump: String, service: String): Int {
            val names = setOf(service, shortForm(service)).map { "$it}" }
            val section = dump.lineSequence()
                .dropWhile { line -> !(line.contains("ServiceRecord{") && names.any { line.trimEnd().endsWith(it) }) }
                .drop(1)
                .takeWhile { !it.contains("ServiceRecord{") }
            return section.flatMap { line -> CONNECTION.findAll(line).map { it.groupValues[1] } }.toSet().size
        }

        /** `pkg/.Class` for a class inside the package, as Android prints it when it can. */
        private fun shortForm(service: String): String {
            val pkg = service.substringBefore('/')
            val cls = service.substringAfter('/')
            return if (cls.startsWith("$pkg.")) "$pkg/${cls.removePrefix(pkg)}" else service
        }

        fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
    }
}
