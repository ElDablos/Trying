package com.eldablos.crimson.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color

/**
 * The launcher's colour themes. Everything is designed in crimson; a theme turns the crimson reds to its
 * own colour (with its own strength and brightness) and the golds to its second accent, leaving other
 * colours, like WhatsApp green or rain blue, as they are.
 */
enum class Theme(
    val label: String,
    /** The hue crimson becomes, in degrees. */
    val hue: Float,
    val saturation: Float,
    val brightness: Float,
    /** The hue gold becomes, and how strong it stays. */
    val hue2: Float,
    val saturation2: Float,
) {
    CRIMSON("CRIMSON", 352f, 1f, 1f, 40f, 1f),
    BLOOD_MOON("BLOOD MOON", 348f, 1.1f, .78f, 24f, 1f),
    MARK_42("MARK 42", 356f, 1.08f, 1.06f, 44f, 1.15f),
    INFERNO("INFERNO", 10f, 1.1f, 1.05f, 50f, 1.1f),
    ROSE_GOLD("ROSE GOLD", 338f, .6f, 1.1f, 30f, .75f),
    SOLAR("SOLAR", 38f, 1f, 1.1f, 18f, 1f),
    TOXIC("TOXIC", 82f, 1f, 1.05f, 55f, 1f),
    VENOM("VENOM", 148f, .9f, 1f, 178f, .9f),
    MATRIX("MATRIX", 122f, 1f, .95f, 90f, 1f),
    ARCTIC("ARCTIC", 192f, .65f, 1.12f, 205f, .35f),
    JARVIS("JARVIS", 200f, .95f, 1.06f, 42f, 1f),
    COBALT("COBALT", 222f, 1f, 1f, 195f, 1f),
    ULTRAVIOLET("ULTRAVIOLET", 272f, 1f, 1.06f, 312f, 1f),
    SYNTHWAVE("SYNTHWAVE", 318f, 1.05f, 1.06f, 184f, 1f),
    PHANTOM("PHANTOM", 0f, 0f, 1.05f, 0f, 0f),
    STEALTH("STEALTH", 352f, .32f, .82f, 40f, .3f);

    /**
     * [color] in this theme. Reds (hue 300–18°) turn to [hue], keeping how far each was from crimson, so
     * the palette's small differences survive; golds (25–62°) turn to [hue2]; the rest stay.
     */
    fun tint(color: Int): Int {
        if (this == CRIMSON) return color
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        val h = hsv[0]
        val s = hsv[1]
        if (s <= .03f) return color
        when {
            h >= 300f || h <= 18f -> {
                val fromCrimson = (if (h > 180f) h - 360f else h) + 8f
                hsv[0] = ((hue + fromCrimson) % 360f + 360f) % 360f
                hsv[1] = (s * saturation).coerceIn(0f, 1f)
                hsv[2] = (hsv[2] * brightness).coerceIn(0f, 1f)
            }
            h in 25f..62f -> {
                hsv[0] = ((hue2 + h - 40f) % 360f + 360f) % 360f
                hsv[1] = (s * saturation2).coerceIn(0f, 1f)
            }
            else -> return color
        }
        return Color.HSVToColor(Color.alpha(color), hsv)
    }

    /** A themed copy of an icon from the Crimson icon pack, pixel by pixel. */
    fun tint(bitmap: Bitmap): Bitmap {
        if (this == CRIMSON) return bitmap
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val seen = HashMap<Int, Int>()
        for (i in pixels.indices) {
            val p = pixels[i]
            if (p ushr 24 == 0) continue
            pixels[i] = seen.getOrPut(p) { tint(p) }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    companion object {
        // Read from widget and wallpaper threads as well as the main one.
        @Volatile
        private var current: Theme = CRIMSON
        private val cache = java.util.concurrent.ConcurrentHashMap<Int, Int>()

        /** The theme in use. Set from settings, read by every screen as it draws. */
        val now: Theme get() = current

        /** Loads the saved theme; call as each screen starts. Returns true if it changed. */
        fun load(context: Context): Boolean {
            val saved = context.getSharedPreferences("launcher", Context.MODE_PRIVATE).getString("theme", null)
            val theme = entries.firstOrNull { it.name == saved } ?: CRIMSON
            if (theme == current) return false
            current = theme
            cache.clear()
            readableCache.clear()
            return true
        }

        fun set(context: Context, theme: Theme) {
            context.getSharedPreferences("launcher", Context.MODE_PRIVATE).edit().putString("theme", theme.name).apply()
            load(context)
        }

        /**
         * [color] made light enough to read on the dark screens. Some themes (Cobalt, Ultraviolet, Blood
         * Moon) turn crimson into colours the eye sees as much darker, so text in them is blended towards
         * white, keeping its hue, until it stands out. Crimson itself is left exactly as designed.
         */
        fun readable(color: Int): Int {
            // Near-black is meant to be dark: text on a bright button (see [on]).
            if (current == CRIMSON || Color.alpha(color) < 40 || Color.luminance(color) < .02f) return color
            return readableCache.getOrPut(color) {
                var c = color
                var i = 0
                while (Color.luminance(c) < MIN_LUMINANCE && i < 12) {
                    c = Color.argb(
                        Color.alpha(c),
                        Color.red(c) + ((255 - Color.red(c)) * .12f).toInt(),
                        Color.green(c) + ((255 - Color.green(c)) * .12f).toInt(),
                        Color.blue(c) + ((255 - Color.blue(c)) * .12f).toInt(),
                    )
                    i++
                }
                c
            }
        }

        private const val MIN_LUMINANCE = .2f

        /** Dark text for bright buttons. */
        const val INK = 0xFF12050A.toInt()

        /**
         * The text colour for something drawn on [background] (which may be see-through, over the dark
         * screens): white normally, dark ink when a bright theme (Toxic, Solar, Arctic, Phantom…) makes
         * the background light.
         */
        fun on(background: Int): Int {
            val a = Color.alpha(background) / 255f
            val r = (Color.red(background) * a + 8 * (1 - a)).toInt()
            val g = (Color.green(background) * a + 3 * (1 - a)).toInt()
            val b = (Color.blue(background) * a + 5 * (1 - a)).toInt()
            val bg = Color.luminance(Color.rgb(r, g, b))
            // Whichever has the better contrast.
            val withWhite = 1.05f / (bg + .05f)
            val withInk = (bg + .05f) / (Color.luminance(INK) + .05f)
            return if (withInk > withWhite) INK else Color.WHITE
        }
        private val readableCache = java.util.concurrent.ConcurrentHashMap<Int, Int>()

        /** [color] in the current theme, remembered so drawing stays quick. */
        fun tint(color: Int): Int {
            val theme = current
            if (theme == CRIMSON) return color
            return cache.getOrPut(color) { theme.tint(color) }
        }
    }
}
