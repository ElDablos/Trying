package com.eldablos.homewidget

import android.content.Context
import org.json.JSONObject
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.util.Base64
import java.util.concurrent.Executors

/**
 * Runs every device command on one background thread, in tap order.
 * Each action updates the stored state straight away (so the widget reacts instantly),
 * sends the command, then reads the real state back.
 */
object HomeController {
    private val executor = Executors.newSingleThreadExecutor()
    private var tapo: TapoClient? = null
    private var tapoKey: String? = null

    fun submit(context: Context, onDone: () -> Unit = {}, action: (Context) -> Unit) {
        val app = context.applicationContext
        executor.execute {
            try {
                action(app)
            } finally {
                HomeWidgetProvider.renderAll(app)
                onDone()
            }
        }
    }

    // ---- Light ----

    private fun tapoClient(config: Config): TapoClient {
        val key = "${config.lightIp}|${config.email}|${config.password}"
        if (tapo == null || tapoKey != key) {
            tapo = TapoClient(config.lightIp, config.email, config.password)
            tapoKey = key
        }
        return tapo!!
    }

    fun refreshLight(context: Context) {
        val config = HomeStore.config(context)
        if (!config.lightConfigured) return
        val current = HomeStore.light(context)
        HomeStore.saveLight(context, try {
            readLight(tapoClient(config), current)
        } catch (e: Exception) {
            current.copy(error = friendlyError("light", e))
        })
    }

    private fun readLight(client: TapoClient, previous: LightState): LightState {
        val info = client.deviceInfo()
        val presets = runCatching { parsePresets(client.request("get_preset_rules").optJSONArray("states")) }
            .getOrNull()
            ?: parsePresets(info.optJSONArray("preset_state"))
            ?: previous.presets
        return LightState(
            known = true,
            name = decodeName(info.optString("nickname")) ?: previous.name,
            on = info.optBoolean("device_on", false),
            brightness = info.optInt("brightness", previous.brightness),
            hue = info.optInt("hue", 0),
            saturation = info.optInt("saturation", 0),
            colorTemp = info.optInt("color_temp", 0),
            presets = presets,
            error = null,
        )
    }

    private fun parsePresets(array: org.json.JSONArray?): List<LightPreset>? {
        if (array == null) return null
        val list = (0 until array.length())
            .mapNotNull { array.optJSONObject(it) }
            .filter { it.has("brightness") }
            .map(LightPreset::fromJson)
        return list.ifEmpty { null }
    }

    private fun decodeName(base64: String): String? = runCatching {
        String(Base64.getDecoder().decode(base64), Charsets.UTF_8).takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun changeLight(context: Context, optimistic: (LightState) -> LightState, params: (LightState) -> JSONObject) {
        val config = HomeStore.config(context)
        if (!config.lightConfigured) return
        val before = HomeStore.light(context)
        val after = optimistic(before).copy(error = null)
        HomeStore.saveLight(context, after)
        HomeWidgetProvider.renderAll(context)
        try {
            val client = tapoClient(config)
            client.setDeviceInfo(params(before))
            HomeStore.saveLight(context, runCatching { readLight(client, after) }.getOrDefault(after))
        } catch (e: Exception) {
            HomeStore.saveLight(context, before.copy(error = friendlyError("light", e)))
        }
    }

    fun toggleLight(context: Context) = changeLight(
        context,
        optimistic = { it.copy(on = !it.on) },
        params = { JSONObject().put("device_on", !it.on) },
    )

    fun setBrightness(context: Context, brightness: Int) = changeLight(
        context,
        optimistic = { it.copy(on = true, brightness = brightness) },
        params = { JSONObject().put("device_on", true).put("brightness", brightness.coerceIn(1, 100)) },
    )

    fun applyPreset(context: Context, index: Int) {
        val preset = HomeStore.light(context).presets.getOrNull(index) ?: return
        changeLight(
            context,
            optimistic = {
                it.copy(
                    on = true, brightness = preset.brightness, hue = preset.hue,
                    saturation = preset.saturation, colorTemp = preset.colorTemp,
                )
            },
            params = {
                JSONObject().put("device_on", true).put("brightness", preset.brightness.coerceIn(1, 100)).apply {
                    if (preset.colorTemp > 0) {
                        put("color_temp", preset.colorTemp)
                    } else {
                        put("hue", preset.hue).put("saturation", preset.saturation).put("color_temp", 0)
                    }
                }
            },
        )
    }

    // ---- Heater ----

    fun refreshHeater(context: Context) {
        val config = HomeStore.config(context)
        if (!config.heaterConfigured) return
        val current = HomeStore.heater(context)
        HomeStore.saveHeater(context, try {
            readHeater(PhilipsClient(config.heaterIp), current)
        } catch (e: Exception) {
            current.copy(error = friendlyError("heater", e))
        })
    }

    private fun readHeater(client: PhilipsClient, previous: HeaterState): HeaterState {
        val s = client.status()
        if (!s.has(PhilipsClient.KEY_POWER)) {
            throw IllegalStateException("Heater answered but uses different settings; open the app and tap Test for details")
        }
        return HeaterState(
            known = true,
            name = s.optString(PhilipsClient.KEY_NAME).ifBlank { previous.name },
            on = s.optInt(PhilipsClient.KEY_POWER, 0) == 1,
            targetTemp = s.optInt(PhilipsClient.KEY_TARGET_TEMP, previous.targetTemp),
            roomTemp = if (s.has(PhilipsClient.KEY_ROOM_TEMP)) s.optInt(PhilipsClient.KEY_ROOM_TEMP) / 10.0 else previous.roomTemp,
            error = null,
        )
    }

    private fun changeHeater(context: Context, optimistic: (HeaterState) -> HeaterState, values: (HeaterState) -> Map<String, Any>) {
        val config = HomeStore.config(context)
        if (!config.heaterConfigured) return
        val before = HomeStore.heater(context)
        val after = optimistic(before).copy(error = null)
        HomeStore.saveHeater(context, after)
        HomeWidgetProvider.renderAll(context)
        try {
            val client = PhilipsClient(config.heaterIp)
            client.control(values(before))
            Thread.sleep(400) // give the heater a moment to report its new state
            HomeStore.saveHeater(context, runCatching { readHeater(client, after) }.getOrDefault(after))
        } catch (e: Exception) {
            HomeStore.saveHeater(context, before.copy(error = friendlyError("heater", e)))
        }
    }

    fun toggleHeater(context: Context) = changeHeater(
        context,
        optimistic = { it.copy(on = !it.on) },
        values = { mapOf(PhilipsClient.KEY_POWER to if (it.on) 0 else 1) },
    )

    fun changeTargetTemp(context: Context, delta: Int) {
        // Work from the optimistic value so quick repeated taps add up.
        val target = (HomeStore.heater(context).targetTemp + delta).coerceIn(PhilipsClient.MIN_TEMP, PhilipsClient.MAX_TEMP)
        changeHeater(
            context,
            optimistic = { it.copy(targetTemp = target) },
            values = { mapOf(PhilipsClient.KEY_TARGET_TEMP to target) },
        )
    }

    fun refreshAll(context: Context) {
        refreshLight(context)
        refreshHeater(context)
    }

    fun friendlyError(device: String, e: Throwable): String = when (e) {
        is TapoAuthException -> e.message ?: "Tapo login failed"
        is SocketTimeoutException, is ConnectException, is NoRouteToHostException ->
            "Can't reach the $device. Are you on home Wi-Fi?"
        else -> e.message ?: "Couldn't talk to the $device"
    }
}
