package com.eldablos.crimson.launcher

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import java.time.LocalDate

/**
 * New-email counts for the briefing, without signing in to anything: Gmail posts a notification for each
 * new email, labelled with the account it came to, and every one is counted per account per day as it
 * arrives, with the latest sender and subject. Only mail Gmail notifies about is counted.
 */
object Mail {
    const val GMAIL = "com.google.android.gm"
    const val DEFAULT_ACCOUNT = "veekuisys@gmail.com"

    class Day(val count: Int, val from: String?, val subject: String?)

    private fun prefs(context: Context) = context.getSharedPreferences("mail", Context.MODE_PRIVATE)

    /** The account the briefing reports on. */
    fun account(context: Context): String = prefs(context).getString("account", null) ?: DEFAULT_ACCOUNT

    fun setAccount(context: Context, a: String) = prefs(context).edit().putString("account", a.trim().lowercase()).apply()

    /** Counts a new Gmail notification, once, for the account it names. */
    fun record(context: Context, sbn: StatusBarNotification) {
        if (sbn.packageName != GMAIL) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val e = n.extras ?: return
        val account = listOf(Notification.EXTRA_SUB_TEXT, Notification.EXTRA_SUMMARY_TEXT)
            .mapNotNull { e.getCharSequence(it)?.toString() }
            .firstOrNull { '@' in it }?.trim()?.lowercase() ?: return
        val today = LocalDate.now().toString()
        val p = prefs(context)
        // Gmail reposts a notification when it updates; each email is counted the first time only.
        val seenKey = "seen:$today"
        val seen = p.getStringSet(seenKey, emptySet()).orEmpty()
        if (sbn.key in seen) return
        val from = e.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val subject = e.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.lineSequence()?.firstOrNull()
        val countKey = "count:$account:$today"
        val edit = p.edit()
            .putStringSet(seenKey, seen + sbn.key)
            .putInt(countKey, p.getInt(countKey, 0) + 1)
            .putString("from:$account:$today", from)
            .putString("subject:$account:$today", subject)
        // Only the last few days are kept.
        val keep = (0..3).map { LocalDate.now().minusDays(it.toLong()).toString() }
        p.all.keys.filter { k -> (k.startsWith("seen:") || k.startsWith("count:") || k.startsWith("from:") || k.startsWith("subject:")) && keep.none { k.endsWith(it) } }
            .forEach { edit.remove(it) }
        edit.apply()
    }

    fun day(context: Context, date: LocalDate, account: String = account(context)): Day {
        val p = prefs(context)
        val d = date.toString()
        return Day(p.getInt("count:$account:$d", 0), p.getString("from:$account:$d", null), p.getString("subject:$account:$d", null))
    }
}
