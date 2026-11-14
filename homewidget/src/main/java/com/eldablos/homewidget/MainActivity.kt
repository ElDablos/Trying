package com.eldablos.homewidget

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import java.util.Base64

class MainActivity : Activity() {
    private lateinit var email: EditText
    private lateinit var password: EditText
    private lateinit var lightIp: EditText
    private lateinit var heaterIp: EditText
    private lateinit var lightResult: TextView
    private lateinit var heaterResult: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        email = findViewById(R.id.email)
        password = findViewById(R.id.password)
        lightIp = findViewById(R.id.light_ip)
        heaterIp = findViewById(R.id.heater_ip)
        lightResult = findViewById(R.id.light_result)
        heaterResult = findViewById(R.id.heater_result)

        HomeStore.config(this).let {
            email.setText(it.email)
            password.setText(it.password)
            lightIp.setText(it.lightIp)
            heaterIp.setText(it.heaterIp)
        }

        findViewById<Button>(R.id.find_light).setOnClickListener { findLight() }
        findViewById<Button>(R.id.test_light).setOnClickListener { testLight() }
        findViewById<Button>(R.id.find_heater).setOnClickListener { findHeater() }
        findViewById<Button>(R.id.test_heater).setOnClickListener { testHeater() }
        findViewById<Button>(R.id.save).setOnClickListener {
            save()
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.add_widget).setOnClickListener { pinWidget() }
    }

    override fun onResume() {
        super.onResume()
        val address = LanScanner.wifiAddress(this)
        findViewById<TextView>(R.id.wifi_text).text =
            if (address != null) "On Wi-Fi ($address)" else "Not on Wi-Fi. Connect to your home Wi-Fi first."
    }

    override fun onPause() {
        save()
        super.onPause()
    }

    private fun config() = Config(
        email.text.toString().trim(), password.text.toString(),
        lightIp.text.toString().trim(), heaterIp.text.toString().trim(),
    )

    private fun save() {
        val old = HomeStore.config(this)
        val new = config()
        if (old == new) return
        HomeStore.saveConfig(this, new)
        HomeController.submit(this) { HomeController.refreshAll(it) }
    }

    private fun background(output: TextView, startText: String, work: () -> String) {
        output.text = startText
        Thread {
            val text = try {
                work()
            } catch (e: Exception) {
                "✗ " + (e.message ?: e.javaClass.simpleName)
            }
            runOnUiThread { output.text = text }
        }.start()
    }

    private fun neighboursOrNull(output: TextView): List<String>? {
        val own = LanScanner.wifiAddress(this)
        if (own == null) {
            output.text = "✗ Connect to your home Wi-Fi first."
            return null
        }
        return LanScanner.neighbours(own)
    }

    private fun findLight() {
        val hosts = neighboursOrNull(lightResult) ?: return
        val cfg = config()
        background(lightResult, "Searching your network for Tapo devices…") {
            val found = LanScanner.findTapoDevices(hosts)
            if (found.isEmpty()) return@background "✗ No Tapo devices found. Is the light switched on?"
            // Name each device if we can log in, so you can pick the right one.
            val labelled = found.map { ip ->
                val name = if (cfg.email.isNotBlank() && cfg.password.isNotBlank()) {
                    runCatching {
                        val info = TapoClient(ip, cfg.email, cfg.password).deviceInfo()
                        val nick = String(Base64.getDecoder().decode(info.optString("nickname")), Charsets.UTF_8)
                        "$nick (${info.optString("model")})"
                    }.getOrNull()
                } else null
                ip to (name ?: "Tapo device")
            }
            runOnUiThread { choose("Pick your light", labelled, lightIp) }
            "Found ${found.size} Tapo device(s)."
        }
    }

    private fun findHeater() {
        val hosts = neighboursOrNull(heaterResult) ?: return
        background(heaterResult, "Searching your network for Philips devices…") {
            val found = LanScanner.findHeaters(hosts)
            if (found.isEmpty()) return@background "✗ No Philips devices found. Is the heater plugged in and on Wi-Fi?"
            val labelled = found.map { ip ->
                val name = runCatching {
                    val s = PhilipsClient(ip).use { it.status() }
                    "${s.optString(PhilipsClient.KEY_NAME, "Philips")} (${s.optString(PhilipsClient.KEY_MODEL, "?")})"
                }.getOrNull()
                ip to (name ?: "Philips device")
            }
            runOnUiThread { choose("Pick your heater", labelled, heaterIp) }
            "Found ${found.size} Philips device(s)."
        }
    }

    private fun choose(title: String, options: List<Pair<String, String>>, target: EditText) {
        if (options.size == 1) {
            target.setText(options[0].first)
            save()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(options.map { "${it.second}\n${it.first}" }.toTypedArray()) { _, which ->
                target.setText(options[which].first)
                save()
            }
            .show()
    }

    private fun testLight() {
        val cfg = config()
        if (!cfg.lightConfigured) {
            lightResult.text = "✗ Fill in email, password and the light's IP first."
            return
        }
        save()
        background(lightResult, "Connecting to the light…") {
            try {
                val client = TapoClient(cfg.lightIp, cfg.email, cfg.password)
                val info = client.deviceInfo()
                val presets = runCatching { client.request("get_preset_rules").optJSONArray("states")?.length() }.getOrNull()
                val nick = runCatching { String(Base64.getDecoder().decode(info.optString("nickname")), Charsets.UTF_8) }.getOrDefault("?")
                HomeController.submit(this) { HomeController.refreshLight(it) }
                "✓ Connected to $nick (${info.optString("model")})\n" +
                    "On: ${info.optBoolean("device_on")} · Brightness: ${info.optInt("brightness")}%\n" +
                    "Presets found: ${presets ?: "none, using defaults"}"
            } catch (e: Exception) {
                "✗ " + HomeController.friendlyError("light", e)
            }
        }
    }

    private fun testHeater() {
        val cfg = config()
        if (!cfg.heaterConfigured) {
            heaterResult.text = "✗ Fill in the heater's IP first (or tap Find)."
            return
        }
        save()
        background(heaterResult, "Connecting to the heater… (it can take up to 25 seconds)") {
            try {
                val status = PhilipsClient(cfg.heaterIp).use { it.status(patient = true) }
                HomeController.submit(this) { HomeController.refreshHeater(it) }
                val summary = if (status.has(PhilipsClient.KEY_POWER)) {
                    "✓ Connected to ${status.optString(PhilipsClient.KEY_NAME)} (${status.optString(PhilipsClient.KEY_MODEL)})\n" +
                        "On: ${status.optInt(PhilipsClient.KEY_POWER) == 1} · Target: ${status.optInt(PhilipsClient.KEY_TARGET_TEMP)}°C · " +
                        "Room: ${status.optInt(PhilipsClient.KEY_ROOM_TEMP) / 10.0}°C"
                } else {
                    "⚠ Connected, but the heater reports different settings than expected. " +
                        "Copy the details below and paste them into your chat with Claude."
                }
                "$summary\n\nRaw status:\n${status.toString(1)}"
            } catch (e: Exception) {
                "✗ " + HomeController.friendlyError("heater", e) + "\n\nDetails: " + (e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun pinWidget() {
        val manager = getSystemService(AppWidgetManager::class.java)
        if (manager?.isRequestPinAppWidgetSupported == true) {
            manager.requestPinAppWidget(ComponentName(this, HomeWidgetProvider::class.java), null, null)
        } else {
            Toast.makeText(this, "Long-press your home screen → Widgets → Home Controls", Toast.LENGTH_LONG).show()
        }
    }
}
