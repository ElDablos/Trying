package com.eldablos.crimson.launcher

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Everything Jarvis knows, in one file: your shifts and clocked times, pay settings, what you've taught him,
 * routines, facts, habits, notes, spending, study sessions and settings (every preference the app keeps).
 * Saved to Downloads/Jarvis/Backups, from where it can go to Drive; restored from a file you pick. The brains
 * and the wake-word model are big downloads, not memory, so they aren't in it.
 */
object JarvisBackup {
    private const val FOLDER = "Jarvis/Backups"
    private const val MIME = "application/json"

    private fun names(context: Context): List<String> =
        File(context.applicationInfo.dataDir, "shared_prefs").listFiles()?.map { it.name.removeSuffix(".xml") }?.filter { !it.startsWith("WebView") }?.sorted().orEmpty()

    /** All of it as JSON, each value with its type so it comes back exactly. */
    fun export(context: Context): String {
        val all = JSONObject()
        for (name in names(context)) {
            val p = JSONObject()
            for ((k, v) in context.getSharedPreferences(name, Context.MODE_PRIVATE).all) {
                val e = JSONObject()
                when (v) {
                    is String -> e.put("t", "s").put("v", v)
                    is Int -> e.put("t", "i").put("v", v)
                    is Long -> e.put("t", "l").put("v", v)
                    is Float -> e.put("t", "f").put("v", v.toDouble())
                    is Boolean -> e.put("t", "b").put("v", v)
                    is Set<*> -> e.put("t", "set").put("v", JSONArray(v.map { it.toString() }))
                    else -> continue
                }
                p.put(k, e)
            }
            all.put(name, p)
        }
        return JSONObject().put("jarvis_backup", 1).put("made", System.currentTimeMillis()).put("prefs", all).toString()
    }

    /**
     * Puts a backup back: each part replaced by what's in the file. The whole file is read and checked first,
     * so a damaged one changes nothing. How many parts, or -1 if it isn't a (whole) backup.
     */
    fun restore(context: Context, json: String): Int {
        val parts = try {
            val root = JSONObject(json)
            if (!root.has("jarvis_backup")) return -1
            val all = root.getJSONObject("prefs")
            all.keys().asSequence().toList().map { name ->
                val p = all.getJSONObject(name)
                name to p.keys().asSequence().toList().map { k ->
                    val e = p.getJSONObject(k)
                    val v: Any = when (e.getString("t")) {
                        "s" -> e.getString("v")
                        "i" -> e.getInt("v")
                        "l" -> e.getLong("v")
                        "f" -> e.getDouble("v").toFloat()
                        "b" -> e.getBoolean("v")
                        "set" -> e.getJSONArray("v").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
                        else -> throw IllegalArgumentException("unknown type")
                    }
                    k to v
                }
            }
        } catch (e: Exception) {
            return -1
        }
        for ((name, values) in parts) {
            val ed = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            for ((k, v) in values) {
                @Suppress("UNCHECKED_CAST")
                when (v) {
                    is String -> ed.putString(k, v)
                    is Int -> ed.putInt(k, v)
                    is Long -> ed.putLong(k, v)
                    is Float -> ed.putFloat(k, v)
                    is Boolean -> ed.putBoolean(k, v)
                    is Set<*> -> ed.putStringSet(k, v as Set<String>)
                }
            }
            ed.commit()
        }
        return parts.size
    }

    fun restore(context: Context, uri: Uri): Int = try {
        context.contentResolver.openInputStream(uri)!!.use { restore(context, it.readBytes().toString(Charsets.UTF_8)) }
    } catch (e: Exception) {
        -1
    }

    /** Saved in Downloads/Jarvis/Backups; its address, or null. [auto] ones are named so they can be tidied. */
    fun save(context: Context, auto: Boolean = false): Uri? = try {
        val data = export(context).toByteArray()
        val name = "Jarvis memory${if (auto) " (weekly)" else ""} ${LocalDate.now().format(DateTimeFormatter.ofPattern("d MMM yyyy"))} ${System.currentTimeMillis() % 100000}.json"
        val cr = context.contentResolver
        val values = ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
            put(android.provider.MediaStore.Downloads.MIME_TYPE, MIME)
            put(android.provider.MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
        }
        val uri = cr.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
        cr.openOutputStream(uri)!!.use { it.write(data) }
        context.getSharedPreferences("jarvis", Context.MODE_PRIVATE).edit().putLong("backup_at", System.currentTimeMillis()).apply()
        uri
    } catch (e: Exception) {
        null
    }

    /** When the last backup was made (0: never). */
    fun last(context: Context): Long = context.getSharedPreferences("jarvis", Context.MODE_PRIVATE).getLong("backup_at", 0)

    /** Once a week, by itself: a fresh backup, keeping the newest three weekly ones. */
    fun weekly(context: Context) {
        if (System.currentTimeMillis() - last(context) < 7 * 86_400_000L) return
        save(context, auto = true) ?: return
        try {
            val col = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val old = ArrayList<Long>()
            context.contentResolver.query(col, arrayOf(android.provider.MediaStore.Downloads._ID),
                "${android.provider.MediaStore.Downloads.DISPLAY_NAME} LIKE ?", arrayOf("Jarvis memory (weekly)%"), "${android.provider.MediaStore.Downloads.DATE_ADDED} DESC")?.use { c ->
                var i = 0
                while (c.moveToNext()) if (i++ >= 3) old += c.getLong(0)
            }
            old.forEach { context.contentResolver.delete(android.content.ContentUris.withAppendedId(col, it), null, null) }
        } catch (e: Exception) {
            // Left as they are.
        }
    }
}
