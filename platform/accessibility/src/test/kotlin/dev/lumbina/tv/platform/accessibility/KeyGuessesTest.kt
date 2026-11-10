package dev.lumbina.tv.platform.accessibility

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyGuessesTest {

    private val phone = InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 20))
    private val other = InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 21))

    @Test
    fun `an address is shut out after the limit and let in again when the window passes`() {
        var clock = 0L
        val limited = mutableListOf<InetAddress>()
        val guesses = KeyGuesses(limit = 3, windowMillis = 1_000, now = { clock }, onLimit = { limited += it })
        repeat(2) { guesses.wrong(phone) }
        assertFalse(guesses.shutOut(phone))
        guesses.wrong(phone)
        assertTrue(guesses.shutOut(phone))
        assertFalse(guesses.shutOut(other))
        assertEquals(listOf(phone), limited)
        clock = 1_001
        assertFalse(guesses.shutOut(phone))
    }

    @Test
    fun `old wrong keys stop counting`() {
        var clock = 0L
        val guesses = KeyGuesses(limit = 3, windowMillis = 1_000, now = { clock })
        repeat(2) { guesses.wrong(phone) }
        clock = 2_000
        repeat(2) { guesses.wrong(phone) }
        assertFalse(guesses.shutOut(phone))
    }
}
