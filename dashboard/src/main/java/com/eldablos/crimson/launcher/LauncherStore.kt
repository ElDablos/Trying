package com.eldablos.crimson.launcher

import android.content.ComponentName
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * A node on the home ring: a folder of apps. The games node fills itself with the Arcade's games
 * instead of holding a list.
 */
data class Node(val name: String, val apps: List<ComponentName>, val games: Boolean = false)

/** The time-of-day modes. Each brings its top node to the top of the ring and can dim others. */
enum class Mode(val label: String, val top: String, val dim: Set<String> = emptySet()) {
    MORNING("MORNING", "MAIN"),
    WORK("WORK MODE", "WORK", setOf("GAMES")),
    NIGHT("NIGHT OPS", "MAIN");

    companion object {
        /** Morning 05:00–07:00, Work 07:00–17:00, Night Ops from 17:00. */
        fun at(hour: Int): Mode = when (hour) {
            in 5 until 7 -> MORNING
            in 7 until 17 -> WORK
            else -> NIGHT
        }

        /** [nodes] turned round the ring so [mode]'s top node sits at the top, keeping their order. */
        fun <T> rotate(nodes: List<T>, mode: Mode, name: (T) -> String): List<T> {
            val i = nodes.indexOfFirst { name(it).equals(mode.top, ignoreCase = true) }
            return if (i <= 0) nodes else nodes.drop(i) + nodes.take(i)
        }
    }
}

/** The launcher's nodes and dock, saved as JSON. First run builds them from the user's old folders. */
object LauncherStore {
    const val MAX_NODES = 8
    const val MAX_APPS = 16
    const val MAX_DOCK = 7
    const val GAMES = "GAMES"

    /** The user's home-screen folders, by app name. "A|B" means either name. */
    private val FOLDERS = listOf(
        "MAIN" to listOf("YT Music", "YouTube", "Instagram", "Facebook", "Lords & Knights", "Air+", "Tapo", "Bible"),
        "AI" to listOf("Claude", "ChatGPT", "Gemini", "Grok"),
        "WORK" to listOf("Nory IM", "Nory Team", "Slack", "Cropster"),
        "APPS" to listOf(
            "Notes|Samsung Notes", "Gallery", "Authenticator", "NordVPN", "Arden", "Citymapper", "Just Eat",
            "Deliveroo", "Tesco", "Amazon Shopping|Amazon", "Ryanair", "Uber", "Caffè Nero|Caffe Nero", "Stremio",
            "Shazam", "Sound Assistant",
        ),
        "GOOGLE" to listOf("Google", "Drive", "Google TV", "Meet", "Photos", "Maps"),
        "SAMSUNG" to listOf("Calculator", "Voice Recorder", "Samsung Health|Health", "Smart Compass", "Smart Switch", "SmartThings"),
    )

    /** The old dock, which now sits in the core. */
    private val DOCK = listOf("WhatsApp", "Messenger", "Messages", "Phone", "Camera", "Play Store", "Chrome")

    private fun prefs(context: Context) = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    fun nodes(context: Context): List<Node> {
        val json = prefs(context).getString("nodes", null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                val apps = o.optJSONArray("apps") ?: JSONArray()
                Node(
                    o.getString("name"),
                    (0 until apps.length()).mapNotNull { ComponentName.unflattenFromString(apps.getString(it)) },
                    o.optBoolean("games"),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun setNodes(context: Context, nodes: List<Node>) {
        val array = JSONArray()
        nodes.forEach { node ->
            array.put(
                JSONObject()
                    .put("name", node.name)
                    .put("apps", JSONArray(node.apps.map { it.flattenToString() }))
                    .put("games", node.games),
            )
        }
        prefs(context).edit().putString("nodes", array.toString()).apply()
    }

    fun dock(context: Context): List<ComponentName> =
        prefs(context).getString("dock", null)?.split('\n')?.mapNotNull { ComponentName.unflattenFromString(it) }.orEmpty()

    fun setDock(context: Context, dock: List<ComponentName>) {
        prefs(context).edit().putString("dock", dock.joinToString("\n") { it.flattenToString() }).apply()
    }

    /** Builds the ring and dock from [apps] the first time the launcher opens. */
    fun seedIfNeeded(context: Context, apps: List<AppEntry>) {
        val p = prefs(context)
        if (p.getBoolean("seeded", false) || apps.isEmpty()) return
        val named = apps.map { it.label to it.component }
        val used = HashSet<ComponentName>()
        val dock = match(DOCK, named, used)
        val nodes = FOLDERS.map { (name, wanted) -> Node(name, match(wanted, named, used)) } +
            Node(GAMES, emptyList(), games = true)
        setDock(context, dock)
        setNodes(context, nodes)
        p.edit().putBoolean("seeded", true).apply()
    }

    /**
     * Finds each wanted app by name, in order: an exact name first, then one starting with it, then one
     * containing it (for "Google Authenticator" and the like). Each app is only used once.
     */
    fun <T> match(wanted: List<String>, apps: List<Pair<String, T>>, used: MutableSet<T>): List<T> = wanted.mapNotNull { want ->
        val names = want.split('|').map { it.trim().lowercase() }
        val free = apps.filter { it.second !in used }
        val found = names.firstNotNullOfOrNull { n -> free.firstOrNull { it.first.trim().lowercase() == n } }
            ?: names.firstNotNullOfOrNull { n -> free.firstOrNull { it.first.trim().lowercase().startsWith(n) } }
            ?: names.firstNotNullOfOrNull { n -> free.firstOrNull { it.first.trim().lowercase().contains(n) } }
        found?.second?.also { used += it }
    }
}
