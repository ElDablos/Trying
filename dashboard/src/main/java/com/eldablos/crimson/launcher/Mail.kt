package com.eldablos.crimson.launcher

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import java.time.LocalDate

/**
 * New email for the briefing, without signing in to anything: Gmail posts a notification for each new
 * email, labelled with the account it came to, and each is kept per account per day as it arrives (who
 * it's from, what it's about, when). Only mail Gmail notifies about is seen.
 */
object Mail {
    const val GMAIL = "com.google.android.gm"

    /**
     * An inbox the briefing reports on: [name] in full, [label] on the deck, [spoken] when read out; a [detailed] one has
     * each new email read out, not just the count.
     */
    class Account(val address: String, val name: String, val label: String, val spoken: String, val detailed: Boolean)

    val ACCOUNTS = listOf(
        Account("evenusltd@gmail.com", "Even Us Mailbox", "EVEN US", "the Even Us Mailbox", detailed = true),
        Account("veekuisys@gmail.com", "Personal email", "PERSONAL", "your personal email", detailed = false),
    )

    class Item(val at: Long, val from: String, val subject: String)

    class Day(val count: Int, val items: List<Item>) {
        val latest: Item? get() = items.lastOrNull()
    }

    private fun prefs(context: Context) = context.getSharedPreferences("mail", Context.MODE_PRIVATE)

    private val ADDRESS = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")

    /**
     * The inbox a Gmail notification belongs to: named on it (Gmail puts the account in the small header
     * text), or else on the summary of its group, which always names it.
     */
    fun accountOf(sbn: StatusBarNotification, others: List<StatusBarNotification>): String? {
        fun named(n: StatusBarNotification): String? {
            val e = n.notification.extras
            val texts = listOfNotNull(
                e?.getCharSequence(Notification.EXTRA_SUB_TEXT), e?.getCharSequence(Notification.EXTRA_SUMMARY_TEXT),
                e?.getCharSequence(Notification.EXTRA_INFO_TEXT), n.notification.group, n.groupKey,
            ).map { it.toString() }
            // One of your inboxes by name first, then any address in the header.
            texts.forEach { t -> ACCOUNTS.firstOrNull { t.contains(it.address, ignoreCase = true) }?.let { return it.address } }
            return listOfNotNull(e?.getCharSequence(Notification.EXTRA_SUB_TEXT), e?.getCharSequence(Notification.EXTRA_SUMMARY_TEXT))
                .firstNotNullOfOrNull { ADDRESS.find(it)?.value }?.lowercase()
        }
        named(sbn)?.let { return it }
        return others.filter { it.packageName == GMAIL && it.groupKey == sbn.groupKey && it.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0 }
            .firstNotNullOfOrNull { named(it) }
    }

    /** Keeps a Gmail notification, once, under its inbox and the day it arrived. */
    fun record(context: Context, sbn: StatusBarNotification, others: List<StatusBarNotification> = Comms.all()) {
        if (sbn.packageName != GMAIL) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val e = n.extras ?: return
        val account = accountOf(sbn, others) ?: return
        val day = java.time.Instant.ofEpochMilli(sbn.postTime).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        if (day.isBefore(LocalDate.now().minusDays(3))) return
        val date = day.toString()
        val p = prefs(context)
        // Gmail reposts a notification when it updates; each email is kept the first time only.
        val seenKey = "seen:$date"
        val seen = p.getStringSet(seenKey, emptySet()).orEmpty()
        if (sbn.key in seen) return
        val from = e.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().clean()
        val subject = e.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.lineSequence()?.firstOrNull().orEmpty().clean()
        val itemsKey = "items:$account:$date"
        val items = (p.getString(itemsKey, null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty() + "${sbn.postTime}\t$from\t$subject")
            .sortedBy { it.substringBefore('\t').toLongOrNull() ?: 0L }.takeLast(40)
        val edit = p.edit()
            .putStringSet(seenKey, seen + sbn.key)
            .putInt("count:$account:$date", p.getInt("count:$account:$date", 0) + 1)
            .putString(itemsKey, items.joinToString("\n"))
        // Only the last few days are kept.
        val keep = (0..3).map { LocalDate.now().minusDays(it.toLong()).toString() }
        p.all.keys.filter { k -> k.contains(':') && keep.none { k.endsWith(it) } }.forEach { edit.remove(it) }
        edit.apply()
    }

    /** Counts any Gmail notifications still up that weren't seen arriving (the deck was closed, or before an update). */
    fun catchUp(context: Context) {
        val all = Comms.all()
        all.filter { it.packageName == GMAIL }.forEach { record(context, it, all) }
    }

    private fun String.clean() = replace('\t', ' ').replace('\n', ' ').trim()

    fun day(context: Context, date: LocalDate, account: String): Day {
        val p = prefs(context)
        val d = date.toString()
        val items = parseItems(p.getString("items:$account:$d", null))
        return Day(maxOf(p.getInt("count:$account:$d", 0), items.size), items)
    }

    fun parseItems(raw: String?): List<Item> = raw.orEmpty().split('\n').mapNotNull { line ->
        val parts = line.split('\t')
        val at = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
        Item(at, parts.getOrNull(1).orEmpty(), parts.getOrNull(2).orEmpty())
    }
}
