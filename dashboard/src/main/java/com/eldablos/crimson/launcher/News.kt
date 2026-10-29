package com.eldablos.crimson.launcher

import android.content.Context
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
        return try {
            val f = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            // A feed has no business pulling in other files.
            try { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } catch (e: Exception) { }
            f.isExpandEntityReferences = false
            val doc = f.newDocumentBuilder().parse(org.xml.sax.InputSource(StringReader(xml)))
            val items = doc.getElementsByTagName("item")
            (0 until items.length).mapNotNull { i ->
                val item = items.item(i) as? org.w3c.dom.Element ?: return@mapNotNull null
                fun text(tag: String) = item.getElementsByTagName(tag).item(0)?.textContent?.trim().orEmpty()
                val title = text("title")
                if (title.isBlank()) null else Story(title, text("description"), text("link"), world)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
