package com.eldablos.crimson.launcher

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.lang.ref.WeakReference

/**
 * The phone's notifications for the launcher: the Comms Centre, unread badges and the incoming pop-up.
 * Fed by the app's notification listener (the same access the widget's music row uses).
 */
object Comms {
    /** One app's notifications, newest first. */
    class Group(val packageName: String, val items: List<StatusBarNotification>) {
        val latest: StatusBarNotification get() = items.first()

        /** Messages waiting: what the apps report, or one per notification. */
        val count: Int get() = items.sumOf { maxOf(1, it.notification.number) }
    }

    private var service: WeakReference<NotificationListenerService>? = null
    private val listeners = LinkedHashSet<Listener>()

    interface Listener {
        /** The notifications changed. */
        fun onComms()

        /** A new notification arrived that's worth a pop-up. */
        fun onIncoming(sbn: StatusBarNotification) {}
    }

    fun addListener(listener: Listener) {
        listeners += listener
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    val connected: Boolean get() = service?.get() != null

    fun attach(listener: NotificationListenerService) {
        service = WeakReference(listener)
        changed()
    }

    fun detach() {
        service = null
        changed()
    }

    fun posted(sbn: StatusBarNotification) {
        changed()
        if (shows(sbn) && !sbn.isOngoing && sbn.notification.flags and Notification.FLAG_ONLY_ALERT_ONCE == 0) {
            listeners.toList().forEach { it.onIncoming(sbn) }
        }
    }

    fun changed() {
        listeners.toList().forEach { it.onComms() }
    }

    /** Whether a notification belongs in the Comms Centre: not media players, group headers or silent system notes. */
    fun shows(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        // Calls get their own card with their controls.
        if (isCall(sbn)) return false
        // System bookkeeping (like SystemUI's "MediaOngoingActivity") isn't a message.
        if ((sbn.packageName == "com.android.systemui" || sbn.packageName == "android") &&
            n.extras?.getCharSequence(Notification.EXTRA_TEXT).isNullOrBlank()
        ) return false
        if (n.category == Notification.CATEGORY_TRANSPORT) return false
        if (n.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true) return false
        if (sbn.isOngoing && n.extras?.getInt(Notification.EXTRA_PROGRESS_MAX, 0) == 0) return false
        val title = n.extras?.getCharSequence(Notification.EXTRA_TITLE)
        val text = n.extras?.getCharSequence(Notification.EXTRA_TEXT)
        return !title.isNullOrBlank() || !text.isNullOrBlank()
    }

    /**
     * Whether a notification is worth reading out in the briefing: real messages and reminders, not
     * downloads or anything with a progress bar, weather (it has its own part), email (likewise), system
     * notices, promotions or anything silent and ongoing.
     */
    fun important(sbn: StatusBarNotification): Boolean {
        if (!shows(sbn) || sbn.isOngoing) return false
        val n = sbn.notification
        val e = n.extras
        val pkg = sbn.packageName
        if (pkg == Mail.GMAIL || pkg in NOT_READ) return false
        if (pkg.contains("weather", ignoreCase = true) || pkg.contains("download", ignoreCase = true)) return false
        val label = AppCatalog.forPackage(pkg)?.label.orEmpty()
        if (label.contains("weather", ignoreCase = true)) return false
        if (n.category in setOf(Notification.CATEGORY_PROGRESS, Notification.CATEGORY_TRANSPORT, Notification.CATEGORY_SERVICE,
                Notification.CATEGORY_SYSTEM, Notification.CATEGORY_STATUS, Notification.CATEGORY_PROMO, Notification.CATEGORY_RECOMMENDATION,
                Notification.CATEGORY_NAVIGATION, Notification.CATEGORY_LOCATION_SHARING, Notification.CATEGORY_STOPWATCH)) return false
        if ((e?.getInt(Notification.EXTRA_PROGRESS_MAX, 0) ?: 0) > 0 || e?.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE) == true) return false
        val text = (title(sbn) + " " + text(sbn)).lowercase()
        if (DOWNLOADISH.any { it in text }) return false
        return title(sbn).isNotBlank() || text(sbn).isNotBlank()
    }

    /** Apps whose notifications are never read out: downloads, stores, weather, system. */
    private val NOT_READ = setOf(
        "com.android.providers.downloads", "com.android.vending", "com.sec.android.app.samsungapps", "com.samsung.android.app.updatecenter",
        "com.sec.android.daemonapp", "com.google.android.apps.weather", "com.accuweather.android", "com.handmark.expressweather",
        "com.meteored.android", "com.android.systemui", "android", "com.samsung.android.lool", "com.samsung.android.sm.devicesecurity",
    )

    /** Words that mark a download or install, whatever app it's from. */
    private val DOWNLOADISH = listOf("downloading", "download complete", "downloaded", "installing", "installed", "uploading", "update available", "updating")

    /** Every notification up now, summaries and all. */
    fun all(): List<StatusBarNotification> = try {
        service?.get()?.activeNotifications.orEmpty().toList()
    } catch (e: Exception) {
        emptyList()
    }

    fun active(): List<StatusBarNotification> = try {
        service?.get()?.activeNotifications.orEmpty().filter { shows(it) }.sortedByDescending { it.postTime }
    } catch (e: Exception) {
        emptyList()
    }

    /** A call notification from any app: the phone, WhatsApp, Messenger and the like. */
    fun isCall(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        if (n.category == Notification.CATEGORY_CALL) return true
        val e = n.extras ?: return false
        return e.getInt(Notification.EXTRA_CALL_TYPE, 0) != 0 || e.containsKey(Notification.EXTRA_CALL_PERSON)
    }

    /** Calls going on or ringing now, newest first. */
    fun calls(): List<StatusBarNotification> = try {
        service?.get()?.activeNotifications.orEmpty().filter { isCall(it) }.sortedByDescending { it.postTime }
    } catch (e: Exception) {
        emptyList()
    }

    /** Who the call is with. */
    fun caller(sbn: StatusBarNotification): String {
        val e = sbn.notification.extras
        val person = e?.getParcelable(Notification.EXTRA_CALL_PERSON, android.app.Person::class.java)
        return person?.name?.toString()?.takeIf { it.isNotBlank() } ?: title(sbn).ifBlank { "Call" }
    }

    /** "03:21" since the call started, when the app shows a timer. */
    fun callTimer(sbn: StatusBarNotification, now: Long = System.currentTimeMillis()): String? {
        val n = sbn.notification
        if (n.extras?.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER) != true || n.`when` <= 0) return null
        val s = ((now - n.`when`) / 1000).coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
    }

    /** True while it's still ringing rather than answered. */
    fun ringing(sbn: StatusBarNotification): Boolean = EdgeLight.isIncomingCall(sbn.notification)

    /** Notifications grouped by app, the most recent app first. */
    fun groups(): List<Group> = active().groupBy { it.packageName }.map { (pkg, items) -> Group(pkg, items) }

    /** Unread count for each app. */
    fun counts(): Map<String, Int> = groups().associate { it.packageName to it.count }

    /**
     * Fires a notification's PendingIntent the way a launcher must on Android 14+: saying it may start
     * the app's screen, which Android otherwise blocks. Returns false if it couldn't be sent.
     */
    fun fire(context: Context, intent: PendingIntent?, fillIn: Intent? = null): Boolean {
        intent ?: return false
        val options = ActivityOptions.makeBasic()
            .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            .toBundle()
        return try {
            intent.send(context, 0, fillIn, null, null, null, options)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Opens a notification: its own target, or else its app. Clears it if the app asked for that. */
    fun open(context: Context, sbn: StatusBarNotification) {
        if (!fire(context, sbn.notification.contentIntent)) {
            AppCatalog.forPackage(sbn.packageName)?.let { AppCatalog.launch(context, it.component) }
        }
        if (sbn.notification.flags and Notification.FLAG_AUTO_CANCEL != 0) dismiss(sbn)
    }

    fun dismiss(group: Group) {
        val s = service?.get() ?: return
        group.items.filter { it.isClearable }.forEach { s.cancelNotification(it.key) }
    }

    fun dismiss(sbn: StatusBarNotification) {
        if (sbn.isClearable) service?.get()?.cancelNotification(sbn.key)
    }

    fun clearAll() {
        val s = service?.get() ?: return
        active().filter { it.isClearable }.forEach { s.cancelNotification(it.key) }
    }

    /** Do Not Disturb, which the notification listener is allowed to switch. */
    var doNotDisturb: Boolean
        get() = (service?.get()?.currentInterruptionFilter ?: NotificationListenerService.INTERRUPTION_FILTER_ALL) !=
            NotificationListenerService.INTERRUPTION_FILTER_ALL
        set(on) {
            service?.get()?.requestInterruptionFilter(
                if (on) NotificationListenerService.INTERRUPTION_FILTER_PRIORITY else NotificationListenerService.INTERRUPTION_FILTER_ALL,
            )
        }

    fun title(sbn: StatusBarNotification): String =
        sbn.notification.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()

    fun text(sbn: StatusBarNotification): String {
        val e = sbn.notification.extras ?: return ""
        return (e.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: e.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString()?.trim().orEmpty()
    }

    /** Live progress (a delivery, a download), as 0–1, or null. */
    fun progress(sbn: StatusBarNotification): Float? {
        val e = sbn.notification.extras ?: return null
        val max = e.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        if (max <= 0) return null
        return (e.getInt(Notification.EXTRA_PROGRESS, 0).toFloat() / max).coerceIn(0f, 1f)
    }

    /** The notification's reply action, if it has one with a text box. */
    fun replyAction(sbn: StatusBarNotification): Notification.Action? =
        sbn.notification.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }

    /** "2M", "1H", "NOW". */
    fun ago(postTime: Long, now: Long = System.currentTimeMillis()): String {
        val minutes = (now - postTime) / 60_000
        return when {
            minutes < 1 -> "NOW"
            minutes < 60 -> "${minutes}M"
            minutes < 60 * 24 -> "${minutes / 60}H"
            else -> "${minutes / (60 * 24)}D"
        }
    }
}
