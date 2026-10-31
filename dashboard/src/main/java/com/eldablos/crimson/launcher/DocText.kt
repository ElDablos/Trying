package com.eldablos.crimson.launcher

import android.content.Context
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Reading a file you hand Jarvis (shared to him, or attached): Word and Excel files and plain text are read
 * as text; a PDF's first pages are turned into pictures for his eyes (Gemma) to read, since a phone can show
 * a PDF but not pull its words out.
 */
object DocText {
    class Picked(val file: File, val name: String, val mime: String) {
        val isPdf get() = mime == "application/pdf" || name.endsWith(".pdf", true)
        val isImage get() = mime.startsWith("image/")
    }

    /** A copy of the file at [uri] in his cache, with its name and type. */
    fun copy(context: Context, uri: Uri): Picked? = try {
        val cr = context.contentResolver
        // A copy of his own (shared to him): its name without his prefix.
        val name = (if (uri.scheme == "file") null else cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null })
            ?: uri.lastPathSegment?.substringAfterLast('/')?.replace(Regex("^jarvis-file-\\d+-"), "") ?: "file"
        val mime = cr.getType(uri) ?: when (name.substringAfterLast('.', "").lowercase()) {
            "pdf" -> "application/pdf"
            "docx" -> Documents.Kind.WORD.mime
            "xlsx" -> Documents.Kind.EXCEL.mime
            "jpg", "jpeg", "png", "webp" -> "image/*"
            else -> "text/plain"
        }
        val out = File(context.cacheDir, "jarvis-file-${System.currentTimeMillis()}-${name.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(60)}")
        cr.openInputStream(uri)!!.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
        Picked(out, name, mime)
    } catch (e: Exception) {
        null
    }

    /** The words in a Word file, paragraph by paragraph. */
    fun docx(bytes: ByteArray): String {
        val xml = entry(bytes, "word/document.xml") ?: return ""
        return unescape(xml.replace(Regex("</w:p>"), "\n").replace(Regex("<w:tab/>"), "\t").replace(Regex("<w:br/>"), "\n").replace(Regex("<[^>]+>"), ""))
            .lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }

    /** An Excel file as rows of cells ("a | b | c"), sheet by sheet. */
    fun xlsx(bytes: ByteArray): String {
        val shared = entry(bytes, "xl/sharedStrings.xml")?.let { s ->
            Regex("<si>(.*?)</si>", RegexOption.DOT_MATCHES_ALL).findAll(s).map { si ->
                unescape(Regex("<t[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL).findAll(si.groupValues[1]).joinToString("") { it.groupValues[1] })
            }.toList()
        }.orEmpty()
        val out = StringBuilder()
        entries(bytes).filter { it.first.startsWith("xl/worksheets/sheet") }.sortedBy { it.first }.forEach { (_, xml) ->
            Regex("<row[^>]*>(.*?)</row>", RegexOption.DOT_MATCHES_ALL).findAll(xml).forEach { row ->
                val cells = Regex("<c ([^>]*?)(?:/>|>(.*?)</c>)", RegexOption.DOT_MATCHES_ALL).findAll(row.groupValues[1]).map { c ->
                    val attrs = c.groupValues[1]
                    val body = c.groupValues[2]
                    val v = Regex("<v>(.*?)</v>").find(body)?.groupValues?.get(1)
                    when {
                        attrs.contains("t=\"s\"") -> v?.toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
                        attrs.contains("t=\"inlineStr\"") -> unescape(Regex("<t[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1).orEmpty())
                        else -> v.orEmpty()
                    }
                }.toList()
                if (cells.any { it.isNotBlank() }) out.append(cells.joinToString(" | ")).append('\n')
            }
            out.append('\n')
        }
        return out.toString().trim()
    }

    /** Text from a Word, Excel or text file; null for a PDF or picture (those are read by his eyes). */
    fun text(p: Picked): String? {
        if (p.isPdf || p.isImage) return null
        val bytes = p.file.readBytes()
        return when {
            p.name.endsWith(".docx", true) || p.mime == Documents.Kind.WORD.mime -> docx(bytes)
            p.name.endsWith(".xlsx", true) || p.mime == Documents.Kind.EXCEL.mime -> xlsx(bytes)
            else -> bytes.toString(Charsets.UTF_8).take(200_000)
        }
    }

    /** A PDF's first [max] pages as pictures, for his eyes. */
    fun pdfPages(context: Context, p: Picked, max: Int = 3): List<File> = try {
        android.os.ParcelFileDescriptor.open(p.file, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            android.graphics.pdf.PdfRenderer(fd).use { r ->
                (0 until minOf(max, r.pageCount)).map { i ->
                    r.openPage(i).use { page ->
                        // About 150 dpi for an A4 page: sharp enough to read, small enough for the brain.
                        val scale = 1400f / maxOf(page.width, page.height)
                        val bmp = android.graphics.Bitmap.createBitmap((page.width * scale).toInt(), (page.height * scale).toInt(), android.graphics.Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(android.graphics.Color.WHITE)
                        page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        File(context.cacheDir, "jarvis-page-$i-${System.currentTimeMillis()}.jpg").also { f ->
                            f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
                            bmp.recycle()
                        }
                    }
                }
            }
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun pageCount(p: Picked): Int = try {
        android.os.ParcelFileDescriptor.open(p.file, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> android.graphics.pdf.PdfRenderer(fd).use { it.pageCount } }
    } catch (e: Exception) {
        0
    }

    private fun entries(bytes: ByteArray): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.name.endsWith(".xml")) out += e.name to z.readBytes().toString(Charsets.UTF_8)
            }
        }
        return out
    }

    private fun entry(bytes: ByteArray, name: String) = runCatching { entries(bytes).firstOrNull { it.first == name }?.second }.getOrNull()

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
}
