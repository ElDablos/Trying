package com.eldablos.crimson.launcher

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Finding your files: everything in the phone's storage (with "All files access"; otherwise your photos,
 * videos and music, and what Jarvis made), searched by name, kind, folder, date, and the words inside Word,
 * Excel and text files. "The PDF from uni about statistics", "the spreadsheet I made last week", "screenshots
 * from yesterday". The index stays on the phone.
 */
object Files {
    class Found(val uri: Uri, val name: String, val folder: String, val mime: String, val modified: Long, val size: Long) {
        val date: LocalDate get() = Instant.ofEpochMilli(modified).atZone(ZoneId.systemDefault()).toLocalDate()
        val kind: String get() = kindOf(mime, name)
    }

    /** Whether he can see everything ("All files access"), not just media and his own files. */
    fun fullAccess(): Boolean = try {
        android.os.Environment.isExternalStorageManager()
    } catch (e: Throwable) {
        false
    }

    /** The settings page where you allow "All files access" for the launcher. */
    fun accessIntent(context: Context) = android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        Uri.parse("package:${context.packageName}")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)

    fun kindOf(mime: String, name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            mime == "application/pdf" || ext == "pdf" -> "pdf"
            ext in setOf("doc", "docx", "odt", "rtf") || mime.contains("wordprocessing") -> "document"
            ext in setOf("xls", "xlsx", "csv", "ods") || mime.contains("spreadsheet") -> "spreadsheet"
            ext in setOf("ppt", "pptx", "odp") || mime.contains("presentation") -> "slides"
            mime.startsWith("image/") -> if (name.contains("screenshot", true)) "screenshot" else "photo"
            mime.startsWith("video/") -> "video"
            mime.startsWith("audio/") -> if (name.contains("record", true) || name.contains("voice", true)) "recording" else "audio"
            ext in setOf("txt", "md", "json", "log") || mime.startsWith("text/") -> "text"
            ext in setOf("zip", "rar", "7z") -> "archive"
            ext == "apk" -> "app"
            else -> "file"
        }
    }

    private val KIND_WORDS = mapOf(
        "pdf" to setOf("pdf", "pdfs"),
        "document" to setOf("document", "documents", "doc", "docs", "word", "essay", "letter", "cv", "resume"),
        "spreadsheet" to setOf("spreadsheet", "spreadsheets", "excel", "sheet", "sheets", "csv", "table"),
        "slides" to setOf("slides", "presentation", "powerpoint", "deck"),
        "screenshot" to setOf("screenshot", "screenshots", "screen shot"),
        "photo" to setOf("photo", "photos", "picture", "pictures", "pic", "pics", "image", "images", "selfie"),
        "video" to setOf("video", "videos", "clip", "clips", "recording of"),
        "recording" to setOf("recording", "recordings", "voice note", "voice memo"),
        "audio" to setOf("song", "songs", "music", "audio", "mp3"),
        "archive" to setOf("zip", "archive"),
        "app" to setOf("apk"),
    )

    private val STOP = setOf("the", "a", "an", "my", "me", "of", "for", "from", "about", "with", "in", "on", "at", "to", "that", "this", "those", "some",
        "file", "files", "find", "search", "look", "where", "is", "are", "i", "it", "and", "or", "which", "had", "have", "got", "was", "made", "saved",
        "downloaded", "sent", "took", "taken", "last", "this", "week", "month", "year", "today", "yesterday", "recent", "latest", "new", "newest", "old", "one")

    /** Everything he can see, newest first (up to [max]). Off the main thread. */
    fun all(context: Context, max: Int = 6000): List<Found> {
        val out = ArrayList<Found>()
        val col = MediaStore.Files.getContentUri("external")
        val cols = arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DISPLAY_NAME, MediaStore.Files.FileColumns.RELATIVE_PATH,
            MediaStore.Files.FileColumns.MIME_TYPE, MediaStore.Files.FileColumns.DATE_MODIFIED, MediaStore.Files.FileColumns.SIZE)
        try {
            context.contentResolver.query(col, cols, "${MediaStore.Files.FileColumns.SIZE} > 0", null, "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC")?.use { c ->
                while (c.moveToNext() && out.size < max) {
                    val name = c.getString(1) ?: continue
                    val folder = c.getString(2).orEmpty().trimEnd('/')
                    if (folder.startsWith("Android/")) continue
                    out += Found(android.content.ContentUris.withAppendedId(col, c.getLong(0)), name, folder, c.getString(3).orEmpty(), c.getLong(4) * 1000, c.getLong(5))
                }
            }
        } catch (e: Exception) {
            // Not allowed to look: nothing found.
        }
        return out
    }

    /** The dates a query talks about ("yesterday", "last week", "in October"), or null for any time. */
    fun dates(q: String, today: LocalDate = LocalDate.now()): ClosedRange<LocalDate>? {
        val t = q.lowercase()
        val months = listOf("january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december")
        return when {
            Regex("\\btoday\\b").containsMatchIn(t) -> today..today
            Regex("\\byesterday\\b").containsMatchIn(t) -> today.minusDays(1)..today.minusDays(1)
            Regex("\\bthis week\\b").containsMatchIn(t) -> Shifts.monday(today)..today
            Regex("\\blast week\\b").containsMatchIn(t) -> Shifts.monday(today).minusWeeks(1)..Shifts.monday(today).minusDays(1)
            Regex("\\bthis month\\b").containsMatchIn(t) -> today.withDayOfMonth(1)..today
            Regex("\\blast month\\b").containsMatchIn(t) -> today.minusMonths(1).let { it.withDayOfMonth(1)..it.withDayOfMonth(it.lengthOfMonth()) }
            // "Recent" narrows it to the last fortnight; "latest" / "last" just puts the newest first.
            Regex("\\b(recent|recently|lately)\\b").containsMatchIn(t) -> today.minusDays(14)..today
            else -> months.indexOfFirst { Regex("\\b$it\\b").containsMatchIn(t) }.takeIf { it >= 0 }?.let { m ->
                var d = today.withMonth(m + 1).withDayOfMonth(1)
                if (d.isAfter(today)) d = d.minusYears(1)
                d..d.withDayOfMonth(d.lengthOfMonth())
            }
        }
    }

    /** The kinds a query asks for ("pdf", "photo"…), empty for any. */
    fun kinds(q: String): Set<String> {
        val t = " ${q.lowercase()} "
        return KIND_WORDS.filter { (_, words) -> words.any { t.contains(" $it ") || t.contains(" $it,") } }.keys
    }

    /** The words that should match a name, folder or contents. */
    fun words(q: String): List<String> = q.lowercase().replace(Regex("[^a-z0-9 ]"), " ").split(Regex("\\s+"))
        .filter { it.length > 1 && it !in STOP && KIND_WORDS.values.none { k -> it in k } && Regex("^(january|february|march|april|may|june|july|august|september|october|november|december)$").matches(it).not() }

    /** The words inside a Word, Excel or text file (the first part of it), for matching. */
    private fun inside(context: Context, f: Found): String = try {
        if (f.size > 8_000_000) "" else when (f.kind) {
            "document" -> if (f.name.endsWith(".docx", true)) context.contentResolver.openInputStream(f.uri)!!.use { DocText.docx(it.readBytes()) } else ""
            "spreadsheet" -> if (f.name.endsWith(".xlsx", true)) context.contentResolver.openInputStream(f.uri)!!.use { DocText.xlsx(it.readBytes()) }
                else if (f.name.endsWith(".csv", true)) context.contentResolver.openInputStream(f.uri)!!.use { it.readBytes().take(60_000).toByteArray().toString(Charsets.UTF_8) } else ""
            "text" -> context.contentResolver.openInputStream(f.uri)!!.use { it.readBytes().take(60_000).toByteArray().toString(Charsets.UTF_8) }
            else -> ""
        }.lowercase().take(60_000)
    } catch (e: Exception) {
        ""
    }

    /**
     * The best matches for [query]: names and folders first, then (for documents) the words inside; kinds and
     * dates narrow it down. Newest first among equals. Off the main thread.
     */
    fun search(context: Context, query: String, max: Int = 8): List<Found> {
        val words = words(query)
        val kinds = kinds(query)
        val range = dates(query)
        // His own memory backups only when you're after them.
        val backups = Regex("\\b(backup|backups|memory)\\b").containsMatchIn(query.lowercase())
        val pool = all(context).filter { f -> (kinds.isEmpty() || f.kind in kinds) && (range == null || f.date in range) && (backups || !f.name.startsWith("Jarvis memory")) }
        if (words.isEmpty()) return pool.take(max)
        fun stem(w: String) = w.removeSuffix("s").removeSuffix("ing").removeSuffix("ed")
        val scored = pool.map { f ->
            val name = f.name.lowercase().replace(Regex("[^a-z0-9]"), " ")
            val folder = f.folder.lowercase()
            var score = 0.0
            for (w in words) {
                val s = stem(w)
                if (Regex("\\b$s").containsMatchIn(name)) score += 3.0
                else if (name.contains(s)) score += 2.0
                if (folder.contains(s)) score += 1.2
            }
            f to score
        }.toMutableList()
        // Not found by name: look inside the documents among the newest few hundred.
        if (scored.none { it.second >= 3.0 }) {
            val docs = scored.filter { it.first.kind in setOf("document", "spreadsheet", "text") }.take(250)
            for (i in scored.indices) {
                val (f, s) = scored[i]
                if (docs.none { it.first === f }) continue
                val text = inside(context, f)
                if (text.isEmpty()) continue
                val hits = words.count { text.contains(stem(it)) }
                if (hits > 0) scored[i] = f to s + hits * 1.5 + (if (hits == words.size) 2.0 else 0.0)
            }
        }
        return scored.filter { it.second > 0 }.sortedWith(compareByDescending<Pair<Found, Double>> { it.second }.thenByDescending { it.first.modified }).map { it.first }.take(max)
    }

    fun open(context: Context, f: Found) {
        val view = android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(f.uri, f.mime.ifBlank { "*/*" })
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(view)
        } catch (e: Exception) {
            share(context, f)
        }
    }

    fun share(context: Context, f: Found) {
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType(f.mime.ifBlank { "*/*" }).putExtra(android.content.Intent.EXTRA_STREAM, f.uri)
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(android.content.Intent.createChooser(send, f.name).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) {
            // Nothing to send it with.
        }
    }
}
