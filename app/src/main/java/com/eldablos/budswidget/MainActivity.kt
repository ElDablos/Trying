package com.eldablos.budswidget

import android.Manifest
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var deviceText: TextView
    private lateinit var batteryText: TextView
    private lateinit var modeText: TextView
    private lateinit var permissionButton: Button
    private lateinit var deviceGroup: RadioGroup
    private lateinit var modeButtons: Map<NoiseMode, Button>

    private val stateListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        deviceText = findViewById(R.id.device_text)
        batteryText = findViewById(R.id.battery_text)
        modeText = findViewById(R.id.mode_text)
        permissionButton = findViewById(R.id.permission_button)
        deviceGroup = findViewById(R.id.device_group)
        modeButtons = mapOf(
            NoiseMode.OFF to findViewById<Button>(R.id.mode_off_button),
            NoiseMode.ANC to findViewById<Button>(R.id.mode_anc_button),
            NoiseMode.AMBIENT to findViewById<Button>(R.id.mode_ambient_button),
        )

        permissionButton.setOnClickListener { requestPermissionsIfNeeded() }
        findViewById<Button>(R.id.connect_button).setOnClickListener {
            if (requestPermissionsIfNeeded()) BudsService.start(this, BudsService.ACTION_CONNECT)
        }
        findViewById<Button>(R.id.disconnect_button).setOnClickListener {
            stopService(Intent(this, BudsService::class.java))
        }
        findViewById<Button>(R.id.add_widget_button).setOnClickListener { pinWidget() }
        modeButtons.forEach { (mode, button) ->
            button.setOnClickListener {
                if (requestPermissionsIfNeeded()) BudsService.start(this, BudsService.ACTION_SET_MODE, mode)
            }
        }
        deviceGroup.setOnCheckedChangeListener { group, checkedId ->
            val address = group.findViewById<RadioButton>(checkedId)?.tag as? String ?: return@setOnCheckedChangeListener
            if (address != BudsState.selectedAddress(this)) BudsState.setSelectedAddress(this, address)
        }

        requestPermissionsIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        BudsState.prefs(this).registerOnSharedPreferenceChangeListener(stateListener)
        populateDevices()
        render()
        BudsWidgetProvider.refreshAll(this)
    }

    override fun onPause() {
        BudsState.prefs(this).unregisterOnSharedPreferenceChangeListener(stateListener)
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        populateDevices()
        render()
        // Widget taps were pointing at this screen until permission was granted.
        BudsWidgetProvider.refreshAll(this)
        if (BudsDevices.hasPermission(this)) BudsService.start(this, BudsService.ACTION_CONNECT)
    }

    /** Returns true if Bluetooth permission is already granted. */
    private fun requestPermissionsIfNeeded(): Boolean {
        if (BudsDevices.hasPermission(this)) return true
        val wanted = buildList {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        requestPermissions(wanted.toTypedArray(), 1)
        return false
    }

    private fun populateDevices() {
        deviceGroup.removeAllViews()
        val buds = BudsDevices.pairedBuds(this)
        val devices = buds.ifEmpty { BudsDevices.paired(this) }
        val selected = BudsDevices.selected(this)?.address
        for (device in devices) {
            val button = RadioButton(this).apply {
                id = View.generateViewId()
                text = BudsDevices.name(device)
                tag = device.address
            }
            deviceGroup.addView(button)
            if (device.address == selected) deviceGroup.check(button.id)
        }
        findViewById<TextView>(R.id.no_devices_text).visibility =
            if (devices.isEmpty() && BudsDevices.hasPermission(this)) View.VISIBLE else View.GONE
    }

    private fun render() {
        val state = BudsState.read(this)
        permissionButton.visibility = if (BudsDevices.hasPermission(this)) View.GONE else View.VISIBLE

        val status = when (state.connection) {
            BudsState.Connection.CONNECTED -> getString(R.string.status_connected)
            BudsState.Connection.CONNECTING -> getString(R.string.status_connecting)
            BudsState.Connection.DISCONNECTED -> state.message ?: getString(R.string.status_disconnected)
        }
        deviceText.text = "${state.deviceName ?: getString(R.string.default_device_name)} — $status"
        batteryText.text = state.batterySummary()
        modeText.text = getString(R.string.mode_label, state.mode?.label ?: "--")

        val model = state.model
        modeButtons.forEach { (mode, button) ->
            button.visibility = if (mode in model.modes) View.VISIBLE else View.GONE
            val selected = state.connection == BudsState.Connection.CONNECTED && state.mode == mode
            button.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
            button.alpha = if (selected) 1f else 0.7f
        }
    }

    private fun pinWidget() {
        val manager = getSystemService(AppWidgetManager::class.java)
        if (manager != null && manager.isRequestPinAppWidgetSupported) {
            manager.requestPinAppWidget(ComponentName(this, BudsWidgetProvider::class.java), null, null)
        } else {
            Toast.makeText(this, R.string.add_widget_manually, Toast.LENGTH_LONG).show()
        }
    }
}
