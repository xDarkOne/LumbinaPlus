package dev.lumbina.tv.core.common

/** Shared storage for the local TizenTube continue-watching card. */
object TizenTubeWatchNext {
    const val PACKAGE = "io.gh.reisxd.tizentube.cobalt"
    const val ACTIVITY = "dev.cobalt.app.MainActivity"
    val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")

    const val PREFERENCES = "tizentube_watch_next"
    const val TITLE = "title"
    const val ARTIST = "artist"
    const val MEDIA_ID = "media_id"
    const val DURATION = "duration"
    const val POSITION = "position"
    const val ARTWORK = "artwork"
    const val UPDATED = "updated"
    const val CARD_ID = -20261103L
}
