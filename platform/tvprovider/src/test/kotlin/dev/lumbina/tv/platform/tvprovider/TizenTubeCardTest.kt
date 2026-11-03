package dev.lumbina.tv.platform.tvprovider

import dev.lumbina.tv.core.common.TizenTubeWatchNext
import dev.lumbina.tv.core.domain.channels.ProgramCard
import org.junit.Assert.assertEquals
import org.junit.Test

class TizenTubeCardTest {
    private val fallback = ProgramCard(
        TizenTubeWatchNext.CARD_ID, TizenTubeWatchNext.PACKAGE, "Video", null, null, null, null,
    )

    @Test
    fun `provider card from VoiceKey wins for the same video`() {
        val fromVoiceKey = fallback.copy(id = 1, packageName = "dev.xdark.voicekey", title = " VIDEO ")
        assertEquals(listOf(fromVoiceKey), mergeTizenTubeCard(listOf(fromVoiceKey to 1L), fallback to 2L))
    }

    @Test
    fun `different videos and unrelated apps remain visible`() {
        val other = fallback.copy(id = 2, packageName = "tv.other")
        val anotherVideo = fallback.copy(id = 3, title = "Other")
        assertEquals(listOf(fallback, other, anotherVideo), mergeTizenTubeCard(listOf(other to 2L, anotherVideo to 1L), fallback to 3L))
    }

    @Test
    fun `newer provider cards come before older fallback`() {
        val newer = fallback.copy(id = 4, packageName = "tv.other")
        assertEquals(listOf(newer, fallback), mergeTizenTubeCard(listOf(newer to 4L), fallback to 3L))
    }

    @Test
    fun `no fallback keeps the published cards`() {
        assertEquals(listOf(fallback), mergeTizenTubeCard(listOf(fallback to 1L), null))
    }
}
