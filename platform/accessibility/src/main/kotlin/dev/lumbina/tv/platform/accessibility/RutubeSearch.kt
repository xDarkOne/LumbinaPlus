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
internal class RutubeSearch(private val service: AccessibilityService, private val onOpenSearch: () -> Unit) {
    private var packageName: String = ""
    private var enteredApp = false

    // Launching, waiting for the keyboard, and allowing manual navigation are distinct states.
    @Suppress("CyclomaticComplexMethod")
    suspend fun search(query: String): Boolean = withTimeoutOrNull(SEARCH_TIMEOUT_MILLIS) {
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

            if (!waitFor(APP_TIMEOUT_MILLIS) { keyboard() != null || sidebar() != null }) return@withTimeoutOrNull false
            if (keyboard() == null) {
                openSearch()
                waitFor(SEARCH_OPEN_MILLIS) { keyboard() != null }
            }
            if (keyboard() == null) {
                if (!isRutubeActive()) return@withTimeoutOrNull false
                onOpenSearch()
                if (!waitFor(MANUAL_SEARCH_MILLIS) { keyboard() != null }) return@withTimeoutOrNull false
                delay(SEARCH_SETTLE_MILLIS)
            }

            // Clear any existing query. If the key is absent, entering text could silently append to it.
            // Clearing an already empty field may return false on TV builds even when the key exists.
            if (key("Очистить") == null) return@withTimeoutOrNull false
            press("Очистить")
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

    // Each early return is an unavailable keyboard or failed click; typing must stop immediately.
    @Suppress("ReturnCount")
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
        delay(KEY_DELAY_MILLIS)
        return success
    }

    private fun key(label: String): AccessibilityNodeInfo? {
        val keyboard = keyboard() ?: return null
        fun matching(container: AccessibilityNodeInfo): AccessibilityNodeInfo? = findNode(container) {
            it.isVisibleToUser && it.isClickable && it.text?.toString()?.equals(label, ignoreCase = true) == true
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
            val inSidebar = bounds.width() in 1..MAX_SIDEBAR_WIDTH && bounds.left < MAX_SIDEBAR_LEFT
            if (node.isFocusable && inSidebar && bounds.top < topY) {
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

    private fun keyboard(): AccessibilityNodeInfo? {
        val root = root() ?: return null
        root.findAccessibilityNodeInfosByViewId("$packageName:id/fsKeyboard").firstOrNull()?.let { return it }
        // Some TV builds expose Compose labels but omit the keyboard view ID.
        val clear = findNode(root) { it.isVisibleToUser && it.isClickable && it.text?.toString() == "Очистить" }
        return root.takeIf { clear != null }
    }

    /** Stops waiting as soon as the user leaves RUTUBE after its window appeared. */
    private suspend fun waitFor(millis: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + millis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (isRutubeActive()) {
                enteredApp = true
            } else if (enteredApp) {
                return false
            }
            if (condition()) return true
            delay(POLL_MILLIS)
        }
        return condition()
    }

    private companion object {
        val PACKAGES = listOf("ru.rutube.app.tv", "ru.rutube.app")
        const val SEARCH_TIMEOUT_MILLIS = 60_000L
        const val APP_TIMEOUT_MILLIS = 25_000L
        const val SEARCH_OPEN_MILLIS = 1_000L
        const val MANUAL_SEARCH_MILLIS = 30_000L
        const val SEARCH_SETTLE_MILLIS = 300L
        const val KEY_DELAY_MILLIS = 70L
        const val POLL_MILLIS = 200L
        const val MAX_SIDEBAR_WIDTH = 199
        const val MAX_SIDEBAR_LEFT = 128
    }
}

private fun findNode(
    root: AccessibilityNodeInfo,
    matches: (AccessibilityNodeInfo) -> Boolean,
): AccessibilityNodeInfo? {
    val pending = ArrayDeque<AccessibilityNodeInfo>()
    pending.add(root)
    while (pending.isNotEmpty()) {
        val node = pending.removeFirst()
        if (matches(node)) return node
        repeat(node.childCount) { node.getChild(it)?.let(pending::addLast) }
    }
    return null
}
