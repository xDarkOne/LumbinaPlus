package dev.lumbina.tv.platform.accessibility

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Vidda's recognition service captures the remote itself. All methods run on the main thread. */
internal class ViddaRecognizer(private val context: Context) {
    private var current: SpeechRecognizer? = null

    fun available(): Boolean {
        val pm = context.packageManager
        val service = try {
            pm.getServiceInfo(COMPONENT, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return false
        }
        return service.enabled && service.applicationInfo.enabled &&
            pm.checkPermission(Manifest.permission.RECORD_AUDIO, PACKAGE) == PackageManager.PERMISSION_GRANTED &&
            pm.checkPermission(Manifest.permission.RECORD_AUDIO, context.packageName) == PackageManager.PERMISSION_GRANTED
    }

    suspend fun listen(onPartial: (String) -> Unit): String? = withTimeout(TOTAL_TIMEOUT_MILLIS) {
        suspendCancellableCoroutine { continuation ->
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context, COMPONENT)
            current = recognizer
            var ready = false
            val handler = Handler(Looper.getMainLooper())
            lateinit var deadline: Runnable
            fun finish(text: String?) {
                if (!continuation.isActive) return
                handler.removeCallbacks(deadline)
                if (current === recognizer) current = null
                recognizer.destroy()
                continuation.resume(text)
            }
            deadline = Runnable { if (!ready) finish(null) }
            handler.postDelayed(deadline, CONNECT_TIMEOUT_MILLIS)
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { ready = true }
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onError(error: Int) = finish(null)
                override fun onResults(results: Bundle?) = finish(results.text())
                override fun onPartialResults(partialResults: Bundle?) {
                    if (continuation.isActive) partialResults.text()?.let(onPartial)
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
                        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5),
                )
            } catch (e: RuntimeException) {
                handler.removeCallbacks(deadline)
                if (current === recognizer) current = null
                recognizer.destroy()
                if (continuation.isActive) continuation.resumeWithException(e)
            }
        }
    }

    fun stop() { current?.stopListening() }

    private fun Bundle?.text(): String? = this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        ?.firstOrNull { it.isNotBlank() }

    private companion object {
        const val PACKAGE = "org.vidda.googlevoicesearch"
        val COMPONENT = ComponentName(PACKAGE, "com.google.android.katniss.search.serviceapi.KatnissRecognitionService")
        const val CONNECT_TIMEOUT_MILLIS = 12_000L
        const val TOTAL_TIMEOUT_MILLIS = 45_000L
    }
}
