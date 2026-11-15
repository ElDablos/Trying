package com.eldablos.homewidget

import android.content.Context
import org.json.JSONObject
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.util.Base64
import java.util.concurrent.Executors

/**
 * Runs every light command on one background thread, in tap order.
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

    fun refreshLight(context: Context, quiet: Boolean = false) {
        val config = HomeStore.config(context)
        if (!config.lightConfigured) return
        val current = HomeStore.light(context)
        HomeStore.saveLight(context, try {
            readLight(tapoClient(config), current)
        } catch (e: Exception) {
            if (quiet) return
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

    fun refreshAll(context: Context, quiet: Boolean = false) {
        refreshLight(context, quiet)
    }

    fun friendlyError(device: String, e: Throwable): String {
        val messages = generateSequence(e) { it.cause }.mapNotNull { it.message }.joinToString(" ")
        return when {
            // Android blocked the network for us (the app was in the background).
            "EPERM" in messages -> "Android blocked the connection to the $device. Tap again, or open the app."
            e is TapoAuthException -> e.message ?: "Tapo login failed"
            e is SocketTimeoutException || e is ConnectException || e is NoRouteToHostException || e.cause is SocketTimeoutException ->
                "Can't reach the $device. Are you on home Wi-Fi?"
            else -> e.message ?: "Couldn't talk to the $device"
        }
    }
}
