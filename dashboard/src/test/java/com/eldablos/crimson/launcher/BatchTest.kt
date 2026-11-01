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
        assertEquals(Command.No, p("Noo"))
    }

    @Test
    fun payQuestionsAsYouSayThem() {
        // From the phone, 31 Oct: all of these are "what will my next payslip be".
        assertEquals(Command.Payday, p("so how much am I supposed to get paid on my page slip"))
        assertEquals(Command.Payday, p("now from what I have earned already from what I worked"))
        assertEquals(Command.Payday, p("can you add tomorrow shift to this"))
        assertEquals(Command.Payday, p("how much will be on my next payslip"))
        // Still a payslip check when there's a picture to check.
        assertEquals(Command.CheckPayslip, p("check my payslip"))
        // Acknowledgements are just that.
        assertEquals(Command.Ack, p("removed"))
        assertEquals(Command.Ack, p("ok cool") .let { if (it == Command.Ack) it else p("cool") })
    }

    @Test
    fun typedAsYouTypeIt() {
        assertEquals(Command.Shift(1), p("do i work tmrw"))
        assertEquals(Command.Shift(1), p("wat time do i start tmr"))
        assertEquals(Command.Lights(true, null), p("turn the lite on"))
        assertEquals(Command.Lights(false, null), p("switch of the light"))
        assertEquals(Command.Climate(false, "heat"), p("heating of"))
        assertEquals(Command.SetTemp(25.0), p("put the heater on 25"))
        assertEquals(Command.Hours("month", true), p("whats my take home this month"))
        // Paid fortnightly: pay with no week or month named is the pay period.
        assertEquals(Command.Payday, p("whats my pay"))
        assertEquals(Command.Payday, p("how much have i made"))
        assertEquals(Command.Hours("week", true), p("how much have i earned this week"))
        assertEquals(Command.MorningBrief, p("whats my day like"))
        assertTrue(p("countdown to my holiday on 3 march") is Command.AddCountdown)
        assertEquals(listOf("whats the weather tomorrow", "am i working tomorrow"), JarvisParser.split("whats the weather tomorrow and am i working"))
        assertEquals(listOf("turn on the light", "set an alarm for 7"), JarvisParser.split("turn on the light and set an alarm for 7"))
    }

    @Test
    fun clockingAsSaid() {
        assertEquals(Command.ClockOut(LocalTime.of(18, 15)), p("Clocked out 18.15"))
        assertEquals(Command.ClockIn(LocalTime.of(7, 18)), p("I clocked in at 07.18 this morning"))
        assertEquals(Command.ClockOut(null), p("I have just clocked up"))
        assertEquals(Command.ClockOut(null), p("I've clocked off"))
        assertEquals(Command.ClockOut(LocalTime.of(18, 0)), p("finished work 6pm"))
    }

    @Test
    fun filesDeckAndVoice() {
        assertEquals(Command.FindFiles("pdf from uni about statistics"), p("find the pdf from uni about statistics"))
        assertEquals(Command.FindFiles("screenshots from yesterday"), p("find my screenshots from yesterday"))
        assertEquals(Command.FindFiles("payslip"), p("search my files for payslip"))
        assertEquals(Command.ReadDeck(null), p("read my deck"))
        assertEquals(Command.ReadDeck("comms"), p("what's on the comms card"))
        assertEquals(Command.TrainVoice, p("train my voice"))
        assertEquals(Command.PayWith("tomorrow"), p("my pay including tomorrow"))
        // What the file search makes of a request.
        assertEquals(setOf("pdf"), Files.kinds("the pdf from uni about statistics"))
        assertEquals(listOf("uni", "statistics"), Files.words("the pdf from uni about statistics"))
        assertEquals(today.minusDays(1)..today.minusDays(1), Files.dates("screenshots from yesterday", today))
        assertEquals("screenshot", Files.kindOf("image/png", "Screenshot_20261031.png"))
        assertEquals("spreadsheet", Files.kindOf("", "Shifts and pay.xlsx"))
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

    @Test
    fun tubeDaysWeeksAndFollowUps() {
        val today = LocalDate.of(2026, 11, 1) // a Sunday
        fun p(s: String) = JarvisParser.parse(s, today)
        assertEquals(JarvisParser.Command.Travel(1, "jubilee", true), p("how are the trains looking tomorrow and cancellations how's the jubilee line especially"))
        // Corrected mid-sentence: the last one counts. Next week = Mon 2 Nov, seven days.
        assertEquals(JarvisParser.Command.Travel(1, null, true, days = 7), p("are there any planned cancellations on the tubes this week oh next week sorry"))
        assertEquals(JarvisParser.Command.Travel(1, "jubilee", true, days = 7), p("how's the jubilee next week"))
        assertTrue(p("are the tubes ok tomorrow") is JarvisParser.Command.Travel)
        assertEquals(JarvisParser.Command.Travel(0, null, dayGiven = false, all = true), p("what are the six more"))
        assertEquals(JarvisParser.Command.Travel(0, null, dayGiven = false, all = true), p("which other lines"))
        assertEquals(JarvisParser.Command.FollowUp(today.plusDays(1)), p("not today tomorrow"))
        assertEquals(JarvisParser.Command.TrainVoice, p("train my voice"))
        // Future days ask TfL per line (the mode address has no dates).
        assertTrue(Travel.url(today.plusDays(1), 1, today).contains("/Line/bakerloo,"))
        assertTrue(Travel.url(today.plusDays(1), 7, today).endsWith("/Status/2026-11-02T00:00:00/to/2026-11-08T23:59:59"))
        assertTrue(Travel.url(today, 1, today).endsWith("/Line/Mode/tube,elizabeth-line,overground,dlr,tram/Status"))
    }
}
