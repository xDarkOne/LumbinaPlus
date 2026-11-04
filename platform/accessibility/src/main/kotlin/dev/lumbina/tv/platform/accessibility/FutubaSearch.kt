package dev.lumbina.tv.platform.accessibility

import android.accessibilityservice.AccessibilityService
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Futuba accepts ACTION_SEARCH at launch. Its Compose field needs an explicit update when already open. */
internal class FutubaSearch(private val service: AccessibilityService) {
    suspend fun search(query: String): Boolean = withTimeoutOrNull(20_000L) {
        val running = root() != null
        val intent = Intent(Intent.ACTION_SEARCH)
            .setComponent(ComponentName(PACKAGE, "$PACKAGE.MainActivity"))
            .putExtra(SearchManager.QUERY, query)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        try {
            service.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            return@withTimeoutOrNull false
        }
        val started = SystemClock.elapsedRealtime()
        var seen = running
        var clicked = false
        var field: AccessibilityNodeInfo? = null
        while (field == null) {
            val root = root()
            if (root == null && seen) return@withTimeoutOrNull false
            if (root != null) {
                seen = true
                field = find(root) { it.isEditable && it.isVisibleToUser }
                if (field == null && !clicked && (running || SystemClock.elapsedRealtime() - started >= 3_000)) {
                    val label = find(root) {
                        it.isVisibleToUser && it.contentDescription?.toString() in setOf("Поиск", "Search")
                    }
                    val button = generateSequence(label) { it.parent }.firstOrNull { it.isClickable }
                    clicked = button?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                }
            }
            if (field == null) delay(200)
        }
        if (field.text?.toString() != query) {
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query)
            }
            if (!field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) return@withTimeoutOrNull false
            delay(200)
        }
        if (Build.VERSION.SDK_INT >= 30) {
            root()?.let { find(it) { node -> node.isEditable && node.isVisibleToUser } }
                ?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
        true
    } ?: false

    private fun root() = service.rootInActiveWindow?.takeIf { it.packageName?.toString() == PACKAGE }

    private fun find(root: AccessibilityNodeInfo, matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            if (matches(node)) return node
            repeat(node.childCount) { node.getChild(it)?.let(pending::addLast) }
        }
        return null
    }

    private companion object {
        const val PACKAGE = "me.pnsrc.futuba"
    }
}
