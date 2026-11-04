package dev.lumbina.tv.platform.packages

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.lumbina.tv.core.common.LumbinaActions
import dev.lumbina.tv.core.common.LumbinaLog
import dev.lumbina.tv.core.domain.access.AdbState
import dev.lumbina.tv.core.domain.access.LocalAdb
import dev.lumbina.tv.core.domain.speech.ViddaVoiceSetup
import dev.lumbina.tv.core.domain.speech.ViddaVoiceSetup.State
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal class AndroidViddaVoiceSetup @Inject constructor(
    @ApplicationContext private val context: Context,
    private val adb: LocalAdb,
    private val log: LumbinaLog,
) : ViddaVoiceSetup {
    override fun status(): State {
        val manager = context.packageManager
        try {
            val service = manager.getServiceInfo(ComponentName(PACKAGE, RECOGNIZER), 0)
            if (!service.enabled || !service.applicationInfo.enabled) return State.Missing
        } catch (_: PackageManager.NameNotFoundException) {
            return State.Missing
        }
        return if (canRecord(PACKAGE) && canRecord(context.packageName)) State.Ready else State.NeedsMicrophone
    }

    override suspend fun prepare(): State = withContext(Dispatchers.IO) {
        val current = status()
        if (current == State.Ready) return@withContext current
        if (adb.state() != AdbState.Connected) return@withContext State.DebuggingRequired
        try {
            if (current == State.Missing && !install()) return@withContext State.Failed
            for (app in listOf(context.packageName, PACKAGE)) {
                if (!canRecord(app)) adb.shell("pm grant $app android.permission.RECORD_AUDIO")
            }
            val result = status()
            if (result == State.Ready) {
                context.sendBroadcast(Intent(LumbinaActions.SPEECH_MODELS_CHANGED).setPackage(context.packageName))
            }
            if (result == State.Missing) State.Failed else result
        } catch (e: IOException) {
            log.log("vidda-voice", "setup download failed: $e")
            State.Failed
        }
    }

    private fun canRecord(app: String): Boolean = context.packageManager.checkPermission(
        Manifest.permission.RECORD_AUDIO,
        app,
    ) == PackageManager.PERMISSION_GRANTED

    private suspend fun install(): Boolean {
        val file = File.createTempFile("vidda-voice-", ".apk", context.cacheDir)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val download = URL(DOWNLOAD_URL).openConnection().apply {
                connectTimeout = 10_000
                readTimeout = 15_000
            }
            download.getInputStream().use { input ->
                file.outputStream().use { output ->
                    copyAndHash(input, output, digest)
                }
            }
            if (digest.digest().joinToString("") { "%02x".format(it) } != SHA256) {
                log.log("vidda-voice", "voice service checksum mismatch")
                return false
            }
            val answer = file.inputStream().use { adb.exec("cmd package install -r -S ${file.length()}", it) }
            return answer?.lineSequence()?.any { it.trim() == "Success" } == true
        } finally {
            file.delete()
        }
    }

    private suspend fun copyAndHash(input: InputStream, output: OutputStream, digest: MessageDigest) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var size = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            size += read
            if (size > MAX_APK_BYTES) throw IOException("Voice service APK exceeds size limit")
            digest.update(buffer, 0, read)
            output.write(buffer, 0, read)
        }
    }

    private companion object {
        const val PACKAGE = "org.vidda.googlevoicesearch"
        const val RECOGNIZER = "com.google.android.katniss.search.serviceapi.KatnissRecognitionService"
        const val DOWNLOAD_URL = "https://github.com/xDarkOne/voicekey/releases/download/" +
            "voice-service/Vidda-Voice-Service.apk"
        const val SHA256 = "8baddc4007bdd7b40a04a86fba7f51ec828c51a90255d4b57d19751fb49ee668"
        const val MAX_APK_BYTES = 80L * 1024 * 1024
    }
}
