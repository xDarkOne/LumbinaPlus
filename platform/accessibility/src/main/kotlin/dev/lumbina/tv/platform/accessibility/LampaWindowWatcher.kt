package dev.lumbina.tv.platform.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import dev.lumbina.tv.core.common.LampaWatchNext as Watch
import dev.lumbina.tv.core.common.LumbinaLog

/** Reports LAMPA → Vimu → another application, ignoring the system's transient overlays. */
internal class LampaWindowWatcher(private val service: AccessibilityService, private val log: LumbinaLog) {
    private var lastApp: String? = null
    private var fromLampa = false

    fun beforeHome() {
        val foreground = service.rootInActiveWindow?.packageName?.toString()
        val active = service.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive }
            ?.root?.packageName?.toString()
        val fromPlayer = foreground == Watch.VIMU || active == Watch.VIMU || lastApp == Watch.VIMU
        log.log("lampa-history", "Home: root=$foreground active=$active last=$lastApp player=$fromPlayer")
        if (fromPlayer) report(Watch.HOME_FROM_PLAYER)
    }

    // Window types and transitions need separate handling to avoid treating overlays as apps.
    @Suppress("CyclomaticComplexMethod")
    fun onEvent(event: AccessibilityEvent) {
        val app = when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOWS_CHANGED ->
                service.windows
                    .firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive }
                    ?.root?.packageName?.toString()

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val className = event.className?.toString().orEmpty()
                if (className.startsWith("android.widget.") || className.startsWith("android.view.")) return
                val candidate = event.packageName?.toString()
                if (candidate == service.packageName && className != "dev.lumbina.tv.MainActivity") return
                candidate
            }

            else -> null
        } ?: return
        if (app in OVERLAYS || app == lastApp) return
        if (app == Watch.VIMU) {
            fromLampa = lastApp == Watch.LAMPA
            if (fromLampa) report(Watch.ENTERED)
        } else if (lastApp == Watch.VIMU && fromLampa) {
            fromLampa = false
            report(if (app == Watch.LAMPA) Watch.RETURNED else Watch.LEFT)
        }
        lastApp = app
    }

    private fun report(destination: String) {
        service.sendBroadcast(
            Intent(Watch.ACTION).setPackage(service.packageName).putExtra(Watch.DESTINATION, destination),
        )
    }

    private companion object {
        val OVERLAYS = setOf("android", "com.android.systemui", "com.hisense.tv.aivirtualassistant")
    }
}
