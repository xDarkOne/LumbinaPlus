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
import dev.lumbina.tv.core.common.LumbinaLog
import dev.lumbina.tv.core.common.TizenTubeWatchNext as Card
import java.io.File
import java.io.IOException

/** Keeps the last partly watched TizenTube video when its media session is available. */
internal class TizenTubeHistory(
    private val context: Context,
    private val listener: ComponentName,
    private val log: LumbinaLog,
) {
    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val preferences = context.getSharedPreferences(Card.PREFERENCES, Context.MODE_PRIVATE)
    private var controller: MediaController? = null
    private var title: String? = null
    private var artist: String? = null
    private var mediaId: String? = null
    private var duration = 0L
    private var position = 0L
    private var positionAt = 0L
    private var speed = 0f
    private var playing = false
    private var artwork: Bitmap? = null
    private var artworkTitle: String? = null
    private var oldArtwork: Bitmap? = null

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            val nextTitle = metadata?.title() ?: return
            if (nextTitle != title) save()
            read(metadata, nextTitle)
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            val wasPlaying = playing
            readState(state)
            if (wasPlaying && !playing) save()
        }

        override fun onSessionDestroyed() {
            save()
            detach()
        }
    }
    private val sessions = MediaSessionManager.OnActiveSessionsChangedListener { attach(it.orEmpty()) }
    private val tick = object : Runnable {
        override fun run() {
            if (playing) save()
            handler.postDelayed(this, SAVE_INTERVAL)
        }
    }

    fun start() {
        try {
            manager.addOnActiveSessionsChangedListener(sessions, listener, handler)
            attach(manager.getActiveSessions(listener))
            handler.postDelayed(tick, SAVE_INTERVAL)
        } catch (e: SecurityException) {
            log.log(TAG, "media session access unavailable: $e")
        }
    }

    fun stop() {
        handler.removeCallbacks(tick)
        runCatching { manager.removeOnActiveSessionsChangedListener(sessions) }
        save()
        detach()
    }

    private fun attach(controllers: List<MediaController>) {
        val next = controllers.firstOrNull { it.packageName == Card.PACKAGE }
        if (next?.sessionToken == controller?.sessionToken) return
        save()
        detach()
        if (next == null) return
        controller = next
        next.registerCallback(callback, handler)
        next.metadata?.let { metadata -> metadata.title()?.let { read(metadata, it) } }
        readState(next.playbackState)
    }

    private fun detach() {
        controller?.unregisterCallback(callback)
        controller = null
        title = null
        artist = null
        mediaId = null
        duration = 0L
        position = 0L
        positionAt = 0L
        playing = false
        artwork = null
        artworkTitle = null
        oldArtwork = null
    }

    private fun MediaMetadata.title(): String? =
        (getString(MediaMetadata.METADATA_KEY_TITLE) ?: getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE))
            ?.takeIf { it.isNotBlank() }

    private fun read(metadata: MediaMetadata, nextTitle: String) {
        title = nextTitle
        artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
        mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
        duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val next = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        if (nextTitle == artworkTitle) {
            if (next != null) artwork = next
        } else if (next != null && (artwork?.sameAs(next) == true || oldArtwork?.sameAs(next) == true)) {
            if (artwork != null) oldArtwork = artwork
            artwork = null
        } else {
            oldArtwork = null
            artwork = next
            if (next != null) artworkTitle = nextTitle
        }
    }

    private fun readState(state: PlaybackState?) {
        if (state == null) return
        playing = state.state == PlaybackState.STATE_PLAYING
        position = state.position
        positionAt = state.lastPositionUpdateTime
        speed = state.playbackSpeed
    }

    private fun save() {
        val name = title ?: return
        val elapsed = if (playing && positionAt > 0) SystemClock.elapsedRealtime() - positionAt else 0L
        val at = (position + (elapsed.coerceAtLeast(0) * speed).toLong()).coerceAtLeast(0)
        if (duration >= MIN_DURATION && at >= duration * DONE_SHARE) {
            val savedId = preferences.getString(Card.MEDIA_ID, null)
            val sameVideo = if (mediaId != null && savedId != null) mediaId == savedId else {
                preferences.getString(Card.TITLE, null) == name
            }
            if (sameVideo) preferences.edit().clear().apply()
            return
        }
        if (at < MIN_POSITION || duration in 1 until MIN_DURATION) return
        val artPath = try {
            artwork?.let { bitmap ->
                val artworkFile = File(context.filesDir, "tizentube-${(mediaId ?: name).hashCode()}.jpg")
                val temporary = File(context.filesDir, "tizentube-artwork.tmp")
                val saved = temporary.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                if (saved && temporary.renameTo(artworkFile)) artworkFile.absolutePath else null
            }
        } catch (e: IOException) {
            log.log(TAG, "artwork write failed: $e")
            null
        }
        preferences.edit().putString(Card.TITLE, name).putString(Card.ARTIST, artist)
            .putString(Card.MEDIA_ID, mediaId).putLong(Card.DURATION, duration)
            .putLong(Card.POSITION, at).putString(Card.ARTWORK, artPath)
            .putLong(Card.UPDATED, System.currentTimeMillis()).apply()
    }

    private companion object {
        const val TAG = "tizentube-history"
        const val SAVE_INTERVAL = 60_000L
        const val MIN_DURATION = 60_000L
        const val MIN_POSITION = 5_000L
        const val DONE_SHARE = 0.95
    }
}
