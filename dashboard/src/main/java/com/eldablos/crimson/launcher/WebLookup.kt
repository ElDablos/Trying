package com.eldablos.crimson.launcher

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Looking something up on the web, for Jarvis to summarise: a search (DuckDuckGo's plain page, no account
 * or key), then the text of the top pages. Blocking: call it off the main thread.
 */
object WebLookup {
    class Result(val title: String, val url: String, val snippet: String)
    class Page(val title: String, val url: String, val text: String)

    private const val AGENT = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130 Mobile Safari/537.36"

    private fun get(url: String, limit: Int = 600_000): String? = try {
        (URL(url).openConnection() as HttpURLConnection).run {
            connectTimeout = 6000
            readTimeout = 8000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", AGENT)
            setRequestProperty("Accept-Language", "en-GB,en;q=0.8")
            if (responseCode !in 200..299) null else inputStream.use { s ->
                val bytes = s.readNBytes(limit)
                String(bytes, Charsets.UTF_8)
            }
        }
    } catch (e: Exception) {
        null
    }

    /** The top results for [query]: DuckDuckGo's, or Wikipedia's if it gives nothing (it can turn robots away). */
    fun search(query: String, max: Int = 5): List<Result> = duck(query, max).ifEmpty { wikipedia(query, max) }

    /** Wikipedia's articles for [query], with their opening lines. */
    private fun wikipedia(query: String, max: Int): List<Result> {
        val json = get("https://en.wikipedia.org/w/api.php?action=opensearch&format=json&limit=$max&search=" + URLEncoder.encode(query, "UTF-8")) ?: return emptyList()
        return try {
            val a = org.json.JSONArray(json)
            val titles = a.getJSONArray(1)
            val links = a.getJSONArray(3)
            (0 until titles.length()).map { Result(titles.getString(it), links.getString(it), "") }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun duck(query: String, max: Int): List<Result> {
        val html = get("https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query, "UTF-8") + "&kl=uk-en") ?: return emptyList()
        val out = ArrayList<Result>()
        val link = Regex("<a[^>]+class=\"result__a\"[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
        val snippet = Regex("<a[^>]+class=\"result__snippet\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
        val snippets = snippet.findAll(html).map { clean(it.groupValues[1]) }.toList()
        link.findAll(html).forEachIndexed { i, m ->
            var href = m.groupValues[1].replace("&amp;", "&")
            // DuckDuckGo wraps links: //duckduckgo.com/l/?uddg=<the real one>
            Regex("[?&]uddg=([^&]+)").find(href)?.let { href = URLDecoder.decode(it.groupValues[1], "UTF-8") }
            if (href.startsWith("//")) href = "https:$href"
            if (!href.startsWith("http") || href.contains("duckduckgo.com/y.js")) return@forEachIndexed
            out += Result(clean(m.groupValues[2]), href, snippets.getOrNull(i).orEmpty())
        }
        return out.distinctBy { it.url }.take(max)
    }

    /** A page's readable text (its paragraphs, without menus and scripts), up to [max] characters. */
    fun read(r: Result, max: Int = 4000): Page? {
        val html = get(r.url) ?: return null
        val body = html.replace(Regex("(?is)<(script|style|noscript|svg|nav|header|footer|form|aside)[^>]*>.*?</\\1>"), " ")
        val paras = Regex("(?is)<(p|li|h1|h2|h3|td)[^>]*>(.*?)</\\1>").findAll(body).map { clean(it.groupValues[2]) }
            .filter { it.length > 40 }.toList()
        val text = (if (paras.isNotEmpty()) paras.joinToString("\n") else clean(body)).take(max)
        return if (text.length < 80) null else Page(r.title, r.url, text)
    }

    /** Search, then read the top few pages: the material for a summary (snippets alone if the pages won't load). */
    fun gather(query: String, pages: Int = 3): Pair<List<Result>, List<Page>> {
        val results = search(query)
        val read = results.take(pages + 2).mapNotNull { read(it) }.take(pages)
        val fallback = if (read.isEmpty()) results.filter { it.snippet.isNotBlank() }.map { Page(it.title, it.url, it.snippet) } else read
        return results to fallback
    }

    private fun clean(html: String): String = html.replace(Regex("<[^>]+>"), " ")
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#x27;", "'").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
        .replace(Regex("&#(\\d+);")) { runCatching { it.groupValues[1].toInt().toChar().toString() }.getOrDefault(" ") }
        .replace(Regex("\\s+"), " ").trim()

    /** "bbc.co.uk" from a link, for the card. */
    fun site(url: String): String = runCatching { URL(url).host.removePrefix("www.") }.getOrDefault(url)
}
