package com.eldablos.crimson.launcher

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
}
