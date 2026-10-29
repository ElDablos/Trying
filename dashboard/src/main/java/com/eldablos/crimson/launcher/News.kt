package com.eldablos.crimson.launcher

import android.content.Context
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Headlines for the briefing, from BBC News' free feeds (top UK stories and world news, no account).
 * Cached for half an hour so the briefing shows them straight away. Blocking: call off the main thread.
 */
object News {
    class Story(val title: String, val summary: String, val link: String, val world: Boolean)

    private const val TOP = "https://feeds.bbci.co.uk/news/rss.xml"
    private const val WORLD = "https://feeds.bbci.co.uk/news/world/rss.xml"
    private const val FRESH_MS = 30 * 60_000L

    private fun prefs(context: Context) = context.getSharedPreferences("news", Context.MODE_PRIVATE)

    /** The top stories, with the leading world ones mixed in; cached, refreshed every half hour when [fetch]. */
    fun stories(context: Context, fetch: Boolean): List<Story> {
        val p = prefs(context)
        val fresh = System.currentTimeMillis() - p.getLong("at", 0) < FRESH_MS
        if (fetch && !fresh) {
            val topXml = get(TOP)
            val worldXml = get(WORLD)
            val top = topXml?.let { parse(it, world = false) }.orEmpty()
            val world = worldXml?.let { parse(it, world = true) }.orEmpty()
            if (top.isNotEmpty() || world.isNotEmpty()) {
                p.edit().putString("top", topXml.orEmpty()).putString("world", worldXml.orEmpty()).putLong("at", System.currentTimeMillis()).apply()
                return mix(top, world)
            }
        }
        return mix(parse(p.getString("top", null).orEmpty(), false), parse(p.getString("world", null).orEmpty(), true))
    }

    /** Three top stories, then two world stories that aren't among them. */
    fun mix(top: List<Story>, world: List<Story>): List<Story> {
        val first = top.take(3)
        return first + world.filter { w -> first.none { it.title == w.title } }.take(2)
    }

    private fun get(url: String): String? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        try {
            if (conn.responseCode == 200) conn.inputStream.bufferedReader().use { it.readText() } else null
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        null
    }

    /** The items of an RSS feed. */
    fun parse(xml: String, world: Boolean): List<Story> {
        if (xml.isBlank()) return emptyList()
        val out = ArrayList<Story>()
        try {
            val p = org.xmlpull.v1.XmlPullParserFactory.newInstance().newPullParser()
            p.setInput(StringReader(xml))
            var inItem = false
            var tag = ""
            var title = ""
            var desc = ""
            var link = ""
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                when (p.eventType) {
                    XmlPullParser.START_TAG -> {
                        tag = p.name
                        if (tag == "item") { inItem = true; title = ""; desc = ""; link = "" }
                    }
                    XmlPullParser.TEXT, XmlPullParser.CDSECT -> if (inItem) when (tag) {
                        "title" -> title += p.text
                        "description" -> desc += p.text
                        "link" -> link += p.text
                    }
                    XmlPullParser.END_TAG -> {
                        if (p.name == "item") {
                            inItem = false
                            if (title.isNotBlank()) out += Story(title.trim(), desc.trim(), link.trim(), world)
                        }
                        tag = ""
                    }
                }
            }
        } catch (e: Exception) {
            // Whatever parsed before the problem.
        }
        return out
    }
}
