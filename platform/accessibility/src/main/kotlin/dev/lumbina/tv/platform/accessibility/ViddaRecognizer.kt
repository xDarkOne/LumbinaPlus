package dev.lumbina.tv.platform.accessibility

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import dev.lumbina.tv.core.common.LumbinaLog
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

/** Vidda's recognition service captures the remote itself. All methods run on the main thread. */
internal class ViddaRecognizer(private val context: Context, private val log: LumbinaLog) {
    private var current: SpeechRecognizer? = null

    fun available(): Boolean {
        val pm = context.packageManager
        val service = try {
            pm.getServiceInfo(COMPONENT, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return false
        }
        val serviceCanRecord = pm.checkPermission(Manifest.permission.RECORD_AUDIO, PACKAGE) ==
            PackageManager.PERMISSION_GRANTED
        val callerCanRecord = pm.checkPermission(Manifest.permission.RECORD_AUDIO, context.packageName) ==
            PackageManager.PERMISSION_GRANTED
        val enabled = service.enabled && service.applicationInfo.enabled
        return enabled && serviceCanRecord && callerCanRecord
    }

    // An Android service may throw different RuntimeExceptions while starting its recognizer.
    @Suppress("TooGenericExceptionCaught")
    suspend fun listen(onReady: () -> Unit, onPartial: (String) -> Unit): List<String> =
        withTimeout(TOTAL_TIMEOUT_MILLIS) {
            suspendCancellableCoroutine { continuation ->
                val recognizer = SpeechRecognizer.createSpeechRecognizer(context, COMPONENT)
                current = recognizer
                val started = SystemClock.elapsedRealtime()
                var ready = false
                var speech = false
                val handler = Handler(Looper.getMainLooper())
                lateinit var deadline: Runnable
                fun finish(result: List<String>) {
                    if (!continuation.isActive) return
                    handler.removeCallbacks(deadline)
                    if (current === recognizer) current = null
                    recognizer.destroy()
                    continuation.resume(result)
                }
                deadline = Runnable {
                    log.log(TAG, "connection timed out after ${elapsed(started)} ms")
                    finish(emptyList())
                }
                handler.postDelayed(deadline, CONNECT_TIMEOUT_MILLIS)
                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        if (!continuation.isActive || ready) return
                        ready = true
                        handler.removeCallbacks(deadline)
                        log.log(TAG, "ready after ${elapsed(started)} ms")
                        onReady()
                    }
                    override fun onBeginningOfSpeech() {
                        speech = true
                    }
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() = Unit
                    override fun onError(error: Int) {
                        log.log(TAG, "error=$error ready=$ready speech=$speech after ${elapsed(started)} ms")
                        finish(emptyList())
                    }

                    override fun onResults(results: Bundle?) {
                        val readings = results.readings()
                        log.log(TAG, "results=${readings.size} speech=$speech after ${elapsed(started)} ms")
                        finish(readings)
                    }
                    override fun onPartialResults(partialResults: Bundle?) {
                        if (continuation.isActive) partialResults.readings().firstOrNull()?.let(onPartial)
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                })
                continuation.invokeOnCancellation {
                    handler.post {
                        handler.removeCallbacks(deadline)
                        if (current === recognizer) {
                            current = null
                            recognizer.cancel()
                            recognizer.destroy()
                        }
                    }
                }
                try {
                    recognizer.startListening(
                        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, MAX_RESULTS),
                    )
                } catch (e: RuntimeException) {
                    handler.removeCallbacks(deadline)
                    if (current === recognizer) current = null
                    recognizer.destroy()
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
        }

    fun stop() {
        current?.stopListening()
    }

    private fun Bundle?.readings(): List<String> =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty().filter { it.isNotBlank() }

    private fun elapsed(started: Long): Long = SystemClock.elapsedRealtime() - started

    private companion object {
        const val TAG = "vidda-voice"
        const val PACKAGE = "org.vidda.googlevoicesearch"
        val COMPONENT = ComponentName(PACKAGE, "com.google.android.katniss.search.serviceapi.KatnissRecognitionService")
        const val CONNECT_TIMEOUT_MILLIS = 12_000L
        const val TOTAL_TIMEOUT_MILLIS = 45_000L
        const val MAX_RESULTS = 5
    }
}
