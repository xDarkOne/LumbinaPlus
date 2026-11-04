package dev.lumbina.tv.platform.system

import dev.lumbina.tv.core.domain.access.AccessSettings
import dev.lumbina.tv.core.domain.access.AdbState
import dev.lumbina.tv.core.domain.access.LocalAdb
import dev.lumbina.tv.core.domain.device.DeviceFingerprint
import dev.lumbina.tv.core.domain.device.DeviceProfile
import dev.lumbina.tv.core.domain.locale.ViddaLanguage
import dev.lumbina.tv.core.domain.remote.KeyMap
import dev.lumbina.tv.core.testing.FakeHealLock
import dev.lumbina.tv.core.testing.FakeSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AndroidViddaSystemLanguageTest {
    private val calls = mutableListOf<String>()
    private val settings = FakeSettings(AccessSettings())
    private var answer: (String) -> String? = { null }
    private val adb = object : LocalAdb {
        override suspend fun state() = AdbState.Connected

        override suspend fun connect() = AdbState.Connected

        override suspend fun shell(command: String): String? {
            calls.add(command)
            return answer(command)
        }
    }
    private val profile = object : DeviceProfile {
        override val id = "hisense-vidaa"
        override val priority = 0
        override val keyMap = KeyMap { _, _ -> null }
        override val stockLauncher = null
        override val localAdb = true

        override fun matches(device: DeviceFingerprint) = true
    }
    private val locale = AndroidViddaSystemLanguage(
        RuntimeEnvironment.getApplication(),
        adb,
        profile,
        settings,
        FakeHealLock(),
    )

    @Test
    fun `saving a language requires readback from firmware`() = runBlocking {
        answer = { "ru-RU" }
        assertTrue(locale.select(ViddaLanguage.Russian))
        assertEquals(ViddaLanguage.Russian, settings.current.value.systemLanguage)
        assertEquals(1, calls.size)
    }

    @Test
    fun `a refused change does not replace the saved choice`() = runBlocking {
        settings.update { it.copy(systemLanguage = ViddaLanguage.Russian) }
        answer = { "ru" }
        assertFalse(locale.select(ViddaLanguage.English))
        assertEquals(ViddaLanguage.Russian, settings.current.value.systemLanguage)
        assertEquals(2, calls.size)
    }

    @Test
    fun `restores the stored language when the firmware reset the property`() = runBlocking {
        settings.update { it.copy(systemLanguage = ViddaLanguage.Russian) }
        answer = { if (it.startsWith("CLASSPATH=")) "locale -> ru\nru" else "en" }
        locale.restore()
        assertEquals("getprop persist.sys.locale", calls.first())
        assertEquals(2, calls.size)
        assertTrue(calls.last().contains("ViddaLocaleTool ru"))
    }

    @Test
    fun `forgetting the choice does not change system language`() = runBlocking {
        settings.update { it.copy(systemLanguage = ViddaLanguage.Russian) }
        assertTrue(locale.select(null))
        locale.restore()
        assertNull(settings.current.value.systemLanguage)
        assertTrue(calls.isEmpty())
    }
}
