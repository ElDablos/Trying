package com.eldablos.budswidget

import android.content.Context
import android.content.SharedPreferences

/** Last known earbud state, shared between the service, widget and app screen. */
object BudsState {
    enum class Connection { DISCONNECTED, CONNECTING, CONNECTED }

    data class Snapshot(
        val left: Int,
        val right: Int,
        val case: Int,
        val mode: NoiseMode?,
        val connection: Connection,
        val deviceName: String?,
        val message: String?,
    ) {
        val model: BudsModel get() = BudsModel.fromName(deviceName)

        fun batterySummary(): String =
            "L ${formatBattery(left)}  ·  Case ${formatBattery(case)}  ·  R ${formatBattery(right)}"
    }

    private const val PREFS = "buds_state"
    private const val K_LEFT = "left"
    private const val K_RIGHT = "right"
    private const val K_CASE = "case"
    private const val K_MODE = "mode"
    private const val K_CONNECTION = "connection"
    private const val K_NAME = "device_name"
    private const val K_MESSAGE = "message"
    private const val K_SELECTED = "selected_address"

    fun formatBattery(value: Int): String = if (value > 0) "$value%" else "--"

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun read(context: Context): Snapshot = prefs(context).run {
        Snapshot(
            left = getInt(K_LEFT, -1),
            right = getInt(K_RIGHT, -1),
            case = getInt(K_CASE, -1),
            mode = getString(K_MODE, null)?.let { runCatching { NoiseMode.valueOf(it) }.getOrNull() },
            connection = runCatching { Connection.valueOf(getString(K_CONNECTION, null)!!) }
                .getOrDefault(Connection.DISCONNECTED),
            deviceName = getString(K_NAME, null),
            message = getString(K_MESSAGE, null),
        )
    }

    fun setBattery(context: Context, left: Int, right: Int, case: Int) = edit(context) {
        putInt(K_LEFT, left)
        putInt(K_RIGHT, right)
        putInt(K_CASE, case)
    }

    fun setMode(context: Context, mode: NoiseMode) = edit(context) { putString(K_MODE, mode.name) }

    fun setDevice(context: Context, name: String?) = edit(context) { putString(K_NAME, name) }

    fun setConnection(context: Context, connection: Connection, message: String? = null) = edit(context) {
        putString(K_CONNECTION, connection.name)
        putString(K_MESSAGE, message)
    }

    fun selectedAddress(context: Context): String? = prefs(context).getString(K_SELECTED, null)

    fun setSelectedAddress(context: Context, address: String) =
        prefs(context).edit().putString(K_SELECTED, address).apply()

    private inline fun edit(context: Context, block: SharedPreferences.Editor.() -> Unit) {
        val editor = prefs(context).edit()
        editor.block()
        editor.apply()
        BudsWidgetProvider.refreshAll(context)
    }
}
