package dev.lumbina.tv.core.domain.diagnostics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReportPrivacyTest {

    @Test
    fun `recognized phrases and their readings keep only their length`() {
        assertEquals(
            "voice: \"…9\" (1200 ms audio, ru=0.90\"…9\", en=0.62\"…9\")",
            privateLogLine("voice: \"один дома\" (1200 ms audio, ru=0.90\"один дома\", en=0.62\"one doma.\")"),
        )
    }

    @Test
    fun `links keep only their host`() {
        assertEquals(
            "phone: open https://rutube.ru/…: failed",
            privateLogLine("phone: open https://rutube.ru/video/abc?t=10: failed"),
        )
    }

    @Test
    fun `plain lines pass unchanged`() {
        val line = "11-10 15:43:17.000 [remote] heal: periodic: AlreadyRunning"
        assertEquals(line, privateLogLine(line))
    }
}
