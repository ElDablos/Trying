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
 * the user has given usage access, and draws game icons as glowing hexagons.
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
    private val NEW_FOR = TimeUnit.DAYS.toMillis(7)
    private const val CRIMSON = 0xFFE01E37.toInt()

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

    fun isNew(game: Game, now: Long = System.currentTimeMillis()) = game.installed > 0 && now - game.installed < NEW_FOR

    // --- Play stats ---

    fun hasUsageAccess(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) ==
            AppOpsManager.MODE_ALLOWED
    }

    /**
     * The most played games over the last [STATS_DAYS] days, most first. Without usage access, or before
     * anything's been played, it falls back to the most recently installed games (with no playtime).
     */
    fun mostPlayed(context: Context, games: List<Game>, count: Int): List<Played> {
        val played = if (hasUsageAccess(context)) {
            val now = System.currentTimeMillis()
            val stats = context.getSystemService(UsageStatsManager::class.java)
                ?.queryAndAggregateUsageStats(now - TimeUnit.DAYS.toMillis(STATS_DAYS.toLong()), now)
                .orEmpty()
            games.mapNotNull { game ->
                stats[game.component.packageName]
                    ?.takeIf { it.totalTimeInForeground > 0 }
                    ?.let { Played(game, it.totalTimeInForeground, it.lastTimeUsed) }
            }.sortedByDescending { it.millis }
        } else {
            emptyList()
        }
        val rest = games.filter { game -> played.none { it.game == game } }
            .sortedByDescending { it.installed }
            .map { Played(it, 0, 0) }
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

    /** A flat-sided hexagon filling [bounds]. */
    fun hexPath(bounds: RectF, path: Path = Path()): Path {
        val w = bounds.width()
        val h = bounds.height()
        path.reset()
        path.moveTo(bounds.left + w * 0.25f, bounds.top + h * 0.03f)
        path.lineTo(bounds.left + w * 0.75f, bounds.top + h * 0.03f)
        path.lineTo(bounds.right, bounds.centerY())
        path.lineTo(bounds.left + w * 0.75f, bounds.bottom - h * 0.03f)
        path.lineTo(bounds.left + w * 0.25f, bounds.bottom - h * 0.03f)
        path.lineTo(bounds.left, bounds.centerY())
        path.close()
        return path
    }

    /**
     * Draws a game's icon cut into a hexagon, edged and haloed in [accent], into [bounds] on [canvas].
     * [glow] sets how strong the halo is.
     */
    fun drawHex(canvas: Canvas, icon: Bitmap?, accent: Int, bounds: RectF, glow: Float, dim: Boolean = false) {
        val edge = bounds.width() * 0.035f
        val path = hexPath(bounds)
        if (glow > 0f) {
            val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = accent
                alpha = (200 * glow).toInt().coerceIn(0, 255)
                maskFilter = BlurMaskFilter(bounds.width() * 0.08f * glow, BlurMaskFilter.Blur.OUTER)
            }
            canvas.drawPath(path, halo)
        }
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent })
        val inner = RectF(bounds.left + edge, bounds.top + edge, bounds.right - edge, bounds.bottom - edge)
        canvas.save()
        canvas.clipPath(hexPath(inner))
        canvas.drawColor(0xFF3A0A14.toInt())
        icon?.let {
            // A little larger than the hexagon, so the tile fills it to the edges.
            val grow = inner.width() * 0.1f
            val dest = RectF(inner.left - grow, inner.top - grow, inner.right + grow, inner.bottom + grow)
            canvas.drawBitmap(it, Rect(0, 0, it.width, it.height), dest, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        if (dim) canvas.drawColor(0x88000000.toInt())
        canvas.restore()
    }

    /** A game's hexagon on its own, with room around it for the halo. */
    fun hexBitmap(icon: Bitmap?, accent: Int, sizePx: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val pad = sizePx * 0.09f
        drawHex(Canvas(bitmap), icon, accent, RectF(pad, pad, sizePx - pad, sizePx - pad), 1f)
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
