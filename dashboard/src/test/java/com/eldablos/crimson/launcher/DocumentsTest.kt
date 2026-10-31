package com.eldablos.crimson.launcher

import com.eldablos.crimson.launcher.Documents.Block
import com.eldablos.crimson.launcher.JarvisParser.Command
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class DocumentsTest {
    private val brainOutput = """
        # Revision plan for November
        A plan around your shifts.

        ## Week 1
        - **Monday**: maths, 2 hours
        - Tuesday: essays

        ## Budget
        | Item | Cost |
        |---|---|
        | Books | £25.50 |
        | Travel | £40 |

        1. Start early
        2. Rest well
    """.trimIndent()

    @Test
    fun readsWhatTheBrainWrites() {
        val d = Documents.parse(brainOutput, "Fallback")
        assertEquals("Revision plan for November", d.title)
        assertEquals(listOf("Week 1", "Budget"), d.headings)
        assertEquals(Block.Para("A plan around your shifts."), d.blocks[0])
        assertEquals(Block.Bullets(listOf("Monday: maths, 2 hours", "Tuesday: essays")), d.blocks[2])
        val t = d.tables.single()
        assertEquals("Budget", t.title)
        assertEquals(listOf("Item", "Cost"), t.header)
        assertEquals(listOf(listOf("Books", "£25.50"), listOf("Travel", "£40")), t.rows)
        assertEquals(Block.Bullets(listOf("Start early", "Rest well"), numbered = true), d.blocks.last())
        // No title line: the fallback.
        assertEquals("Shifts", Documents.parse("Just a paragraph.", "Shifts").title)
    }

    @Test
    fun numbersExcelCanAdd() {
        assertEquals(25.5, Documents.number("£25.50")!!, 1e-9)
        assertEquals(1234.0, Documents.number("£1,234")!!, 1e-9)
        assertEquals(0.45, Documents.number("45%")!!, 1e-9)
        assertEquals(7.5, Documents.number("7.5")!!, 1e-9)
        assertNull(Documents.number("07:00–15:00"))
        assertNull(Documents.number("31 Oct 2026"))
        assertNull(Documents.number("2 hours"))
    }

    private fun parts(bytes: ByteArray): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                out[e.name] = z.readBytes().toString(Charsets.UTF_8)
            }
        }
        // Every part is well-formed XML, or Word and Excel refuse the file.
        val f = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        out.values.forEach { f.newDocumentBuilder().parse(ByteArrayInputStream(it.toByteArray())) }
        return out
    }

    @Test
    fun wordFiles() {
        val p = parts(Documents.docx(Documents.parse(brainOutput + "\nTom & Jerry <3", "X")))
        assertTrue(p.keys.containsAll(listOf("[Content_Types].xml", "_rels/.rels", "word/document.xml")))
        val doc = p.getValue("word/document.xml")
        assertTrue(doc.contains("Revision plan for November"))
        assertTrue(doc.contains("<w:tbl>"))
        assertTrue(doc.contains("Tom &amp; Jerry &lt;3"))
    }

    @Test
    fun picturesGoInWordFiles() {
        val jpg = java.io.File.createTempFile("pic", ".jpg").apply { writeBytes(byteArrayOf(-1, -40, -1, -32, 1, 2, 3)); deleteOnExit() }
        val doc = Documents.Doc("With a picture", listOf(Block.Para("Here it is."), Block.Image(jpg.path, 1440, 3120, "Your screenshot")))
        val bytes = Documents.docx(doc)
        val names = ArrayList<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z -> while (true) { names += (z.nextEntry ?: break).name } }
        assertTrue(names.containsAll(listOf("word/media/image1.jpg", "word/_rels/document.xml.rels")))
        val p = parts(Documents.docx(doc.copy(blocks = doc.blocks.filterNot { it is Block.Image } + Block.Image(jpg.path, 1440, 3120, null))).let { b ->
            // Only the XML parts are checked for being well-formed.
            val out = java.io.ByteArrayOutputStream()
            java.util.zip.ZipOutputStream(out).use { zo -> ZipInputStream(ByteArrayInputStream(b)).use { z -> while (true) { val e = z.nextEntry ?: break; val d = z.readBytes(); if (!e.name.endsWith(".jpg")) { zo.putNextEntry(java.util.zip.ZipEntry(e.name)); zo.write(d); zo.closeEntry() } } } }
            out.toByteArray()
        })
        assertTrue(p.getValue("word/document.xml").contains("r:embed=\"rIdImg1\""))
        assertTrue(p.getValue("word/_rels/document.xml.rels").contains("media/image1.jpg"))
        // A tall screenshot fits the page: 7.5 inches high at most.
        assertTrue(p.getValue("word/document.xml").contains("cy=\"${(3120 * (7.5 * 914400 / 3120)).toLong()}\""))
    }

    @Test
    fun excelFiles() {
        val p = parts(Documents.xlsx(Documents.parse(brainOutput, "X")))
        assertTrue(p.keys.containsAll(listOf("[Content_Types].xml", "xl/workbook.xml", "xl/styles.xml", "xl/worksheets/sheet1.xml", "xl/worksheets/sheet2.xml")))
        // The writing on a Notes sheet, the table on its own, its money as numbers in £.
        assertTrue(p.getValue("xl/workbook.xml").contains("name=\"Notes\""))
        assertTrue(p.getValue("xl/workbook.xml").contains("name=\"Budget\""))
        val budget = p.getValue("xl/worksheets/sheet2.xml")
        assertTrue(budget.contains("<c r=\"B2\" s=\"3\"><v>25.5</v></c>"))
        assertTrue(budget.contains("<t xml:space=\"preserve\">Books</t>"))
        // Only a table: just that sheet.
        val only = parts(Documents.xlsx(Documents.Doc("Habits", listOf(Block.Table("Habits", listOf("Habit", "Streak"), listOf(listOf("Gym", "4")))))))
        assertTrue(only.containsKey("xl/worksheets/sheet1.xml") && !only.containsKey("xl/worksheets/sheet2.xml"))
    }

    @Test
    fun askingForADocument() {
        val p = { s: String -> JarvisParser.parse(s) }
        assertEquals(Command.MakeDoc("pdf", "my shifts"), p("make me a PDF of my shifts"))
        assertEquals(Command.MakeDoc("excel", "my pay"), p("put my pay in a spreadsheet"))
        assertEquals(Command.MakeDoc("excel", "my shifts and pay"), p("export my shifts and pay to excel"))
        assertEquals(Command.MakeDoc("word", "that"), p("summarise that into a word doc"))
        assertEquals(Command.MakeDoc(null, "study plan for my exams"), p("create a study plan for my exams"))
        assertEquals(Command.MakeDoc("pdf", "revision timetable for november"), p("can you make a revision timetable for november as a pdf"))
        assertEquals(Command.MakeDoc("word", "the history of the london underground"), p("write a document about the history of the London Underground"))
        assertEquals(Command.MakeDoc("pdf", "it"), p("make it a pdf instead"))
        // One request, even with "and" in it: never split into two.
        val both = "create a PDF from my payslip and my work for this weekend last week"
        assertTrue(p(both) is Command.MakeDoc)
        assertNull(JarvisParser.split(both))
        assertEquals(Command.OpenDoc(false), p("open the pdf"))
        assertEquals(Command.OpenDoc(false), p("open my last document"))
        assertEquals(Command.OpenDoc(true), p("my documents"))
        assertEquals(Command.OpenDoc(true), p("where are my files"))
        // Not documents.
        assertTrue(p("change the bed sheets") !is Command.MakeDoc)
        assertTrue(p("when's my next shift") !is Command.MakeDoc)
    }
}
