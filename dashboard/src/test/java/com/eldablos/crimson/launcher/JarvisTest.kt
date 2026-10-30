package com.eldablos.crimson.launcher

import com.eldablos.crimson.launcher.JarvisParser.Command
import com.eldablos.crimson.launcher.JarvisParser.Control
import org.junit.Assert.assertEquals
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
        assertEquals(Command.AddEvent("call the bank", today.plusDays(1), LocalTime.of(10, 0)), p("Remind me to call the bank tomorrow at 10"))
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
}
