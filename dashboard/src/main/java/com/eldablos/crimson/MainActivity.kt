package com.eldablos.crimson

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var placeText: TextView
    private lateinit var stepsStatus: TextView
    private lateinit var calendarStatus: TextView
    private lateinit var cityInput: EditText
    private lateinit var goalInput: EditText
    private lateinit var weatherPill: TextView
    private lateinit var stepsPill: TextView
    private lateinit var calendarPill: TextView
    private lateinit var opacityPill: TextView
    private lateinit var weatherAppButton: Button
    private lateinit var budsStatus: TextView
    private lateinit var budsPill: TextView
    private lateinit var budsButton: Button
    private lateinit var budsChooseButton: Button

    private val budsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        runOnUiThread { renderBuds() }
    }

    private enum class Status { ON, PARTIAL, OFF }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        placeText = findViewById(R.id.place_text)
        stepsStatus = findViewById(R.id.steps_status)
        calendarStatus = findViewById(R.id.calendar_status)
        cityInput = findViewById(R.id.city_input)
        goalInput = findViewById(R.id.goal_input)
        weatherPill = findViewById(R.id.weather_pill)
        stepsPill = findViewById(R.id.steps_pill)
        calendarPill = findViewById(R.id.calendar_pill)

        findViewById<Button>(R.id.search_button).setOnClickListener { searchCity() }
        findViewById<Button>(R.id.my_location_button).setOnClickListener { useMyLocation() }
        weatherAppButton = findViewById(R.id.weather_app_button)
        weatherAppButton.setOnClickListener { pickWeatherApp() }
        budsStatus = findViewById(R.id.buds_status)
        budsPill = findViewById(R.id.buds_pill)
        budsButton = findViewById(R.id.buds_button)
        budsChooseButton = findViewById(R.id.buds_choose_button)
        budsButton.setOnClickListener {
            if (BudsDevices.hasPermission(this)) {
                if (!BudsService.start(this, BudsService.ACTION_CONNECT)) toast("Couldn't start the Buds connection")
            } else {
                requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_BLUETOOTH)
            }
        }
        budsChooseButton.setOnClickListener { chooseBuds() }
        findViewById<Button>(R.id.steps_button).setOnClickListener {
            requestPermissions(arrayOf(Steps.PERMISSION_READ, Steps.PERMISSION_BACKGROUND), REQ_STEPS)
        }
        findViewById<Button>(R.id.calendar_button).setOnClickListener {
            requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), REQ_CALENDAR)
        }
        findViewById<Button>(R.id.goal_button).setOnClickListener { saveGoal() }
        findViewById<Button>(R.id.add_widget_button).setOnClickListener { pinWidget() }
        goalInput.setText(DashboardStore.goal(this).toString())

        opacityPill = findViewById(R.id.opacity_pill)
        findViewById<SeekBar>(R.id.opacity_slider).apply {
            progress = DashboardStore.opacity(this@MainActivity)
            opacityPill.text = "$progress%"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    opacityPill.text = "$value%"
                }

                override fun onStartTrackingTouch(bar: SeekBar) {}

                override fun onStopTrackingTouch(bar: SeekBar) {
                    DashboardStore.setOpacity(this@MainActivity, bar.progress)
                    CrimsonWidgetProvider.refreshAll(this@MainActivity)
                }
            })
        }
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        if (intent.getBooleanExtra(EXTRA_PICK_WEATHER_APP, false)) pickWeatherApp()
    }

    override fun onResume() {
        super.onResume()
        BudsState.prefs(this).registerOnSharedPreferenceChangeListener(budsListener)
        render()
        refreshWidget()
    }

    override fun onPause() {
        BudsState.prefs(this).unregisterOnSharedPreferenceChangeListener(budsListener)
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            useMyLocation()
        }
        if (requestCode == REQ_BLUETOOTH && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            BudsService.start(this, BudsService.ACTION_CONNECT)
        }
        render()
        refreshWidget()
    }

    private fun render() {
        val place = DashboardStore.place(this)
        placeText.text = place?.let { "Showing weather for ${it.name}" } ?: "Search for your town, or use your location."
        setPill(weatherPill, if (place != null) Status.ON else Status.OFF)
        weatherAppButton.text = "Weather tap opens: ${weatherAppName() ?: "choose an app"}"

        val steps = when {
            !Steps.hasPermission(this) -> Status.OFF
            !Steps.hasBackgroundPermission(this) -> Status.PARTIAL
            else -> Status.ON
        }
        stepsStatus.text = when (steps) {
            Status.OFF -> "Show today's steps from Samsung Health on the widget."
            Status.PARTIAL ->
                "Steps only update while this app is open. Tap below and allow \"Access data in the background\"."
            Status.ON -> "Your steps update on the widget every 15 minutes."
        }
        setPill(stepsPill, steps)

        val calendar = CalendarMonth.hasPermission(this)
        calendarStatus.text = if (calendar) "Days with events get a red dot on the widget."
        else "The calendar works without this. Allow it to see red dots on days with events."
        setPill(calendarPill, if (calendar) Status.ON else Status.OFF)
        renderBuds()
    }

    private fun renderBuds() {
        val buds = BudsState.read(this)
        val allowed = BudsDevices.hasPermission(this)
        val device = if (allowed) BudsDevices.selected(this) else null
        budsChooseButton.text = "Earbuds: ${device?.let { BudsDevices.name(it) } ?: "choose"}"
        budsChooseButton.visibility = if (allowed) android.view.View.VISIBLE else android.view.View.GONE
        budsButton.text = if (allowed) "Connect now" else "Allow Bluetooth"
        when {
            !allowed -> {
                budsStatus.text = "Allow Bluetooth so the widget can show your Buds' battery and switch ANC / Ambient."
                setPill(budsPill, Status.OFF)
            }
            buds.connection == BudsState.Connection.CONNECTED -> {
                budsStatus.text = "Connected to ${buds.deviceName ?: "your Buds"}\n${buds.batterySummary()}" +
                    (buds.mode?.let { "  ·  ${it.label}" } ?: "")
                setPill(budsPill, Status.ON, "Linked")
            }
            buds.connection == BudsState.Connection.CONNECTING -> {
                budsStatus.text = "Connecting to ${buds.deviceName ?: "your Buds"}…"
                setPill(budsPill, Status.PARTIAL, "Linking")
            }
            else -> {
                budsStatus.text = buds.message ?: "Links automatically when your Buds connect to the phone."
                setPill(budsPill, Status.PARTIAL, "Ready")
            }
        }
    }

    /** Lists paired devices, Galaxy Buds first, and saves the one the widget should talk to. */
    private fun chooseBuds() {
        val devices = BudsDevices.paired(this)
            .sortedByDescending { BudsDevices.name(it).contains("buds", ignoreCase = true) }
        if (devices.isEmpty()) {
            toast("No paired devices. Pair your Buds in Bluetooth settings first.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Your earbuds")
            .setItems(devices.map { BudsDevices.name(it) }.toTypedArray()) { _, i ->
                BudsState.setSelectedAddress(this, devices[i].address)
                renderBuds()
                BudsService.start(this, BudsService.ACTION_CONNECT)
            }
            .show()
    }

    private fun setPill(pill: TextView, status: Status, label: String? = null) {
        val (text, background, color) = when (status) {
            Status.ON -> Triple("On", R.drawable.pill_on, R.color.bone)
            Status.PARTIAL -> Triple("Partly", R.drawable.pill_warn, R.color.crimson)
            Status.OFF -> Triple("Off", R.drawable.pill_off, R.color.dust)
        }
        pill.text = label ?: text
        pill.setBackgroundResource(background)
        pill.setTextColor(getColor(color))
    }

    private fun searchCity() {
        val query = cityInput.text.toString().trim()
        if (query.isEmpty()) return
        Thread {
            val results = try {
                Weather.searchPlaces(query)
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                when {
                    results == null -> toast("Couldn't search. Check your internet connection.")
                    results.isEmpty() -> toast("No places found for \"$query\"")
                    else -> AlertDialog.Builder(this)
                        .setTitle("Pick your place")
                        .setItems(results.map { it.name }.toTypedArray()) { _, i -> setPlace(results[i]) }
                        .show()
                }
            }
        }.start()
    }

    private fun useMyLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOCATION)
            return
        }
        val lm = getSystemService(LocationManager::class.java)
        val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .firstOrNull { lm.isProviderEnabled(it) }
        if (provider == null) {
            toast("Turn on Location in quick settings, then try again")
            return
        }
        toast("Finding you…")
        lm.getCurrentLocation(provider, null, mainExecutor) { location ->
            if (location == null) {
                toast("Couldn't get your location. Try searching for your town instead.")
                return@getCurrentLocation
            }
            Geocoder(this, java.util.Locale.getDefault()).getFromLocation(location.latitude, location.longitude, 1, object : Geocoder.GeocodeListener {
                override fun onGeocode(addresses: MutableList<android.location.Address>) {
                    val a = addresses.firstOrNull()
                    val name = a?.locality ?: a?.subAdminArea ?: "My location"
                    runOnUiThread { setPlace(Place(name, location.latitude, location.longitude)) }
                }

                override fun onError(errorMessage: String?) {
                    runOnUiThread { setPlace(Place("My location", location.latitude, location.longitude)) }
                }
            })
        }
    }

    private fun weatherAppName(): String? {
        val saved = DashboardStore.weatherApp(this)?.let { android.content.ComponentName.unflattenFromString(it) }
            ?: return null
        return OpenAppActivity.launcherApps(this)
            .firstOrNull { it.activityInfo.packageName == saved.packageName && it.activityInfo.name == saved.className }
            ?.let { OpenAppActivity.appLabel(packageManager, it) }
    }

    /** Lists every app, weather apps first, and saves the one picked for the widget's weather tap. */
    private fun pickWeatherApp() {
        val apps = OpenAppActivity.launcherApps(this)
            .map { it to OpenAppActivity.appLabel(packageManager, it) }
            .sortedWith(
                compareByDescending<Pair<android.content.pm.ResolveInfo, String>> { (info, label) ->
                    label.contains("weather", ignoreCase = true) ||
                        info.activityInfo.packageName.contains("weather", ignoreCase = true) ||
                        info.activityInfo.packageName == "com.sec.android.daemonapp"
                }.thenBy { it.second.lowercase() }
            )
        if (apps.isEmpty()) {
            toast("Couldn't list your apps")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Which app should the weather open?")
            .setItems(apps.map { it.second }.toTypedArray()) { _, i ->
                val info = apps[i].first.activityInfo
                DashboardStore.setWeatherApp(this, android.content.ComponentName(info.packageName, info.name).flattenToString())
                render()
                toast("Weather tap now opens ${apps[i].second}")
            }
            .show()
    }

    private fun setPlace(place: Place) {
        DashboardStore.setPlace(this, place)
        render()
        refreshWidget()
    }

    private fun saveGoal() {
        val goal = goalInput.text.toString().toIntOrNull()
        if (goal == null || goal <= 0) {
            toast("Enter a number of steps, like 10000")
            return
        }
        DashboardStore.setGoal(this, goal)
        toast("Goal saved")
        CrimsonWidgetProvider.refreshAll(this)
    }

    private fun refreshWidget() {
        val context = applicationContext
        Thread { RefreshJob.refresh(context) }.start()
    }

    private fun pinWidget() {
        val manager = AppWidgetManager.getInstance(this)
        if (manager.isRequestPinAppWidgetSupported) {
            manager.requestPinAppWidget(ComponentName(this, CrimsonWidgetProvider::class.java), null, null)
        } else {
            toast("Long-press your home screen → Widgets → Crimson Widget")
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    companion object {
        const val EXTRA_PICK_WEATHER_APP = "pick_weather_app"
        private const val REQ_STEPS = 1
        private const val REQ_CALENDAR = 2
        private const val REQ_LOCATION = 3
        private const val REQ_BLUETOOTH = 4
    }
}
