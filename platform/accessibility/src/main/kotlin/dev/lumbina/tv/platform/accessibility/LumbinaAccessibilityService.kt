package dev.lumbina.tv.platform.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import dagger.hilt.android.AndroidEntryPoint
import dev.lumbina.tv.core.common.LumbinaLog
import dev.lumbina.tv.core.domain.remote.RemoteSettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus

/**
 * Lumbina's always-running part: takes over the remote's Home and mic buttons from the firmware
 * (the mic as push-to-talk voice input), shows the app switcher, quick menu and sleep warning
 * over any app, serves the phone remote, answers apps that ask for speech and opens Lumbina on
 * power-on. Runs in a process of its own, apart from the UI (see the manifest). Only routes the
 * service's lifecycle, keys and events to the [RemoteFeatures] built on connecting.
 */
@AndroidEntryPoint
class LumbinaAccessibilityService : AccessibilityService() {

    @Inject internal lateinit var featuresFactory: RemoteFeatures.Factory

    @Inject lateinit var log: LumbinaLog

    @Inject lateinit var remote: RemoteSettingsRepository

    /**
     * A failure in one feature is logged and the rest go on: a crash of this process leaves the
     * service among the system's "crashed services", the remote's keys then go to the firmware.
     */
    private val scope = MainScope() + CoroutineExceptionHandler { _, error ->
        log.log(TAG, "feature failed: ${error.stackTraceToString().take(ERROR_TEXT)}")
    }
    private var remoteSettingsJob: Job? = null
    private var features: RemoteFeatures? = null
    private val lampaWindowWatcher by lazy { LampaWindowWatcher(this, log) }
    private var translator: Translator? = null
    private var translatorExtraEvents = 0
    private var translatorEventsEnabled = false

    override fun onServiceConnected() {
        // Bound twice (a heal toggling the service while it was still coming up), each instance
        // would act on every key. The newest connection is the one the system keeps: it takes over.
        current?.takeIf { it !== this }?.let {
            log.log(TAG, "another instance was still connected; it stops")
            it.retire()
        }
        current = this
        remoteSettingsJob?.cancel()
        remoteSettingsJob = scope.launch {
            remote.settings
                .map { it.interceptRemote to it.translateSystemScreens }
                .distinctUntilChanged()
                .collect { (interceptRemote, translateSystemScreens) ->
                    if (interceptRemote) {
                        startRemote()
                        configureTranslator(translateSystemScreens)
                    } else {
                        translator?.stop()
                        disableSelf()
                    }
                }
        }
    }

    private fun startRemote() {
        // Keys let go for a held volume button stay so after the process ends, unless taken back here.
        filterKeys(true)
        (features ?: featuresFactory.create(this, scope, lampaWindowWatcher::beforeHome).also { features = it }).start()
    }

    private fun configureTranslator(enabled: Boolean) {
        val info = serviceInfo
        if (info != null && enabled != translatorEventsEnabled) {
            if (enabled) {
                translatorExtraEvents = TRANSLATOR_EVENTS and info.eventTypes.inv()
                info.eventTypes = info.eventTypes or TRANSLATOR_EVENTS
            } else {
                info.eventTypes = info.eventTypes and translatorExtraEvents.inv()
                translatorExtraEvents = 0
            }
            serviceInfo = info
            translatorEventsEnabled = enabled
        }
        if (enabled && translator == null) translator = Translator(this, log)
        translator?.setEnabled(enabled)
    }

    /** Stops acting for good, leaving keys and events to the instance connected after this one. */
    private fun retire() {
        remoteSettingsJob?.cancel()
        translator?.stop()
        translator = null
        runCatching { features?.stop() }.onFailure { log.log(TAG, "stopping the earlier instance: $it") }
        features = null
        runCatching { filterKeys(false) }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean = if (current === this) features?.onKey(event) ?: false else false

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (current !== this) return
        features?.onAccessibilityEvent(event)
        lampaWindowWatcher.onEvent(event)
        translator?.onEvent(event)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        log.log(TAG, "destroyed")
        if (current === this) current = null
        translator?.stop()
        // A start cut short leaves parts not registered; unregistering them must not crash the process.
        runCatching { features?.stop() }.onFailure { log.log(TAG, "stop: $it") }
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "a11y"
        const val ERROR_TEXT = 2_000

        /** The instance acting on the remote in this process; touched on the main thread only. */
        var current: LumbinaAccessibilityService? = null
        const val TRANSLATOR_EVENTS = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
            AccessibilityEvent.TYPE_WINDOWS_CHANGED or AccessibilityEvent.TYPE_VIEW_SCROLLED or
            AccessibilityEvent.TYPE_VIEW_FOCUSED or AccessibilityEvent.TYPE_VIEW_SELECTED
    }
}
