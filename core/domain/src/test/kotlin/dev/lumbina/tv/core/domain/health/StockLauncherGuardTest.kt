package dev.lumbina.tv.core.domain.health

import dev.lumbina.tv.core.domain.access.AdbState
import dev.lumbina.tv.core.domain.access.LocalAdb
import dev.lumbina.tv.core.domain.device.PackageState
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StockLauncherGuardTest {

    private class Adb(private val answer: (String) -> String?) : LocalAdb {
        val commands = mutableListOf<String>()
        override suspend fun state() = AdbState.Connected
        override suspend fun connect() = AdbState.Connected
        override suspend fun shell(command: String): String? {
            commands += command
            return answer(command)
        }
    }

    private val home = "dev.lumbina.tv/.MainActivity"
    private val ok = { _: String -> "Package com.oversea.aslauncher new state: disabled-user" }

    private fun guard(
        adb: Adb,
        state: PackageState = PackageState.Enabled,
        intercept: Boolean = true,
        ourHome: String? = home,
    ) = StockLauncherGuard({ state }, adb, { ourHome }, { intercept })

    @Test
    fun `turns the stock launcher off and points Home at Lumbina`() = runTest {
        val adb = Adb(ok)
        assertEquals(StockLauncherResult.Disabled, guard(adb)())
        assertEquals(
            listOf("cmd package set-home-activity $home", "pm disable-user --user 0 com.oversea.aslauncher"),
            adb.commands,
        )
    }

    @Test
    fun `does nothing when Lumbina is not meant to catch Home`() = runTest {
        val adb = Adb(ok)
        assertEquals(StockLauncherResult.NotNeeded, guard(adb, intercept = false)())
        assertTrue(adb.commands.isEmpty())
    }

    @Test
    fun `does nothing when the launcher is absent or already off`() = runTest {
        for (state in listOf(PackageState.Missing, PackageState.Disabled)) {
            val adb = Adb(ok)
            assertEquals(StockLauncherResult.NotNeeded, guard(adb, state)())
            assertTrue(adb.commands.isEmpty())
        }
    }

    @Test
    fun `leaves the launcher alone without a home to point at`() = runTest {
        val adb = Adb(ok)
        assertEquals(StockLauncherResult.NotNeeded, guard(adb, ourHome = null)())
        assertTrue(adb.commands.isEmpty())
    }

    @Test
    fun `without debugging nothing is turned off`() = runTest {
        val adb = Adb { null }
        assertEquals(StockLauncherResult.NotConnected, guard(adb)())
        assertEquals(1, adb.commands.size)
    }

    @Test
    fun `reports a refusal`() = runTest {
        val adb = Adb { if (it.startsWith("pm")) "Error: refused" else "" }
        assertEquals(StockLauncherResult.Refused, guard(adb)())
    }
}
