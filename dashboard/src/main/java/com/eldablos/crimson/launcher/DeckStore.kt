package com.eldablos.crimson.launcher

import android.content.Context
import java.time.LocalDate

/** The Command Deck's own settings: section order and visibility, open cards, quick actions, links and steps. */
object DeckStore {
    /** The deck's sections, in the order they start in: what's next first, reference readouts last. */
    enum class Section(val title: String, val expandable: Boolean) {
        CLAUDE("ASK CLAUDE", false),
        AGENDA("AGENDA", true),
        COMMS("COMMS", true),
        VITALS("VITALS", true),
        AUDIO("AUDIO", true),
        ACTIONS("QUICK ACTIONS", false),
        SKY("SKY", true),
        NOTES("NOTES", true),
        LINKS("LINKS", false),
        MARKETS("MARKETS", true),
        MOST_USED("MOST USED TODAY", false),
        BUDS("BUDS", false),
        POWER("POWER · 24H", true),
        DATA("DATA", false),
        TIME("TIME", false),
        SYSTEMS("SYSTEMS", true),
    }

    /** Quick actions to choose from; the deck shows six. */
    enum class Action(val glyph: String, val label: String) {
        TORCH("✦", "TORCH"),
        DND("☾", "DND"),
        TIMER("⏱", "TIMER 5M"),
        ALARMS("⏰", "ALARMS"),
        CAMERA("◉", "CAMERA"),
        WIFI("≋", "WI-FI"),
        BLUETOOTH("ᛒ", "BLUETOOTH"),
        DATA("⇅", "DATA"),
        AIRPLANE("✈", "AIRPLANE"),
        SOUND("♪", "SOUND"),
        SAVER("▣", "SAVER"),
        CALC("±", "CALC"),
        SETTINGS("⚙", "PHONE"),
    }

    private fun prefs(context: Context) = context.getSharedPreferences("deck", Context.MODE_PRIVATE)

    fun order(context: Context): List<Section> {
        val saved = prefs(context).getString("order", null)?.split(',')?.mapNotNull { n -> Section.entries.firstOrNull { it.name == n } }.orEmpty()
        // New sections (from updates) join at their usual place in the list.
        return saved + Section.entries.filter { it !in saved }
    }

    fun setOrder(context: Context, order: List<Section>) = prefs(context).edit().putString("order", order.joinToString(",") { it.name }).apply()

    fun hidden(context: Context): Set<Section> =
        prefs(context).getString("hidden", null)?.split(',')?.mapNotNull { n -> Section.entries.firstOrNull { it.name == n } }?.toSet().orEmpty()

    fun setHidden(context: Context, hidden: Set<Section>) = prefs(context).edit().putString("hidden", hidden.joinToString(",") { it.name }).apply()

    fun expanded(context: Context): Set<Section> =
        prefs(context).getString("expanded", null)?.split(',')?.mapNotNull { n -> Section.entries.firstOrNull { it.name == n } }?.toSet().orEmpty()

    fun setExpanded(context: Context, open: Set<Section>) = prefs(context).edit().putString("expanded", open.joinToString(",") { it.name }).apply()

    fun actions(context: Context): List<Action> {
        val saved = prefs(context).getString("actions", null)?.split(',')?.mapNotNull { n -> Action.entries.firstOrNull { it.name == n } }
        return saved?.takeIf { it.isNotEmpty() } ?: listOf(Action.TORCH, Action.DND, Action.TIMER, Action.WIFI, Action.CAMERA, Action.SOUND)
    }

    fun setActions(context: Context, actions: List<Action>) = prefs(context).edit().putString("actions", actions.joinToString(",") { it.name }).apply()

    // --- Links: name and address, one per line ---

    class Link(val name: String, val url: String)

    private val DEFAULT_LINKS = listOf(
        Link("BBC NEWS", "https://www.bbc.co.uk/news"),
        Link("COINGECKO", "https://www.coingecko.com"),
        Link("YOUTUBE", "https://www.youtube.com"),
        Link("GOOGLE NEWS", "https://news.google.com"),
    )

    fun links(context: Context): List<Link> {
        val raw = prefs(context).getString("links", null) ?: return DEFAULT_LINKS
        return raw.split('\n').mapNotNull { line ->
            val i = line.indexOf('\t')
            if (i <= 0) null else Link(line.substring(0, i), line.substring(i + 1))
        }
    }

    fun setLinks(context: Context, links: List<Link>) =
        prefs(context).edit().putString("links", links.joinToString("\n") { "${it.name}\t${it.url}" }).apply()

    /** "bbc.co.uk/news" → "https://bbc.co.uk/news". */
    fun normaliseUrl(text: String): String {
        val t = text.trim()
        return if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
    }

    // --- Crypto watchlist (CoinGecko ids) ---

    fun watchlist(context: Context): List<String> =
        prefs(context).getString("watchlist", null)?.split(',')?.filter { it.isNotBlank() } ?: listOf("bitcoin", "ethereum", "solana")

    fun setWatchlist(context: Context, ids: List<String>) = prefs(context).edit().putString("watchlist", ids.joinToString(",")).apply()

    // --- Steps over the last fortnight, for the vitals chart ---

    fun recordSteps(context: Context, steps: Long, today: LocalDate = LocalDate.now()) {
        val history = stepHistory(context).toMutableMap()
        history[today] = maxOf(steps, history[today] ?: 0L)
        val kept = history.filterKeys { !it.isBefore(today.minusDays(13)) }
        prefs(context).edit().putString("steps", kept.entries.joinToString(";") { "${it.key}=${it.value}" }).apply()
    }

    fun stepHistory(context: Context): Map<LocalDate, Long> = parseSteps(prefs(context).getString("steps", null))

    fun parseSteps(raw: String?): Map<LocalDate, Long> = raw.orEmpty().split(';').mapNotNull {
        val parts = it.split('=')
        if (parts.size != 2) return@mapNotNull null
        try {
            LocalDate.parse(parts[0]) to (parts[1].toLongOrNull() ?: return@mapNotNull null)
        } catch (e: Exception) {
            null
        }
    }.toMap()
}
