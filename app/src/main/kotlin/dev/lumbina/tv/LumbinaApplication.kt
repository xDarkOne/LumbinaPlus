package dev.lumbina.tv

import android.app.Application
import android.os.Build
import android.os.SystemClock
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import dagger.hilt.android.HiltAndroidApp
import dev.lumbina.tv.core.common.LumbinaLog
import dev.lumbina.tv.core.domain.device.DeviceProfile
import dev.lumbina.tv.core.domain.health.HealAccessibilityService
import dev.lumbina.tv.core.domain.health.HealResult
import dev.lumbina.tv.core.ui.lumbinaImageLoader
import dev.lumbina.tv.platform.accessibility.SpeechForApps
import dev.lumbina.tv.platform.system.HealJob
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class LumbinaApplication :
    Application(),
    SingletonImageLoader.Factory {

    @Inject lateinit var heal: HealAccessibilityService

    @Inject lateinit var log: LumbinaLog

    @Inject lateinit var profile: DeviceProfile

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var healing: Job? = null

    override fun onCreate() {
        super.onCreate()
        val process = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) getProcessName() else "?"
        log.log("app", "process started: $process")
        recordCrashes(process)
        HealJob.ensureScheduled(this)
        // Every start of a process is a moment the remote's service may be found down: after boot,
        // after the firmware stopped Lumbina in sleep, after the remote's process crashed.
        runCatching { HealJob.scheduleQuickCheck(this) }
        SpeechForApps.sync(this, wanted = profile.speechForApps)
    }

    /**
     * Writes an uncaught exception to Lumbina's log before the process dies; logcat alone is gone
     * on some firmware. A crash of the remote's process leaves its accessibility service among the
     * system's "crashed services", which are not bound again by themselves, and the remote then
     * goes to the firmware or repeats presses: a check is asked for to mend it in seconds.
     */
    private fun recordCrashes(process: String) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val startedAt = SystemClock.elapsedRealtime()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                log.logNow("crash", "$process, thread ${thread.name}: ${error.stackTraceToString().take(CRASH_TEXT)}")
                // A process that dies right after starting would only be brought back to die again:
                // that is left to the periodic check.
                if (SystemClock.elapsedRealtime() - startedAt > CRASH_LOOP_MILLIS) HealJob.scheduleQuickCheck(this)
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * Brings the accessibility service back if it is down. Called when the UI opens: foreground
     * starts are allowed even while background ones are blocked after boot. Runs in the app's
     * scope so leaving the screen can't interrupt the off/on toggle halfway; one run at a time.
     */
    fun healOnUiOpen() {
        if (healing?.isActive == true) return
        healing = scope.launch {
            val result = runCatching { heal() }.getOrElse { "failed: $it" }
            if (result != HealResult.AlreadyRunning) log.log("heal", "on UI open: $result")
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = lumbinaImageLoader(context)

    private companion object {
        const val CRASH_TEXT = 4_000
        const val CRASH_LOOP_MILLIS = 60_000L
    }
}
