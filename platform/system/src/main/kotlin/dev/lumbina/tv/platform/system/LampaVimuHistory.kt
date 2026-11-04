package dev.lumbina.tv.platform.system

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.lumbina.tv.core.common.LampaWatchNext as Watch
import dev.lumbina.tv.core.common.LumbinaLog
import java.io.File
import java.io.IOException

/** Records Vimu only when the window watcher saw LAMPA launch it. */
internal class LampaVimuHistory(
    private val context: Context,
    private val listener: ComponentName,
    private val log: LumbinaLog,
) {
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
                if (name != null && prefs.getString(Watch.TITLE, null) == cleanTitle(name)) prefs.edit().clear().apply()
                finishVisit()
            }
            Watch.HOME_FROM_PLAYER -> {
                val controller = activeSessions().filter { it.packageName == Watch.VIMU }
                    .maxByOrNull { it.playbackState?.lastPositionUpdateTime ?: 0L }
                val state = controller?.playbackState
                val metadata = controller?.metadata
                val rawTitle = metadata.title()
                if (state != null && metadata != null && rawTitle != null) {
                    val name = cleanTitle(rawTitle)
                    if (name.isNotEmpty()) store(sample(name, state, metadata))
                }
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
        latest = sample(name, state, metadata)
    }

    private fun position(state: PlaybackState): Long {
        val extra = if (state.state == PlaybackState.STATE_PLAYING && state.lastPositionUpdateTime > 0) {
            ((SystemClock.elapsedRealtime() - state.lastPositionUpdateTime).coerceAtLeast(0) * state.playbackSpeed)
                .toLong()
        } else {
            0L
        }
        return state.position + extra
    }

    private fun saveIfCurrent() {
        if (enteredAt == 0L) return
        rememberSample()
        val sample = latest?.takeIf { it.title == title } ?: return
        store(sample.copy(title = cleanTitle(sample.title)))
    }

    private fun store(sample: Sample) {
        if (sample.duration > 0 && sample.position >= sample.duration * DONE_SHARE) {
            if (prefs.getString(Watch.TITLE, null) == sample.title) prefs.edit().clear().apply()
            return
        }
        if (sample.position < MIN_POSITION) return
        val previousArt = prefs.getString(Watch.ARTWORK, null)
        val art = savePoster(sample) ?: previousArt.takeIf { prefs.getString(Watch.TITLE, null) == sample.title }
        log.log("lampa-history", "saved player position, poster=${art != null}")
        prefs.edit().putString(Watch.TITLE, sample.title).putLong(Watch.POSITION, sample.position)
            .putLong(Watch.DURATION, sample.duration).putLong(Watch.UPDATED, System.currentTimeMillis())
            .putString(Watch.ARTWORK, art).apply()
        previousArt?.takeIf { it != art }?.let { File(it).delete() }
    }

    private fun sample(title: String, state: PlaybackState, metadata: MediaMetadata): Sample {
        val art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
        return Sample(title, position(state), metadata.getLong(MediaMetadata.METADATA_KEY_DURATION), art)
    }

    private fun savePoster(sample: Sample): String? {
        val art = sample.poster ?: return null
        val file = File(context.filesDir, "lampa-poster-${sample.title.hashCode()}.jpg")
        if (file.isFile) return file.absolutePath
        val temporary = File(context.filesDir, "lampa-poster.tmp")
        return try {
            val saved = temporary.outputStream().use { art.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            file.absolutePath.takeIf { saved && temporary.renameTo(file) }
        } catch (e: IOException) {
            log.log("lampa-history", "could not store poster: $e")
            null
        } finally {
            temporary.delete()
        }
    }

    private fun MediaMetadata?.title(): String? =
        (this?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: this?.description?.title?.toString())
            ?.trim()?.takeIf { it.isNotEmpty() }

    private fun cleanTitle(title: String): String =
        if (title.startsWith(LAMPA_PREFIX, ignoreCase = true)) title.drop(LAMPA_PREFIX.length).trim() else title

    private data class Sample(val title: String, val position: Long, val duration: Long, val poster: Bitmap?)

    private companion object {
        const val LAMPA_PREFIX = "[LAMPA]"
        const val DONE_SHARE = 0.95
        const val MIN_POSITION = 5_000L
        const val SESSION_GRACE = 10_000L
    }
}
