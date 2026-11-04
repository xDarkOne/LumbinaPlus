package dev.lumbina.tv.platform.tvprovider

import android.os.SystemClock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** Null means the bridge failed, as distinct from a successful query with no rows. */
internal fun parseShellRows(output: String?, table: String): List<TvRow>? {
    val prefix = "$PREFIX$table\t"
    val json = output?.lineSequence()?.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix) ?: return null
    return try {
        val rows = JSONArray(json)
        List(rows.length()) { JsonRow(rows.getJSONObject(it)) }
    } catch (_: org.json.JSONException) {
        null
    }
}

/** Shares one shell process across the channel, program and watch-next readers. */
internal class ShellTvSnapshot(private val load: suspend () -> String?) {
    private val lock = Mutex()
    private var output: String? = null
    private var refreshedAt = Long.MIN_VALUE

    suspend fun rows(table: String): List<TvRow>? = lock.withLock {
        if (refreshedAt == Long.MIN_VALUE || SystemClock.elapsedRealtime() - refreshedAt >= SHARE_MILLIS) {
            output = load()
            refreshedAt = SystemClock.elapsedRealtime()
        }
        parseShellRows(output, table)
    }

    private companion object {
        const val SHARE_MILLIS = 300L
    }
}

private const val PREFIX = "TVPROVIDER_JSON="

private class JsonRow(private val row: JSONObject) : TvRow {
    override fun string(column: String): String? = row.opt(column)?.takeUnless { it == JSONObject.NULL }?.toString()

    override fun long(column: String): Long? = when (val value = row.opt(column)) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull()
        else -> null
    }
}
