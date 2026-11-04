package dev.lumbina.tv.core.domain.locale

enum class ViddaLanguage(val tag: String) {
    Russian("ru"),
    English("en"),
}

/** The firmware's locale, separate from the launcher-only language setting. */
interface ViddaSystemLanguage {
    val supported: Boolean

    val activeTag: String

    /** Verifies and remembers the firmware change. Null forgets the preference. */
    suspend fun select(language: ViddaLanguage?): Boolean

    /** Restores the saved preference if VIDAA reset it after boot. */
    suspend fun restore()
}
