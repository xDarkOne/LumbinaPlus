package dev.lumbina.tv.platform.system

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.lumbina.tv.core.common.LampaWatchNext as Watch

/** Records Vimu only when the window watcher saw LAMPA launch it. */
internal class LampaVimuHistory(context: Context, private val listener: ComponentName) {
    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val prefs = context.getSharedPreferences(Watch.PREFERENCES, Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private var enteredAt = 0L
    private var title: String? = null
    private var watched: MediaController? = null
    private var latest: Sample? = null
    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            if (enteredAt == 0L) return
            metadata.title()?.let { title = it }
            rememberSample()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            rememberSample(state)
        }

        override fun onSessionDestroyed() {
            watched?.unregisterCallback(this)
            watched = null
        }
    }
    private val sessions = MediaSessionManager.OnActiveSessionsChangedListener { follow(it.orEmpty()) }

    fun start() {
        try {
            manager.addOnActiveSessionsChangedListener(sessions, listener, handler)
        } catch (_: SecurityException) {
            // Notification access may have been withdrawn before the listener was disconnected.
        }
    }

    fun stop() {
        runCatching { manager.removeOnActiveSessionsChangedListener(sessions) }
        watched?.unregisterCallback(callback)
        watched = null
        enteredAt = 0
    }

    fun onWindow(destination: String) {
        when (destination) {
            Watch.ENTERED -> {
                enteredAt = SystemClock.elapsedRealtime()
                title = null
                latest = null
                follow(activeSessions())
            }
            Watch.RETURNED -> {
                val name = title ?: latest?.title
                if (name != null && prefs.getString(Watch.TITLE, null) == name) prefs.edit().clear().apply()
                finishVisit()
            }
            Watch.LEFT -> {
                saveIfCurrent()
                finishVisit()
            }
        }
    }

    private fun finishVisit() {
        enteredAt = 0
        latest = null
        watched?.unregisterCallback(callback)
        watched = null
    }

    private fun activeSessions(): List<MediaController> = try {
        manager.getActiveSessions(listener)
    } catch (_: SecurityException) {
        emptyList()
    }

    private fun follow(controllers: List<MediaController>) {
        if (enteredAt == 0L) return
        val newest = controllers.filter { it.packageName == Watch.VIMU }
            .maxByOrNull { it.playbackState?.lastPositionUpdateTime ?: Long.MIN_VALUE }
        if (newest?.sessionToken == watched?.sessionToken) {
            newest?.metadata.title()?.let { title = it }
            rememberSample()
            return
        }
        watched?.unregisterCallback(callback)
        watched = newest
        newest?.registerCallback(callback, handler)
        newest?.metadata.title()?.let { title = it }
        rememberSample()
    }

    private fun rememberSample(state: PlaybackState? = watched?.playbackState) {
        if (enteredAt == 0L || state == null) return
        val controller = watched ?: return
        val metadata = controller.metadata ?: return
        val name = metadata.title() ?: return
        if (name != title) return
        if (state.lastPositionUpdateTime < enteredAt - SESSION_GRACE) return
        val extra = if (state.state == PlaybackState.STATE_PLAYING && state.lastPositionUpdateTime > 0) {
            ((SystemClock.elapsedRealtime() - state.lastPositionUpdateTime).coerceAtLeast(0) * state.playbackSpeed)
                .toLong()
        } else {
            0L
        }
        val position = state.position + extra
        latest = Sample(name, position, metadata.getLong(MediaMetadata.METADATA_KEY_DURATION))
    }

    private fun saveIfCurrent() {
        if (enteredAt == 0L) return
        rememberSample()
        val sample = latest?.takeIf { it.title == title } ?: return
        if (sample.duration > 0 && sample.position >= sample.duration * DONE_SHARE) {
            if (prefs.getString(Watch.TITLE, null) == sample.title) prefs.edit().clear().apply()
            return
        }
        if (sample.position < MIN_POSITION) return
        prefs.edit().putString(Watch.TITLE, sample.title).putLong(Watch.POSITION, sample.position)
            .putLong(Watch.DURATION, sample.duration).putLong(Watch.UPDATED, System.currentTimeMillis()).apply()
    }

    private fun MediaMetadata?.title(): String? =
        (this?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: this?.description?.title?.toString())
            ?.trim()?.takeIf { it.isNotEmpty() }

    private data class Sample(val title: String, val position: Long, val duration: Long)

    private companion object {
        const val DONE_SHARE = 0.95
        const val MIN_POSITION = 5_000L
        const val SESSION_GRACE = 10_000L
    }
}
