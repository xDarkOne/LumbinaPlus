package dev.lumbina.tv.platform.system

import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.lumbina.tv.core.common.LampaWatchNext
import dev.lumbina.tv.core.common.LumbinaLog
import dev.lumbina.tv.core.domain.status.Notice
import dev.lumbina.tv.core.domain.status.NotificationFeed
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

/**
 * Receives other apps' notifications once allowed (Android TV settings, or on devices without
 * that screen: `adb shell cmd notification allow_listener` with this component).
 * Keeps them in [NotificationStore] for the home screen.
 */
@AndroidEntryPoint
class LumbinaNotificationListener : NotificationListenerService() {
    @Inject lateinit var log: LumbinaLog

    private var tizenTubeHistory: TizenTubeHistory? = null
    private var lampaVimuHistory: LampaVimuHistory? = null
    private var lampaReceiverRegistered = false
    private val lampaWindowReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.getStringExtra(LampaWatchNext.DESTINATION)?.let { lampaVimuHistory?.onWindow(it) }
        }
    }

    override fun onListenerConnected() {
        NotificationStore.connected(this)
        tizenTubeHistory?.stop()
        tizenTubeHistory = TizenTubeHistory(this, ComponentName(this, javaClass), log).also { it.start() }
        lampaVimuHistory?.stop()
        lampaVimuHistory = LampaVimuHistory(this, ComponentName(this, javaClass), log).also { it.start() }
        if (!lampaReceiverRegistered) {
            ContextCompat.registerReceiver(
                this, lampaWindowReceiver, IntentFilter(LampaWatchNext.ACTION), ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            lampaReceiverRegistered = true
        }
        val all = runCatching { activeNotifications }.getOrNull().orEmpty().filter { isVisible(it) }
        NotificationStore.replace(all, packageName, packageManager::labelOf)
    }

    override fun onListenerDisconnected() {
        tizenTubeHistory?.stop()
        tizenTubeHistory = null
        lampaVimuHistory?.stop()
        lampaVimuHistory = null
        if (lampaReceiverRegistered) unregisterReceiver(lampaWindowReceiver)
        lampaReceiverRegistered = false
        NotificationStore.connected(null)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!isVisible(sbn)) return NotificationStore.remove(sbn.key)
        NotificationStore.post(sbn, packageName, packageManager::labelOf)
    }

    /**
     * Leaves out what a status bar would not show either: "app is running" notices of foreground
     * services (some firmware's system apps keep several) and those their channel marks as minimal.
     */
    private fun isVisible(sbn: StatusBarNotification): Boolean {
        if (sbn.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return false
        val ranking = Ranking()
        val known = runCatching { currentRanking.getRanking(sbn.key, ranking) }.getOrDefault(false)
        return !known || ranking.importance > NotificationManager.IMPORTANCE_MIN
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        NotificationStore.remove(sbn.key)
    }
}

/** Process-wide state shared by the listener (bound by the system) and [AndroidNotificationFeed]. */
internal object NotificationStore {
    private class Entry(val notice: Notice, val intent: PendingIntent?, val physicalKeyboard: Boolean)

    private val entries = MutableStateFlow<Map<String, Entry>>(emptyMap())
    private val listener = MutableStateFlow<NotificationListenerService?>(null)

    val access: StateFlow<Boolean> get() = accessFlow
    private val accessFlow = MutableStateFlow(false)

    val notices: Flow<List<Notice>> = entries.map { map ->
        map.values.map { it.notice }.sortedByDescending { it.postedAt }
    }

    fun connected(service: NotificationListenerService?) {
        listener.value = service
        accessFlow.value = service != null
        if (service == null) entries.value = emptyMap()
    }

    fun replace(all: List<StatusBarNotification>, ownPackage: String, label: (String) -> String) {
        entries.value = all.mapNotNull { entryOf(it, ownPackage, label) }.associateBy { it.notice.key }
    }

    fun post(sbn: StatusBarNotification, ownPackage: String, label: (String) -> String) {
        val entry = entryOf(sbn, ownPackage, label) ?: return remove(sbn.key)
        entries.value += entry.notice.key to entry
    }

    fun remove(key: String) {
        entries.value -= key
    }

    fun intent(key: String): PendingIntent? = entries.value[key]?.intent

    fun physicalKeyboard(key: String): Boolean = entries.value[key]?.physicalKeyboard == true

    fun cancel(key: String?) {
        val service = listener.value ?: return
        runCatching { if (key == null) service.cancelAllNotifications() else service.cancelNotification(key) }
    }

    /** What is worth showing: not ours, not ongoing (media, downloads), not a group summary, with a title. */
    private fun entryOf(sbn: StatusBarNotification, ownPackage: String, label: (String) -> String): Entry? {
        val notification = sbn.notification
        val skip = sbn.packageName == ownPackage || sbn.isOngoing ||
            notification.flags and Notification.FLAG_GROUP_SUMMARY != 0
        if (skip) return null
        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.takeIf { it.isNotBlank() }
            ?: return null
        val text = (
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT)
            )
            ?.toString()?.takeIf { it.isNotBlank() }
        val opens = notification.contentIntent
        val physicalKeyboard = sbn.packageName == "android" && notification.channelId == "PHYSICAL_KEYBOARD"
        val notice = Notice(
            sbn.key, sbn.packageName, label(sbn.packageName), title, text, sbn.postTime, opens != null || physicalKeyboard,
        )
        return Entry(notice, opens, physicalKeyboard)
    }
}

internal class AndroidNotificationFeed @Inject constructor(
    private val log: LumbinaLog,
    @ApplicationContext private val context: Context,
) : NotificationFeed {

    override val hasAccess: Flow<Boolean> = NotificationStore.access

    override fun notices(): Flow<List<Notice>> = NotificationStore.notices

    override fun open(key: String): Boolean {
        if (NotificationStore.physicalKeyboard(key) && openKeyboardSettings()) {
            NotificationStore.cancel(key)
            return true
        }
        val intent = NotificationStore.intent(key) ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val options = ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                intent.send(null, 0, null, null, null, null, options.toBundle())
            } else {
                intent.send()
            }
            NotificationStore.cancel(key)
            true
        } catch (e: PendingIntent.CanceledException) {
            log.log(TAG, "open $key: $e")
            false
        }
    }

    private fun openKeyboardSettings(): Boolean =
        listOf("android.settings.HARD_KEYBOARD_SETTINGS", Settings.ACTION_INPUT_METHOD_SETTINGS).any { action ->
            try {
                context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (_: ActivityNotFoundException) {
                false
            } catch (_: SecurityException) {
                false
            }
        }

    override fun dismiss(key: String) = NotificationStore.cancel(key)

    override fun dismissAll() = NotificationStore.cancel(null)

    private companion object {
        const val TAG = "notifications"
    }
}
