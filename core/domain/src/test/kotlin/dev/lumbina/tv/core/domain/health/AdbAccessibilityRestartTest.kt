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

    // As Android 11 on the Vidda C2 Ultra prints it, a second connection left by a toggle.
    private val boundTwice = """
        ACTIVITY MANAGER SERVICES (dumpsys activity services)
          User 0 active services:
          * ServiceRecord{7d6ce2c u0 $ours}
            intent={cmp=$ours}
            Bindings:
            * IntentBindRecord{6e92a62 CREATE}:
              * Client AppBindRecord{3016cb0 ProcessRecord{2573e5a 1112:system/1000}}
                Per-process Connections:
                  ConnectionRecord{8062f07 u0 CR FGSA CAPS $ours:@222a146}
                  ConnectionRecord{969c916 u0 CR FGSA CAPS $ours:@3b12e31}
            All Connections:
              ConnectionRecord{8062f07 u0 CR FGSA CAPS $ours:@222a146}
              ConnectionRecord{969c916 u0 CR FGSA CAPS $ours:@3b12e31}

          * ServiceRecord{829b2a7 u0 dev.xdark.lumbinaplus/androidx.room.MultiInstanceInvalidationService}
            All Connections:
              ConnectionRecord{11541 u0 CR dev.xdark.lumbinaplus/androidx.room.MultiInstanceInvalidationService:@1}
              ConnectionRecord{1b15a13 u0 CR dev.xdark.lumbinaplus/androidx.room.MultiInstanceInvalidationService:@2}
    """.trimIndent()

    private var last = 0L
    private var clock = 100 * 60_000L

    private fun restart(output: String?) = FakeAdb(output).let {
        it to AdbAccessibilityRestart(it, ours, { last }, { at -> last = at }, { clock })
    }

    @Test
    fun `counts the distinct connections of our service only`() {
        assertEquals(2, AdbAccessibilityRestart.connectionsOf(boundTwice, ours))
        val once = boundTwice.lines().filterNot { "969c916" in it }.joinToString("\n")
        assertEquals(1, AdbAccessibilityRestart.connectionsOf(once, ours))
        assertEquals(0, AdbAccessibilityRestart.connectionsOf("  (nothing)", ours))
    }

    @Test
    fun `finds the short form Android prints for a class inside the package`() {
        val service = "dev.lumbina.tv/dev.lumbina.tv.platform.accessibility.LumbinaAccessibilityService"
        val dump = boundTwice.replace(ours, "dev.lumbina.tv/.platform.accessibility.LumbinaAccessibilityService")
        assertEquals(2, AdbAccessibilityRestart.connectionsOf(dump, service))
    }

    @Test
    fun `cannot count without adb`() = runTest {
        assertNull(restart(null).second.connections())
        assertEquals(2, restart(boundTwice).second.connections())
    }

    @Test
    fun `stops the app and lists it again from a background shell`() = runTest {
        val (adb, restart) = restart("")
        assertTrue(restart.restartClean("a/.A:$ours"))
        val command = adb.commands.single()
        assertTrue(command.startsWith("setsid nohup sh -c '"), command)
        assertTrue(command.endsWith(" </dev/null >/dev/null 2>&1 & sleep 1"), command)
        assertTrue(command.contains("am force-stop dev.xdark.lumbinaplus ; sleep 1 ; "), command)
        assertTrue(command.contains("enabled_accessibility_services '\\''a/.A:$ours'\\''"), command)
        assertEquals(clock, last)
    }

    @Test
    fun `refuses a second restart within ten minutes`() = runTest {
        val (adb, restart) = restart("")
        assertTrue(restart.restartClean(ours))
        clock += 9 * 60_000L
        assertFalse(restart.restartClean(ours))
        clock += 2 * 60_000L
        assertTrue(restart.restartClean(ours))
        assertEquals(2, adb.commands.size)
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
