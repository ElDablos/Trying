package com.eldablos.crimson.launcher

import android.content.Context
import java.time.LocalDate

/** The Command Deck's own settings: section order and visibility, open cards, quick actions, links and steps. */
object DeckStore {
    /** The deck's sections, in the order they start in: what's next first, reference readouts last. */
    enum class Section(val title: String, val expandable: Boolean) {
        CLAUDE("ASK CLAUDE", false),
        AGENDA("AGENDA", true),
        CALENDAR("CALENDAR", true),
        COMMS("COMMS", true),
        VITALS("VITALS", true),
        AUDIO("AUDIO", true),
        ACTIONS("QUICK ACTIONS", false),
        FOCUS("FOCUS", false),
        SKY("SKY", true),
        WEEK("7-DAY FORECAST", false),
        NOTES("NOTES", true),
        HABITS("HABITS", false),
        COUNTDOWNS("COUNTDOWNS", false),
        CLOCKS("CLOCKS", false),
        LINKS("LINKS", false),
        MARKETS("MARKETS", true),
        MOST_USED("MOST USED TODAY", false),
        BUDS("BUDS", false),
        POWER("POWER · 24H", true),
        DATA("NETWORK", false),
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

    fun order(context: Context): List<Section> = mergeOrder(prefs(context).getString("order", null))

    /** The saved order, with sections new since it was saved slotted in after the one they follow by default. */
    fun mergeOrder(raw: String?): List<Section> {
        val list = raw?.split(',')?.mapNotNull { n -> Section.entries.firstOrNull { it.name == n } }?.distinct().orEmpty().toMutableList()
        if (list.isEmpty()) return Section.entries
        for (s in Section.entries) {
            if (s in list) continue
            val before = Section.entries.subList(0, s.ordinal).lastOrNull { it in list }
            list.add(if (before == null) 0 else list.indexOf(before) + 1, s)
        }
        return list
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

    // --- Clocks: other cities (time zone ids); home is always first ---

    fun clocks(context: Context): List<String> =
        prefs(context).getString("clocks", null)?.split(',')?.filter { it.isNotBlank() } ?: listOf("Europe/Vilnius", "Europe/Oslo")

    fun setClocks(context: Context, zones: List<String>) = prefs(context).edit().putString("clocks", zones.joinToString(",")).apply()

    /** Cities to pick from, by zone. */
    val CITIES = listOf(
        "London" to "Europe/London", "Dublin" to "Europe/Dublin", "Lisbon" to "Europe/Lisbon", "Paris" to "Europe/Paris",
        "Madrid" to "Europe/Madrid", "Berlin" to "Europe/Berlin", "Rome" to "Europe/Rome", "Vilnius" to "Europe/Vilnius",
        "Molde" to "Europe/Oslo", "Oslo" to "Europe/Oslo", "Stockholm" to "Europe/Stockholm", "Athens" to "Europe/Athens", "Istanbul" to "Europe/Istanbul", "Moscow" to "Europe/Moscow", "Dubai" to "Asia/Dubai",
        "Delhi" to "Asia/Kolkata", "Bangkok" to "Asia/Bangkok", "Singapore" to "Asia/Singapore", "Hong Kong" to "Asia/Hong_Kong",
        "Tokyo" to "Asia/Tokyo", "Seoul" to "Asia/Seoul", "Sydney" to "Australia/Sydney", "Auckland" to "Pacific/Auckland",
        "Honolulu" to "Pacific/Honolulu", "Los Angeles" to "America/Los_Angeles", "Denver" to "America/Denver",
        "Chicago" to "America/Chicago", "New York" to "America/New_York", "Toronto" to "America/Toronto",
        "Mexico City" to "America/Mexico_City", "São Paulo" to "America/Sao_Paulo", "Buenos Aires" to "America/Argentina/Buenos_Aires",
        "Cape Town" to "Africa/Johannesburg", "Lagos" to "Africa/Lagos", "Cairo" to "Africa/Cairo",
    )

    fun cityName(zone: String): String = CITIES.firstOrNull { it.second == zone }?.first ?: zone.substringAfterLast('/').replace('_', ' ')

    // --- Countdowns: name and date, and when it was added (for the progress bar) ---

    class Countdown(val name: String, val date: LocalDate, val added: LocalDate)

    fun countdowns(context: Context): List<Countdown> = parseCountdowns(prefs(context).getString("countdowns", null))

    fun setCountdowns(context: Context, list: List<Countdown>) =
        prefs(context).edit().putString("countdowns", list.joinToString("\n") { "${it.name}\t${it.date}\t${it.added}" }).apply()

    fun parseCountdowns(raw: String?): List<Countdown> {
        if (raw == null) return listOf(Countdown("Christmas", LocalDate.of(LocalDate.now().year, 12, 25).let { if (it.isBefore(LocalDate.now())) it.plusYears(1) else it }, LocalDate.now().minusDays(30)))
        return raw.split('\n').mapNotNull { line ->
            val p = line.split('\t')
            if (p.size < 2) return@mapNotNull null
            try {
                Countdown(p[0], LocalDate.parse(p[1]), p.getOrNull(2)?.let { LocalDate.parse(it) } ?: LocalDate.now())
            } catch (e: Exception) {
                null
            }
        }.sortedBy { it.date }
    }

    // --- Habits: a name and the days it was done ---

    class Habit(val name: String, val done: Set<LocalDate>)

    fun habits(context: Context): List<Habit> = parseHabits(prefs(context).getString("habits", null))

    fun setHabits(context: Context, list: List<Habit>, today: LocalDate = LocalDate.now()) =
        prefs(context).edit().putString("habits", list.joinToString("\n") { h ->
            h.name + "\t" + h.done.filter { !it.isBefore(today.minusDays(400)) }.sorted().joinToString(",")
        }).apply()

    fun parseHabits(raw: String?): List<Habit> {
        if (raw == null) return listOf(Habit("Gym", emptySet()), Habit("Read 20m", emptySet()), Habit("Water 2L", emptySet()))
        return raw.split('\n').filter { it.isNotBlank() }.map { line ->
            val name = line.substringBefore('\t')
            val days = line.substringAfter('\t', "").split(',').mapNotNull { d -> try { LocalDate.parse(d) } catch (e: Exception) { null } }.toSet()
            Habit(name, days)
        }
    }

    /** Days in a row it was done, counting back from today, or from yesterday while today's still open. */
    fun streak(done: Set<LocalDate>, today: LocalDate = LocalDate.now()): Int {
        var d = if (today in done) today else today.minusDays(1)
        var n = 0
        while (d in done) { n++; d = d.minusDays(1) }
        return n
    }

    // --- Holdings: how much of each coin, and optionally what it cost in all ---

    class Holding(val amount: Double, val cost: Double?)

    fun holdings(context: Context): Map<String, Holding> = parseHoldings(prefs(context).getString("holdings", null))

    fun setHolding(context: Context, id: String, h: Holding?) {
        val all = holdings(context).toMutableMap()
        if (h == null || h.amount <= 0) all.remove(id) else all[id] = h
        prefs(context).edit().putString("holdings", all.entries.joinToString(";") { (k, v) -> "$k=${v.amount}" + (v.cost?.let { "@$it" } ?: "") }).apply()
    }

    fun parseHoldings(raw: String?): Map<String, Holding> = raw.orEmpty().split(';').mapNotNull { part ->
        val id = part.substringBefore('=', "").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val rest = part.substringAfter('=')
        val amount = rest.substringBefore('@').toDoubleOrNull() ?: return@mapNotNull null
        val cost = if ('@' in rest) rest.substringAfter('@').toDoubleOrNull() else null
        id to Holding(amount, cost)
    }.toMap()

    // --- Focus sessions ---

    class Focus(val endAt: Long, val minutes: Int, val dnd: Boolean)

    fun focus(context: Context): Focus? {
        val p = prefs(context)
        val end = p.getLong("focus_end", 0L)
        return if (end == 0L) null else Focus(end, p.getInt("focus_min", 25), p.getBoolean("focus_dnd", false))
    }

    fun setFocus(context: Context, f: Focus?) {
        val e = prefs(context).edit()
        if (f == null) e.remove("focus_end") else e.putLong("focus_end", f.endAt).putInt("focus_min", f.minutes).putBoolean("focus_dnd", f.dnd)
        e.apply()
    }

    /** Minutes focused today and how many sessions. */
    fun focusToday(context: Context, today: LocalDate = LocalDate.now()): Pair<Int, Int> {
        val p = prefs(context)
        if (p.getString("focus_day", null) != today.toString()) return 0 to 0
        return p.getInt("focus_total", 0) to p.getInt("focus_count", 0)
    }

    fun addFocus(context: Context, minutes: Int, today: LocalDate = LocalDate.now()) {
        val (total, count) = focusToday(context, today)
        prefs(context).edit().putString("focus_day", today.toString()).putInt("focus_total", total + minutes).putInt("focus_count", count + 1).apply()
    }
}
