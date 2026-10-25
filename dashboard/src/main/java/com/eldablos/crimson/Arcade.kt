package com.eldablos.crimson

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Process
import com.eldablos.crimson.OpenAppActivity.Companion.component
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
            canvas.drawRoundRect(bounds, radius, radius, paint.apply { color = 0xFF3A0A14.toInt(); this.alpha = alpha })
        }
        if (lit) {
            canvas.drawRoundRect(bounds, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = bounds.width() * 0.035f
                color = accent
            })
        }
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
