package com.eldablos.crimson.launcher

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Jarvis's documents: Word (.docx), Excel (.xlsx) and PDF, made on the phone with nothing extra installed.
 * Content comes as a [Doc] (headings, paragraphs, bullet lists, tables), either built from your own data
 * (shifts, pay, notes, habits) or written by his brain in a simple plain-text form that [parse] reads.
 * Saved to Downloads/Jarvis, and opened or shared from there.
 */
object Documents {
    enum class Kind(val ext: String, val mime: String, val label: String) {
        WORD("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "WORD"),
        EXCEL("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "EXCEL"),
        PDF("pdf", "application/pdf", "PDF"),
    }

    sealed class Block {
        data class Heading(val text: String, val level: Int = 1) : Block()
        data class Para(val text: String) : Block()
        data class Bullets(val items: List<String>, val numbered: Boolean = false) : Block()
        data class Table(val title: String?, val header: List<String>, val rows: List<List<String>>) : Block()
        /** A picture (a JPEG on the phone, [w]×[h] pixels), shown in PDFs and Word files. */
        data class Image(val path: String, val w: Int, val h: Int, val caption: String?) : Block()
    }

    data class Doc(val title: String, val blocks: List<Block>) {
        val tables: List<Block.Table> get() = blocks.filterIsInstance<Block.Table>()
        val images: List<Block.Image> get() = blocks.filterIsInstance<Block.Image>()
        val headings: List<String> get() = blocks.filterIsInstance<Block.Heading>().map { it.text }
        val words: Int get() = blocks.sumOf { b ->
            when (b) {
                is Block.Heading -> b.text.split(' ').size
                is Block.Para -> b.text.split(' ').size
                is Block.Bullets -> b.items.sumOf { it.split(' ').size }
                is Block.Table -> (b.rows.flatten() + b.header).sumOf { it.split(' ').size }
                is Block.Image -> 0
            }
        }
    }

    // --- Reading what his brain wrote ---

    /**
     * The brain's plain-text document: "# Title", "## Heading" (and "###"), "- " or "1. " for lists, rows of
     * cells split by "|" for tables (the first row names the columns), anything else a paragraph. Stray
     * markdown (bold stars, backticks, table rule lines) is cleaned off.
     */
    fun parse(text: String, fallbackTitle: String): Doc {
        var title: String? = null
        val blocks = ArrayList<Block>()
        val para = StringBuilder()
        var bullets: MutableList<String>? = null
        var numbered = false
        var table: MutableList<List<String>>? = null
        var tableTitle: String? = null
        var lastHeading: String? = null

        fun clean(s: String) = s.replace(Regex("\\*\\*|__|`"), "").replace(Regex("(?<![\\w*])\\*(?!\\s)([^*]+)\\*"), "$1").trim()
        fun flushPara() {
            if (para.isNotBlank()) blocks += Block.Para(clean(para.toString()))
            para.clear()
        }
        fun flushBullets() {
            bullets?.takeIf { it.isNotEmpty() }?.let { blocks += Block.Bullets(it.toList(), numbered) }
            bullets = null
        }
        fun flushTable() {
            val t = table ?: return
            table = null
            if (t.size >= 1) {
                val width = t.maxOf { it.size }
                val pad = t.map { r -> r + List(width - r.size) { "" } }
                blocks += Block.Table(tableTitle, pad.first(), pad.drop(1))
            }
            tableTitle = null
        }
        fun flushAll() { flushPara(); flushBullets(); flushTable() }

        for (raw in text.lines()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> { flushPara(); flushBullets() }
                line.startsWith("```") -> {}
                // A picture kept from before ("[picture: path|w|h|caption]").
                line.startsWith("[picture: ") && line.endsWith("]") -> {
                    flushAll()
                    val f = line.removePrefix("[picture: ").removeSuffix("]").split('|')
                    if (f.size >= 3) blocks += Block.Image(f[0], f[1].toIntOrNull() ?: 0, f[2].toIntOrNull() ?: 0, f.getOrNull(3)?.ifBlank { null })
                }
                // A table: "a | b | c", or "| a | b |"; rule lines ("|---|---|") skipped.
                line.count { it == '|' } >= 1 && line.split('|').count { it.isNotBlank() } >= 2 -> {
                    flushPara(); flushBullets()
                    if (Regex("^[|:\\-\\s]+$").matches(line)) continue
                    if (table == null) { table = ArrayList(); tableTitle = lastHeading }
                    table!! += line.trim('|').split('|').map { clean(it) }
                }
                line.startsWith("#") -> {
                    flushAll()
                    val level = line.takeWhile { it == '#' }.length
                    val h = clean(line.trimStart('#'))
                    if (h.isEmpty()) continue
                    if (level == 1 && title == null && blocks.isEmpty()) title = h
                    else { blocks += Block.Heading(h, (level - 1).coerceIn(1, 3)); lastHeading = h }
                }
                Regex("^([-*•]|\\d{1,2}[.)])\\s+").containsMatchIn(line) -> {
                    flushPara(); flushTable()
                    val isNum = Regex("^\\d{1,2}[.)]").containsMatchIn(line)
                    if (bullets == null) { bullets = ArrayList(); numbered = isNum }
                    bullets!! += clean(line.replace(Regex("^([-*•]|\\d{1,2}[.)])\\s+"), ""))
                }
                // "Title: …" on the first line, the way some brains start.
                title == null && blocks.isEmpty() && para.isEmpty() && Regex("^title:\\s*", RegexOption.IGNORE_CASE).containsMatchIn(line) ->
                    title = clean(line.substringAfter(':'))
                else -> {
                    flushBullets(); flushTable()
                    if (para.isNotEmpty()) para.append(' ')
                    para.append(line)
                }
            }
        }
        flushAll()
        return Doc((title ?: fallbackTitle).take(90), blocks)
    }

    /** A document back in the plain form [parse] reads: for his brain to edit, and to keep between visits. */
    fun markup(doc: Doc): String = buildString {
        append("# ${doc.title}\n\n")
        for (b in doc.blocks) {
            when (b) {
                is Block.Heading -> append("${"#".repeat(b.level + 1)} ${b.text}\n")
                is Block.Para -> append("${b.text}\n")
                is Block.Bullets -> b.items.forEachIndexed { i, it -> append(if (b.numbered) "${i + 1}. $it\n" else "- $it\n") }
                is Block.Table -> (listOf(b.header) + b.rows).forEach { append(it.joinToString(" | ")).append('\n') }
                is Block.Image -> append("[picture: ${b.path}|${b.w}|${b.h}|${b.caption ?: ""}]\n")
            }
            append('\n')
        }
    }

    // --- Word ---

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        .replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]"), "")

    fun docx(doc: Doc): ByteArray {
        val body = StringBuilder()
        val media = ArrayList<Pair<String, ByteArray>>()
        fun run(text: String, bold: Boolean = false, size: Int? = null, color: String? = null) =
            "<w:r><w:rPr>${if (bold) "<w:b/>" else ""}${color?.let { "<w:color w:val=\"$it\"/>" } ?: ""}${size?.let { "<w:sz w:val=\"$it\"/>" } ?: ""}</w:rPr>" +
                "<w:t xml:space=\"preserve\">${esc(text)}</w:t></w:r>"
        fun p(inner: String, after: Int = 120, indent: Int? = null, keep: Boolean = false) =
            "<w:p><w:pPr>${if (keep) "<w:keepNext/>" else ""}<w:spacing w:after=\"$after\"/>${indent?.let { "<w:ind w:left=\"$it\" w:hanging=\"280\"/>" } ?: ""}</w:pPr>$inner</w:p>"

        body.append(p(run(doc.title, bold = true, size = 44, color = "B3122E"), after = 60))
        body.append(p(run("Made by Jarvis · ${stamp()}", size = 18, color = "777777"), after = 240))
        for (b in doc.blocks) when (b) {
            is Block.Heading -> body.append(p(run(b.text, bold = true, size = if (b.level == 1) 30 else 26, color = if (b.level == 1) "B3122E" else "333333"), after = 80, keep = true))
            is Block.Para -> body.append(p(run(b.text, size = 22)))
            is Block.Bullets -> b.items.forEachIndexed { i, item ->
                body.append(p(run(if (b.numbered) "${i + 1}.  " else "•  ", bold = b.numbered, size = 22) + run(item, size = 22), after = 60, indent = 560))
            }
            is Block.Image -> {
                val bytes = runCatching { java.io.File(b.path).readBytes() }.getOrNull()
                if (bytes != null && b.w > 0 && b.h > 0) {
                    media += "image${media.size + 1}.jpg" to bytes
                    val n = media.size
                    // Fits the page: at most 6.3 inches wide and 7.5 tall (a phone screenshot is tall).
                    val scale = minOf(6.3 * 914400 / b.w, 7.5 * 914400 / b.h)
                    val cx = (b.w * scale).toLong()
                    val cy = (b.h * scale).toLong()
                    body.append("<w:p><w:pPr><w:jc w:val=\"center\"/><w:spacing w:after=\"80\"/></w:pPr><w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">" +
                        "<wp:extent cx=\"$cx\" cy=\"$cy\"/><wp:docPr id=\"$n\" name=\"Picture $n\"/>" +
                        "<a:graphic xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
                        "<pic:pic xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:nvPicPr><pic:cNvPr id=\"$n\" name=\"image$n.jpg\"/><pic:cNvPicPr/></pic:nvPicPr>" +
                        "<pic:blipFill><a:blip r:embed=\"rIdImg$n\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>" +
                        "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic>" +
                        "</a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>")
                    b.caption?.let { body.append(p(run(it, size = 18, color = "777777"), after = 200)) }
                }
            }
            is Block.Table -> {
                val cols = b.header.size.coerceAtLeast(1)
                val w = 9000 / cols
                body.append("<w:tbl><w:tblPr><w:tblW w:w=\"9000\" w:type=\"dxa\"/><w:tblBorders>")
                listOf("top", "left", "bottom", "right", "insideH", "insideV").forEach { body.append("<w:$it w:val=\"single\" w:sz=\"4\" w:color=\"BBBBBB\"/>") }
                body.append("</w:tblBorders></w:tblPr><w:tblGrid>")
                repeat(cols) { body.append("<w:gridCol w:w=\"$w\"/>") }
                body.append("</w:tblGrid>")
                (listOf(b.header) + b.rows).forEachIndexed { r, row ->
                    body.append("<w:tr>")
                    row.take(cols).forEach { cell ->
                        body.append("<w:tc><w:tcPr><w:tcW w:w=\"$w\" w:type=\"dxa\"/>${if (r == 0) "<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"F3E1E4\"/>" else ""}</w:tcPr>")
                        body.append("<w:p><w:pPr><w:spacing w:after=\"0\"/></w:pPr>${run(cell, bold = r == 0, size = 20)}</w:p></w:tc>")
                    }
                    body.append("</w:tr>")
                }
                body.append("</w:tbl>")
                body.append(p("", after = 120))
            }
        }
        val document = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" " +
            "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" " +
            "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\"><w:body>$body" +
            "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\" w:header=\"567\" w:footer=\"567\" w:gutter=\"0\"/></w:sectPr>" +
            "</w:body></w:document>"
        val docRels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            media.indices.joinToString("") { "<Relationship Id=\"rIdImg${it + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/image${it + 1}.jpg\"/>" } +
            "</Relationships>"
        return zipBytes(listOf(
            "[Content_Types].xml" to ("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                "<Default Extension=\"jpg\" ContentType=\"image/jpeg\"/>" +
                "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
                "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/></Types>").toByteArray(),
            "_rels/.rels" to rels("officeDocument/2006/relationships/officeDocument" to "word/document.xml").toByteArray(),
            "docProps/core.xml" to core(doc.title).toByteArray(),
            "word/document.xml" to document.toByteArray(),
            "word/_rels/document.xml.rels" to docRels.toByteArray(),
        ) + media.map { (name, bytes) -> "word/media/$name" to bytes })
    }

    // --- Excel ---

    /** A number Excel can add up: "12", "-3.5", "£1,234.56", "45%" (as 0.45). Null for anything else. */
    fun number(cell: String): Double? {
        val t = cell.trim()
        if (!Regex("^-?£?-?\\d{1,3}(,\\d{3})*(\\.\\d+)?%?$|^-?£?-?\\d+(\\.\\d+)?%?$").matches(t)) return null
        val v = t.replace("£", "").replace(",", "").removeSuffix("%").toDoubleOrNull() ?: return null
        return if (t.endsWith("%")) v / 100 else v
    }

    fun xlsx(doc: Doc): ByteArray {
        // One sheet per table; anything else (headings, paragraphs, lists) on a "Notes" sheet at the front.
        class Sheet(val name: String, val rows: List<List<String>>, val headerRow: Int?, val boldRows: Set<Int>)
        val sheets = ArrayList<Sheet>()
        val text = ArrayList<List<String>>()
        val bold = HashSet<Int>()
        text += listOf(doc.title); bold += 0
        text += listOf("Made by Jarvis · ${stamp()}")
        text += listOf("")
        for (b in doc.blocks) when (b) {
            is Block.Heading -> { bold += text.size; text += listOf(b.text) }
            is Block.Para -> text += listOf(b.text)
            is Block.Bullets -> b.items.forEachIndexed { i, it -> text += listOf(if (b.numbered) "${i + 1}. $it" else "• $it") }
            is Block.Image -> text += listOf("[Picture${b.caption?.let { ": $it" } ?: ""} (in the PDF or Word version)]")
            is Block.Table -> {}
        }
        val tables = doc.tables
        if (tables.isEmpty() || doc.blocks.any { it is Block.Para || it is Block.Bullets || it is Block.Image }) sheets += Sheet(if (tables.isEmpty()) "Summary" else "Notes", text, null, bold)
        val used = HashSet<String>()
        tables.forEachIndexed { i, t ->
            var name = (t.title ?: if (tables.size == 1) doc.title else "Table ${i + 1}").replace(Regex("[\\[\\]:*?/\\\\]"), " ").trim().take(28).ifBlank { "Table ${i + 1}" }
            while (!used.add(name.lowercase())) name = name.take(25) + " ${i + 1}"
            sheets += Sheet(name, listOf(t.header) + t.rows, 0, emptySet())
        }

        fun col(i: Int): String { var n = i + 1; val sb = StringBuilder(); while (n > 0) { val r = (n - 1) % 26; sb.insert(0, 'A' + r); n = (n - 1) / 26 }; return sb.toString() }
        fun sheetXml(s: Sheet): String {
            val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
            if (s.headerRow != null) sb.append("<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>")
            val widths = (0 until (s.rows.maxOfOrNull { it.size } ?: 1)).map { c -> s.rows.maxOf { r -> r.getOrNull(c)?.length ?: 0 } }
            sb.append("<cols>")
            widths.forEachIndexed { c, w -> sb.append("<col min=\"${c + 1}\" max=\"${c + 1}\" width=\"${(w + 3).coerceIn(8, if (s.headerRow == null) 110 else 48)}\" customWidth=\"1\"/>") }
            sb.append("</cols><sheetData>")
            s.rows.forEachIndexed { r, row ->
                sb.append("<row r=\"${r + 1}\">")
                row.forEachIndexed { c, cell ->
                    val ref = "${col(c)}${r + 1}"
                    val style = when {
                        r == s.headerRow -> 1
                        r in s.boldRows -> if (r == 0) 2 else 1
                        cell.trim().endsWith("%") && number(cell) != null -> 4
                        cell.trim().startsWith("£") && number(cell) != null -> 3
                        else -> 0
                    }
                    val n = if (r == s.headerRow) null else number(cell)
                    if (n != null) sb.append("<c r=\"$ref\" s=\"$style\"><v>$n</v></c>")
                    else if (cell.isNotEmpty()) sb.append("<c r=\"$ref\" s=\"$style\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${esc(cell)}</t></is></c>")
                }
                sb.append("</row>")
            }
            sb.append("</sheetData>")
            if (s.headerRow != null && s.rows.size > 1) sb.append("<autoFilter ref=\"A1:${col((widths.size - 1).coerceAtLeast(0))}${s.rows.size}\"/>")
            sb.append("</worksheet>")
            return sb.toString()
        }

        val styles = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
            "<numFmts count=\"1\"><numFmt numFmtId=\"164\" formatCode=\"&quot;£&quot;#,##0.00\"/></numFmts>" +
            "<fonts count=\"3\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"16\"/><color rgb=\"FFB3122E\"/><name val=\"Calibri\"/></font></fonts>" +
            "<fills count=\"3\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill><fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFF3E1E4\"/><bgColor indexed=\"64\"/></patternFill></fill></fills>" +
            "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>" +
            "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
            "<cellXfs count=\"5\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"><alignment wrapText=\"1\" vertical=\"top\"/></xf>" +
            "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\"/>" +
            "<xf numFmtId=\"0\" fontId=\"2\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>" +
            "<xf numFmtId=\"164\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>" +
            "<xf numFmtId=\"10\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/></cellXfs>" +
            "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>"
        val workbook = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" " +
            "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>" +
            sheets.mapIndexed { i, s -> "<sheet name=\"${esc(s.name)}\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"/>" }.joinToString("") + "</sheets></workbook>"
        val wbRels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            sheets.indices.joinToString("") { "<Relationship Id=\"rId${it + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet${it + 1}.xml\"/>" } +
            "<Relationship Id=\"rId${sheets.size + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>"
        val types = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
            "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>" +
            "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>" +
            sheets.indices.joinToString("") { "<Override PartName=\"/xl/worksheets/sheet${it + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" } +
            "</Types>"
        return zip(
            *(listOf(
                "[Content_Types].xml" to types,
                "_rels/.rels" to rels("officeDocument/2006/relationships/officeDocument" to "xl/workbook.xml"),
                "docProps/core.xml" to core(doc.title),
                "xl/workbook.xml" to workbook,
                "xl/_rels/workbook.xml.rels" to wbRels,
                "xl/styles.xml" to styles,
            ) + sheets.mapIndexed { i, s -> "xl/worksheets/sheet${i + 1}.xml" to sheetXml(s) }).toTypedArray(),
        )
    }

    private fun rels(vararg r: Pair<String, String>) = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        r.mapIndexed { i, (type, target) -> "<Relationship Id=\"rId${i + 1}\" Type=\"http://schemas.openxmlformats.org/$type\" Target=\"$target\"/>" }.joinToString("") +
        "<Relationship Id=\"rId${r.size + 1}\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/></Relationships>"

    private fun core(title: String) = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" " +
        "xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:title>${esc(title)}</dc:title><dc:creator>Jarvis</dc:creator></cp:coreProperties>"

    private fun zip(vararg parts: Pair<String, String>): ByteArray = zipBytes(parts.map { (n, c) -> n to c.toByteArray(Charsets.UTF_8) })

    private fun zipBytes(parts: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, content) in parts) {
                z.putNextEntry(ZipEntry(name))
                z.write(content)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun stamp() = LocalDateTime.now().format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm"))

    // --- PDF ---

    /** A4, set like the launcher: a crimson title, clear headings, wrapped text, ruled tables, page numbers. */
    fun pdf(doc: Doc): ByteArray {
        val pdf = android.graphics.pdf.PdfDocument()
        val w = 595
        val h = 842
        val margin = 48f
        val width = (w - 2 * margin).toInt()
        val crimson = 0xFFB3122E.toInt()
        fun paint(size: Float, bold: Boolean = false, color: Int = 0xFF222222.toInt()) = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = if (bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
        }
        fun layout(text: String, p: android.text.TextPaint, wd: Int) =
            android.text.StaticLayout.Builder.obtain(text, 0, text.length, p, wd.coerceAtLeast(20)).setLineSpacing(2f, 1f).build()

        var pageNo = 0
        var page: android.graphics.pdf.PdfDocument.Page? = null
        var y = 0f
        val footer = paint(8f, color = 0xFF888888.toInt())
        fun finishPage() {
            page?.let { pg ->
                pg.canvas.drawText("Made by Jarvis · ${stamp()}", margin, h - 24f, footer)
                val n = "Page $pageNo"
                pg.canvas.drawText(n, w - margin - footer.measureText(n), h - 24f, footer)
                pdf.finishPage(pg)
            }
            page = null
        }
        fun newPage() {
            finishPage()
            pageNo++
            page = pdf.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(w, h, pageNo).create())
            y = margin
        }
        fun room(need: Float) { if (page == null || y + need > h - margin - 16) newPage() }
        fun draw(l: android.text.StaticLayout, x: Float = margin, gap: Float = 6f) {
            val bottom = h - margin - 16
            if (y + l.height <= bottom || l.lineCount <= 1) {
                room(l.height.toFloat())
                val c = page!!.canvas
                c.save(); c.translate(x, y); l.draw(c); c.restore()
                y += l.height + gap
                return
            }
            // Taller than what's left: line by line, carrying on over the page.
            for (line in 0 until l.lineCount) {
                val top = l.getLineTop(line)
                val lh = (l.getLineBottom(line) - top).toFloat()
                if (y + lh > bottom) newPage()
                val c = page!!.canvas
                c.save()
                c.translate(x, y - top)
                c.clipRect(0f, top.toFloat(), l.width.toFloat(), top + lh)
                l.draw(c)
                c.restore()
                y += lh
            }
            y += gap
        }

        newPage()
        draw(layout(doc.title, paint(22f, true, crimson), width), gap = 4f)
        page!!.canvas.drawRect(margin, y, margin + 60f, y + 2.5f, android.graphics.Paint().apply { color = crimson })
        y += 14f
        val body = paint(10.5f)
        for (b in doc.blocks) when (b) {
            is Block.Heading -> {
                val l = layout(b.text, paint(if (b.level == 1) 14.5f else 12f, true, if (b.level == 1) crimson else 0xFF333333.toInt()), width)
                room(l.height + 40f)
                y += 6f
                draw(l, gap = 4f)
            }
            is Block.Para -> draw(layout(b.text, body, width), gap = 8f)
            is Block.Bullets -> {
                b.items.forEachIndexed { i, item ->
                    val mark = if (b.numbered) "${i + 1}." else "•"
                    val l = layout(item, body, width - 18)
                    room(l.height.toFloat())
                    page!!.canvas.drawText(mark, margin + 2f, y + body.textSize, paint(10.5f, b.numbered, crimson))
                    draw(l, margin + 18f, gap = 3f)
                }
                y += 5f
            }
            is Block.Image -> {
                val bmp = runCatching { android.graphics.BitmapFactory.decodeFile(b.path) }.getOrNull()
                if (bmp != null) {
                    // Fits the width and at most two thirds of a page (a phone screenshot is tall).
                    val scale = minOf(width.toFloat() / bmp.width, (h - 2 * margin) * .66f / bmp.height)
                    val iw = bmp.width * scale
                    val ih = bmp.height * scale
                    room(ih + 24f)
                    val x = margin + (width - iw) / 2
                    page!!.canvas.drawBitmap(bmp, null, android.graphics.RectF(x, y, x + iw, y + ih), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
                    page!!.canvas.drawRect(x, y, x + iw, y + ih, android.graphics.Paint().apply { style = android.graphics.Paint.Style.STROKE; color = 0xFFCCCCCC.toInt(); strokeWidth = .6f })
                    y += ih + 6f
                    b.caption?.let { draw(layout(it, paint(9f, color = 0xFF777777.toInt()), width), gap = 10f) } ?: run { y += 8f }
                    bmp.recycle()
                }
            }
            is Block.Table -> {
                val cols = b.header.size.coerceAtLeast(1)
                // Columns sized to their content, within reason.
                val want = (0 until cols).map { c -> (listOf(b.header) + b.rows).maxOf { it.getOrNull(c)?.length ?: 0 }.coerceIn(4, 40).toFloat() }
                val colW = want.map { it / want.sum() * width }
                val small = paint(9f)
                val head = paint(9f, true)
                val line = android.graphics.Paint().apply { color = 0xFFBBBBBB.toInt(); strokeWidth = 0.6f }
                val shade = android.graphics.Paint().apply { color = 0xFFF3E1E4.toInt() }
                fun row(cells: List<String>, header: Boolean) {
                    val ls = cells.take(cols).mapIndexed { c, t -> layout(t, if (header) head else small, (colW[c] - 8).toInt()) }
                    val rh = (ls.maxOfOrNull { it.height } ?: 10) + 8f
                    room(rh)
                    val cv = page!!.canvas
                    if (header) cv.drawRect(margin, y, margin + width, y + rh, shade)
                    var x = margin
                    ls.forEachIndexed { c, l ->
                        cv.save(); cv.translate(x + 4f, y + 4f); l.draw(cv); cv.restore()
                        x += colW[c]
                    }
                    cv.drawLine(margin, y + rh, margin + width, y + rh, line)
                    y += rh
                }
                y += 4f
                row(b.header, true)
                b.rows.forEach { r ->
                    // A table that runs onto a new page gets its header again.
                    if (y + 30f > h - margin - 16) { newPage(); row(b.header, true) }
                    row(r, false)
                }
                y += 12f
            }
        }
        finishPage()
        val out = ByteArrayOutputStream()
        pdf.writeTo(out)
        pdf.close()
        return out.toByteArray()
    }

    /** A picture ready to go in a document: a JPEG copy, at most 1600 pixels on its longest side. Null if unreadable. */
    fun picture(context: Context, file: java.io.File, caption: String?): Block.Image? = try {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.path, o)
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= 1600) sample *= 2
        val bmp = android.graphics.BitmapFactory.decodeFile(file.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })!!
        val out = java.io.File(context.cacheDir, "doc-picture-${System.currentTimeMillis()}.jpg")
        out.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, it) }
        Block.Image(out.path, bmp.width, bmp.height, caption).also { bmp.recycle() }
    } catch (e: Throwable) {
        null
    }

    // --- Saving, opening, sharing ---

    fun bytes(doc: Doc, kind: Kind): ByteArray = when (kind) {
        Kind.WORD -> docx(doc)
        Kind.EXCEL -> xlsx(doc)
        Kind.PDF -> pdf(doc)
    }

    /** A file name from the title: "Study plan for exams 31 Oct.pdf". */
    fun fileName(doc: Doc, kind: Kind): String {
        val base = doc.title.replace(Regex("[\\\\/:*?\"<>|]"), " ").replace(Regex("\\s+"), " ").trim().take(60).ifBlank { "Jarvis" }
        return "$base ${LocalDateTime.now().format(DateTimeFormatter.ofPattern("d MMM HHmm"))}.${kind.ext}"
    }

    /** Saved in Downloads/Jarvis (where Files and other apps can see it); its address, or null if it couldn't be. */
    fun save(context: Context, doc: Doc, kind: Kind): Uri? = try {
        val data = bytes(doc, kind)
        val values = ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName(doc, kind))
            put(android.provider.MediaStore.Downloads.MIME_TYPE, kind.mime)
            put(android.provider.MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/Jarvis")
            put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
        }
        val cr = context.contentResolver
        val uri = cr.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("no place")
        cr.openOutputStream(uri)!!.use { it.write(data) }
        cr.update(uri, ContentValues().apply { put(android.provider.MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        uri
    } catch (e: Exception) {
        null
    }

    class Saved(val uri: Uri, val name: String, val kind: Kind, val at: Long)

    /** The documents he's made, newest first (from Downloads/Jarvis). */
    fun recent(context: Context, max: Int = 6): List<Saved> = try {
        val out = ArrayList<Saved>()
        val col = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
        context.contentResolver.query(col, arrayOf(android.provider.MediaStore.Downloads._ID, android.provider.MediaStore.Downloads.DISPLAY_NAME, android.provider.MediaStore.Downloads.DATE_ADDED),
            "${android.provider.MediaStore.Downloads.RELATIVE_PATH} LIKE ?", arrayOf("%Jarvis%"), "${android.provider.MediaStore.Downloads.DATE_ADDED} DESC")?.use { c ->
            while (c.moveToNext() && out.size < max) {
                val name = c.getString(1) ?: continue
                val kind = Kind.values().firstOrNull { name.endsWith(".${it.ext}", true) } ?: continue
                out += Saved(android.content.ContentUris.withAppendedId(col, c.getLong(0)), name, kind, c.getLong(2) * 1000)
            }
        }
        out
    } catch (e: Exception) {
        emptyList()
    }

    fun open(context: Context, uri: Uri, kind: Kind) {
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, kind.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(view)
        } catch (e: Exception) {
            share(context, uri, kind, "Open with")
        }
    }

    fun share(context: Context, uri: Uri, kind: Kind, title: String = "Send it with") {
        val send = Intent(Intent.ACTION_SEND).setType(kind.mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) {
            // Nothing to send it with.
        }
    }
}
