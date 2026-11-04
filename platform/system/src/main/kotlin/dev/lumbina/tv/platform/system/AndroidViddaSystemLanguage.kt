package dev.lumbina.tv.platform.system

import android.content.Context
import android.content.res.Resources
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.lumbina.tv.core.domain.access.AccessSettingsRepository
import dev.lumbina.tv.core.domain.access.LocalAdb
import dev.lumbina.tv.core.domain.device.DeviceProfile
import dev.lumbina.tv.core.domain.health.HealLock
import dev.lumbina.tv.core.domain.locale.ViddaLanguage
import dev.lumbina.tv.core.domain.locale.ViddaSystemLanguage
import javax.inject.Inject
import kotlinx.coroutines.flow.first

internal class AndroidViddaSystemLanguage @Inject constructor(
    @ApplicationContext private val context: Context,
    private val adb: LocalAdb,
    private val profile: DeviceProfile,
    private val settings: AccessSettingsRepository,
    private val lock: HealLock,
) : ViddaSystemLanguage {
    override val supported: Boolean get() = profile.id == "hisense-vidaa" && profile.localAdb

    override val activeTag: String get() = Resources.getSystem().configuration.locales[0].toLanguageTag()

    override suspend fun select(language: ViddaLanguage?): Boolean = lock.withLock {
        if (!supported || (language != null && !apply(language))) return@withLock false
        settings.update { it.copy(systemLanguage = language) }
        true
    }

    private suspend fun apply(language: ViddaLanguage): Boolean {
        val tag = language.tag
        val result = adb.shell("setprop persist.sys.locale $tag 2>&1; getprop persist.sys.locale")
        if (verified(result, tag)) return true
        return applyConfiguration(tag)
    }

    private suspend fun applyConfiguration(tag: String): Boolean {
        val apk = context.packageCodePath.replace("'", "'\\''")
        val command = "CLASSPATH='$apk' app_process /system/bin ${ViddaLocaleTool::class.java.name} $tag"
        return verified(adb.shell("$command 2>&1; getprop persist.sys.locale"), tag)
    }

    override suspend fun restore(): Unit = lock.withLock {
        if (!supported) return@withLock
        val language = settings.settings.first().systemLanguage ?: return@withLock
        val stored = adb.shell("getprop persist.sys.locale") ?: return@withLock
        if (!verified(activeTag, language.tag) || !verified(stored, language.tag)) {
            if (!applyConfiguration(language.tag) && !verified(stored, language.tag)) apply(language)
        }
    }

    private fun verified(result: String?, wanted: String): Boolean = result?.lineSequence()?.map(String::trim)
        ?.lastOrNull { it.isNotEmpty() }?.substringBefore('-') == wanted
}
