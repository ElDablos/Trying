package com.eldablos.crimson

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Process
import com.eldablos.crimson.OpenAppActivity.Companion.component
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * The games behind the Crimson Arcade widget: found automatically from the "game" category apps declare,
 * plus any the user added and minus any they hid, sorted A–Z. Also reads how long each was played, when
 * the user has given usage access, keeps each widget's selected game, and draws game icons as tiles.
 */
object Arcade {
    class Game(
        val component: ComponentName,
        val label: String,
        val installed: Long,
    ) {
        /** The letter it files under in the A–Z rail: digits and symbols go under "#". */
        val letter: Char get() = label.firstOrNull()?.uppercaseChar()?.takeIf { it in 'A'..'Z' } ?: '#'
    }

    class Played(val game: Game, val millis: Long, val lastUsed: Long)

    const val STATS_DAYS = 14
    private const val PLAYTIME_WEIGHT = 0.6f
    private const val LAST_PLAYED_DAYS = 365L
    private val NEW_FOR = TimeUnit.DAYS.toMillis(7)
    private const val CRIMSON = 0xFFB3182C.toInt()

    private fun prefs(context: Context) = context.getSharedPreferences("arcade", Context.MODE_PRIVATE)

    // --- Which apps count as games ---

    /** Every launchable app, with whether Android files it as a game. */
    fun launchable(context: Context): List<Pair<Game, Boolean>> {
        val pm = context.packageManager
        return OpenAppActivity.launcherApps(context)
            .distinctBy { it.activityInfo.packageName }
            .map { info ->
                val app = info.activityInfo.applicationInfo
                val installed = try {
                    pm.getPackageInfo(app.packageName, 0).firstInstallTime
                } catch (e: Exception) {
                    0L
                }
                @Suppress("DEPRECATION")
                val game = app.category == ApplicationInfo.CATEGORY_GAME || app.flags and ApplicationInfo.FLAG_IS_GAME != 0
                Game(info.component(), OpenAppActivity.appLabel(pm, info), installed) to game
            }
    }

    /** The arcade's games, A–Z. */
    fun games(context: Context): List<Game> {
        val added = added(context)
        val hidden = hidden(context)
        return launchable(context)
            .filter { (game, auto) -> game.component.packageName !in hidden && (auto || game.component.packageName in added) }
            .map { it.first }
            .sortedWith(compareBy({ it.letter != '#' }, { it.label.lowercase() }))
    }

    fun added(context: Context): Set<String> = prefs(context).getStringSet("added", emptySet()).orEmpty()

    fun hidden(context: Context): Set<String> = prefs(context).getStringSet("hidden", emptySet()).orEmpty()

    /** Puts an app in the arcade or takes it out, overriding what Android says about it. */
    fun setIncluded(context: Context, packageName: String, include: Boolean, auto: Boolean) {
        val added = added(context).toMutableSet()
        val hidden = hidden(context).toMutableSet()
        added.remove(packageName)
        hidden.remove(packageName)
        if (include && !auto) added += packageName
        if (!include && auto) hidden += packageName
        prefs(context).edit().putStringSet("added", added).putStringSet("hidden", hidden).apply()
    }

    /** The game a widget has selected, if it's still in the arcade. */
    fun selected(context: Context, widgetId: Int, games: List<Game>): Game? {
        val flat = prefs(context).getString("selected_$widgetId", null) ?: return null
        return games.firstOrNull { it.component.flattenToString() == flat }
    }

    fun select(context: Context, widgetId: Int, component: ComponentName) {
        prefs(context).edit().putString("selected_$widgetId", component.flattenToString()).apply()
    }

    fun forget(context: Context, widgetId: Int) {
        prefs(context).edit().remove("selected_$widgetId").apply()
    }

    fun isNew(game: Game, now: Long = System.currentTimeMillis()) = game.installed > 0 && now - game.installed < NEW_FOR

    // --- Play stats ---

    fun hasUsageAccess(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) ==
            AppOpsManager.MODE_ALLOWED
    }

    /** Playtime over the last [STATS_DAYS] days for each played game, by package; empty without usage access. */
    fun playStats(context: Context, games: List<Game>): Map<String, Played> {
        if (!hasUsageAccess(context)) return emptyMap()
        val now = System.currentTimeMillis()
        val stats = context.getSystemService(UsageStatsManager::class.java)
            ?.queryAndAggregateUsageStats(now - TimeUnit.DAYS.toMillis(STATS_DAYS.toLong()), now)
            .orEmpty()
        return games.mapNotNull { game ->
            stats[game.component.packageName]
                ?.takeIf { it.totalTimeInForeground > 0 }
                ?.let { game.component.packageName to Played(game, it.totalTimeInForeground, it.lastTimeUsed) }
        }.toMap()
    }

    /** When each game was last played, looking back up to a year; empty without usage access. */
    fun lastPlayed(context: Context, games: List<Game>): Map<String, Long> {
        if (!hasUsageAccess(context)) return emptyMap()
        val now = System.currentTimeMillis()
        val stats = context.getSystemService(UsageStatsManager::class.java)
            ?.queryAndAggregateUsageStats(now - TimeUnit.DAYS.toMillis(LAST_PLAYED_DAYS), now)
            .orEmpty()
        return games.mapNotNull { game ->
            stats[game.component.packageName]?.lastTimeUsed?.takeIf { it > 0 }?.let { game.component.packageName to it }
        }.toMap()
    }

    /**
     * The games to recommend. Played games come first, scored on how long they were played over the last
     * [STATS_DAYS] days and how recently ([PLAYTIME_WEIGHT] to the rest), so a long-loved game and one
     * started yesterday both make it. Without usage access, or to fill up, the most recently installed follow.
     */
    fun recommended(games: List<Game>, stats: Map<String, Played>, count: Int, now: Long = System.currentTimeMillis()): List<Game> {
        val longest = stats.values.maxOfOrNull { it.millis }?.coerceAtLeast(1) ?: 1
        val window = TimeUnit.DAYS.toMillis(STATS_DAYS.toLong()).toFloat()
        val played = stats.values.sortedByDescending {
            val playtime = it.millis.toFloat() / longest
            val recency = (1f - (now - it.lastUsed) / window).coerceIn(0f, 1f)
            PLAYTIME_WEIGHT * playtime + (1 - PLAYTIME_WEIGHT) * recency
        }.map { it.game }
        val rest = games.filter { it !in played }.sortedByDescending { it.installed }
        return (played + rest).take(count)
    }

    // --- Looks ---

    /**
     * The colour a game glows in: the most common bright, non-red colour in its icon (its logo, since
     * Crimson icons sit on red tiles), or crimson when the logo is white or red.
     */
    fun accent(icon: Bitmap?): Int {
        icon ?: return CRIMSON
        val small = Bitmap.createScaledBitmap(icon, 32, 32, true)
        val counts = HashMap<Int, Float>()
        val hsv = FloatArray(3)
        for (y in 0 until 32) for (x in 0 until 32) {
            val c = small.getPixel(x, y)
            if (Color.alpha(c) < 200) continue
            Color.colorToHSV(c, hsv)
            if (hsv[2] < 0.35f || hsv[1] < 0.25f) continue
            if (hsv[0] < 15f || hsv[0] > 335f) continue
            val bucket = (Color.red(c) / 32 shl 6) or (Color.green(c) / 32 shl 3) or (Color.blue(c) / 32)
            counts[bucket] = (counts[bucket] ?: 0f) + 1f + hsv[1] * 2
        }
        val best = counts.maxByOrNull { it.value }?.takeIf { it.value > 12f }?.key ?: return CRIMSON
        return Color.rgb((best shr 6 and 7) * 32 + 16, (best shr 3 and 7) * 32 + 16, (best and 7) * 32 + 16)
    }

    /**
     * Draws a game's icon into [bounds] on [canvas] as a rounded tile. A [lit] tile gets an edge and a halo in
     * [accent]; [alpha] fades it.
     */
    fun drawTile(canvas: Canvas, icon: Bitmap?, accent: Int, bounds: RectF, lit: Boolean, alpha: Int = 255) {
        val radius = bounds.width() * 0.24f
        if (lit) {
            val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = accent
                maskFilter = BlurMaskFilter(bounds.width() * 0.12f, BlurMaskFilter.Blur.OUTER)
            }
            canvas.drawRoundRect(bounds, radius, radius, halo)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.alpha = alpha }
        if (icon != null) {
            canvas.save()
            canvas.clipPath(Path().apply { addRoundRect(bounds, radius, radius, Path.Direction.CW) })
            canvas.drawBitmap(icon, Rect(0, 0, icon.width, icon.height), bounds, paint)
            canvas.restore()
        } else {
            canvas.drawRoundRect(bounds, radius, radius, paint.apply { color = WidgetTheme.color(0xFF3A0A14.toInt()); this.alpha = alpha })
        }
        if (lit) {
            canvas.drawRoundRect(bounds, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = bounds.width() * 0.035f
                color = accent
            })
        }
    }

    /** A game's own icon, exactly as it is (its own logo and tile shape: nothing clipped or reshaped). */
    fun drawIcon(canvas: Canvas, icon: Bitmap?, accent: Int, bounds: RectF) {
        if (icon == null) return drawTile(canvas, null, accent, bounds, lit = false)
        canvas.drawBitmap(icon, Rect(0, 0, icon.width, icon.height), bounds, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
    }

    /** A game's icon for the A–Z grid, with room around it for the halo when it's the selected one. */
    fun tileBitmap(icon: Bitmap?, accent: Int, sizePx: Int, selected: Boolean): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val pad = sizePx * 0.1f
        val box = RectF(pad, pad, sizePx - pad, sizePx - pad)
        drawTile(canvas, icon, accent, box, selected)
        if (selected) {
            // Target brackets on two corners.
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = accent
                style = Paint.Style.STROKE
                strokeWidth = sizePx * 0.03f
            }
            val len = sizePx * 0.16f
            val o = pad * 0.35f
            canvas.drawPath(Path().apply {
                moveTo(o, o + len); lineTo(o, o); lineTo(o + len, o)
                moveTo(sizePx - o - len, sizePx - o); lineTo(sizePx - o, sizePx - o); lineTo(sizePx - o, sizePx - o - len)
            }, p)
        }
        return bitmap
    }

    /** [accent] made rich enough to carry a card or banner (dull or near-black icons still get colour). */
    fun vivid(accent: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(accent, hsv)
        hsv[1] = hsv[1].coerceAtLeast(.55f)
        hsv[2] = hsv[2].coerceIn(.55f, .85f)
        return Color.HSVToColor(hsv)
    }

    /** [color] mixed towards black by [amount] (0–1). */
    fun shade(color: Int, amount: Float): Int = Color.rgb(
        (Color.red(color) * (1 - amount)).toInt(), (Color.green(color) * (1 - amount)).toInt(), (Color.blue(color) * (1 - amount)).toInt())

    /**
     * A game's card for the library: a rounded card in the game's own colour, its icon, its name, a NEW badge
     * when it's just installed, the letter on the first game under each letter, and a white outline when
     * it's the one selected.
     */
    fun cardBitmap(icon: Bitmap?, accent: Int, label: String, wPx: Int, hPx: Int, selected: Boolean, new: Boolean, letter: Char?): Bitmap {
        val bmp = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val w = wPx.toFloat()
        val h = hPx.toFloat()
        val inset = w * .03f
        val box = RectF(inset, inset, w - inset, h - inset)
        val r = w * .17f
        val v = vivid(accent)
        if (selected) c.drawRoundRect(box, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = v; maskFilter = BlurMaskFilter(inset * 1.5f, BlurMaskFilter.Blur.OUTER) })
        c.drawRoundRect(box, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, box.top, 0f, box.bottom, shade(v, .45f), shade(v, .82f), Shader.TileMode.CLAMP)
        })
        // A soft light from the top left, and a hairline edge.
        c.save()
        c.clipPath(Path().apply { addRoundRect(box, r, r, Path.Direction.CW) })
        c.drawCircle(box.left + w * .2f, box.top, w * .7f, Paint().apply {
            shader = RadialGradient(box.left + w * .2f, box.top, w * .7f, Color.argb(46, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        })
        c.restore()
        c.drawRoundRect(box, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = if (selected) w * .025f else w * .008f
            color = if (selected) Color.WHITE else Color.argb(38, 255, 255, 255)
        })
        // The icon, with a shadow under it.
        val s = w * .48f
        val ib = RectF((w - s) / 2, h * .15f, (w + s) / 2, h * .15f + s)
        drawIcon(c, icon, accent, ib)
        // The name.
        val tp = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(240, 255, 255, 255); textSize = w * .118f; textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }
        c.drawText(android.text.TextUtils.ellipsize(label, tp, box.width() - w * .1f, android.text.TextUtils.TruncateAt.END).toString(), w / 2, h - h * .11f, tp)
        if (new) {
            val np = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = w * .085f; typeface = android.graphics.Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER; letterSpacing = .08f }
            val bw = np.measureText("NEW") + w * .09f
            val b = RectF(box.right - bw - w * .05f, box.top + w * .05f, box.right - w * .05f, box.top + w * .05f + w * .13f)
            c.drawRoundRect(b, b.height() / 2, b.height() / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE0405A.toInt() })
            c.drawText("NEW", b.centerX(), b.centerY() + np.textSize * .36f, np)
        }
        if (letter != null) {
            val lp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = w * .1f; typeface = android.graphics.Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
            val cr = w * .085f
            val cx = box.left + w * .05f + cr
            val cy = box.top + w * .05f + cr
            c.drawCircle(cx, cy, cr, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 0, 0, 0) })
            c.drawCircle(cx, cy, cr, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = w * .008f; color = Color.argb(90, 255, 255, 255) })
            c.drawText(letter.toString(), cx, cy + lp.textSize * .36f, lp)
        }
        return bmp
    }

    // --- Sessions, for the player stats page ---

    /** One stretch of play: [pkg] in front from [start] to [end]. */
    class Session(val pkg: String, val start: Long, val end: Long) {
        val millis: Long get() = end - start
    }

    /** A game coming to the front ([resumed]) or leaving it, at [time]. */
    class Event(val pkg: String, val resumed: Boolean, val time: Long)

    /**
     * Joins front/back events into sessions. Gaps under a minute (a permission dialog, a quick look at a
     * message) don't break a session; a game still in front at [now] runs until then.
     */
    fun sessions(events: List<Event>, now: Long): List<Session> {
        val open = HashMap<String, Long>()
        val out = ArrayList<Session>()
        for (e in events.sortedBy { it.time }) {
            if (e.resumed) {
                if (e.pkg !in open) {
                    val last = out.lastOrNull()
                    if (last != null && last.pkg == e.pkg && e.time - last.end < 60_000) {
                        out.removeAt(out.size - 1)
                        open[e.pkg] = last.start
                    } else {
                        open[e.pkg] = e.time
                    }
                }
            } else {
                val start = open.remove(e.pkg) ?: continue
                if (e.time > start) out += Session(e.pkg, start, e.time)
            }
        }
        open.forEach { (pkg, start) -> if (now > start) out += Session(pkg, start, now) }
        return out.sortedBy { it.start }
    }

    /** Play per day over the [days] days ending [today], oldest first; sessions over midnight are split. */
    fun perDay(sessions: List<Session>, days: Int, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): LongArray {
        val out = LongArray(days)
        for (d in 0 until days) {
            val day = today.minusDays((days - 1 - d).toLong())
            val from = day.atStartOfDay(zone).toInstant().toEpochMilli()
            val to = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            out[d] = sessions.sumOf { (minOf(it.end, to) - maxOf(it.start, from)).coerceAtLeast(0) }
        }
        return out
    }

    /** Days in a row with some play, counting back from today (or from yesterday, if today has none yet). */
    fun streak(daily: LongArray): Int {
        var i = daily.size - 1
        if (i >= 0 && daily[i] == 0L) i--
        var n = 0
        while (i >= 0 && daily[i] > 0) { n++; i-- }
        return n
    }

    /** The last [days] days of game sessions, from the phone's usage events; empty without usage access. */
    fun recentSessions(context: Context, games: List<Game>, days: Int = 7): List<Session> {
        if (!hasUsageAccess(context)) return emptyList()
        val packages = games.map { it.component.packageName }.toSet()
        val now = System.currentTimeMillis()
        val from = LocalDate.now().minusDays((days - 1).toLong()).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val usage = context.getSystemService(UsageStatsManager::class.java) ?: return emptyList()
        val events = ArrayList<Event>()
        try {
            val stream = usage.queryEvents(from - TimeUnit.HOURS.toMillis(6), now)
            val e = UsageEvents.Event()
            while (stream.hasNextEvent()) {
                stream.getNextEvent(e)
                if (e.packageName !in packages) continue
                when (e.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> events += Event(e.packageName, true, e.timeStamp)
                    UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> events += Event(e.packageName, false, e.timeStamp)
                }
            }
        } catch (e: Exception) {
            return emptyList()
        }
        return sessions(events, now).filter { it.end > from }
    }

    /** A badge on the stats page: [progress] towards [goal], earned when it gets there. */
    class Achievement(val glyph: String, val title: String, val detail: String, val progress: Float, val goal: Float) {
        val earned: Boolean get() = progress >= goal
        val fraction: Float get() = (progress / goal).coerceIn(0f, 1f)
    }

    fun achievements(sessions: List<Session>, daily: LongArray, played: Int, owned: Int): List<Achievement> {
        val longest = sessions.maxOfOrNull { it.millis } ?: 0L
        val zone = ZoneId.systemDefault()
        val owl = sessions.count { java.time.Instant.ofEpochMilli(it.end).atZone(zone).hour in 0..4 }
        val week = daily.sum()
        return listOf(
            Achievement("⏱", "MARATHON", "ONE SESSION OVER AN HOUR", TimeUnit.MILLISECONDS.toMinutes(longest).toFloat(), 60f),
            Achievement("✦", "ON A STREAK", "PLAY 5 DAYS IN A ROW", streak(daily).toFloat(), 5f),
            Achievement("◈", "EXPLORER", "PLAY 5 GAMES IN 2 WEEKS", played.toFloat(), 5f),
            Achievement("▦", "COLLECTOR", "HAVE 20 GAMES", owned.toFloat(), 20f),
            Achievement("☾", "NIGHT OWL", "PLAY PAST MIDNIGHT", owl.toFloat(), 1f),
            Achievement("⚡", "COMMITTED", "10 HOURS IN A WEEK", TimeUnit.MILLISECONDS.toMinutes(week) / 60f, 10f),
        )
    }

    /** "14H 20M", "35M", or "" for nothing. */
    fun playtime(millis: Long): String {
        if (millis <= 0) return ""
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis)
        return if (minutes >= 60) "%dH %02dM".format(minutes / 60, minutes % 60) else "${maxOf(1, minutes)}M"
    }

    /** "2H AGO", "3D AGO", "JUST NOW". */
    fun ago(time: Long, now: Long = System.currentTimeMillis()): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(now - time)
        return when {
            minutes < 5 -> "JUST NOW"
            minutes < 60 -> "${minutes}M AGO"
            minutes < 60 * 24 -> "${minutes / 60}H AGO"
            else -> "${minutes / (60 * 24)}D AGO"
        }
    }
}
