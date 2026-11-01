package com.eldablos.crimson.launcher

import android.content.Context

/**
 * The launcher's own log: crashes, things Jarvis couldn't do, a brain that didn't answer, a service that
 * didn't reply. Kept on the phone (the last [KEEP] entries) and shown on the Command Deck's System tab, so a
 * problem can be seen, copied and sent instead of guessed at.
 */
object SysLog {
    class Entry(val at: Long, val level: Char, val source: String, val text: String)

    private const val KEEP = 200
    @Volatile
    private var app: Context? = null
    private var installed = false

    /** Call as each part starts (the launcher, Jarvis's ear): remembers where to write, and notes crashes. */
    @Synchronized
    fun init(context: Context) {
        app = context.applicationContext
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                val where = e.stackTrace.firstOrNull { it.className.startsWith("com.eldablos") } ?: e.stackTrace.firstOrNull()
                add('E', "Crash", "${e.javaClass.simpleName}: ${e.message.orEmpty().take(160)}${where?.let { " · ${it.fileName}:${it.lineNumber}" } ?: ""}", now = true)
            } catch (ignored: Throwable) {
                // Never stand in the way of the real crash handling.
            }
            previous?.uncaughtException(thread, e)
        }
    }

    fun error(source: String, text: String) = add('E', source, text)
    fun warn(source: String, text: String) = add('W', source, text)
    fun info(source: String, text: String) = add('I', source, text)

    private fun prefs(context: Context) = context.getSharedPreferences("syslog", Context.MODE_PRIVATE)

    @Synchronized
    private fun add(level: Char, source: String, text: String, now: Boolean = false) {
        val c = app ?: return
        val line = "${System.currentTimeMillis()}|$level|${source.replace('|', '/')}|${text.replace('\n', ' ').replace('|', '/').take(300)}"
        val kept = prefs(c).getString("log", "").orEmpty().lines().filter { it.isNotBlank() }.takeLast(KEEP - 1) + line
        val ed = prefs(c).edit().putString("log", kept.joinToString("\n"))
        if (now) ed.commit() else ed.apply()
    }

    /** Newest first. */
    fun all(context: Context): List<Entry> = prefs(context).getString("log", "").orEmpty().lines().mapNotNull { l ->
        val p = l.split('|', limit = 4)
        if (p.size < 4) null else Entry(p[0].toLongOrNull() ?: 0L, p[1].firstOrNull() ?: 'I', p[2], p[3])
    }.reversed()

    fun clear(context: Context) = prefs(context).edit().remove("log").apply()

    /** All of it as text, for copying. */
    fun text(context: Context): String {
        val f = java.time.format.DateTimeFormatter.ofPattern("d MMM HH:mm:ss")
        return all(context).joinToString("\n") { e ->
            java.time.Instant.ofEpochMilli(e.at).atZone(java.time.ZoneId.systemDefault()).format(f) + " ${e.level} ${e.source}: ${e.text}"
        }
    }
}
