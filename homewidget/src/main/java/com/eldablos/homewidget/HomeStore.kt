package com.eldablos.homewidget

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject

/** A Tapo light setting: either a white temperature (colorTemp > 0) or a hue/saturation colour. */
data class LightPreset(val brightness: Int, val hue: Int, val saturation: Int, val colorTemp: Int) {
    val swatchColor: Int
        get() = if (colorTemp > 0) kelvinColor(colorTemp) else Color.HSVToColor(
            floatArrayOf(hue.toFloat(), saturation.coerceIn(0, 100) / 100f, 1f)
        )

    fun toJson(): JSONObject = JSONObject()
        .put("brightness", brightness).put("hue", hue).put("saturation", saturation).put("color_temp", colorTemp)

    companion object {
        fun fromJson(json: JSONObject) = LightPreset(
            json.optInt("brightness", 100), json.optInt("hue", 0),
            json.optInt("saturation", 0), json.optInt("color_temp", 0),
        )

        /** Rough on-screen colour for a white temperature, warm (2500K) to cool (6500K). */
        private fun kelvinColor(kelvin: Int): Int {
            val t = ((kelvin - 2500) / 4000f).coerceIn(0f, 1f)
            fun mix(a: Int, b: Int) = (a + (b - a) * t).toInt()
            return Color.rgb(mix(0xFF, 0xE8), mix(0xC8, 0xF0), mix(0x80, 0xFF))
        }

        /** Shown until the bulb reports its own presets. */
        val DEFAULTS = listOf(
            LightPreset(100, 0, 0, 2700),
            LightPreset(100, 0, 0, 6500),
            LightPreset(100, 240, 100, 0),
            LightPreset(100, 0, 100, 0),
            LightPreset(100, 120, 100, 0),
            LightPreset(100, 270, 100, 0),
        )
    }
}

data class LightState(
    val known: Boolean = false,
    val name: String = "Light",
    val on: Boolean = false,
    val brightness: Int = 100,
    val hue: Int = 0,
    val saturation: Int = 0,
    val colorTemp: Int = 0,
    val presets: List<LightPreset> = LightPreset.DEFAULTS,
    val error: String? = null,
)

data class HeaterState(
    val known: Boolean = false,
    val name: String = "Heater",
    val on: Boolean = false,
    val targetTemp: Int = 20,
    val roomTemp: Double? = null,
    val error: String? = null,
)

data class Config(val email: String, val password: String, val lightIp: String, val heaterIp: String) {
    val lightConfigured get() = email.isNotBlank() && password.isNotBlank() && lightIp.isNotBlank()
    val heaterConfigured get() = heaterIp.isNotBlank()
}

/** Settings plus the last known device state, so the widget can draw instantly. */
object HomeStore {
    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences("home", Context.MODE_PRIVATE)

    fun config(context: Context): Config = prefs(context).let {
        Config(
            it.getString("tapo_email", "") ?: "",
            it.getString("tapo_password", "") ?: "",
            it.getString("light_ip", "") ?: "",
            it.getString("heater_ip", "") ?: "",
        )
    }

    fun saveConfig(context: Context, config: Config) {
        prefs(context).edit()
            .putString("tapo_email", config.email.trim())
            .putString("tapo_password", config.password)
            .putString("light_ip", config.lightIp.trim())
            .putString("heater_ip", config.heaterIp.trim())
            .apply()
    }

    fun light(context: Context): LightState {
        val p = prefs(context)
        val presets = runCatching {
            val array = JSONArray(p.getString("light_presets", null) ?: return@runCatching LightPreset.DEFAULTS)
            List(array.length()) { LightPreset.fromJson(array.getJSONObject(it)) }
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: LightPreset.DEFAULTS
        return LightState(
            known = p.getBoolean("light_known", false),
            name = p.getString("light_name", null) ?: "Light",
            on = p.getBoolean("light_on", false),
            brightness = p.getInt("light_brightness", 100),
            hue = p.getInt("light_hue", 0),
            saturation = p.getInt("light_saturation", 0),
            colorTemp = p.getInt("light_color_temp", 0),
            presets = presets,
            error = p.getString("light_error", null),
        )
    }

    fun saveLight(context: Context, s: LightState) {
        prefs(context).edit()
            .putBoolean("light_known", s.known)
            .putString("light_name", s.name)
            .putBoolean("light_on", s.on)
            .putInt("light_brightness", s.brightness)
            .putInt("light_hue", s.hue)
            .putInt("light_saturation", s.saturation)
            .putInt("light_color_temp", s.colorTemp)
            .putString("light_presets", JSONArray(s.presets.map { it.toJson() }).toString())
            .putString("light_error", s.error)
            .commit()
    }

    fun heater(context: Context): HeaterState {
        val p = prefs(context)
        return HeaterState(
            known = p.getBoolean("heater_known", false),
            name = p.getString("heater_name", null) ?: "Heater",
            on = p.getBoolean("heater_on", false),
            targetTemp = p.getInt("heater_target", 20),
            roomTemp = if (p.contains("heater_room")) p.getFloat("heater_room", 0f).toDouble() else null,
            error = p.getString("heater_error", null),
        )
    }

    fun saveHeater(context: Context, s: HeaterState) {
        prefs(context).edit()
            .putBoolean("heater_known", s.known)
            .putString("heater_name", s.name)
            .putBoolean("heater_on", s.on)
            .putInt("heater_target", s.targetTemp)
            .apply { if (s.roomTemp != null) putFloat("heater_room", s.roomTemp.toFloat()) else remove("heater_room") }
            .putString("heater_error", s.error)
            .commit()
    }
}
