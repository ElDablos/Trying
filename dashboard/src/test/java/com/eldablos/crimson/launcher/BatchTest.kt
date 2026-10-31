package com.eldablos.crimson.launcher

import com.eldablos.crimson.launcher.JarvisParser.Command
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class BatchTest {
    private val p = { s: String -> JarvisParser.parse(s) }
    private val today = LocalDate.of(2026, 10, 31) // a Saturday

    @Test
    fun newCommands() {
        assertEquals(Command.SystemsCheck, p("run a check"))
        assertEquals(Command.SystemsCheck, p("Jarvis, are you ok"))
        assertEquals(Command.SystemsCheck, p("run diagnostics"))
        assertEquals(Command.Backup, p("back up your memory"))
        assertEquals(Command.Backup, p("make a backup"))
        assertEquals(Command.Restore, p("restore your memory"))
        assertEquals(Command.CheckPayslip, p("check my payslip"))
        assertEquals(Command.CheckPayslip, p("is my payslip right"))
        assertEquals(Command.MorningBrief, p("morning brief"))
        assertEquals(Command.StudyDone, p("I've finished studying"))
        assertEquals(Command.StudyDone, p("done with revision"))
        assertEquals(Command.StudyProgress(false), p("how's my study going"))
        assertEquals(Command.StudyProgress(true), p("study progress report"))
        assertEquals(Command.StudyNext, p("when's my next study session"))
        assertEquals(Command.StudyClear, p("clear my study plan"))
    }

    @Test
    fun spending() {
        assertEquals(Command.Spent(12.0, "lunch"), p("I spent £12 on lunch"))
        assertEquals(Command.Spent(4.5, "coffee"), p("spent 4.50 on coffee"))
        assertEquals(Command.Spent(30.0, "a haircut"), p("I paid 30 quid for a haircut"))
        assertEquals(Command.Spent(8.0, "a book"), p("I bought a book for £8"))
        assertEquals(Command.Spent(0.5, "a sweet"), p("spent 50p on a sweet"))
        assertEquals(Command.Spending("week"), p("how much have I spent this week"))
        assertEquals(Command.Spending("month"), p("how much have I spent this month"))
        assertEquals(Command.Spending("today"), p("what have I spent today"))
        assertEquals("Food & drink", Spending.categoryOf("lunch at Pret"))
        assertEquals("Travel", Spending.categoryOf("an uber home"))
        assertEquals("Other", Spending.categoryOf("a thing"))
        assertEquals(12.5, Spending.amountIn("12 pounds 50p")!!, 1e-9)
        assertNull(Spending.amountIn("nothing"))
        // Not spending: pay questions stay what they were.
        assertTrue(p("what's my pay") !is Command.Spent)
    }

    @Test
    fun documentsOfMoreKinds() {
        assertEquals(Command.MakeDoc(null, "flashcards from it"), p("make flashcards from it"))
        assertEquals(Command.MakeDoc("pdf", "cheat sheet for statistics").kind, (p("make me a cheat sheet for statistics") as Command.MakeDoc).kind)
        assertTrue(p("make me some flashcards on the french revolution") is Command.MakeDoc)
    }

    @Test
    fun studySessionsFromAPlan() {
        val doc = Documents.parse("""
            # Exam plan
            ## Sessions
            Date | Time | Subject | Minutes
            2026-11-02 | 18:00 | Statistics | 60
            Tue 3 Nov | 7pm–8.30pm | Essay | 
            Wednesday | 10:00 | Rest | 30
            2026-11-05 | | Reading | 1.5 hours
        """.trimIndent(), "X")
        val s = Study.fromDoc(doc, today)
        assertEquals(3, s.size)
        assertEquals(LocalDate.of(2026, 11, 2), s[0].date)
        assertEquals(LocalTime.of(18, 0), s[0].time)
        assertEquals(60, s[0].minutes)
        assertEquals(LocalDate.of(2026, 11, 3), s[1].date)
        assertEquals(LocalTime.of(19, 0), s[1].time)
        assertEquals(90, s[1].minutes)
        assertNull(s[2].time)
        assertEquals(90, s[2].minutes)
        assertEquals(LocalDate.of(2026, 11, 4), Study.dateOf("Wed", today))
        assertEquals(45, Study.minutesOf("45 min"))
        assertEquals(75, Study.minutesOf("1 hour 15 mins"))
    }

    @Test
    fun documentsRoundTripAndRead() {
        val doc = Documents.parse("# Notes\n## Part one\n- a\n- b\nA paragraph.\nName | Score\nAmy | 7\n", "X")
        assertEquals(doc, Documents.parse(Documents.markup(doc), "Y"))
        // What he makes, he can read back.
        val word = DocText.docx(Documents.docx(doc))
        assertTrue(word.contains("Part one") && word.contains("A paragraph."))
        val sheet = DocText.xlsx(Documents.xlsx(doc))
        assertTrue(sheet, sheet.contains("Name | Score") && sheet.contains("Amy | 7"))
    }
}
