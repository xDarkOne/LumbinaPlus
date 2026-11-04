package dev.lumbina.tv.platform.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Searches the official RUTUBE TV app using its own keyboard. */
internal class RutubeSearch(
    private val service: AccessibilityService,
    private val onOpenSearch: () -> Unit,
) {
    private var packageName: String = ""
    private var enteredApp = false

    suspend fun search(query: String): Boolean = withTimeoutOrNull(60_000L) {
        val launch = PACKAGES.firstNotNullOfOrNull { pkg ->
            service.packageManager.getLeanbackLaunchIntentForPackage(pkg)
                ?: service.packageManager.getLaunchIntentForPackage(pkg)
        } ?: return@withTimeoutOrNull false
        packageName = launch.component?.packageName ?: return@withTimeoutOrNull false

        val flags = service.serviceInfo.flags
        val addedViewIds = flags and AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS == 0
        if (addedViewIds) {
            service.serviceInfo = service.serviceInfo.apply {
                this.flags = this.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            }
        }
        try {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            try {
                service.startActivity(launch)
            } catch (_: ActivityNotFoundException) {
                return@withTimeoutOrNull false
            } catch (_: SecurityException) {
                return@withTimeoutOrNull false
            }

            if (!waitFor(25_000) { keyboard() != null || sidebar() != null }) return@withTimeoutOrNull false
            if (keyboard() == null) {
                openSearch()
                waitFor(1_000) { keyboard() != null }
            }
            if (keyboard() == null) {
                if (!isRutubeActive()) return@withTimeoutOrNull false
                onOpenSearch()
                if (!waitFor(30_000) { keyboard() != null }) return@withTimeoutOrNull false
                delay(300)
            }

            // Clear any existing query. If the key is absent, entering text could silently append to it.
            if (!press("Очистить")) return@withTimeoutOrNull false
            for (character in query.lowercase(Locale.forLanguageTag("ru"))) {
                if (!typeCharacter(character)) return@withTimeoutOrNull false
            }
            press("Найти")
        } finally {
            if (addedViewIds) {
                service.serviceInfo = service.serviceInfo.apply {
                    this.flags = this.flags and AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS.inv()
                }
            }
        }
    } ?: false

    private suspend fun typeCharacter(character: Char): Boolean {
        if (character == ' ') {
            if (key("й") == null && key("q") == null && !press("Рус")) return false
            val root = root() ?: return false
            val space = root.findAccessibilityNodeInfosByViewId("$packageName:id/lettersRowLayout2")
                .asSequence()
                .flatMap { row -> (0 until row.childCount).asSequence().mapNotNull(row::getChild) }
                .firstOrNull { it.isClickable && it.text.isNullOrEmpty() } ?: return false
            return click(space)
        }

        val (probe, layout) = when {
            Character.UnicodeBlock.of(character) == Character.UnicodeBlock.CYRILLIC -> "й" to "Рус"
            character in 'a'..'z' -> "q" to "Eng"
            else -> "1" to "!#1"
        }
        if (key(probe) == null && !press(layout)) return false
        return press(character.toString())
    }

    private suspend fun press(label: String): Boolean {
        val target = key(label) ?: return false
        return click(target)
    }

    private suspend fun click(node: AccessibilityNodeInfo): Boolean {
        if (!isRutubeActive()) return false
        val success = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        delay(70)
        return success
    }

    private fun key(label: String): AccessibilityNodeInfo? {
        val keyboard = keyboard() ?: return null
        fun matching(container: AccessibilityNodeInfo): AccessibilityNodeInfo? =
            container.findAccessibilityNodeInfosByText(label).firstOrNull {
                it.isClickable && it.text?.toString()?.equals(label, ignoreCase = true) == true
            }
        return matching(keyboard) ?: if (label == "Очистить" || label == "Найти") {
            root()?.let(::matching)
        } else {
            null
        }
    }

    private fun openSearch() {
        val root = sidebar() ?: return
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        pending.add(root)
        var top: AccessibilityNodeInfo? = null
        var topY = Int.MAX_VALUE
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (node.isFocusable && bounds.width() in 1..199 && bounds.left < 128 && bounds.top < topY) {
                top = node
                topY = bounds.top
            }
            repeat(node.childCount) { node.getChild(it)?.let(pending::addLast) }
        }
        top?.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        top?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun root(): AccessibilityNodeInfo? =
        service.rootInActiveWindow?.takeIf { it.packageName?.toString() == packageName }

    private fun isRutubeActive(): Boolean = root() != null

    private fun sidebar(): AccessibilityNodeInfo? = root()
        ?.findAccessibilityNodeInfosByViewId("$packageName:id/sidebar_container")
        ?.firstOrNull { it.childCount > 0 }

    private fun keyboard(): AccessibilityNodeInfo? =
        root()?.findAccessibilityNodeInfosByViewId("$packageName:id/fsKeyboard")?.firstOrNull()

    /** Stops waiting as soon as the user leaves RUTUBE after its window appeared. */
    private suspend fun waitFor(millis: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + millis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (isRutubeActive()) enteredApp = true else if (enteredApp) return false
            if (condition()) return true
            delay(200)
        }
        return condition()
    }

    private companion object {
        val PACKAGES = listOf("ru.rutube.app.tv", "ru.rutube.app")
    }
}
