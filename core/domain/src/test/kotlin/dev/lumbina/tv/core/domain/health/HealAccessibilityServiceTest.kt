package dev.lumbina.tv.core.domain.health

import dev.lumbina.tv.core.testing.FakeHealLock
import dev.lumbina.tv.core.testing.FakeSecureSettings
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HealAccessibilityServiceTest {

    private val ours = "dev.lumbina.tv/dev.lumbina.tv.platform.accessibility.LumbinaAccessibilityService"
    private val assistant = "com.example.assistant/.AssistantService"
    private val reader = "com.example.reader/.ReaderService"

    private fun heal(settings: FakeSecureSettings, bound: Boolean) = HealAccessibilityService(
        settings = settings,
        health = { bound },
        ourService = ours,
        lock = FakeHealLock(),
        toggleDelayMillis = 0,
        settleMillis = 0,
    )

    @Test
    fun `intentional opt out does not restore a missing service`() = runTest {
        val settings = FakeSecureSettings(services = assistant)
        val heal = HealAccessibilityService(settings, { false }, ours, FakeHealLock(), interceptRemote = { false })
        assertEquals(HealResult.Disabled, heal())
        assertTrue(settings.writes.isEmpty())
    }

    @Test
    fun `opt out during a heal keeps our service off and preserves other services`() = runTest {
        val settings = FakeSecureSettings(services = "$assistant:$ours")
        var intercept = true
        val heal = HealAccessibilityService(
            settings,
            { false },
            ours,
            FakeHealLock(),
            toggleDelayMillis = 1_500,
            settleMillis = 0,
            interceptRemote = { intercept },
        )
        val result = async { heal() }
        runCurrent()
        assertEquals(listOf(assistant), settings.writes)
        intercept = false
        assertEquals(HealResult.Disabled, result.await())
        assertEquals(listOf(assistant), settings.writes)
    }

    @Test
    fun `nothing is written while the service runs`() = runTest {
        val settings = FakeSecureSettings(services = "$assistant:$ours")
        assertEquals(HealResult.AlreadyRunning, heal(settings, bound = true)())
        assertTrue(settings.writes.isEmpty())
    }

    @Test
    fun `without the grant it reports instead of writing`() = runTest {
        val settings = FakeSecureSettings(services = assistant, canWrite = false)
        assertEquals(HealResult.MissingPermission, heal(settings, bound = false)())
        assertTrue(settings.writes.isEmpty())
    }

    @Test
    fun `toggles our entry off and on, dropping duplicates`() = runTest {
        val settings = FakeSecureSettings(services = "$assistant:$reader:$ours:$assistant:$reader")
        assertEquals(HealResult.Reenabled, heal(settings, bound = false)())
        assertEquals(listOf("$assistant:$reader", "$assistant:$reader:$ours"), settings.writes)
        assertTrue(settings.accessibilityEnabled)
    }

    @Test
    fun `works when the list is empty or null`() = runTest {
        val settings = FakeSecureSettings(services = "null")
        heal(settings, bound = false)()
        assertEquals(listOf("", ours), settings.writes)
    }

    @Test
    fun `short and long forms of our component count as ours`() {
        val list = "$assistant:dev.lumbina.tv/.platform.accessibility.LumbinaAccessibilityService"
        assertEquals(listOf(assistant), AccessibilityServiceList.withoutOurs(list, ours))
    }

    @Test
    fun `leaves a service the system binds meanwhile alone`() = runTest {
        val settings = FakeSecureSettings(services = ours)
        var checks = 0
        val heal =
            HealAccessibilityService(settings, { checks++ > 0 }, ours, FakeHealLock(), 0, settleMillis = 5_000)
        assertEquals(HealResult.AlreadyRunning, heal())
        assertTrue(settings.writes.isEmpty())
    }

    @Test
    fun `two heals at once toggle once, the second waits and finds the service bound`() = runTest {
        val settings = FakeSecureSettings(services = ours)
        // The system binds the service once our entry is written back.
        val lock = FakeHealLock()
        fun heal() = HealAccessibilityService(settings, { settings.writes.size >= 2 }, ours, lock, 1_500, 8_000)
        val first = async { heal()() }
        val second = async { heal()() }
        assertEquals(setOf(HealResult.Reenabled, HealResult.AlreadyRunning), setOf(first.await(), second.await()))
        assertEquals(2, settings.writes.size)
    }

    @Test
    fun `a heal cancelled between turning the service off and on still turns it back on`() = runTest {
        val settings = FakeSecureSettings(services = "$assistant:$ours")
        val heal = HealAccessibilityService(settings, { false }, ours, FakeHealLock(), 1_500, settleMillis = 0)
        val job = launch { heal() }
        runCurrent()
        assertEquals(listOf(assistant), settings.writes)
        job.cancel()
        advanceUntilIdle()
        assertEquals(listOf(assistant, "$assistant:$ours"), settings.writes)
    }

    /** A shell that reports [counts] in turn (the last one from then on), or none for null. */
    private class FakeRestart(vararg counts: Int?, private val allowed: Boolean = true) : AccessibilityRestart {
        private val counts = ArrayDeque(counts.toList())
        val restarts = mutableListOf<String>()

        override suspend fun connections(): Int? = if (counts.size > 1) counts.removeFirst() else counts.first()

        override suspend fun restartClean(services: String): Boolean {
            if (allowed) restarts += services
            return allowed
        }
    }

    private fun shellHeal(settings: FakeSecureSettings, restart: AccessibilityRestart, bound: () -> Boolean) =
        HealAccessibilityService(settings, bound, ours, FakeHealLock(), 0, 0, restart)

    @Test
    fun `with a shell an unbound service the system brings back in time is left alone`() = runTest {
        val settings = FakeSecureSettings(services = "$assistant:$ours")
        val restart = FakeRestart(0)
        var checks = 0
        assertEquals(HealResult.AlreadyRunning, shellHeal(settings, restart) { checks++ >= 3 }())
        assertTrue(settings.writes.isEmpty())
        assertTrue(restart.restarts.isEmpty())
    }

    @Test
    fun `with a shell the list is never toggled, the app is restarted cleanly with our entry listed`() = runTest {
        val settings = FakeSecureSettings(services = "$assistant:$ours:$assistant")
        val restart = FakeRestart(1)
        assertEquals(HealResult.Restarting, shellHeal(settings, restart) { false }())
        assertTrue(settings.writes.isEmpty())
        assertEquals(listOf("$assistant:$ours"), restart.restarts)
    }

    @Test
    fun `a restart refused leaves the service to the system, the list untouched`() = runTest {
        val settings = FakeSecureSettings(services = ours)
        val restart = FakeRestart(0, allowed = false)
        assertEquals(HealResult.WaitingForSystem, shellHeal(settings, restart) { false }())
        assertTrue(settings.writes.isEmpty())
    }

    @Test
    fun `turning interception off during the wait stops the restart`() = runTest {
        val settings = FakeSecureSettings(services = ours)
        val restart = FakeRestart(0)
        var intercept = true
        val heal = HealAccessibilityService(settings, { false }, ours, FakeHealLock(), 0, 0, restart) { intercept }
        val result = async { heal() }
        runCurrent()
        intercept = false
        assertEquals(HealResult.Disabled, result.await())
        assertTrue(restart.restarts.isEmpty())
    }

    @Test
    fun `without a shell the list is toggled`() = runTest {
        val settings = FakeSecureSettings(services = ours)
        assertEquals(HealResult.Reenabled, shellHeal(settings, FakeRestart(null)) { false }())
        assertEquals(listOf("", ours), settings.writes)
    }

    @Test
    fun `a service bound twice, still so after a while, is restarted cleanly`() = runTest {
        val settings = FakeSecureSettings(services = "$assistant:$ours")
        val restart = FakeRestart(2, 2)
        assertEquals(HealResult.RestartingBoundTwice, shellHeal(settings, restart) { true }())
        assertEquals(listOf("$assistant:$ours"), restart.restarts)
        assertTrue(settings.writes.isEmpty())
    }

    @Test
    fun `a second connection that goes away by itself is left alone`() = runTest {
        val restart = FakeRestart(2, 1)
        assertEquals(HealResult.AlreadyRunning, shellHeal(FakeSecureSettings(services = ours), restart) { true }())
        assertTrue(restart.restarts.isEmpty())
    }

    @Test
    fun `a service bound twice is restarted even without the settings grant`() = runTest {
        val settings = FakeSecureSettings(services = ours, canWrite = false)
        assertEquals(HealResult.RestartingBoundTwice, shellHeal(settings, FakeRestart(2)) { true }())
    }

    @Test
    fun `bound twice with the restart refused waits`() = runTest {
        val restart = FakeRestart(2, allowed = false)
        assertEquals(HealResult.WaitingForSystem, shellHeal(FakeSecureSettings(services = ours), restart) { true }())
    }

    @Test
    fun `bound once, nothing is done`() = runTest {
        val restart = FakeRestart(1)
        assertEquals(HealResult.AlreadyRunning, shellHeal(FakeSecureSettings(services = ours), restart) { true }())
        assertTrue(restart.restarts.isEmpty())
    }
}
