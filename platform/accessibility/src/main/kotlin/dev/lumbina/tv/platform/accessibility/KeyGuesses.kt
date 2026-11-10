package dev.lumbina.tv.platform.accessibility

import java.net.InetAddress

/**
 * Wrong phone-remote keys per address: after [limit] within [windowMillis] the address is shut out
 * until that time has passed, the right key included, so the key can't be guessed by trying.
 */
internal class KeyGuesses(
    private val limit: Int = MAX_WRONG_KEYS,
    private val windowMillis: Long = GUESS_WINDOW_MILLIS,
    private val now: () -> Long = System::currentTimeMillis,
    private val onLimit: (InetAddress) -> Unit = {},
) {
    private class Tries(var count: Int, var since: Long)

    private val tries = mutableMapOf<InetAddress, Tries>()

    fun shutOut(address: InetAddress): Boolean = synchronized(tries) {
        val tried = tries[address] ?: return false
        if (now() - tried.since > windowMillis) {
            tries.remove(address)
            return false
        }
        tried.count >= limit
    }

    fun wrong(address: InetAddress) = synchronized(tries) {
        val at = now()
        val tried = tries.getOrPut(address) { Tries(0, at) }
        if (at - tried.since > windowMillis) {
            tried.count = 0
            tried.since = at
        }
        tried.count++
        if (tried.count == limit) onLimit(address)
    }

    private companion object {
        const val MAX_WRONG_KEYS = 20
        const val GUESS_WINDOW_MILLIS = 600_000L
    }
}
