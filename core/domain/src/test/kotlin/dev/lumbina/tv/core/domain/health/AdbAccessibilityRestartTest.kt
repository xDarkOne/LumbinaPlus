package dev.lumbina.tv.core.domain.health

import dev.lumbina.tv.core.domain.access.AdbState
import dev.lumbina.tv.core.domain.access.LocalAdb
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AdbAccessibilityRestartTest {

    private class FakeAdb(private val output: String?) : LocalAdb {
        val commands = mutableListOf<String>()

        override suspend fun state() = AdbState.Connected

        override suspend fun connect() = AdbState.Connected

        override suspend fun shell(command: String): String? {
            commands += command
            return output
        }
    }

    private val ours = "dev.xdark.lumbinaplus/dev.lumbina.tv.platform.accessibility.LumbinaAccessibilityService"

    private fun dump(crashed: String) = """
        User state[
             Bound services:{Service[label=app, feedbackType[FEEDBACK_GENERIC], capabilities=1]}
             Enabled services:{{dev.xdark.lumbinaplus/dev.lumbina.tv.platform.accessibility.LumbinaAccessibilityService}}
             Binding services:{}
             Crashed services:{$crashed}
             Client list info:{
                Client[0] : label=Lumbina,
    """.trimIndent()

    private fun restart(output: String?) = FakeAdb(output).let { it to AdbAccessibilityRestart(it, ours, "Lumbina") }

    @Test
    fun `finds our label among the crashed services only`() = runTest {
        val crashed = "Service[label=app, capabilities=1], \n  Service[label=Lumbina, feedbackType[FEEDBACK_GENERIC]]"
        assertEquals(true, restart(dump(crashed)).second.isCrashed())
        assertEquals(false, restart(dump("")).second.isCrashed())
    }

    @Test
    fun `cannot tell without adb or with an unknown dump`() = runTest {
        assertNull(restart(null).second.isCrashed())
        assertNull(restart("Can't find service: accessibility").second.isCrashed())
    }

    @Test
    fun `stops the app and lists it again from a background shell`() = runTest {
        val (adb, restart) = restart("")
        assertTrue(restart.restartClean("a/.A:$ours"))
        val command = adb.commands.single()
        assertTrue(command.startsWith("nohup sh -c '"), command)
        assertTrue(command.endsWith(" >/dev/null 2>&1 &"), command)
        assertTrue(command.contains("am force-stop dev.xdark.lumbinaplus ; sleep 1 ; "), command)
        assertTrue(command.contains("enabled_accessibility_services '\\''a/.A:$ours'\\''"), command)
    }

    @Test
    fun `quotes what the shell would expand`() {
        assertEquals("'a\$B'\\''c'", AdbAccessibilityRestart.quote("a\$B'c"))
    }

    @Test
    fun `reports no shell`() = runTest {
        assertFalse(restart(null).second.restartClean(ours))
    }
}
