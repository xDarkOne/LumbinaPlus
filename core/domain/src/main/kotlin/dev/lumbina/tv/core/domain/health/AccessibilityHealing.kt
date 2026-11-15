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
 * service whose process died and restarts the service through it; listing the service again
 * meanwhile adds a second connection, and the remote's keys then repeat. Only stopping the app
 * drops every connection, so with a shell the list is never toggled.
 */
interface AccessibilityRestart {
    /** How many connections the system holds to our service; null when there is no shell to tell. */
    suspend fun connections(): Int?

    /**
     * Stops our app, dropping every connection to the service, and then lists [services]
     * again, from a shell that outlives our process. False when there is no shell, or when it
     * restarted the service a short while ago (a restart that doesn't mend must not loop).
     */
    suspend fun restartClean(services: String): Boolean

    /** Without a shell: connections go uncounted and the list is toggled. */
    object None : AccessibilityRestart {
        override suspend fun connections(): Int? = null

        override suspend fun restartClean(services: String) = false
    }
}

sealed interface HealResult {
    data object Disabled : HealResult
    data object AlreadyRunning : HealResult
    data object MissingPermission : HealResult
    data object Reenabled : HealResult

    /** Not bound again by the system in time: the app is being stopped and listed anew. */
    data object Restarting : HealResult

    /** Bound twice, the remote's keys repeating: the app is being stopped and listed anew. */
    data object RestartingBoundTwice : HealResult

    /** Down or bound twice, and no clean restart now (see [AccessibilityRestart.restartClean]). */
    data object WaitingForSystem : HealResult
}

/**
 * Brings our accessibility service back when the system doesn't run it, listed or not (another app
 * may have taken it off the list), and mends it bound twice. A service found unbound gets
 * [settleMillis] first: the system may be binding it right now (e.g. as the device wakes). With a
 * shell ([AccessibilityRestart]) it then gets [systemWaitMillis] more to be restarted by the
 * system, and after that our app is stopped and listed again in one go; without one, removing it
 * from the list and adding it again makes the system bind it. A service bound twice, seen so
 * twice [confirmMillis] apart, is restarted the same way. The user who wants it off turns this
 * off ("The remote's service turns itself on", which takes the right). All runs under [lock], so
 * a second heal waits for the first and then sees its result.
 */
class HealAccessibilityService(
    private val settings: SecureSettings,
    private val health: AccessibilityHealth,
    private val ourService: String,
    private val lock: HealLock,
    private val toggleDelayMillis: Long = 1_500,
    private val settleMillis: Long = 8_000,
    private val restart: AccessibilityRestart = AccessibilityRestart.None,
    private val systemWaitMillis: Long = 60_000,
    private val confirmMillis: Long = 10_000,
    private val interceptRemote: suspend () -> Boolean = { true },
) {
    suspend operator fun invoke(): HealResult {
        if (!interceptRemote()) return HealResult.Disabled
        if (health.isServiceBound()) {
            return if (boundTwice()) {
                lock.withLock {
                    mendBoundTwice()
                }
            } else {
                HealResult.AlreadyRunning
            }
        }
        if (!settings.canWrite) return HealResult.MissingPermission
        return lock.withLock { healUnbound() }
    }

    private suspend fun boundTwice(): Boolean = (restart.connections() ?: 0) > 1

    private suspend fun mendBoundTwice(): HealResult {
        delay(confirmMillis)
        return when {
            !interceptRemote() -> HealResult.Disabled
            !boundTwice() -> HealResult.AlreadyRunning
            restart.restartClean(ourServiceListed()) -> HealResult.RestartingBoundTwice
            else -> HealResult.WaitingForSystem
        }
    }

    private suspend fun healUnbound(): HealResult {
        delay(settleMillis)
        return when {
            !interceptRemote() -> HealResult.Disabled
            health.isServiceBound() -> HealResult.AlreadyRunning
            restart.connections() == null -> toggle()
            else -> restartUnbound()
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

    private suspend fun restartUnbound(): HealResult = when {
        systemRestarts() -> HealResult.AlreadyRunning
        !interceptRemote() -> HealResult.Disabled
        restart.restartClean(ourServiceListed()) -> HealResult.Restarting
        else -> HealResult.WaitingForSystem
    }

    /** Whether the system binds the service again within [systemWaitMillis]. */
    private suspend fun systemRestarts(): Boolean {
        repeat((systemWaitMillis / POLL_MILLIS).toInt()) {
            delay(POLL_MILLIS)
            if (health.isServiceBound()) return true
        }
        return false
    }

    private fun ourServiceListed(): String = AccessibilityServiceList.join(
        AccessibilityServiceList.withoutOurs(settings.enabledAccessibilityServices(), ourService) + ourService,
    )

    private companion object {
        const val POLL_MILLIS = 5_000L
    }
}
