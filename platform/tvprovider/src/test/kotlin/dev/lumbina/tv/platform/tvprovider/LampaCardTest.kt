package dev.lumbina.tv.platform.tvprovider

import dev.lumbina.tv.core.common.LampaWatchNext
import dev.lumbina.tv.core.domain.channels.ProgramCard
import org.junit.Assert.assertEquals
import org.junit.Test

class LampaCardTest {
    private val fallback = ProgramCard(LampaWatchNext.CARD_ID, LampaWatchNext.LAMPA, "Movie", null, null, null, null)

    @Test
    fun `publisher card takes priority over local fallback`() {
        val published = fallback.copy(id = 11, title = " movie ")
        assertEquals(listOf(published), mergeLocalCards(listOf(published to 1L), null, fallback to 2L))
    }

    @Test
    fun `VoiceKey card takes priority during transition`() {
        val published = fallback.copy(id = 11, packageName = "dev.xdark.voicekey")
        assertEquals(listOf(published), mergeLocalCards(listOf(published to 1L), null, fallback to 2L))
    }

    @Test
    fun `other application with same title does not hide fallback`() {
        val other = fallback.copy(id = 11, packageName = "other.app")
        assertEquals(listOf(fallback, other), mergeLocalCards(listOf(other to 1L), null, fallback to 2L))
    }

    @Test
    fun `fallbacks and provider cards follow playback time`() {
        val first = fallback.copy(id = 1, packageName = "tizen", title = "clip")
        val last = fallback.copy(id = 2, packageName = "other", title = "other")
        assertEquals(listOf(first, fallback, last), mergeLocalCards(listOf(last to 1L), first to 3L, fallback to 2L))
    }
}
