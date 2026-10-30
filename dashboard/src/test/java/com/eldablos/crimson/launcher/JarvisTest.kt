package com.eldablos.crimson.launcher

import com.eldablos.crimson.launcher.JarvisParser.Command
import com.eldablos.crimson.launcher.JarvisParser.Control
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class JarvisTest {
    private val today = LocalDate.of(2026, 10, 30) // a Friday

    private fun p(s: String) = JarvisParser.parse(s, today)

    @Test
    fun smallTalkAndTheBriefing() {
        assertEquals(Command.Greet, p("Hey Jarvis"))
        assertEquals(Command.Greet, p("Good morning, Jarvis"))
        assertEquals(Command.Briefing, p("Tell me the briefing for today"))
        assertEquals(Command.Briefing, p("Jarvis, brief me please"))
        assertEquals(Command.BriefingControl(Control.CHAPTER, "NEWS"), p("Continue from the news"))
        assertEquals(Command.BriefingControl(Control.RESUME), p("Carry on"))
        assertEquals(Command.BriefingControl(Control.BACK), p("Go back a bit"))
        assertEquals(Command.BriefingControl(Control.STOP), p("Stop"))
        assertEquals(Command.Thanks, p("Thank you Jarvis"))
    }

    @Test
    fun notesAndCalendar() {
        assertEquals(Command.AddNote("Buy milk"), p("Add Buy milk to my notes"))
        assertEquals(Command.AddNote("call the bank"), p("Remind me to call the bank"))
        // Notes unless he's told the calendar.
        assertEquals(Command.AddNote("call the bank tomorrow at 10"), p("Remind me to call the bank tomorrow at 10"))
        assertEquals(Command.AddNote("call the bank tomorrow"), p("Add a note to call the bank tomorrow"))
        assertEquals(Command.AddNote("milk"), p("Add milk"))
        assertEquals(Command.AddEvent("call the bank", today.plusDays(1), LocalTime.of(10, 0)), p("Add call the bank tomorrow at 10 to my calendar"))
        assertEquals(Command.AddEvent("gym", today, LocalTime.of(18, 0)), p("Put gym in my calendar for today at 6pm"))
        assertEquals(Command.AddEvent("dentist", LocalDate.of(2026, 11, 2), LocalTime.of(15, 0)), p("Book the dentist for Monday at 3pm"))
        assertEquals(Command.AddEvent("Dentist", LocalDate.of(2026, 11, 2), LocalTime.of(15, 30)), p("Add Dentist on monday at 3:30pm to my calendar"))
        assertEquals(Command.ReadNotes, p("Read my notes"))
    }

    @Test
    fun timersAlarmsAndControls() {
        assertEquals(Command.Timer(600), p("Set a timer for 10 minutes"))
        assertEquals(Command.Alarm(LocalTime.of(6, 0), 0), p("Wake me up at 6"))
        assertEquals(Command.Alarm(LocalTime.of(6, 30), 1), p("Set an alarm for 6:30 tomorrow"))
        assertEquals(Command.Torch(true), p("Turn the torch on"))
        assertEquals(Command.Torch(false), p("Turn off the torch"))
        assertEquals(Command.Lights(true), p("Can you turn the light on please"))
        assertEquals(Command.Dnd(true), p("Turn on do not disturb"))
    }

    @Test
    fun peopleAndApps() {
        assertEquals(Command.Call("mum"), p("Call Mum"))
        assertEquals(Command.WhatsApp("mum", "I'm on my way"), p("Message Mum on WhatsApp saying I'm on my way"))
        assertEquals(Command.Text("tom", "Running late"), p("Text Tom saying Running late"))
        assertEquals(Command.OpenApp("spotify"), p("Open Spotify"))
        assertEquals(Command.OpenApp("messages"), p("Open messages"))
        assertEquals(Command.OpenApp("tapo"), p("Launch the Tapo app"))
    }

    @Test
    fun questions() {
        assertEquals(Command.Weather(1, rainOnly = true), p("Is it going to rain tomorrow?"))
        assertEquals(Command.Shift(1), p("Am I working tomorrow?"))
        assertEquals(Command.ShiftWeek(next = true), p("How many hours am I working next week"))
        assertEquals(Command.Agenda(1), p("What's on tomorrow"))
        assertEquals(Command.Emails(true), p("Any emails from Even Us?"))
        assertEquals(Command.Crypto("bitcoin"), p("How's bitcoin doing"))
        assertEquals(Command.Countdown("christmas"), p("How long until Christmas"))
        assertEquals(Command.Habit("gym"), p("I went to the gym"))
        assertEquals(Command.Focus(25), p("Start a focus session"))
        assertTrue(p("Sing me a song about llamas") is Command.Unknown)
    }

    @Test
    fun conversation() {
        assertEquals(Command.Yes, p("Yes please"))
        assertEquals(Command.Yes, p("Go ahead"))
        assertEquals(Command.No, p("No thanks"))
        assertEquals(Command.Undo, p("Undo that"))
        assertEquals(Command.Repeat, p("Say that again"))
        assertEquals(Command.FollowUp(LocalDate.of(2026, 11, 6)), p("And Friday?"))
        assertEquals(Command.FollowUp(today.plusDays(1)), p("What about tomorrow?"))
        assertEquals(Command.FollowUp(null, nextWeek = true), p("And next week?"))
        assertEquals(Command.Amend(null, LocalTime.of(6, 30)), p("Make it 6:30"))
        assertEquals(Command.Amend(LocalDate.of(2026, 11, 2), null), p("Move it to Monday"))
        assertEquals(Command.Text("her", null), p("Actually, text her instead"))
        assertEquals(Command.Persona("WITTY"), p("Be more witty"))
        assertEquals(Command.Settings, p("Open your settings"))
        assertEquals(Command.OpenApp("settings"), p("Open settings"))
    }

    @Test
    fun memoryAndDays() {
        assertEquals(Command.Remember("locker code", "4412"), p("Remember my locker code is 4412"))
        assertEquals(Command.Remember("tom's birthday", "3 March"), p("Remember Tom's birthday is 3 March"))
        assertEquals(Command.Recall("locker code"), p("What's my locker code?"))
        assertEquals(Command.Recall("tom's birthday"), p("When is Tom's birthday?"))
        assertEquals(Command.Forget("locker code"), p("Forget my locker code"))
        assertEquals(Command.Forget("*"), p("Forget everything"))
        assertEquals(Command.AddNote("buy bread"), p("Remember to buy bread"))
        assertEquals(LocalDate.of(2027, 3, 3), JarvisParser.dateIn("3 March", today))
        assertEquals(LocalDate.of(2026, 12, 25), JarvisParser.dateIn("december 25th", today))
        assertEquals(Command.Agenda(7), p("What's on next Friday"))
        assertEquals(Command.Weather(1), p("What's the weather tomorrow"))
        assertEquals(Command.Shift(3), p("Am I working on Monday?"))
    }

    @Test
    fun handsAndRoutines() {
        assertEquals(Command.Lights(true, "bedroom light"), p("Turn on the bedroom light"))
        assertEquals(Command.Lights(false, "bedroom light"), p("Turn the bedroom light off"))
        assertEquals(Command.Switch("fan", true), p("Turn on the fan"))
        assertEquals(Command.Teach("bedroom light", null), p("Learn the bedroom light"))
        assertEquals(Command.Teach("fan", "smartthings"), p("Learn how to turn on the fan in SmartThings"))
        assertEquals(Command.Press("Start", "tapo"), p("Press Start in Tapo"))
        assertEquals(Command.ShiftAlarm, p("Set an alarm for my next shift"))
        assertEquals(Command.NewRoutine, p("Create a routine"))
    }

    @Test
    fun routinesAndSounds() {
        val all = JarvisRoutines.DEFAULTS
        assertEquals("night", JarvisRoutines.match(all, "Good night, Jarvis")?.id)
        assertEquals("night", JarvisRoutines.match(all, "I'm going to bed")?.id)
        assertEquals("focus", JarvisRoutines.match(all, "Run focus mode")?.id)
        assertEquals("leaving", JarvisRoutines.match(all, "Jarvis, I'm leaving work please")?.id)
        assertEquals(null, JarvisRoutines.match(all, "Good night sweet prince of Denmark"))
        // Every step of the ready-made routines is something he understands.
        for (r in all) for (step in r.steps) assertTrue("$step", p(step) !is Command.Unknown)
        val chime = JarvisSound.synth(JarvisSound.Cue.LISTEN)
        assertEquals(44_100 * 180 / 1000, chime.size)
        assertTrue(chime.maxOf { it.toInt() } < Short.MAX_VALUE * .3)
    }

    /** Everything from the 1.31 screenshots that he got wrong. */
    @Test
    fun whatWentWrongIn131() {
        assertEquals(Command.NoteAsk, p("Can you make a note"))
        assertEquals(Command.NoteAsk, p("Take a note"))
        assertEquals(Command.AddNote("do laundry"), p("Add a note to do laundry"))
        assertEquals(Command.AddNote("do my laundry today"), p("Can you add a note to do my laundry today"))
        assertEquals(Command.AddNote("the boiler man comes Friday"), p("Make a note that the boiler man comes Friday"))
        assertEquals(Command.Help, p("What can you do?"))
        assertEquals(Command.Alarms(true), p("Turn on my alarms"))
        assertEquals(Command.Alarms(null), p("When's my alarm?"))
        assertEquals(Command.Correct("claude ai"), p("It's called Claude AI"))
        assertEquals(Command.Correct("tom"), p("No, I meant Tom"))
        assertEquals(Command.OpenApp("cloud"), p("Open Cloud app"))
        assertEquals(Command.OpenApp("tapo"), p("Open tapo app"))
        assertEquals(Command.Weather(0), p("Could you tell me the weather"))
        assertEquals(Jarvis.soundex("claude"), Jarvis.soundex("cloud"))
        assertEquals("C430", Jarvis.soundex("Claude"))
    }

    /** Everyday ways of saying things: every one of these must mean something to him. */
    @Test
    fun everydayPhrasing() {
        val phrases = listOf(
            "Hey Jarvis what's the plan for today", "What's happening tomorrow", "Do I have work tomorrow", "Am I off on Sunday",
            "What time do I start tomorrow", "Wake me up at half six", "Set alarm 7am", "Cancel my alarm", "Set a 5 minute timer",
            "Remind me in 10 minutes to check the oven", "Put milk on my shopping list", "Delete my notes", "Did anyone text me",
            "Call my mum", "Tell Tom I'm running late", "Text mum I'm home", "Take a photo", "Dim the lights", "Skip this song",
            "Is it cold outside", "Weather this weekend", "Days until my birthday", "You're brilliant", "Turn on wifi",
            "Silence my phone", "Tell me a joke", "Set a timer for quarter of an hour", "Remind me in a few minutes to call back",
        )
        for (s in phrases) assertTrue(s, p(s) !is Command.Unknown)
        assertEquals(Command.Alarm(LocalTime.of(6, 30)), p("Wake me up at half six"))
        assertEquals(Command.Alarm(LocalTime.of(6, 45)), p("Set an alarm for quarter to seven"))
        assertEquals(Command.Timer(600, "check the oven"), p("Remind me in ten minutes to check the oven"))
        assertEquals(Command.Music(JarvisParser.Play.NEXT), p("Skip this song"))
        assertEquals(Command.WhatsApp("tom", "I'm running late"), p("Tell Tom I'm running late"))
        assertEquals(Command.Text("mum", "I'm home"), p("Text mum I'm home"))
        assertEquals(Command.AddNote("call the dentist tomorrow at 9"), p("Remind me tomorrow at 9 to call the dentist"))
    }

    @Test
    fun alarmsHabitsShiftsByVoice() {
        assertEquals(Command.Alarms(null), p("What are my alarms for tomorrow"))
        assertEquals(Command.AlarmSwitch(LocalTime.of(6, 0), false), p("Turn off my 6am alarm"))
        assertEquals(Command.AlarmSwitch(LocalTime.of(7, 0), true), p("Turn on my 7 o'clock alarm"))
        assertEquals(Command.AlarmSwitch(null, false), p("Turn off all my alarms"))
        assertEquals(Command.MoveAlarm(LocalTime.of(6, 0), LocalTime.of(6, 30)), p("Move my 6am alarm to 6:30"))
        assertEquals(Command.MoveAlarm(null, LocalTime.of(7, 0)), p("Change my alarm to 7"))
        assertEquals(Command.Alarm(LocalTime.of(7, 0)), p("Set an alarm for 7"))
        assertEquals(Command.AddHabit("Gym", true), p("Add a habit gym and tick it off today"))
        assertEquals(Command.AddHabit("Read 20 pages", false), p("Add a new habit called read 20 pages"))
        assertEquals(Command.RemoveHabit("gym"), p("Remove the gym habit"))
        assertEquals(Command.Untick("gym"), p("Untick gym"))
        assertEquals(Command.Habits, p("How are my habits"))
        assertEquals(Command.ItAction("tick"), p("Tick it off"))
        assertEquals(Command.AddCountdown("Holiday", LocalDate.of(2027, 3, 3)), p("Add a countdown to holiday on 3 March"))
        assertEquals(Command.RemoveCountdown("christmas"), p("Remove the christmas countdown"))
        assertEquals(Command.AddShift(LocalDate.of(2026, 11, 2), LocalTime.of(7, 0), LocalTime.of(15, 0)), p("I'm working Monday 7 till 3"))
        assertEquals(Command.AddShift(LocalDate.of(2026, 11, 6), LocalTime.of(14, 0), LocalTime.of(22, 0)), p("I'm working on Friday from 2 till 10"))
        assertEquals(Command.AddShift(today.plusDays(1), LocalTime.of(22, 0), LocalTime.of(6, 0)), p("Add a shift on Saturday 10pm to 6am"))
        assertEquals(Command.RemoveShift(LocalDate.of(2026, 11, 2)), p("I'm off on Monday"))
        assertEquals(Command.Shift(1), p("Am I off tomorrow"))
        assertEquals(Command.LightsStatus, p("Which lights are on"))
        assertEquals(Command.ScreenTime, p("What's my screen time"))
    }

    @Test
    fun heatingInAirPlus() {
        assertEquals(Command.Climate(true, "heat"), p("Turn on the heating"))
        assertEquals(Command.Climate(false, "heat"), p("Heating off"))
        assertEquals(Command.Climate(true, "cool"), p("Turn on the air con"))
        assertEquals(Command.SetTemp(28.0), p("Set it to 28 degrees"))
        assertEquals(Command.SetTemp(22.0), p("Set the heating to 22"))
        assertEquals(Command.SetTemp(21.5), p("Make it 21.5 degrees"))
        assertEquals(Command.SetTemp(null, 2.0), p("Turn the heat up two degrees"))
        assertEquals(Command.SetTemp(null, -1.0), p("Cooler"))
        assertEquals(Command.Climate(true, "heat"), p("I'm cold"))
        // Still the weather, and still alarms.
        assertEquals(Command.Weather(0), p("What's the temperature"))
        assertEquals(Command.Amend(null, LocalTime.of(6, 30)), p("Make it 6:30"))
    }

    @Test
    fun editingByVoice() {
        assertEquals(Command.EditNote("dim my laundry", "do my laundry"), p("Change my note from dim my laundry to do my laundry"))
        assertEquals(Command.EditNote("#last", "do my laundry"), p("Change the last note to do my laundry"))
        assertEquals(Command.EditNote("milk", "oat milk"), p("Replace milk with oat milk on my list"))
        assertEquals(Command.DeleteNote("buy milk"), p("Delete buy milk from my notes"))
        assertEquals(Command.DeleteNote("#last"), p("Delete the last note"))
        assertEquals(Command.RenameHabit("gym", "Gym session"), p("Rename the gym habit to gym session"))
        assertEquals(Command.AddShift(LocalDate.of(2026, 11, 2), LocalTime.of(8, 0), LocalTime.of(16, 0)), p("Change my shift on Monday to 8 till 4"))
        assertEquals(Command.Change("dentist", "Friday at 2"), p("Move the dentist to Friday at 2"))
        assertEquals(Command.Change("locker code", "9921"), p("Change my locker code to 9921"))
        assertEquals(Command.Change("holiday countdown", "5 March"), p("Change the holiday countdown to 5 March"))
        assertEquals(Command.DeleteThing("dentist"), p("Cancel the dentist"))
        // Still what they were.
        assertEquals(Command.MoveAlarm(LocalTime.of(6, 0), LocalTime.of(6, 30)), p("Move my 6am alarm to 6:30"))
        assertEquals(Command.ClearNotes, p("Clear my notes"))
        assertEquals(Command.Undo, p("Undo that"))
        assertEquals(Command.No, p("Cancel"))
    }

    @Test
    fun lightBrightnessAndColour() {
        assertEquals(Command.Brightness(null, 20), p("Brighter"))
        assertEquals(Command.Brightness(null, -20), p("Dim the lights"))
        assertEquals(Command.Brightness(null, -20), p("Make the light dimmer"))
        assertEquals(Command.Brightness(30), p("Set the lights to 30%"))
        assertEquals(Command.Brightness(80), p("Light brightness 80 percent"))
        assertEquals(Command.Brightness(100), p("Full brightness on the light"))
        assertEquals(Command.LightColour(null), p("Next colour"))
        assertEquals(Command.LightColour("red"), p("Make the light red"))
        assertEquals(Command.LightColour("warm white"), p("Change the light to warm white"))
        assertEquals(Command.Lights(false, "bedroom light"), p("Turn off the bedroom light"))
    }

    @Test
    fun countdownsToOccasions() {
        assertEquals(Command.AddCountdown("New Year", LocalDate.of(2027, 1, 1)), p("Add a countdown to the new year"))
        assertEquals(Command.AddCountdown("Christmas", LocalDate.of(2026, 12, 25)), p("Start a countdown to Christmas"))
        assertEquals(Command.AddCountdown("Easter", LocalDate.of(2027, 3, 28)), p("Set a countdown to Easter"))
        assertEquals(Command.AddCountdown("Holiday", LocalDate.of(2027, 3, 3)), p("Add a countdown to holiday on 3 March"))
        assertEquals(Command.AddCountdown("Gym competition", null), p("Add a countdown to gym competition"))
        assertEquals(Command.Countdown("christmas"), p("How long until Christmas"))
    }

    @Test
    fun changeIt() {
        assertEquals(Command.ChangeIt("do my laundry"), p("Can you change it to do my laundry"))
        assertEquals(Command.ChangeIt("do my laundry"), p("That should say do my laundry"))
        assertEquals(Command.ChangeIt("Dentist check-up"), p("Rename it to Dentist check-up"))
        assertEquals(Command.Amend(null, LocalTime.of(6, 30)), p("Change it to 6:30"))
        assertEquals(Command.Amend(LocalDate.of(2026, 11, 6), null), p("Move it to Friday"))
    }

    @Test
    fun londonTravel() {
        assertEquals(Command.Travel(1), p("How's the traffic tomorrow"))
        assertEquals(Command.Travel(1), p("Any tube delays tomorrow?"))
        assertEquals(Command.Travel(0, "jubilee", dayGiven = false), p("Is the Jubilee line running"))
        assertEquals(Command.Travel(0, null, dayGiven = false), p("Any cancellations on the trains"))
        assertEquals(Command.Travel(0, "central", dayGiven = false), p("And the Central?"))
        assertEquals(Command.Travel(1, "elizabeth"), p("Is the Elizabeth line OK tomorrow"))
        assertEquals(Command.CommuteTime(40), p("It takes me 40 minutes to get to work"))
        assertEquals(Command.ItAction("delete"), p("Delete it"))
        assertEquals(Command.ItAction("tick"), p("Tick it off"))
        assertEquals(Command.Undo, p("undo"))
        assertEquals(Command.Travel(1), p("Should I plan my journey to work tomorrow"))
        assertEquals(Command.Commute("northern"), p("I usually take the Northern line to work"))
        assertTrue(p("add a note about train delays") is Command.AddNote)
        assertTrue(p("next colour") is Command.LightColour)
    }

    @Test
    fun travelAdvice() {
        val day = java.time.LocalDate.now().plusDays(1)
        fun line(id: String, name: String, sev: Int, desc: String, reason: String = "", mode: String = "tube") =
            """{"id":"$id","name":"$name","modeName":"$mode","lineStatuses":[{"statusSeverity":$sev,"statusSeverityDescription":"$desc","reason":"$reason"}]}"""
        val good = Travel.parseLines("[" + listOf(line("jubilee", "Jubilee", 10, "Good Service"), line("central", "Central", 10, "Good Service"),
            line("elizabeth", "Elizabeth line", 10, "Good Service", mode = "elizabeth-line")).joinToString(",") + "]")
        val fine = Travel.advise(Travel.Report(day, good, emptyList(), 0), Travel.DEFAULT_COMMUTE)
        assertFalse(fine.planAhead)
        assertEquals("The Jubilee's running fine tomorrow, no need to plan ahead.", fine.say)
        val bad = Travel.parseLines("[" + listOf(line("jubilee", "Jubilee", 4, "Planned Closure", "JUBILEE LINE: No service between Stratford and Waterloo due to engineering works. Replacement buses."),
            line("elizabeth", "Elizabeth line", 10, "Good Service", mode = "elizabeth-line"), line("central", "Central", 9, "Minor Delays"),
            line("northern", "Northern", 6, "Severe Delays")).joinToString(",") + "]")
        val roads = Travel.parseRoads("""[{"displayName":"A2","statusSeverity":"Serious","statusSeverityDescription":"Serious Delays"},{"displayName":"A40","statusSeverity":"Good","statusSeverityDescription":"No Exceptional Delays"}]""")
        val plan = Travel.advise(Travel.Report(day, bad, roads, 0), Travel.DEFAULT_COMMUTE)
        assertTrue(plan.planAhead)
        assertTrue(plan.say, plan.say.startsWith("The Jubilee line has a planned closure tomorrow: no service between Stratford and Waterloo"))
        assertTrue(plan.say, plan.say.length < 260)
        assertTrue(plan.say, plan.say.contains("take the Elizabeth line"))
        assertTrue(plan.say, plan.say.contains("Elsewhere, problems on the Northern"))
        assertFalse(plan.say, plan.say.contains("A2"))
        assertEquals("jubilee", Travel.lineId("the Jubilee line"))
        assertEquals("elizabeth", Travel.lineId("lizzy line"))
    }

    @Test
    fun editANoteWithoutSayingWhatTo() {
        assertEquals(Command.EditNote("#last", ""), p("Edit last note"))
        assertEquals(Command.EditNote("#last", ""), p("Edit last note/"))
        assertEquals(Command.EditNote("#last", ""), p("Change my last note"))
        assertEquals(Command.EditNote("#2", ""), p("Edit the second note"))
        assertEquals(Command.EditNote("#3", ""), p("Edit note 3"))
        assertEquals(Command.EditNote("#2", "buy bread"), p("Edit the second note to say buy bread"))
    }

    @Test
    fun tapoPresetNames() {
        assertEquals("blue", JarvisReaders.presetName("Preset 2, brightness 100%, dark Blue, "))
        assertEquals("bright green", JarvisReaders.presetName("Preset 4, brightness 100%, dark Bright Green, "))
        assertEquals("warm white", JarvisReaders.presetName("Preset 1, brightness 50%, White Light 2700 K"))
        assertEquals("auto", JarvisReaders.presetName("Auto Mode"))
    }

    @Test
    fun teachingHowIDoThings() {
        val all = p("I need to wake up at 5 am to start work at 7am and I set 6 alarms 5 minutes apart starting 5 am as I leave my house at around 6.15 am")
        assertTrue(all.toString(), all is Command.Learn)
        val l = (all as Command.Learn).lessons
        assertTrue(l.toString(), l.contains(JarvisLessons.Lesson.WakeBefore(LocalTime.of(5, 0), LocalTime.of(7, 0))))
        assertTrue(l.toString(), l.contains(JarvisLessons.Lesson.AlarmSeries(6, 5, LocalTime.of(5, 0))))
        assertTrue(l.toString(), l.contains(JarvisLessons.Lesson.LeaveAt(LocalTime.of(6, 15), LocalTime.of(7, 0))))
        assertEquals(Command.Learn(listOf(JarvisLessons.Lesson.DayLine(java.time.DayOfWeek.THURSDAY, "elizabeth"))), p("On Thursdays I take the Elizabeth line"))
        assertEquals(Command.Learn(listOf(JarvisLessons.Lesson.AlarmSeries(6, 5, null))), p("I set six alarms five minutes apart"))
        assertEquals(Command.AlarmSeries(6, 5, LocalTime.of(5, 0)), p("Set 6 alarms 5 minutes apart starting 5am"))
        assertEquals(Command.Learn(listOf(JarvisLessons.Lesson.WakeBefore(LocalTime.of(5, 0), LocalTime.of(7, 0)))), p("When I start at 7 I get up at 5"))
    }

    @Test
    fun tellingHimWhatTheHeaterIsOn() {
        assertEquals(Command.HeaterNow(23.0), p("The heater's on 23"))
        assertEquals(Command.HeaterNow(25.0), p("heating is set to 25 degrees"))
        assertEquals(Command.SetTemp(22.0), p("set the heating to 22"))
    }

    @Test
    fun heaterCorrections() {
        fun r(s: String) = HeaterWords.read(s)
        assertEquals(HeaterWords.Said(22.0, 23.0, null, null), r("that's 22 actually can you set it to 23"))
        assertEquals(HeaterWords.Said(21.0, 23.0, null, null), r("now you set it to 21 set it to 23"))
        assertEquals(HeaterWords.Said(21.0, 23.0, null, null), r("no set the temperature to 23 it's at 21 now"))
        assertEquals(HeaterWords.Said(22.0, 23.0, null, null), r("no that's the heater is on 22 now set it to 23"))
        assertEquals(HeaterWords.Said(22.0, null, null, null), r("no it's not it's on 22 degrees"))
        assertEquals(HeaterWords.Said(null, null, -1.0, 1.0), r("you went down by 1 go up"))
        assertEquals(HeaterWords.Said(null, null, null, 2.0), r("go up 2"))
        assertEquals(null, r("what's the weather"))
    }

    @Test
    fun phoneCommandsAndHeaterStatements() {
        assertEquals(Command.PhoneAction("lock"), p("Lock my phone"))
        assertEquals(Command.PhoneAction("lock"), p("lock the screen"))
        assertEquals(Command.PhoneAction("screenshot"), p("take a screenshot"))
        assertEquals(Command.PhoneAction("notifications"), p("show my notifications"))
        assertEquals(Command.PhoneAction("home"), p("go home"))
        assertEquals(Command.Ringer("vibrate"), p("put my phone on vibrate"))
        assertEquals(Command.Ringer("silent"), p("silent mode"))
        assertEquals(Command.HeaterNow(22.0), p("The temperature is at 22 right now"))
        assertEquals(Command.HeaterNow(21.0), p("The heater temperature right now is at 21"))
        assertEquals(Command.HeaterNow(22.0, 23.0), p("the heater is on 22 now set it to 23"))
        assertEquals(Command.SetTemp(22.0), p("set the temperature to 22"))
    }

    @Test
    fun tickingNotesOff() {
        assertEquals(Command.TickAllNotes, p("Tick my notes"))
        assertEquals(Command.TickAllNotes, p("T i c k my notes"))
        assertEquals(Command.TickAllNotes, p("Take my notes"))
        assertEquals(Command.TickAllNotes, p("Can you take all of them"))
        assertEquals(Command.TickAllNotes, p("Take all of them"))
        assertEquals(Command.TickAllNotes, p("Finish my notes"))
        assertEquals(Command.TickAllNotes, p("I have completed my notes"))
        assertEquals(Command.TickAllNotes, p("tick off all my notes"))
        assertEquals(Command.TickNote("#2"), p("take the second note off"))
        assertEquals(Command.TickNote("#2"), p("I want to finish my second note"))
        assertEquals(Command.TickNote("#2"), p("finish my second note"))
        assertEquals(Command.TickNote("#last"), p("I completed the last one"))
        assertEquals(Command.TickNote("#1"), p("I completed the first one"))
        assertEquals(Command.TickNote("#1"), p("the first one's done"))
        assertEquals(Command.TickNote("#3"), p("mark the third as done"))
        assertEquals(Command.TickNote("#2"), p("I've done note 2"))
        assertEquals(Command.TickNote("#last"), p("check off the last item"))
        assertEquals(Command.TickNote("#2"), p("second note is finished"))
        assertEquals(Command.ReadNotes, p("What notes do I have"))
        assertEquals(Command.ReadNotes, p("read my notes"))
    }

    @Test
    fun keepingThingsOn() {
        for (said in listOf("The 7pm alarm stays always on", "the 7pm alarm always stays on", "keep my 7pm alarm on", "never turn off the 7pm alarm",
                "don't ever turn off my 7pm alarm", "the 7pm alarm should always stay on", "the 7pm alarm is always on", "always keep the 7pm alarm on",
                "my 7pm alarm must never be turned off", "don't touch my 7pm alarm")) {
            assertEquals(said, Command.KeepRule(if (said.contains("my 7pm")) "7pm alarm" else "7pm alarm", true), p(said).let { if (it is Command.KeepRule) it.copy(subject = it.subject.removePrefix("my ").removePrefix("the ")) else it })
        }
        assertEquals(Command.KeepRule("7pm alarm", false), p("you can turn off the 7pm alarm now"))
        assertEquals(Command.KeepRule("7pm alarm", true), p("I have a 7pm alarm that's always on except for Saturdays"))
        assertEquals(Command.KeepRule("7pm alarm", true), p("the 7pm alarm always stays on apart from weekends"))
        assertEquals(Command.KeepRule("hallway light", true), p("never turn off the hallway light"))
        assertEquals(JarvisRules.Keep(JarvisRules.ALARM, "19:00", "7pm alarm"), JarvisRules.keyFor("7pm alarm"))
        assertEquals(JarvisRules.Keep(JarvisRules.ALARM, "07:00", "7am alarm"), JarvisRules.keyFor("7am alarm"))
        assertEquals(JarvisRules.Keep(JarvisRules.ALARM, "07:00|19:00", "7 o'clock alarm"), JarvisRules.keyFor("7 o'clock alarm"))
        assertEquals(Command.AlarmSwitch(null, false), p("turn all my alarms off"))
        assertFalse(p("keep going") is Command.KeepRule)
    }

    @Test
    fun habitsSaidNaturally() {
        assertEquals(Command.Habit("i have placed my orders"), p("I have placed my orders"))
        assertEquals(Command.Habit("i have placed orders for today"), p("I have placed orders for today"))
        assertEquals(Command.Habit("i worked on lessons today"), p("I worked on lessons today"))
        assertTrue(p("I completed the first one") is Command.TickNote)
        assertFalse(p("I wanted to ask something") is Command.Habit)
    }

    @Test
    fun handsLogAsHeard() {
        assertEquals(Command.HandsLog, p("Send me hands long"))
        assertEquals(Command.HandsLog, p("Hands long"))
        assertEquals(Command.HandsLog, p("send me the hands log"))
        assertEquals(Command.HandsLog, p("hand lock"))
    }

    @Test
    fun restartAndSystemsCheck() {
        assertEquals(Command.Reboot, p("Restart yourself"))
        assertEquals(Command.Reboot, p("Restart Jarvis"))
        assertEquals(Command.Reboot, p("reboot"))
        assertEquals(Command.SystemsCheck, p("Run a systems check"))
        assertEquals(Command.SystemsCheck, p("Are your hands working"))
    }

    @Test
    fun notesByNumberAndEndingTheConversation() {
        assertEquals(Command.EditNote("#2", "buy bread"), p("Edit the second note to say buy bread"))
        assertEquals(Command.EditNote("#3", "call mum"), p("Change note 3 to call mum"))
        assertEquals(Command.DeleteNote("#3"), p("Delete the third note"))
        assertEquals(Command.DeleteNote("#last"), p("Remove the last note"))
        assertEquals(Command.TickNote("#1"), p("Tick off the first note"))
        assertEquals(Command.ReadNote("#2"), p("What's my second note"))
        assertEquals(Command.Dismiss, p("That's all, thanks"))
        assertEquals(Command.Dismiss, p("Goodbye Jarvis"))
        assertEquals(Command.ReadNotes, p("What are my notes"))
    }
}
