package com.eldablos.crimson.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** What you've spent ("I spent £12 on lunch"), sorted into kinds, to add up by day, week or month. */
object Spending {
    class Item(val at: Long, val amount: Double, val what: String, val category: String) {
        val date: LocalDate get() = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate()
    }

    private val KINDS = listOf(
        "Food & drink" to "lunch|dinner|breakfast|brunch|coffee|food|meal|takeaway|take away|restaurant|groceries|grocery|supermarket|tesco|sainsbury|lidl|aldi|asda|waitrose|co-op|pret|mcdonald|kfc|nando|greggs|snack|drink|drinks|pub|bar|beer|wine|deliveroo|uber eats|just eat",
        "Travel" to "tube|train|bus|uber|taxi|cab|oyster|tfl|petrol|fuel|travel|fare|bolt|parking|flight|railcard",
        "Bills" to "rent|bill|phone|internet|wifi|electric|electricity|gas|water|council|subscription|netflix|spotify|insurance|gym membership",
        "Shopping" to "clothes|shoes|amazon|shop|shopping|asos|zara|primark|h&m|boots|present|gift|haircut|barber",
        "Going out" to "cinema|film|movie|game|games|concert|gig|ticket|tickets|night out|club|bowling|event",
        "Uni" to "book|books|textbook|course|uni|stationery|printing|laptop",
        "Health" to "pharmacy|medicine|dentist|doctor|vitamins|gym",
    )

    fun categoryOf(what: String): String {
        val w = what.lowercase()
        return KINDS.firstOrNull { (_, words) -> Regex("\\b($words)\\b").containsMatchIn(w) }?.first ?: "Other"
    }

    private fun prefs(context: Context) = context.getSharedPreferences("spending", Context.MODE_PRIVATE)

    fun all(context: Context): List<Item> {
        val a = runCatching { JSONArray(prefs(context).getString("items", "[]")) }.getOrDefault(JSONArray())
        return (0 until a.length()).map { a.getJSONObject(it) }.map { Item(it.getLong("at"), it.getDouble("a"), it.getString("w"), it.optString("c", "Other")) }
    }

    private fun save(context: Context, list: List<Item>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("at", it.at).put("a", it.amount).put("w", it.what).put("c", it.category)) }
        prefs(context).edit().putString("items", a.toString()).apply()
    }

    fun add(context: Context, amount: Double, what: String): Item {
        val item = Item(System.currentTimeMillis(), amount, what, categoryOf(what))
        save(context, all(context) + item)
        return item
    }

    fun remove(context: Context, item: Item) = save(context, all(context).filterNot { it.at == item.at && it.amount == item.amount })

    fun between(context: Context, from: LocalDate, to: LocalDate) = all(context).filter { !it.date.isBefore(from) && !it.date.isAfter(to) }

    /** Totals by kind, biggest first. */
    fun byKind(items: List<Item>): List<Pair<String, Double>> = items.groupBy { it.category }.mapValues { (_, v) -> v.sumOf { it.amount } }.toList().sortedByDescending { it.second }

    /** "£12.50" from what you said: "12", "12.50", "£12", "12 pounds", "50p", "12 quid 50". Null if there's no amount. */
    fun amountIn(s: String): Double? {
        val t = s.lowercase()
        Regex("(\\d+)\\s*p\\b").find(t)?.takeIf { !Regex("£|pound|quid").containsMatchIn(t) }?.let { return it.groupValues[1].toDouble() / 100 }
        val m = Regex("£?\\s*(\\d+(?:[.,]\\d{1,2})?)\\s*(?:pounds?|quid|gbp)?(?:\\s*(?:and\\s*)?(\\d{1,2})\\s*(?:p|pence)\\b)?").find(t) ?: return null
        val pounds = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        return pounds + (m.groupValues[2].toIntOrNull() ?: 0) / 100.0
    }

    // --- Money left until payday ---

    /** What you said you had left, and when: what's spent since is taken off. */
    class Balance(val amount: Double, val at: Long) {
        val date: LocalDate get() = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate()
    }

    fun balance(context: Context): Balance? {
        val p = prefs(context)
        if (!p.contains("balance")) return null
        return Balance(p.getFloat("balance", 0f).toDouble(), p.getLong("balance_at", 0L))
    }

    fun setBalance(context: Context, amount: Double?) {
        val e = prefs(context).edit()
        if (amount == null) e.remove("balance").remove("balance_at") else e.putFloat("balance", amount.toFloat()).putLong("balance_at", System.currentTimeMillis())
        e.apply()
    }

    /** Money left now: the balance you gave, less what you've logged spending since. Null if none, or it's from before the last payday. */
    fun left(context: Context, lastPayday: LocalDate?): Double? {
        val b = balance(context) ?: return null
        if (lastPayday != null && b.date.isBefore(lastPayday)) return null
        return b.amount - all(context).filter { it.at > b.at }.sumOf { it.amount }
    }

    /** A day's share of [left] until [payday] (today counts; payday doesn't). */
    fun perDay(left: Double, today: LocalDate, payday: LocalDate): Double {
        val days = java.time.temporal.ChronoUnit.DAYS.between(today, payday).coerceAtLeast(1)
        return left / days
    }
}
