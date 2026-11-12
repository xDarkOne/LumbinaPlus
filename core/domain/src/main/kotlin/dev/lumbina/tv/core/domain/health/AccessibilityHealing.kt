package dev.lumbina.tv.core.domain.health

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Access to the system's enabled-accessibility-services setting. */
interface SecureSettings {
    /** False until the user grants WRITE_SECURE_SETTINGS over adb. */
    val canWrite: Boolean

    fun enabledAccessibilityServices(): String?

    fun setEnabledAccessibilityServices(value: String)

    fun setAccessibilityEnabled()
}

/** Whether our accessibility service is currently bound by the system. */
fun interface AccessibilityHealth {
    fun isServiceBound(): Boolean
}

/**
 * The colon-separated `enabled_accessibility_services` list. Some firmware rewrites it on
 * boot, leaving duplicates or an entry that is listed but never bound.
 */
object AccessibilityServiceList {

    private fun parse(value: String?): List<String> =
        value.orEmpty().split(':').map(String::trim).filter { it.isNotEmpty() && it != "null" }

    /** Other services, de-duplicated, without [ours]. */
    fun withoutOurs(value: String?, ours: String): List<String> = parse(value)
        .filterNot { it.substringBefore('/') == ours.substringBefore('/') }
        .distinct()

    fun join(services: List<String>): String = services.joinToString(":")
}

/**
 * Lets one heal run at a time across Lumbina's processes: the remote's job, the home screen coming
 * to the front and the settings may all try at once.
 */
interface HealLock {
    suspend fun <T> withLock(block: suspend () -> T): T
}

/**
 * What only a shell can tell and do about our service. Android 11 keeps the connection of a
 * service whose process died as "crashed" and restarts it through that connection; listing the
 * service again meanwhile adds a second connection, and every remote key then acts twice.
 */
interface AccessibilityRestart {
    /** Whether the system holds our service as crashed; null when that can't be told. */
    suspend fun isCrashed(): Boolean?

    /**
     * Stops our app, dropping every connection to the service, and then lists [services]
     * again, from a shell that outlives our process. False when there is no shell.
     */
    suspend fun restartClean(services: String): Boolean

    /** Without a shell: crashes go unseen and the list is toggled as before. */
    object None : AccessibilityRestart {
        override suspend fun isCrashed(): Boolean? = null

        override suspend fun restartClean(services: String) = false
    }
}

sealed interface HealResult {
    data object Disabled : HealResult
    data object AlreadyRunning : HealResult
    data object MissingPermission : HealResult
    data object Reenabled : HealResult

    /** Crashed and not restarted by the system in time: the app is being stopped and listed anew. */
    data object Restarting : HealResult

    /** Crashed and no shell to restart it cleanly: left for the system, the list is not touched. */
    data object WaitingForSystem : HealResult
}

/**
 * Brings our accessibility service back when the system doesn't run it, listed or not (another app
 * may have taken it off the list): removing it from the list and adding it again makes the system
 * bind it. The user who wants it off turns this off ("The remote's service turns itself on", which
 * takes the right). A service found unbound gets [settleMillis] first: the system may be binding
 * it right now (e.g. as the device wakes, or after another heal just toggled it), and toggling
 * then leaves it bound twice, every remote key acting twice. The wait and the toggle run under
 * [lock], so a second heal waits for the first and then sees its result. A service the system
 * holds as crashed is never toggled (see [AccessibilityRestart]): the system gets [crashWaitMillis]
 * to restart it, then our app is stopped and listed again in one go.
 */
class HealAccessibilityService(
    private val settings: SecureSettings,
    private val health: AccessibilityHealth,
    private val ourService: String,
    private val lock: HealLock,
    private val toggleDelayMillis: Long = 1_500,
    private val settleMillis: Long = 8_000,
    private val restart: AccessibilityRestart = AccessibilityRestart.None,
    private val crashWaitMillis: Long = 60_000,
    private val interceptRemote: suspend () -> Boolean = { true },
) {
    suspend operator fun invoke(): HealResult {
        if (!interceptRemote()) return HealResult.Disabled
        if (health.isServiceBound()) return HealResult.AlreadyRunning
        if (!settings.canWrite) return HealResult.MissingPermission
        return lock.withLock { healUnbound() }
    }

    private suspend fun healUnbound(): HealResult {
        delay(settleMillis)
        return when {
            !interceptRemote() -> HealResult.Disabled
            health.isServiceBound() -> HealResult.AlreadyRunning
            restart.isCrashed() == true -> healCrashed()
            else -> toggle()
        }
    }

    private suspend fun toggle(): HealResult {
        val others = AccessibilityServiceList.withoutOurs(settings.enabledAccessibilityServices(), ourService)
        // Finish the toggle after cancellation unless the user turned interception off.
        return withContext(NonCancellable) {
            settings.setEnabledAccessibilityServices(AccessibilityServiceList.join(others))
            delay(toggleDelayMillis)
            if (!interceptRemote()) return@withContext HealResult.Disabled
            val current = AccessibilityServiceList.withoutOurs(settings.enabledAccessibilityServices(), ourService)
            settings.setEnabledAccessibilityServices(AccessibilityServiceList.join(current + ourService))
            settings.setAccessibilityEnabled()
            HealResult.Reenabled
        }
    }

    private suspend fun healCrashed(): HealResult = when {
        systemRestarts() -> HealResult.AlreadyRunning

        !interceptRemote() -> HealResult.Disabled

        // Off the crashed list but not bound: the system is binding it now.
        restart.isCrashed() == false -> HealResult.WaitingForSystem

        restart.restartClean(ourServiceListed()) -> HealResult.Restarting

        else -> HealResult.WaitingForSystem
    }

    /** Whether the system binds the crashed service again within [crashWaitMillis]. */
    private suspend fun systemRestarts(): Boolean {
        repeat((crashWaitMillis / CRASH_POLL_MILLIS).toInt()) {
            delay(CRASH_POLL_MILLIS)
            if (health.isServiceBound()) return true
        }
        return false
    }

    private fun ourServiceListed(): String = AccessibilityServiceList.join(
        AccessibilityServiceList.withoutOurs(settings.enabledAccessibilityServices(), ourService) + ourService,
    )

    private companion object {
        const val CRASH_POLL_MILLIS = 5_000L
    }
}
