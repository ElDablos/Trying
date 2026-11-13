package com.eldablos.crimson.assistant

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import com.eldablos.crimson.DashboardStore
import com.eldablos.crimson.Forecast
import com.eldablos.crimson.OpenAppActivity
import com.eldablos.crimson.Weather
import com.eldablos.crimson.alarm.Alarms
import com.eldablos.crimson.core.DocText
import com.eldablos.crimson.core.Documents
import com.eldablos.crimson.core.Files
import com.eldablos.crimson.core.Lists
import com.eldablos.crimson.core.Memory
import com.eldablos.crimson.core.Pay
import com.eldablos.crimson.core.Payslips
import com.eldablos.crimson.core.Shifts
import com.eldablos.crimson.core.Spending
import com.eldablos.crimson.core.Travel
import com.eldablos.crimson.core.Uni
import com.eldablos.crimson.core.Work
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Something to show under a reply: a title, a big figure, rows, little chips (alarm times), a note line, and
 * buttons. Plain data, so chats can be saved.
 */
class Card(val title: String, val big: String? = null, val rows: List<Row> = emptyList(), val actions: List<Act> = emptyList(), val good: Boolean = false,
           val chips: List<String> = emptyList(), val note: String? = null) {
    class Row(val left: String, val right: String = "", val act: Act? = null)

    fun json(): JSONObject = JSONObject().put("title", title).put("big", big ?: "").put("good", good).put("chips", JSONArray(chips)).put("note", note ?: "")
        .put("rows", JSONArray().also { a -> rows.forEach { a.put(JSONObject().put("l", it.left).put("r", it.right).put("a", it.act?.json() ?: JSONObject.NULL)) } })
        .put("actions", JSONArray().also { a -> actions.forEach { a.put(it.json()) } })

    companion object {
        fun from(o: JSONObject): Card = Card(o.optString("title"), o.optString("big").takeIf { it.isNotEmpty() },
            (o.optJSONArray("rows") ?: JSONArray()).let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map { Row(it.optString("l"), it.optString("r"), it.optJSONObject("a")?.let { x -> Act.from(x) }) } },
            (o.optJSONArray("actions") ?: JSONArray()).let { a -> (0 until a.length()).map { Act.from(a.getJSONObject(it)) } }, o.optBoolean("good"),
            (o.optJSONArray("chips") ?: JSONArray()).let { a -> (0 until a.length()).map { a.optString(it) } }, o.optString("note").takeIf { it.isNotEmpty() })
    }
}

/**
 * A button: what it's called and what it does. Kinds: "say" (sends [data] as your next message), "view" and
 * "share" (a file: "uri|mime"), "url", "alarms" (the Clock's alarm list), "intent" (a settings page by action),
 * "undo" (takes back what a tool just did: [data] is the undo's number).
 */
class Act(val label: String, val kind: String, val data: String = "") {
    fun json(): JSONObject = JSONObject().put("label", label).put("kind", kind).put("data", data)

    companion object {
        fun from(o: JSONObject) = Act(o.optString("label"), o.optString("kind"), o.optString("data"))
    }
}

/**
 * Everything the assistant can do, as tools Gemini (or the on-phone model) calls by name. Each one is ordinary
 * app code: the model decides what's wanted and fills in the details, the code does it. Sums (pay, tax,
 * dates, alarm times) are always worked out here, never by the model.
 */
class Tools(private val activity: Activity, private val host: Host) {
    interface Host {
        /** Asks you yes or no before something that removes or overwrites; blocks until you answer. */
        fun confirm(question: String): Boolean
        fun requestPermission(vararg permissions: String)
        /** The files attached to the conversation so far (newest last). */
        fun attachments(): List<DocText.Picked>
    }

    class Out(val json: JSONObject, val card: Card? = null, val said: String = "")

    companion object {
        /** What the last few tools did, ready to be taken back: number to "undo it, and say what happened". */
        private val undos = java.util.concurrent.ConcurrentHashMap<String, () -> String>()
        private val nextUndo = java.util.concurrent.atomic.AtomicInteger(1)

        fun undo(id: String): String = undos.remove(id)?.let { runCatching { it() }.getOrElse { "I couldn't undo that." } } ?: "That can't be undone any more."
    }

    private fun undoable(action: () -> String): Act {
        val id = nextUndo.getAndIncrement().toString()
        undos[id] = action
        return Act("Undo", "undo", id)
    }
    class Tool(val name: String, val description: String, val params: JSONObject, val run: (JSONObject) -> Out)

    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val hm = DateTimeFormatter.ofPattern("HH:mm")
    private val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())
    private var found: List<Files.Found> = emptyList()

    // --- Building blocks ---

    private fun str(desc: String) = JSONObject().put("type", "STRING").put("description", desc)
    private fun int(desc: String) = JSONObject().put("type", "INTEGER").put("description", desc)
    private fun num(desc: String) = JSONObject().put("type", "NUMBER").put("description", desc)
    private fun bool(desc: String) = JSONObject().put("type", "BOOLEAN").put("description", desc)
    private fun choice(desc: String, vararg options: String) = str(desc).put("enum", JSONArray(options.toList()))
    private fun list(desc: String, items: JSONObject) = JSONObject().put("type", "ARRAY").put("description", desc).put("items", items)
    private fun obj(vararg props: Pair<String, JSONObject>, required: List<String> = emptyList()): JSONObject {
        val p = JSONObject()
        props.forEach { p.put(it.first, it.second) }
        return JSONObject().put("type", "OBJECT").put("properties", p).also { if (required.isNotEmpty()) it.put("required", JSONArray(required)) }
    }

    private fun ok(vararg pairs: Pair<String, Any?>) = JSONObject().also { o -> pairs.forEach { o.put(it.first, it.second ?: JSONObject.NULL) } }
    private fun fail(why: String) = Out(JSONObject().put("error", why), said = why)
    private fun date(s: String?): LocalDate? = s?.trim()?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    private fun time(s: String?): LocalTime? = s?.trim()?.let { t ->
        runCatching { LocalTime.parse(if (Regex("^\\d:\\d\\d$").matches(t)) "0$t" else t.take(5)) }.getOrNull()
    }
    private fun JSONObject.s(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
    private fun money(v: Double) = Pay.money(Math.round(v * 100) / 100.0)
    private fun pounds(v: Double) = Pay.money(Math.round(v).toDouble())
    private fun dayName(d: LocalDate): String = when (d) {
        LocalDate.now() -> "today"
        LocalDate.now().plusDays(1) -> "tomorrow"
        else -> d.format(dayFmt)
    }
    private fun start(intent: Intent): Boolean = try {
        activity.startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }

    private fun slipJson(s: Pay.Slip) = ok("from_real_payslip" to s.actual, "hours" to Math.round(s.hours * 10) / 10.0, "basic" to s.basic, (if (s.actual) "tronc" else "tronc_estimate") to s.tronc,
        "bonus" to s.bonus.takeIf { it > 0 }, "before_tax" to Math.round(s.gross * 100) / 100.0, "tax" to s.tax, "national_insurance" to s.ni, "pension" to s.pension,
        "student_loan" to s.studentLoan.takeIf { it > 0 }, "advance_and_other_deductions" to s.other.takeIf { it > 0 }, "take_home" to Math.round(s.net * 100) / 100.0)

    private fun slipRows(s: Pay.Slip) = listOfNotNull(
        Card.Row("Basic · ${"%.1f".format(Locale.ROOT, s.hours).removeSuffix(".0")}h", money(s.basic)),
        Card.Row(if (s.actual) "TRONC" else "TRONC (estimate)", money(s.tronc)),
        s.bonus.takeIf { it > 0 }?.let { Card.Row("Bonus", money(it)) },
        Card.Row("Before tax", money(s.gross)), Card.Row("Tax", "−" + money(s.tax)), Card.Row("National Insurance", "−" + money(s.ni)), Card.Row("Pension", "−" + money(s.pension)),
        s.studentLoan.takeIf { it > 0 }?.let { Card.Row("Student loan", "−" + money(it)) },
        s.other.takeIf { it > 0 }?.let { Card.Row("Advance & other", "−" + money(it)) },
    )

    // --- The tools ---

    val all: List<Tool> = listOf(
        Tool("get_shifts", "The user's work shifts (planned times, and the times actually worked where clocked) and uni sessions between two dates. Defaults to today and the next 14 days.",
            obj("from" to str("First date, YYYY-MM-DD"), "to" to str("Last date, YYYY-MM-DD"))) { a ->
            val from = date(a.s("from")) ?: LocalDate.now()
            val to = (date(a.s("to")) ?: from.plusDays(14)).coerceAtMost(from.plusDays(120))
            val planned = Shifts.all(activity)
            val actual = Shifts.actual(activity)
            val out = JSONArray()
            var d = from
            while (!d.isAfter(to)) {
                val s = planned[d]
                val u = Uni.on(activity, d)
                if (s != null || u != null) out.put(ok("date" to d.toString(), "weekday" to d.dayOfWeek.name.lowercase(), "shift" to s?.let { "${it.start.format(hm)}-${it.end.format(hm)}" },
                    "hours" to s?.let { it.minutes / 60.0 }, "worked" to actual[d]?.let { "${it.start.format(hm)}-${it.end.format(hm)}" }, "uni" to u?.times()))
                d = d.plusDays(1)
            }
            Out(ok("days" to out, "clocked_in_since" to Shifts.clockedIn(activity)?.toString(), "note" to "Days not listed have no shift."))
        },

        Tool("set_shifts", "Adds or changes work shifts (one per day), and the times really worked where known. They show on the Money widget and are saved to the user's calendar. Use for 'I'm working Saturday 7 till 6', and for a rota screenshot (Nory and the like): each shift row's scheduled times go in start/end; a second, smaller time under it with a green tick is when they actually clocked in and out, which goes in worked_start/worked_end. Rows saying 'Day off' go in days_off. Days that aren't on the screenshot are left alone. Send every shift on it in one call.",
            obj("shifts" to list("The shifts to set", obj("date" to str("YYYY-MM-DD"), "start" to str("Scheduled start, HH:MM 24-hour"), "end" to str("Scheduled end, HH:MM 24-hour"),
                "worked_start" to str("Actually clocked in, HH:MM (only if shown)"), "worked_end" to str("Actually clocked out, HH:MM (only if shown)"),
                "name" to str("Optional name like Office"), required = listOf("date", "start", "end"))),
                "days_off" to list("Dates shown as a day off, YYYY-MM-DD: any shift on them is removed", str("A date")))) { a ->
            val arr = a.optJSONArray("shifts") ?: JSONArray()
            val set = ArrayList<Pair<LocalDate, Shifts.Shift>>()
            val worked = HashMap<LocalDate, Shifts.Shift>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val d = date(o.s("date")) ?: return@Tool fail("I couldn't read the date \"${o.s("date")}\".")
                val st = time(o.s("start")) ?: return@Tool fail("I couldn't read the start time for $d.")
                val en = time(o.s("end")) ?: return@Tool fail("I couldn't read the end time for $d.")
                val name = (o.s("name") ?: "SHIFT").uppercase().take(20)
                set += d to Shifts.Shift(st, en, name)
                val ws = time(o.s("worked_start"))
                val we = time(o.s("worked_end"))
                if (ws != null && we != null) worked[d] = Shifts.Shift(ws, we, name)
            }
            val off = a.optJSONArray("days_off")?.let { o -> (0 until o.length()).mapNotNull { date(o.optString(it)) } }.orEmpty().filter { d -> set.none { it.first == d } }
            if (set.isEmpty() && off.isEmpty()) return@Tool fail("No shifts given.")
            val before = Shifts.all(activity)
            val beforeWorked = Shifts.actual(activity)
            set.forEach { (d, s) -> Shifts.set(activity, d, s) }
            worked.forEach { (d, s) -> Shifts.setActual(activity, d, s) }
            off.forEach { Shifts.set(activity, it, null); Shifts.setActual(activity, it, null) }
            val touched = set.map { it.first } + off
            val undo = undoable {
                touched.forEach { d -> Shifts.set(activity, d, before[d]); Shifts.setActual(activity, d, beforeWorked[d]) }
                Work.changed(activity)
                "Put your shifts back as they were."
            }
            Work.changed(activity)
            val calendar = Shifts.canWrite(activity)
            if (!calendar) host.requestPermission(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
            val setup = Pay.setup(activity)
            val (pa, pb) = Pay.periodOf((set.map { it.first } + off).max(), setup)
            val slip = Pay.slipFor(activity, pa, pb, setup)
            // What changed: new, moved, clocked, or now off.
            val rows = ArrayList<Card.Row>()
            var changed = 0
            for ((d, s) in set.sortedBy { it.first }) {
                val was = before[d]
                val note = when {
                    was == null -> "new"
                    was.start != s.start || was.end != s.end -> "was ${Work.short(was)}"
                    else -> null
                }
                if (note != null) changed++
                val w = worked[d]
                val right = (w?.let { "✓ ${it.start.format(hm)}–${it.end.format(hm)}" } ?: "${s.start.format(hm)}–${s.end.format(hm)}") + (note?.let { " · $it" } ?: "")
                rows += Card.Row(d.format(dayFmt), right)
            }
            off.sorted().forEach { d -> rows += Card.Row(d.format(dayFmt), if (before[d] != null) "day off · removed ${Work.short(before[d]!!)}" else "day off") }
            rows += Card.Row(if (calendar) "✓ In your calendar" else "Not in your calendar yet", if (calendar) "" else "allow calendar access")
            val title = when {
                worked.isNotEmpty() -> "Rota updated · ${worked.size} clock-in${if (worked.size == 1) "" else "s"}"
                set.size == 1 && off.isEmpty() -> "Shift saved"
                else -> "${set.size} shifts saved"
            }
            Out(ok("saved" to set.size, "changed_or_new" to changed, "clocked_times_saved" to worked.size, "days_off" to off.size, "in_calendar" to calendar,
                "payslip_for_that_period" to ok("from" to pa.toString(), "to" to pb.toString(), "paid_on" to Pay.payday(pb).toString(), "take_home" to Math.round(slip.net), "hours" to Math.round(slip.hours * 10) / 10.0)),
                Card(title, money(Math.round(slip.net).toDouble()), rows, listOf(Act("My pay", "say", "What's my next payslip? Show it in full."), undo), good = true,
                    note = "Payslip ${pa.format(dayFmt)} – ${pb.format(dayFmt)} · ${"%.1f".format(Locale.ROOT, slip.hours).removeSuffix(".0")}h · paid ${Pay.payday(pb).format(dayFmt)}"),
                "Saved ${set.size} shift${if (set.size == 1) "" else "s"}${if (worked.isNotEmpty()) " and ${worked.size} clock-in times" else ""}.")
        },

        Tool("remove_shifts", "Removes the work shifts on the given dates (days off). Also removes them from the calendar.",
            obj("dates" to list("Dates to clear, YYYY-MM-DD", str("A date")), required = listOf("dates"))) { a ->
            val arr = a.optJSONArray("dates") ?: return@Tool fail("No dates given.")
            val planned = Shifts.all(activity)
            val days = (0 until arr.length()).mapNotNull { date(arr.optString(it)) }.filter { planned[it] != null }
            if (days.isEmpty()) return@Tool Out(ok("removed" to 0, "note" to "There were no shifts on those dates."), said = "There were no shifts on those dates.")
            if (days.size > 1 && !host.confirm("Remove ${days.size} shifts (${days.joinToString(", ") { it.format(dayFmt) }})?")) return@Tool Out(ok("removed" to 0, "note" to "The user said no."), said = "Left as they were.")
            days.forEach { Shifts.set(activity, it, null); Shifts.setActual(activity, it, null) }
            Work.changed(activity)
            Out(ok("removed" to days.size), Card("Removed", null, days.map { Card.Row(it.format(dayFmt), "day off") }), "Removed ${days.size}.")
        },

        Tool("clock", "Clocks the user in or out of work (the real times worked, used for pay).",
            obj("action" to choice("in or out", "in", "out"), "time" to str("HH:MM if they gave a time; omit for now"), required = listOf("action"))) { a ->
            val at = time(a.s("time"))?.let { LocalDate.now().atTime(it) } ?: LocalDateTime.now()
            val r = if (a.optString("action") == "out") Work.clockOut(activity, at) else Work.clockIn(activity, at)
            Out(ok("done" to r.ok, "result" to r.said), said = r.said)
        },

        Tool("get_pay", "The user's pay, worked out exactly (basic, estimated TRONC, tax, NI, pension, take-home). With no dates: the payslip that's paid next. With dates: that stretch. extra_hours adds hypothetical hours ('what if I pick up Sunday').",
            obj("from" to str("YYYY-MM-DD"), "to" to str("YYYY-MM-DD"), "extra_hours" to num("Extra hours to add, for a what-if"))) { a ->
            val setup = Pay.setup(activity)
            val today = LocalDate.now()
            val from = date(a.s("from"))
            val to = date(a.s("to"))
            val (pa, pb) = if (from != null && to != null && !to.isBefore(from)) from to to else Pay.nextPaid(today, setup)
            val wholePeriod = Pay.periodOf(pa, setup).let { it.first == pa && it.second == pb }
            val extra = a.optDouble("extra_hours", 0.0).takeIf { !it.isNaN() && it > 0 } ?: 0.0
            // A whole period you've a real payslip for is the payslip (unless it's a what-if).
            var slip = if (wholePeriod && extra == 0.0) Pay.period(activity, pa, pb, setup) else if (wholePeriod) Pay.slipFor(activity, pa, pb, setup) else Pay.span(activity, pa, pb, setup)
            val without = slip
            if (extra > 0) slip = Pay.slip(slip.basic + extra * setup.basicRate, slip.tronc + extra * setup.troncRate, slip.hours + extra, setup, if (wholePeriod) setup.periods else (364 / (ChronoUnit.DAYS.between(pa, pb) + 1).coerceAtLeast(1)).toInt().coerceIn(1, 52))
            val shifts = Shifts.worked(activity).filterKeys { !it.isBefore(pa) && !it.isAfter(pb) }
            val done = shifts.filter { (d, s) -> s.finish(d, zone) <= System.currentTimeMillis() }.values.sumOf { it.minutes } / 60.0
            val json = ok("from" to pa.toString(), "to" to pb.toString(), "is_whole_pay_period" to wholePeriod, "paid_on" to if (wholePeriod) Pay.payday(pb).toString() else null,
                "shifts" to shifts.size, "hours_worked_so_far" to Math.round(done * 10) / 10.0, "pay" to slipJson(slip), "hourly" to setup.hourly,
                "note" to if (slip.actual) "These are the real figures from the payslip." else "An estimate: TRONC varies${if (setup.troncLearnt) " (TRONC per hour is from the real payslips)" else ""}.")
            if (extra > 0) json.put("take_home_without_the_extra", Math.round(without.net * 100) / 100.0)
            val title = if (wholePeriod) "Payslip · paid ${Pay.payday(pb).format(dayFmt)}" else "${pa.format(dayFmt)} – ${pb.format(dayFmt)}"
            Out(json, Card((if (slip.actual) "✓ " else "") + title + if (extra > 0) " · with ${"%.1f".format(Locale.ROOT, extra).removeSuffix(".0")}h more" else "", money(slip.net), slipRows(slip) + Card.Row("Take-home", money(slip.net)), good = true),
                "About ${money(slip.net)} take-home.")
        },

        Tool("save_payslip", "Saves one of the user's real payslips (a payslip PDF or photo they've attached), so the Money widget shows what they were really paid and estimates learn from it. Read the figures for THIS PERIOD only (not year to date). Amounts are positive numbers.",
            obj("from" to str("Period start, YYYY-MM-DD"), "to" to str("Period end, YYYY-MM-DD"), "paid" to str("Payment date, YYYY-MM-DD"),
                "hours" to num("Basic hours"), "rate" to num("Basic hourly rate"), "basic" to num("Basic pay"), "tronc" to num("TRONC"), "bonus" to num("Bonus"),
                "gross" to num("Gross"), "tax" to num("PAYE tax"), "ni" to num("National Insurance"), "pension" to num("Pension contribution"),
                "student_loan" to num("Student loan deduction"), "advance" to num("Advance deduction"), "take_home" to num("Take home pay"),
                required = listOf("from", "to", "take_home"))) { a ->
            // The PDF's own words when the phone can read them: exact, whatever was read off the picture.
            val fromPdf = host.attachments().reversed().filter { it.isPdf }.firstNotNullOfOrNull { f -> DocText.pdfText(f)?.let { Payslips.parse(it) } }
            fun n(k: String) = a.optDouble(k, 0.0).takeIf { !it.isNaN() } ?: 0.0
            val p = fromPdf ?: run {
                val from = date(a.s("from")) ?: return@Tool fail("I couldn't read the pay period's dates.")
                val to = date(a.s("to")) ?: return@Tool fail("I couldn't read the pay period's dates.")
                Payslips.Payslip.checked(from, to, date(a.s("paid")) ?: Pay.payday(to), n("hours"), n("rate"), n("basic"), n("tronc"), n("bonus"),
                    a.optDouble("gross").takeIf { !it.isNaN() && it > 0 }, n("tax"), n("ni"), n("pension"), n("student_loan"), n("advance"), n("take_home"))
            } ?: return@Tool fail("Those figures don't add up (take-home more than the gross, or the dates the wrong way round). Read the payslip again.")
            val setup = Pay.setup(activity)
            val estimate = Pay.slipFor(activity, p.from, p.to, setup)
            val tracked = estimate.hours
            val troncBefore = setup.troncRate
            Payslips.save(activity, p)
            Work.changed(activity)
            val after = Pay.setup(activity)
            val notes = listOfNotNull(
                "Estimated ${money(estimate.net)} · real ${money(p.takeHome)}".takeIf { estimate.hours > 0 },
                "TRONC now ${money(after.troncRate)}/h from your payslips (was ${money(troncBefore)})".takeIf { kotlin.math.abs(after.troncRate - troncBefore) > .005 },
                "Student loan ${money(p.studentLoan)} on this one: not counted in estimates (switch on in ⚙ if it's right)".takeIf { p.studentLoan > 0 && after.loan == null },
                "Hours: payslip ${"%.1f".format(Locale.ROOT, p.hours)}h, your clocked shifts ${"%.1f".format(Locale.ROOT, tracked)}h".takeIf { p.hours > 0 && tracked > 0 && kotlin.math.abs(p.hours - tracked) >= .25 },
            )
            Out(ok("saved" to true, "read_from" to if (fromPdf != null) "the PDF's text" else "your reading", "payslip" to p.json(), "estimate_was" to Math.round(estimate.net * 100) / 100.0,
                "tronc_per_hour_now" to after.troncRate, "student_loan_in_estimates" to (after.loan != null)),
                Card("✓ Payslip saved · paid ${p.paid.format(dayFmt)}", money(p.takeHome), slipRows(p.slip()) + Card.Row("Take-home", money(p.takeHome)), good = true,
                    note = notes.joinToString(" · ").ifBlank { null }),
                "Saved your payslip for ${Payslips.label(p)}: ${money(p.takeHome)} take-home.")
        },

        Tool("get_payslips", "The user's real payslips saved so far (newest first), with take-home and the main lines.", obj()) { _ ->
            val list = Payslips.all(activity).reversed()
            if (list.isEmpty()) return@Tool Out(ok("payslips" to JSONArray(), "note" to "None saved yet: they can share a payslip PDF to you."), said = "No payslips saved yet.")
            Out(ok("payslips" to JSONArray(list.take(12).map { it.json() })),
                Card("Your payslips", null, list.take(8).map { Card.Row("Paid ${it.paid.format(dayFmt)}", money(it.takeHome)) }), "${list.size} payslips saved.")
        },

        Tool("set_balance", "Saves how much money the user has left now ('I've got 300 left till payday'), so the Money widget shows what that is a day until payday. Spending they log afterwards comes off it. amount 0 with clear=true removes it.",
            obj("amount" to num("Money left, in pounds"), "clear" to bool("Remove the balance"), required = listOf("amount"))) { a ->
            if (a.optBoolean("clear")) {
                Spending.setBalance(activity, null)
                com.eldablos.crimson.money.MoneyWidget.refresh(activity)
                return@Tool Out(ok("cleared" to true), said = "Cleared.")
            }
            val amount = a.optDouble("amount").takeIf { !it.isNaN() && it >= 0 } ?: return@Tool fail("I couldn't read the amount.")
            Spending.setBalance(activity, amount)
            com.eldablos.crimson.money.MoneyWidget.refresh(activity)
            val setup = Pay.setup(activity)
            val payday = Pay.payday(Pay.nextPaid(LocalDate.now(), setup).second)
            val per = Spending.perDay(amount, LocalDate.now(), payday)
            val days = ChronoUnit.DAYS.between(LocalDate.now(), payday).coerceAtLeast(1)
            Out(ok("left" to amount, "payday" to payday.toString(), "days" to days, "per_day" to Math.round(per * 100) / 100.0),
                Card("Until payday", money(Math.round(per * 100) / 100.0) + "/day", listOf(Card.Row("Left now", money(amount)), Card.Row("Payday", "${payday.format(dayFmt)} · $days day${if (days == 1L) "" else "s"}")),
                    note = "On the Money widget now. Tell me what you spend and it comes off."),
                "That's ${money(per)} a day until ${payday.format(dayFmt)}.")
        },

        Tool("set_work_alarms", "Sets the user's work alarms: Crimson's own alarms (not the Clock app), which follow their shifts using their routine (how long before, how many, how far apart). With no date: turns on automatic work alarms for every shift and shows the next one's. With a date: makes sure that shift has them. Moving or removing shifts moves or removes the alarms by itself, so there are never duplicates. Call it straight away without asking.",
            obj("date" to str("The shift's date, YYYY-MM-DD; omit for every shift (and the next one shown)"))) { a ->
            val planned = Shifts.all(activity)
            val asked = date(a.s("date"))
            if (asked != null) {
                if (planned[asked] == null) return@Tool fail("There's no shift on ${asked.format(dayFmt)}.")
                Alarms.want(activity, asked)
                if (asked in Alarms.skipped(activity)) Alarms.setSkipped(activity, asked, false)
            } else Alarms.set(activity, "auto", true)
            Alarms.syncWork(activity)
            val d = asked ?: Shifts.next(planned, System.currentTimeMillis(), zone)?.first ?: return@Tool fail("There are no shifts coming up to set alarms for.")
            val shift = planned[d] ?: return@Tool fail("There's no shift on ${d.format(dayFmt)}.")
            val set = Alarms.forShift(activity, d)
            if (set.isEmpty()) return@Tool fail("The alarms for ${d.format(dayFmt)}'s shift would already have gone off.")
            if (!Reminders.canNotify(activity)) host.requestPermission(Manifest.permission.POST_NOTIFICATIONS)
            val bits = arrayListOf(Nudges.headsLine(activity, d, shift))
            val when_ = dayName(d).replaceFirstChar { it.uppercase() }
            Out(ok("set" to JSONArray(set.map { it.time.format(hm) }), "for_shift" to "$d ${shift.start.format(hm)}", "automatic_for_all_shifts" to Alarms.auto(activity), "morning" to bits.joinToString("; ")),
                Card("✓ ${set.size} alarm${if (set.size == 1) "" else "s"} set · $when_", set.first().time.format(hm), emptyList(), listOf(Act("Alarms", "alarms")), good = true,
                    chips = set.map { it.time.format(hm) }, note = bits.joinToString(" · ")),
                "Done: ${set.size} alarm${if (set.size == 1) "" else "s"} for ${dayName(d)}'s ${shift.start.format(hm)} shift, from ${set.first().time.format(hm)}." +
                    if (asked == null) " They'll follow your shifts from now on." else "")
        },

        Tool("skip_work_alarms", "Turns off the work alarms for one shift ('no alarms Saturday', 'I'm up already'), or back on with on=true.",
            obj("date" to str("The shift's date, YYYY-MM-DD"), "on" to bool("true to turn them back on"), required = listOf("date"))) { a ->
            val d = date(a.s("date")) ?: return@Tool fail("I couldn't read the date.")
            val on = a.optBoolean("on")
            Alarms.setSkipped(activity, d, !on)
            Out(ok("date" to d.toString(), "alarms_on" to on), said = if (on) "Work alarms back on for ${dayName(d)}." else "No work alarms for ${dayName(d)}.")
        },

        Tool("set_alarm", "Sets a real, ringing alarm (Crimson's alarm clock) for anything: waking up, taking medicine, leaving for an appointment, a reminder that must ring loudly. Once (the next time it's that time, or on a given date) or on chosen days. Always give it a short label saying what it's for.",
            obj("time" to str("HH:MM, 24-hour"), "label" to str("What it's for, e.g. Dentist, Take meds"), "date" to str("YYYY-MM-DD for a particular day (omit for the next time it comes round)"), "days" to list("Days it repeats on (omit for once)", choice("A day", "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY")),
                required = listOf("time"))) { a ->
            val t = time(a.s("time")) ?: return@Tool fail("I couldn't read that time.")
            val days = a.optJSONArray("days")?.let { arr -> (0 until arr.length()).mapNotNull { runCatching { java.time.DayOfWeek.valueOf(arr.getString(it).uppercase()) }.getOrNull() } }.orEmpty().toSet()
            val on = date(a.s("date"))
            if (on != null && on.atTime(t).isBefore(LocalDateTime.now())) return@Tool fail("${on.format(dayFmt)} at ${t.format(hm)} has already passed.")
            val al = Alarms.addOwn(activity, t, a.s("label") ?: "Alarm", days, on)
            if (!Reminders.canNotify(activity)) host.requestPermission(Manifest.permission.POST_NOTIFICATIONS)
            val undo = undoable { Alarms.remove(activity, al.id); "Removed the ${t.format(hm)} alarm." }
            Out(ok("set" to t.format(hm), "rings" to Alarms.describe(al)), Card("✓ Alarm set", t.format(hm), listOf(Card.Row(al.label, Alarms.describe(al).substringAfter(' '))), listOf(Act("Alarms", "alarms"), undo), good = true),
                "Alarm set for ${Alarms.describe(al)}.")
        },

        Tool("list_alarms", "The user's alarms (work ones and their own): times, what for, on or off.", obj()) { _ ->
            val all = Alarms.all(activity)
            Out(ok("alarms" to JSONArray(all.map { ok("time" to it.time.format(hm), "when" to Alarms.describe(it), "label" to it.label, "on" to it.enabled, "kind" to it.kind) })),
                Card("Your alarms", Alarms.next(activity)?.let { java.time.Instant.ofEpochMilli(it.due()).atZone(zone).toLocalTime().format(hm) },
                    all.filter { it.enabled }.take(10).map { Card.Row(it.label, Alarms.describe(it)) }.ifEmpty { listOf(Card.Row("None on", "")) }, listOf(Act("Alarms", "alarms"))),
                "${all.count { it.enabled }} alarms on.")
        },

        Tool("cancel_alarm", "Switches off one of the user's own alarms by its time (HH:MM).", obj("time" to str("HH:MM"), required = listOf("time"))) { a ->
            val t = time(a.s("time")) ?: return@Tool fail("I couldn't read that time.")
            val hit = Alarms.all(activity).firstOrNull { it.kind != "work" && it.time == t && it.enabled } ?: return@Tool fail("There's no alarm of yours at ${t.format(hm)} switched on.")
            Alarms.setEnabled(activity, hit.id, false)
            Out(ok("off" to t.format(hm)), said = "Switched off the ${t.format(hm)} alarm.")
        },

        Tool("set_timer", "Starts a countdown timer in the Clock app.", obj("minutes" to num("How long, in minutes"), "label" to str("What it's for"), required = listOf("minutes"))) { a ->
            val minutes = a.optDouble("minutes", 0.0).takeIf { !it.isNaN() && it > 0 } ?: return@Tool fail("How long should the timer be?")
            val secs = (minutes * 60).toInt().coerceIn(1, 24 * 3600)
            if (!start(Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, secs).putExtra(AlarmClock.EXTRA_SKIP_UI, true).putExtra(AlarmClock.EXTRA_MESSAGE, a.s("label") ?: "Timer")))
                return@Tool fail("The Clock app wouldn't start the timer.")
            Out(ok("started_seconds" to secs), said = "Timer started.")
        },

        Tool("show_alarms", "Opens Crimson's alarms screen (work alarms, the user's own, sound and volume).", obj()) {
            start(Intent(activity, com.eldablos.crimson.alarm.AlarmsActivity::class.java))
            Out(ok("opened" to true), said = "Opened your alarms.")
        },

        Tool("add_reminder", "Sets a reminder: a notification on the phone at that time (not a calendar event). Can repeat.",
            obj("text" to str("What to remind them of"), "at" to str("When: YYYY-MM-DDTHH:MM, local time"), "repeat" to choice("How it repeats", "none", "daily", "weekdays", "weekly"), required = listOf("text", "at"))) { a ->
            val at = runCatching { LocalDateTime.parse(a.optString("at").take(16)) }.getOrNull() ?: return@Tool fail("I couldn't read the time \"${a.optString("at")}\".")
            val repeat = a.s("repeat") ?: "none"
            if (repeat == "none" && !at.isAfter(LocalDateTime.now())) return@Tool fail("That time has already passed.")
            val first = if (at.isAfter(LocalDateTime.now())) at else Reminders.next(at, repeat, LocalDateTime.now()) ?: at
            val r = Reminders.add(activity, a.optString("text"), first, repeat)
            if (!Reminders.canNotify(activity)) host.requestPermission(Manifest.permission.POST_NOTIFICATIONS)
            val undo = undoable { Reminders.cancel(activity, r.id); "Reminder cancelled." }
            Out(ok("id" to r.id, "when" to Reminders.describe(r), "notifications_allowed" to Reminders.canNotify(activity)),
                Card("✓ Reminder set", null, listOf(Card.Row(r.text, Reminders.describe(r))), listOf(Act("My reminders", "say", "What reminders have I got?"), undo), good = true),
                "I'll remind you ${Reminders.describe(r)}.")
        },

        Tool("list_reminders", "The reminders that are set.", obj()) {
            val all = Reminders.all(activity)
            Out(ok("reminders" to JSONArray(all.map { ok("id" to it.id, "text" to it.text, "when" to Reminders.describe(it)) })),
                if (all.isEmpty()) null else Card("Reminders", "${all.size}", all.map { Card.Row(it.text, Reminders.describe(it)) }), if (all.isEmpty()) "No reminders set." else "${all.size} reminders.")
        },

        Tool("cancel_reminder", "Cancels a reminder, by its id or some of its words.", obj("which" to str("The reminder's id or words from it"), required = listOf("which"))) { a ->
            val r = Reminders.find(activity, a.optString("which")) ?: return@Tool fail("I couldn't find that reminder.")
            Reminders.cancel(activity, r.id)
            Out(ok("cancelled" to r.text), said = "Cancelled \"${r.text}\".")
        },

        Tool("get_calendar", "Events in the user's calendar between two dates (defaults to the next 7 days).", obj("from" to str("YYYY-MM-DD"), "to" to str("YYYY-MM-DD"))) { a ->
            if (activity.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
                host.requestPermission(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
                return@Tool fail("Calendar access isn't allowed yet: I've asked for it. Try again once it's allowed.")
            }
            val from = date(a.s("from")) ?: LocalDate.now()
            val to = (date(a.s("to")) ?: from.plusDays(7)).coerceAtMost(from.plusDays(92))
            val out = JSONArray()
            val rows = ArrayList<Card.Row>()
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().appendPath(from.atStartOfDay(zone).toInstant().toEpochMilli().toString())
                .appendPath(to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli().toString()).build()
            activity.contentResolver.query(uri, arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.EVENT_LOCATION), null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { c ->
                while (c.moveToNext() && out.length() < 60) {
                    val allDay = c.getInt(3) == 1
                    val begin = java.time.Instant.ofEpochMilli(c.getLong(1)).atZone(if (allDay) java.time.ZoneOffset.UTC else zone)
                    val end = java.time.Instant.ofEpochMilli(c.getLong(2)).atZone(if (allDay) java.time.ZoneOffset.UTC else zone)
                    val title = c.getString(0).orEmpty().ifBlank { "(no title)" }
                    out.put(ok("title" to title, "date" to begin.toLocalDate().toString(), "start" to if (allDay) "all day" else begin.format(hm), "end" to if (allDay) null else end.format(hm), "where" to c.getString(4)))
                    if (rows.size < 10) rows += Card.Row(title, begin.toLocalDate().format(dayFmt) + if (allDay) "" else " · ${begin.format(hm)}")
                }
            }
            Out(ok("events" to out), if (rows.isEmpty()) null else Card("Calendar", null, rows), if (out.length() == 0) "Nothing in the calendar then." else "${out.length()} events.")
        },

        Tool("add_calendar_event", "Adds an event to the user's own calendar (appointments, plans). For work shifts use set_shifts instead; for nudges use add_reminder.",
            obj("title" to str("What it is"), "start" to str("YYYY-MM-DDTHH:MM, or YYYY-MM-DD for an all-day event"), "end" to str("YYYY-MM-DDTHH:MM; omit for one hour"), "where" to str("Place"), required = listOf("title", "start"))) { a ->
            if (!Shifts.canWrite(activity)) {
                host.requestPermission(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
                return@Tool fail("Calendar access isn't allowed yet: I've asked for it. Try again once it's allowed.")
            }
            val raw = a.optString("start")
            val allDay = raw.length <= 10
            val begin = if (allDay) date(raw)?.atStartOfDay() else runCatching { LocalDateTime.parse(raw.take(16)) }.getOrNull()
            begin ?: return@Tool fail("I couldn't read the start \"$raw\".")
            // An all-day event ends the morning after its last day; a timed one must end after it starts.
            val end = if (allDay) (date(a.s("end"))?.takeIf { !it.isBefore(begin.toLocalDate()) } ?: begin.toLocalDate()).plusDays(1).atStartOfDay()
                else a.s("end")?.let { runCatching { LocalDateTime.parse(it.take(16)) }.getOrNull() }?.takeIf { it.isAfter(begin) } ?: begin.plusHours(1)
            val cal = Shifts.calendars(activity).let { list -> list.firstOrNull { it.primary } ?: list.firstOrNull() } ?: return@Tool fail("There's no calendar on the phone to add to.")
            val v = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, cal.id)
                put(CalendarContract.Events.TITLE, a.optString("title"))
                a.s("where")?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
                if (allDay) {
                    put(CalendarContract.Events.ALL_DAY, 1)
                    put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
                    put(CalendarContract.Events.DTSTART, begin.toLocalDate().atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli())
                    put(CalendarContract.Events.DTEND, end.toLocalDate().atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli())
                } else {
                    put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
                    put(CalendarContract.Events.DTSTART, begin.atZone(zone).toInstant().toEpochMilli())
                    put(CalendarContract.Events.DTEND, end.atZone(zone).toInstant().toEpochMilli())
                }
            }
            val uri = activity.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v) ?: return@Tool fail("The calendar wouldn't take it.")
            val lastDay = end.toLocalDate().minusDays(1)
            val whenText = begin.toLocalDate().format(dayFmt) + if (allDay) (if (lastDay.isAfter(begin.toLocalDate())) " – ${lastDay.format(dayFmt)}" else " · all day") else " · ${begin.format(hm)} – ${end.format(hm)}"
            Out(ok("added" to a.optString("title"), "when" to whenText, "calendar" to cal.name),
                Card("✓ In your calendar", null, listOf(Card.Row(a.optString("title"), whenText)), listOf(Act("Open it", "view", "$uri|"),
                    undoable { activity.contentResolver.delete(uri, null, null); "Taken out of your calendar." }), good = true), "Added to your calendar.")
        },

        Tool("notes", "The user's notes / to-do list: list them, add one, tick one done, untick, or delete one.",
            obj("action" to choice("What to do", "list", "add", "tick", "untick", "delete"), "text" to str("The note's text (to add), or its number or some words from it (to tick, untick or delete)"), required = listOf("action"))) { a ->
            val text = a.s("text")
            when (a.optString("action")) {
                "add" -> { Lists.addNote(activity, text ?: return@Tool fail("What should the note say?")); Out(ok("added" to text), said = "Noted: $text") }
                "tick", "untick", "delete" -> {
                    val line = Lists.findNote(activity, text ?: return@Tool fail("Which note?")) ?: return@Tool fail("I couldn't find a note like \"$text\".")
                    val shown = line.removePrefix(Lists.DONE)
                    when (a.optString("action")) {
                        "delete" -> { Lists.removeNote(activity, line); Out(ok("deleted" to shown), said = "Deleted \"$shown\".") }
                        "tick" -> { Lists.tickNote(activity, line, true); Out(ok("ticked" to shown), said = "Ticked off \"$shown\".") }
                        else -> { Lists.tickNote(activity, line, false); Out(ok("unticked" to shown), said = "Unticked \"$shown\".") }
                    }
                }
                else -> {
                    val all = Lists.notes(activity)
                    Out(ok("notes" to JSONArray(all.mapIndexed { i, n -> ok("number" to i + 1, "text" to n.removePrefix(Lists.DONE), "done" to n.startsWith(Lists.DONE)) })),
                        if (all.isEmpty()) null else Card("Notes", null, all.mapIndexed { i, n -> Card.Row("${i + 1}. ${n.removePrefix(Lists.DONE)}", if (n.startsWith(Lists.DONE)) "✓" else "") }), if (all.isEmpty()) "No notes." else "${all.size} notes.")
                }
            }
        },

        Tool("habits", "The user's daily habits: list them with today's state and streaks, tick or untick one for today, add or remove one.",
            obj("action" to choice("What to do", "list", "tick", "untick", "add", "remove"), "name" to str("The habit's name (or part of it)"), required = listOf("action"))) { a ->
            val today = LocalDate.now()
            val all = Lists.habits(activity)
            val name = a.s("name")
            fun match() = name?.let { n -> all.firstOrNull { it.name.equals(n, true) } ?: all.firstOrNull { it.name.contains(n, true) || n.contains(it.name, true) } }
            when (a.optString("action")) {
                "add" -> { Lists.setHabits(activity, all + Lists.Habit(name ?: return@Tool fail("What's the habit called?"), emptySet())); Out(ok("added" to name), said = "Added the habit \"$name\".") }
                "remove" -> {
                    val h = match() ?: return@Tool fail("I couldn't find that habit.")
                    if (!host.confirm("Remove the habit \"${h.name}\" and its history?")) return@Tool Out(ok("removed" to false), said = "Left it.")
                    Lists.setHabits(activity, all.filter { it !== h }); Out(ok("removed" to h.name), said = "Removed \"${h.name}\".")
                }
                "tick", "untick" -> {
                    val h = match() ?: return@Tool fail("I couldn't find a habit like \"$name\". They are: ${all.joinToString(", ") { it.name }}.")
                    val done = a.optString("action") == "tick"
                    val updated = Lists.Habit(h.name, if (done) h.done + today else h.done - today)
                    Lists.setHabits(activity, all.map { if (it === h) updated else it })
                    Out(ok("habit" to h.name, "done_today" to done, "streak" to Lists.streak(updated.done, today)), said = if (done) "Ticked \"${h.name}\": ${Lists.streak(updated.done, today)} days running." else "Unticked \"${h.name}\".")
                }
                else -> Out(ok("habits" to JSONArray(all.map { ok("name" to it.name, "done_today" to (today in it.done), "streak_days" to Lists.streak(it.done, today)) })),
                    if (all.isEmpty()) null else Card("Habits", "${all.count { today in it.done }} of ${all.size}", all.map { Card.Row(it.name, if (today in it.done) "✓ done" else "to do") }), "${all.count { today in it.done }} of ${all.size} done today.")
            }
        },

        Tool("countdowns", "Countdowns to dates the user cares about: list them, add one, or remove one.",
            obj("action" to choice("What to do", "list", "add", "remove"), "name" to str("What it's for"), "date" to str("YYYY-MM-DD (to add)"), required = listOf("action"))) { a ->
            val all = Lists.countdowns(activity)
            val today = LocalDate.now()
            when (a.optString("action")) {
                "add" -> {
                    val d = date(a.s("date")) ?: return@Tool fail("What date is it?")
                    val name = a.s("name") ?: return@Tool fail("What's it for?")
                    Lists.setCountdowns(activity, all.filter { !it.name.equals(name, true) } + Lists.Countdown(name, d, today))
                    Out(ok("added" to name, "days_to_go" to ChronoUnit.DAYS.between(today, d)), said = "${ChronoUnit.DAYS.between(today, d)} days until $name.")
                }
                "remove" -> {
                    val c = a.s("name")?.let { n -> all.firstOrNull { it.name.contains(n, true) } } ?: return@Tool fail("I couldn't find that countdown.")
                    Lists.setCountdowns(activity, all.filter { it !== c }); Out(ok("removed" to c.name), said = "Removed the countdown to ${c.name}.")
                }
                else -> {
                    val up = all.filter { !it.date.isBefore(today) }
                    Out(ok("countdowns" to JSONArray(up.map { ok("name" to it.name, "date" to it.date.toString(), "days_to_go" to ChronoUnit.DAYS.between(today, it.date)) })),
                        if (up.isEmpty()) null else Card("Countdowns", null, up.map { Card.Row(it.name, "${ChronoUnit.DAYS.between(today, it.date)} days") }), if (up.isEmpty()) "No countdowns." else "${up.size} countdowns.")
                }
            }
        },

        Tool("spending", "What the user has spent: log something they bought, or total it up between two dates (defaults to this week).",
            obj("action" to choice("What to do", "add", "list"), "amount" to num("Pounds spent (to add)"), "what" to str("What it was on (to add)"), "from" to str("YYYY-MM-DD"), "to" to str("YYYY-MM-DD"), required = listOf("action"))) { a ->
            if (a.optString("action") == "add") {
                val amount = a.optDouble("amount", 0.0).takeIf { !it.isNaN() && it > 0 } ?: return@Tool fail("How much was it?")
                val item = Spending.add(activity, amount, a.s("what") ?: "something")
                val week = Spending.between(activity, Shifts.monday(LocalDate.now()), LocalDate.now()).sumOf { it.amount }
                return@Tool Out(ok("logged" to money(amount), "category" to item.category, "this_week_total" to money(week)), said = "Logged ${money(amount)} on ${item.what}. ${money(week)} this week.")
            }
            val from = date(a.s("from")) ?: Shifts.monday(LocalDate.now())
            val to = date(a.s("to")) ?: LocalDate.now()
            val items = Spending.between(activity, from, to)
            val kinds = Spending.byKind(items)
            Out(ok("from" to from.toString(), "to" to to.toString(), "total" to money(items.sumOf { it.amount }), "by_kind" to JSONArray(kinds.map { ok("kind" to it.first, "total" to money(it.second)) }),
                "items" to JSONArray(items.takeLast(40).map { ok("date" to it.date.toString(), "amount" to money(it.amount), "what" to it.what) })),
                if (items.isEmpty()) null else Card("Spent · ${from.format(dayFmt)} – ${to.format(dayFmt)}", money(items.sumOf { it.amount }), kinds.map { Card.Row(it.first, money(it.second)) }), "${money(items.sumOf { it.amount })} spent.")
        },

        Tool("search_files", "Searches the files on the user's phone (documents, PDFs, spreadsheets, photos, screenshots, videos, downloads) by name, kind, date words ('last week', 'October') and the text inside Word, Excel and text files. Returns numbered results.",
            obj("query" to str("What to look for, in plain words: e.g. 'payslip pdf october', 'screenshots yesterday'"), required = listOf("query"))) { a ->
            val q = a.optString("query")
            val list = runCatching { Files.search(activity, q, 8) }.getOrDefault(emptyList())
            found = list
            val full = Files.fullAccess()
            val json = ok("results" to JSONArray(list.mapIndexed { i, f -> ok("number" to i + 1, "name" to f.name, "kind" to f.kind, "folder" to f.folder, "modified" to f.date.toString(), "size_kb" to f.size / 1024) }),
                "can_see_all_files" to full)
            if (!full) json.put("note", "Only photos, videos, music and this app's own files are visible until the user allows 'All files access' (button on the card).")
            val acts = ArrayList<Act>()
            if (!full) acts += Act("Allow all files", "intent", "files")
            Out(json, if (list.isEmpty() && full) null else Card(if (list.isEmpty()) "Nothing found" else "Files", if (list.isEmpty()) null else "${list.size}",
                list.map { f -> Card.Row(f.name, "${f.kind} · ${f.date.format(dayFmt)}", Act("Open", "view", "${f.uri}|${f.mime}")) }, acts), if (list.isEmpty()) "Nothing found." else "Found ${list.size}.")
        },

        Tool("read_file", "Reads a file so you can answer questions about it, summarise it or check it: one of the numbered search results, or a file the user attached to the chat (number 0 = the latest attachment). PDFs and pictures are read too.",
            obj("number" to int("The file's number from search_files, or 0 for the latest attachment"), "question" to str("What to find out from it; omit to get its full text"), required = listOf("number"))) { a ->
            val n = a.optInt("number", 0)
            val picked: DocText.Picked = if (n <= 0) host.attachments().lastOrNull() ?: return@Tool fail("Nothing's attached to the chat.")
            else found.getOrNull(n - 1)?.let { f -> DocText.copy(activity, f.uri, f.mime) } ?: return@Tool fail("There's no result number $n: search first.")
            if (picked.file.length() > 7_000_000) return@Tool fail("That file's too big to read (over 7 MB).")
            val text = DocText.text(picked)
            if (text != null) return@Tool Out(ok("name" to picked.name, "text" to text.take(24_000), "cut_short" to (text.length > 24_000)), said = "Read ${picked.name}.")
            if (Gemini.key(activity) == null) return@Tool fail("Reading PDFs and pictures needs Gemini (a key and a connection).")
            val mime = if (picked.isPdf) "application/pdf" else picked.mime.takeIf { it.contains('/') && !it.endsWith("*") } ?: "image/jpeg"
            val ask = a.s("question") ?: "Write out everything in this file as plain text: every heading, figure, date, name and table row, in order. Don't summarise."
            val answer = Gemini.ask(activity, "You read files for an assistant. Be exact with every number, date and name. Plain text, no commentary.",
                JSONArray().put(Gemini.inline(picked.file.readBytes(), mime)).put(JSONObject().put("text", ask))) ?: return@Tool fail("I couldn't read that file just now.")
            Out(ok("name" to picked.name, "text" to answer.take(24_000)), said = "Read ${picked.name}.")
        },

        Tool("open_file", "Opens one of the numbered search results on the phone, or shares it to another app.",
            obj("number" to int("The file's number from search_files"), "share" to bool("True to share it instead of opening")) ) { a ->
            val f = found.getOrNull(a.optInt("number", 1) - 1) ?: return@Tool fail("There's no result with that number: search first.")
            activity.runOnUiThread { if (a.optBoolean("share")) Files.share(activity, f) else Files.open(activity, f) }
            Out(ok("opened" to f.name), said = "Opened ${f.name}.")
        },

        Tool("weather", "The weather forecast where the user is, for a date (defaults to today): highs, lows, rain chance, and through the day.", obj("date" to str("YYYY-MM-DD"))) { a ->
            if (DashboardStore.place(activity) == null) return@Tool fail("No city is set for the weather yet: it's set in the Crimson Widget app.")
            val f = Forecast.cached(activity)?.takeIf { System.currentTimeMillis() - it.fetchedAt < 45 * 60_000L } ?: runCatching { Forecast.fetch(activity) }.getOrNull() ?: Forecast.cached(activity)
                ?: return@Tool fail("I couldn't get the forecast just now.")
            val d = date(a.s("date")) ?: LocalDate.now()
            val day = f.dayOf(d) ?: return@Tool fail("The forecast doesn't reach ${d.format(dayFmt)} (it covers about a week).")
            val hours = f.hoursOf(d).filter { it.time.hour % 3 == 0 }
            val json = ok("date" to d.toString(), "summary" to Weather.describe(day.code, true).label, "high_c" to day.high, "low_c" to day.low, "rain_chance_percent" to day.rain, "wind_max_kmh" to day.windMax,
                "through_the_day" to JSONArray(hours.map { ok("time" to it.time.format(hm), "temp_c" to it.temp, "rain_percent" to it.rain, "sky" to Weather.describe(it.code, it.isDay).label) }))
            if (d == LocalDate.now()) json.put("now", ok("temp_c" to f.temp, "feels_like_c" to f.feels, "sky" to Weather.describe(f.code, f.isDay).label))
            Out(json, Card("Weather · ${dayName(d)}", "${day.low}° – ${day.high}°", listOf(Card.Row(Weather.describe(day.code, true).label, "rain ${day.rain}%")) +
                hours.filter { it.time.hour in 6..21 }.map { Card.Row(it.time.format(hm), "${it.temp}° · rain ${it.rain}%") }), "${day.low}° to ${day.high}°, rain ${day.rain}%.")
        },

        Tool("tube", "London Underground, Elizabeth line, Overground and DLR status from TfL for a date (defaults to today), including planned closures; days > 1 covers a stretch (a weekend, a week). The user's own line comes first.",
            obj("date" to str("YYYY-MM-DD"), "days" to int("How many days from that date (1 to 14)"))) { a ->
            val d = date(a.s("date")) ?: LocalDate.now()
            val days = a.optInt("days", 1).coerceIn(1, 14)
            val r = Travel.report(activity, d, fetch = true, days = days) ?: return@Tool fail("TfL didn't answer just now.")
            val commute = Travel.commute(activity, d)
            val mine = commute.mapNotNull { id -> r.lines.firstOrNull { it.id == id } }
            val trouble = r.lines.filter { !it.fine && it.mode != "national-rail" }
            fun line(l: Travel.Line) = ok("line" to Travel.lineName(l), "status" to l.status, "why" to Travel.why(l).ifEmpty { null }, "when" to l.period.ifEmpty { null })
            Out(ok("date" to d.toString(), "days" to days, "users_lines" to JSONArray(mine.map { line(it) }), "disrupted" to JSONArray(trouble.map { line(it) }), "everything_else" to "good service"),
                Card("Tube · ${dayName(d)}${if (days > 1) " + ${days - 1} days" else ""}", if (trouble.isEmpty()) "All good" else "${trouble.size} disrupted",
                    mine.map { Card.Row(Travel.lineName(it), it.status) } + trouble.filter { it.id !in commute }.take(8).map { Card.Row(Travel.lineName(it), it.status + if (it.period.isNotEmpty()) " · ${it.period}" else "") },
                    listOf(Act("TfL status", "url", "https://tfl.gov.uk/tube-dlr-overground/status/")), good = trouble.isEmpty()), if (trouble.isEmpty()) "Good service everywhere." else "${trouble.size} lines disrupted.")
        },

        Tool("remember", "Saves a fact about the user for good (preferences, codes, people, corrections to how you should do things), so you know it in every later conversation.",
            obj("key" to str("A short name for it, e.g. 'locker code', 'manager'"), "value" to str("The fact itself"), required = listOf("key", "value"))) { a ->
            Memory.remember(activity, a.optString("key"), a.optString("value"))
            Out(ok("remembered" to a.optString("key")), said = "I'll remember that.")
        },

        Tool("forget", "Forgets a saved fact, by its name.", obj("key" to str("The fact's name or part of it"), required = listOf("key"))) { a ->
            if (Memory.forget(activity, a.optString("key"))) Out(ok("forgotten" to a.optString("key")), said = "Forgotten.") else fail("I don't have anything saved like that.")
        },

        Tool("set_routine", "Changes the user's saved routine and pay settings: how many minutes before a shift the first alarm rings, how many alarms and the gap between them, how long the journey to work takes, and the hourly pay. Only pass what's changing.",
            obj("alarm_lead_minutes" to int("Minutes before the shift starts that the first alarm rings (2 hours = 120)"), "alarm_count" to int("How many alarms"), "alarm_gap_minutes" to int("Minutes between alarms"),
                "commute_minutes" to int("Minutes to get to work"), "hourly_pay" to num("Total pay per hour in pounds, including TRONC"))) { a ->
            fun i(k: String) = if (a.has(k) && !a.isNull(k)) a.optInt(k) else null
            Memory.setAlarmRoutine(activity, i("alarm_lead_minutes"), i("alarm_count"), i("alarm_gap_minutes"))
            Alarms.syncWork(activity)
            i("commute_minutes")?.let { Memory.setCommuteMinutes(activity, it) }
            a.optDouble("hourly_pay", 0.0).takeIf { !it.isNaN() && it in 5.0..200.0 }?.let { Memory.setHourlyRate(activity, it); com.eldablos.crimson.money.MoneyWidget.refresh(activity) }
            val example = Memory.alarmTimes(activity, LocalTime.of(7, 0))
            Out(ok("alarm_lead_minutes" to Memory.alarmLead(activity), "alarm_count" to Memory.alarmCount(activity), "alarm_gap_minutes" to Memory.alarmGap(activity), "commute_minutes" to Memory.commuteMinutes(activity),
                "hourly_pay" to Pay.setup(activity).hourly, "example_for_a_7am_shift" to JSONArray(example.map { it.format(hm) })),
                Card("Routine saved", null, listOf(Card.Row("Alarms", "${Memory.alarmCount(activity)}, every ${Memory.alarmGap(activity)} min"), Card.Row("First alarm", "${Work.hours(Memory.alarmLead(activity).toLong())} before the shift"),
                    Card.Row("For a 07:00 start", "${example.first().format(hm)} – ${example.last().format(hm)}"), Card.Row("Journey", "${Memory.commuteMinutes(activity)} min"), Card.Row("Pay", "${money(Pay.setup(activity).hourly)}/h")), good = true), "Saved.")
        },

        Tool("set_uni", "Sets or clears the user's university session on a date, or every week on that weekday.",
            obj("date" to str("YYYY-MM-DD (its weekday is used when every_week is true)"), "start" to str("HH:MM; omit to clear"), "end" to str("HH:MM"), "every_week" to bool("True for every week on that weekday"), required = listOf("date"))) { a ->
            val d = date(a.s("date")) ?: return@Tool fail("I couldn't read that date.")
            val st = time(a.s("start"))
            val en = time(a.s("end"))
            val session = if (st != null && en != null) Uni.Session(st, en) else null
            Uni.set(activity, d, session, a.optBoolean("every_week"))
            Thread { runCatching { Uni.sync(activity.applicationContext) }; com.eldablos.crimson.money.MoneyWidget.refresh(activity.applicationContext) }.start()
            Out(ok("uni" to (session?.times() ?: "cleared"), "every_week" to a.optBoolean("every_week")), said = if (session == null) "Uni cleared." else "Uni set: ${session.times()}.")
        },

        Tool("make_document", "Makes a document file (PDF, Word or Excel) and saves it to Downloads/Jarvis. Write the content yourself in simple markup: '# Title', '## Heading', '- ' bullets, '1. ' numbered, and tables as rows of cells split by '|' (first row = column names).",
            obj("kind" to choice("The file type", "pdf", "word", "excel"), "title" to str("The document's title"), "content" to str("The whole document in the simple markup"), required = listOf("kind", "title", "content"))) { a ->
            val kind = when (a.optString("kind")) { "word" -> Documents.Kind.WORD; "excel" -> Documents.Kind.EXCEL; else -> Documents.Kind.PDF }
            val doc = Documents.parse(a.optString("content"), a.optString("title").ifBlank { "Document" })
            val uri = Documents.save(activity, doc, kind) ?: return@Tool fail("I couldn't save the file.")
            Out(ok("saved" to Documents.fileName(doc, kind), "folder" to "Downloads/Jarvis"),
                Card("${kind.label.lowercase().replaceFirstChar { it.uppercase() }} ready", null, listOf(Card.Row(doc.title, "${doc.words} words")),
                    listOf(Act("Open", "view", "$uri|${kind.mime}"), Act("Share", "share", "$uri|${kind.mime}")), good = true), "Made \"${doc.title}\".")
        },

        Tool("send_message", "Opens WhatsApp, text messages or email with a message already written, for the user to check and send (you can't send it yourself).",
            obj("app" to choice("Which app", "whatsapp", "sms", "email"), "to" to str("Who to: a contact's name, a phone number, or an email address"), "subject" to str("Email subject"), "text" to str("The message"), required = listOf("app", "text"))) { a ->
            val text = a.optString("text")
            val to = a.s("to")
            val number = to?.let { phoneOf(it) }
            val okd = when (a.optString("app")) {
                "email" -> start(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + (to?.takeIf { it.contains('@') } ?: ""))).putExtra(Intent.EXTRA_SUBJECT, a.s("subject") ?: "").putExtra(Intent.EXTRA_TEXT, text))
                "sms" -> start(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + (number ?: ""))).putExtra("sms_body", text))
                else -> (number != null && start(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/${international(number)}?text=${Uri.encode(text)}")).setPackage("com.whatsapp"))) ||
                    start(Intent(Intent.ACTION_SEND).setType("text/plain").setPackage("com.whatsapp").putExtra(Intent.EXTRA_TEXT, text)) ||
                    start(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Send with"))
            }
            if (!okd) return@Tool fail("I couldn't open that app.")
            Out(ok("opened" to a.optString("app"), "recipient_found" to (number != null || to?.contains('@') == true), "note" to "The user still has to press send."), said = "It's ready to send.")
        },

        Tool("open_app", "Opens an app on the phone by name.", obj("name" to str("The app's name"), required = listOf("name"))) { a ->
            val name = a.optString("name").trim()
            val pm = activity.packageManager
            val apps = OpenAppActivity.launcherApps(activity).map { OpenAppActivity.appLabel(pm, it) to it }
            val hit = apps.firstOrNull { it.first.equals(name, true) } ?: apps.firstOrNull { it.first.startsWith(name, true) } ?: apps.firstOrNull { it.first.contains(name, true) }
                ?: return@Tool fail("There's no app called \"$name\" on the phone.")
            start(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClassName(hit.second.activityInfo.packageName, hit.second.activityInfo.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Out(ok("opened" to hit.first), said = "Opened ${hit.first}.")
        },

        Tool("web_search", "Looks something up on Google for current or factual information (news, prices, opening times, how-tos, anything after your training). Returns an answer with what it found.",
            obj("query" to str("What to find out, as a full question"), required = listOf("query"))) { a ->
            val answer = Gemini.ask(activity, "Answer from what you find on Google. Be accurate and specific: give dates, figures and names, and say where they're from. Plain text, short.",
                JSONArray().put(JSONObject().put("text", a.optString("query"))), search = true) ?: return@Tool fail("The search didn't work just now.")
            Out(ok("answer" to answer.take(6000)), said = answer.take(600))
        },
    )

    /** A phone number for [who]: the number itself, or the first mobile of the contact with that name (when contacts are allowed). */
    private fun phoneOf(who: String): String? {
        if (Regex("^[+\\d][\\d\\s()-]{6,}$").matches(who.trim())) return who.trim()
        if (who.contains('@')) return null
        if (activity.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            host.requestPermission(Manifest.permission.READ_CONTACTS)
            return null
        }
        return try {
            activity.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%${who.trim()}%"), null)?.use { c ->
                var best: String? = null
                while (c.moveToNext()) {
                    val name = c.getString(1).orEmpty()
                    if (name.equals(who.trim(), true)) return@use c.getString(0)
                    if (best == null) best = c.getString(0)
                }
                best
            }
        } catch (e: Exception) {
            null
        }
    }

    /** A number as WhatsApp wants it: digits with the country code ("07123 456789" is a UK number: 447123456789). */
    private fun international(number: String): String {
        val digits = number.filter { it.isDigit() }
        return when {
            digits.startsWith("00") -> digits.drop(2)
            digits.startsWith("0") -> "44" + digits.drop(1)
            else -> digits
        }
    }

    fun byName(name: String): Tool? = all.firstOrNull { it.name == name }

    /** The tools as Gemini's function declarations. */
    fun declarations(): JSONArray = JSONArray().also { a ->
        all.forEach { t ->
            val d = JSONObject().put("name", t.name).put("description", t.description)
            // A tool that takes nothing must leave its parameters out altogether (Gemini rejects an empty object).
            if ((t.params.optJSONObject("properties")?.length() ?: 0) > 0) d.put("parameters", t.params)
            a.put(d)
        }
    }

    /** Runs a tool by name, never throwing: a failure comes back as an error the model can explain. */
    fun run(name: String, args: JSONObject): Out {
        val tool = byName(name) ?: return fail("There's no tool called $name.")
        return try {
            tool.run(args)
        } catch (e: Exception) {
            com.eldablos.crimson.core.SysLog.error("Assistant", "$name failed: ${e.javaClass.simpleName} ${e.message.orEmpty().take(120)}")
            fail("That went wrong inside the app (${e.javaClass.simpleName}).")
        }
    }
}
