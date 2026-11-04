package dev.lumbina.tv.platform.accessibility

import android.accessibilityservice.AccessibilityService
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import dev.lumbina.tv.core.common.LumbinaActions
import dev.lumbina.tv.core.common.LumbinaLog
import dev.lumbina.tv.core.domain.app.App
import dev.lumbina.tv.core.domain.app.AppLauncher
import dev.lumbina.tv.core.domain.app.InstalledApps
import dev.lumbina.tv.core.domain.home.HomeLayoutRepository
import dev.lumbina.tv.core.domain.sleep.SleepSettingsRepository
import dev.lumbina.tv.core.domain.speech.AudioSource
import dev.lumbina.tv.core.domain.speech.SpeechEngine
import dev.lumbina.tv.core.domain.speech.VoiceLanguageMode
import dev.lumbina.tv.core.domain.speech.VoiceLanguageSetting
import dev.lumbina.tv.core.domain.speech.VoiceSettingsRepository
import dev.lumbina.tv.core.domain.speech.WarmAudioSource
import dev.lumbina.tv.core.domain.text.AnyFieldSink
import dev.lumbina.tv.core.domain.text.DeliverText
import dev.lumbina.tv.core.domain.text.FocusedFieldSink
import dev.lumbina.tv.core.domain.text.LauncherSearchSink
import dev.lumbina.tv.core.domain.text.OnScreenKeyboardSink
import dev.lumbina.tv.core.domain.text.Search
import dev.lumbina.tv.core.domain.text.YouTube
import dev.lumbina.tv.core.domain.text.YouTubeSearch
import dev.lumbina.tv.core.domain.text.withYouTube
import dev.lumbina.tv.core.domain.voice.AppMatcher
import dev.lumbina.tv.core.domain.voice.DeviceControls
import dev.lumbina.tv.core.domain.voice.HandleUtterance
import dev.lumbina.tv.core.domain.voice.VideoSearchResult
import dev.lumbina.tv.core.domain.voice.VideoSearchTarget
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Wires the voice pipeline (mic → recognition → text delivery → overlay) for the service. */
internal class VoiceAssembly(
    private val service: AccessibilityService,
    private val engine: SpeechEngine,
    private val voiceSettings: VoiceSettingsRepository,
    private val searches: Searches,
    private val launcherPackages: Set<String>,
    private val commandPorts: CommandPorts,
    private val log: LumbinaLog,
    /** Whether the part of the firmware that gives the remote's sound is turned off: why no sound came, then. */
    private val micServiceOff: () -> Boolean,
) {
    val sessions = VoiceSessions(engine)
    val vidda = ViddaRecognizer(service, log)

    /** Where what was said is looked for: YouTube on a command to, the user's search for a phrase on a launcher. */
    class Searches(val youTube: YouTubeSearch, val chosen: Search)

    /** What voice commands act on. */
    class CommandPorts(
        val installedApps: InstalledApps,
        val layout: HomeLayoutRepository,
        val launcher: AppLauncher,
        val device: DeviceControls,
        val sleep: SleepSettingsRepository,
        val youTube: YouTube,
    )

    /** A command when the phrase is one, otherwise text (see [textDelivery]). */
    fun utterances(): HandleUtterance = HandleUtterance(
        apps = ::spokenApps,
        launcher = commandPorts.launcher,
        device = commandPorts.device,
        search = searches.youTube,
        deliver = textDelivery(),
        sleep = commandPorts.sleep,
        now = System::currentTimeMillis,
        commandsEnabled = { voiceSettings.settings.first().commands },
        matcher = AppMatcher(VOICE_ALIASES),
        searchInApp = ::searchInApp,
    )

    private suspend fun searchInApp(target: VideoSearchTarget, query: String): VideoSearchResult {
        val opened = when (target) {
            VideoSearchTarget.YouTube -> searches.youTube.search(query)
            VideoSearchTarget.VkVideo -> startSearch(
                Intent(Intent.ACTION_VIEW, Uri.parse("vkvideo://video/search?query=" + Uri.encode(query)))
                    .setPackage("com.vk.tv"),
            )

            VideoSearchTarget.Rutube -> {
                val futuba = service.packageManager.getLeanbackLaunchIntentForPackage("me.pnsrc.futuba")
                    ?: service.packageManager.getLaunchIntentForPackage("me.pnsrc.futuba")
                if (futuba != null) {
                    FutubaSearch(service).search(query)
                } else {
                    RutubeSearch(service) {
                        plate.show(service.getString(R.string.voice_rutube_open_search), VoiceOverlay.Mark.Working)
                    }.search(query)
                }
            }

            VideoSearchTarget.Kinopoisk -> {
                val title = try {
                    kinopoiskTitle(query)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: RuntimeException) {
                    log.log("voice", "Kinopoisk search unavailable: $e")
                    if (e is IllegalArgumentException && e.message?.contains("applicationComponent is not initialized") == true) {
                        return VideoSearchResult.Starting
                    }
                    null
                }
                val found = title?.let(::startSearch) == true
                log.log("vidda-voice", "Kinopoisk match=${title != null} opened=$found")
                found
            }

            VideoSearchTarget.Lampa -> startSearch(
                Intent(Intent.ACTION_SEARCH)
                    .setComponent(ComponentName("top.rootu.lampa", "top.rootu.lampa.MainActivity"))
                    .putExtra(SearchManager.QUERY, query)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
        return if (opened) VideoSearchResult.Opened else VideoSearchResult.Failed
    }

    private suspend fun kinopoiskTitle(query: String): Intent? = withContext(Dispatchers.IO) {
        val suggestions = Uri.parse("content://ru.kinopoisk.tv.search.kinopoisk.tv/search_suggest_query?limit=10")
        val wanted = comparableTitle(query)
        service.contentResolver.query(suggestions, null, null, arrayOf(query), null)?.use { rows ->
            val titleColumn = rows.getColumnIndex(SearchManager.SUGGEST_COLUMN_TEXT_1)
            val linkColumn = rows.getColumnIndex(SearchManager.SUGGEST_COLUMN_INTENT_DATA)
            val actionColumn = rows.getColumnIndex(SearchManager.SUGGEST_COLUMN_INTENT_ACTION)
            if (titleColumn < 0 || linkColumn < 0) return@use null
            var best: Intent? = null
            while (rows.moveToNext()) {
                val title = comparableTitle(rows.getString(titleColumn).orEmpty())
                val link = rows.getString(linkColumn)?.takeIf { it.isNotBlank() } ?: continue
                val exact = title == wanted
                val prefix = title.isNotEmpty() && (title.startsWith("$wanted ") || wanted.startsWith("$title "))
                if (!exact && (!prefix || best != null)) continue
                val action = if (actionColumn < 0) null else rows.getString(actionColumn)
                best = Intent(action ?: Intent.ACTION_VIEW, Uri.parse(link)).setPackage("ru.kinopoisk.tv")
                if (exact) break
            }
            best
        }
    }

    private fun comparableTitle(title: String): String =
        title.lowercase().replace('ё', 'е').replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private fun startSearch(intent: Intent): Boolean = try {
        service.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    private fun foregroundSearch(): VideoSearchTarget = when (commandPorts.device.appInFront()) {
        "ru.kinopoisk.tv" -> VideoSearchTarget.Kinopoisk
        "top.rootu.lampa" -> VideoSearchTarget.Lampa
        "com.vk.tv" -> VideoSearchTarget.VkVideo
        "me.pnsrc.futuba", "ru.rutube.app.tv", "ru.rutube.app" -> VideoSearchTarget.Rutube
        else -> VideoSearchTarget.YouTube
    }

    /** The apps under the names people may say; "YouTube" is the app in use for it ([withYouTube]). */
    private suspend fun spokenApps(): List<App> {
        val installed = commandPorts.installedApps.observe().first()
        val tube = commandPorts.youTube.app()?.packageName
        return commandPorts.layout.layout.first().spokenNames(installed)
            .withYouTube(installed.firstOrNull { it.id.packageName == tube })
    }

    /** Where recognized text goes: focused field → visible field → app keyboard → search. */
    fun textDelivery(): DeliverText {
        val screen = AccessibilityScreenReader(service)
        return DeliverText(
            listOf(
                FocusedFieldSink(screen),
                AnyFieldSink(screen),
                OnScreenKeyboardSink(screen),
                LauncherSearchSink(screen, launcherPackages, searches.chosen::search),
            ),
        )
    }

    /**
     * The remote's mic as voice input reads it: the connection opens on the first use and stays
     * open a while after each, so follow-up phrases lose no syllable (see [WarmAudioSource]). One
     * for the service: the remote has room for one reader only.
     */
    fun warm(scope: CoroutineScope, audio: AudioSource): WarmAudioSource {
        val warm = WarmAudioSource(
            upstream = audio,
            now = SystemClock::elapsedRealtime,
            onError = logChanges("mic source"),
            onWake = { log.log(TAG, "mic connected, first audio after $it ms") },
        )
        warm.attach(scope, Dispatchers.IO)
        return warm
    }

    fun language(scope: CoroutineScope) = VoiceLanguageSetting(available = { engine.languages }, voiceSettings, scope)

    /**
     * The one plate at the bottom of the screen: voice, its answers and the service's notes ("couldn't
     * open …") take turns on it instead of standing one over the other.
     */
    val plate by lazy { VoiceOverlay(service) }

    /**
     * Voice input reading [warm]: push-to-talk on the remote's mic key, hands-free (ended by silence)
     * on the launcher's on-screen mic key.
     */
    fun controller(scope: CoroutineScope, warm: AudioSource, language: VoiceLanguageSetting): VoiceController {
        val listened = VoiceController.Sessions(
            pushToTalk = sessions.pushToTalk(warm, language::languages),
            handsFree = sessions.handsFree(warm, language::languages),
        )
        return VoiceController(scope, listened, language, utterances(), plate, strings(), log, vidda, ::foregroundSearch) {
            (warm as? WarmAudioSource)?.stop()
        }
    }

    /** Speech for other apps ([RecognizeSpeechActivity], [LumbinaRecognitionService]), reading [warm]. */
    fun forApps(warm: AudioSource, language: VoiceLanguageSetting) = AppRecognition(warm, sessions, language, log)

    /** The plate's answers by themselves, for a debug command that says a phrase where there is no mic. */
    fun answers(scope: CoroutineScope) = VoiceAnswers(scope, plate, strings(), log)

    private fun strings() = VoiceController.Strings(
        listening = service.getString(R.string.voice_listening),
        viddaConnecting = service.getString(R.string.voice_vidda_connecting),
        viddaNotReady = service.getString(R.string.voice_vidda_not_ready),
        viddaNotHeard = service.getString(R.string.voice_vidda_not_heard),
        videoSearchStarting = service.getString(R.string.voice_video_search_starting),
        recognizing = service.getString(R.string.voice_recognizing),
        stillRecognizing = service.getString(R.string.voice_still_recognizing),
        notStarted = service.getString(R.string.voice_not_started),
        go = ::go,
        heard = { service.getString(R.string.voice_heard, it) },
        notHeard = service.getString(R.string.voice_not_heard),
        silence = service.getString(R.string.voice_silence),
        noSound = service.getString(R.string.voice_no_sound),
        micOff = { if (micServiceOff()) service.getString(R.string.voice_mic_service_off) else null },
        noField = { service.getString(R.string.voice_no_field, it) },
        searchFailed = service.getString(R.string.voice_search_failed),
        failed = service.getString(R.string.voice_failed),
        unavailable = { service.getString(it.reason.message, it.status) },
        opened = { service.getString(R.string.voice_opened, it) },
        closed = { service.getString(R.string.voice_closed, it) },
        appNotFound = { service.getString(R.string.voice_app_not_found, it) },
        notOpened = { service.getString(R.string.voice_not_opened, it) },
        which = service.getString(R.string.voice_which),
        tooMany = service.getString(R.string.voice_too_many),
        nothingToClose = service.getString(R.string.voice_nothing_to_close),
        cantClose = service.getString(R.string.voice_cant_close),
        noTimer = service.getString(R.string.voice_no_timer),
        oneLanguage = { language ->
            language?.let { service.getString(R.string.voice_one_language, languageName(it)) }
                ?: service.getString(R.string.voice_language_auto)
        },
        searched = { service.getString(R.string.voice_searched, it) },
        sleep = { minutes ->
            when (minutes) {
                null -> service.getString(R.string.voice_sleep_cancelled)
                0 -> service.getString(R.string.voice_sleep_now)
                else -> service.resources.getQuantityString(R.plurals.voice_sleep_in, minutes, minutes)
            }
        },
        notDone = service.getString(R.string.voice_not_done),
        languageMode = { mode ->
            when (mode) {
                VoiceLanguageMode.Auto -> service.getString(R.string.voice_language_auto)

                is VoiceLanguageMode.Only -> service.getString(
                    R.string.voice_language_only,
                    languageName(mode.language),
                )
            }
        },
    )

    /** What OK does on a notice that sends the user to [place]. */
    private fun go(place: VoiceController.Place): VoiceOverlay.Action {
        val page = when (place) {
            VoiceController.Place.Diagnostics -> LumbinaActions.SETTINGS_PAGE_STATE
            VoiceController.Place.Voice -> LumbinaActions.SETTINGS_PAGE_VOICE
            VoiceController.Place.Phone -> LumbinaActions.SETTINGS_PAGE_PHONE
            VoiceController.Place.Access -> LumbinaActions.SETTINGS_PAGE_ACCESS
            VoiceController.Place.Widgets -> LumbinaActions.SETTINGS_PAGE_WIDGETS
            VoiceController.Place.Home -> null
        }
        val hint = if (page == null) R.string.voice_ok_home else R.string.voice_ok_open
        return VoiceOverlay.Action(service.getString(hint)) {
            if (page == null) service.openLumbinaHome() else service.openLumbina(page)
        }
    }

    /**
     * Logs an error when it differs from the last one, and the same one again after a while: the
     * source is retried every couple of seconds, and a report must still show that it keeps failing.
     */
    private fun logChanges(what: String): (Exception) -> Unit {
        var last: String? = null
        var loggedAt = 0L
        return { e ->
            val text = e.toString()
            val now = SystemClock.elapsedRealtime()
            if (text != last || now - loggedAt >= SAME_ERROR_MILLIS) {
                log.log(TAG, "$what: $text")
                loggedAt = now
            }
            last = text
        }
    }

    private companion object {
        const val TAG = "voice"
        const val SAME_ERROR_MILLIS = 60_000L
    }
}

/** The language's own name for itself: "русский", "English". */
private fun languageName(code: String): String = Locale.forLanguageTag(code).let { it.getDisplayLanguage(it) }
