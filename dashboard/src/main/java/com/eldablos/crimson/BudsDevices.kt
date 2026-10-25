package com.eldablos.crimson

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

@SuppressLint("MissingPermission") // every entry point checks hasPermission() first
object BudsDevices {
    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun adapter(context: Context): BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    fun name(device: BluetoothDevice): String =
        try {
            device.name
        } catch (e: SecurityException) {
            null
        } ?: device.address

    fun paired(context: Context): List<BluetoothDevice> {
        if (!hasPermission(context)) return emptyList()
        return try {
            adapter(context)?.bondedDevices?.toList().orEmpty()
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    fun pairedBuds(context: Context): List<BluetoothDevice> =
        paired(context).filter { name(it).contains("buds", ignoreCase = true) }

    /** The earbuds picked in the app, or the first paired Galaxy Buds. */
    fun selected(context: Context): BluetoothDevice? {
        val address = BudsState.selectedAddress(context)
        return paired(context).firstOrNull { it.address == address } ?: pairedBuds(context).firstOrNull()
    }

    /**
     * Whether [device] is connected to the phone right now, or null if Android won't say. Uses
     * BluetoothDevice.isConnected(), which apps can call but which isn't in the public SDK.
     */
    fun isConnected(device: BluetoothDevice): Boolean? =
        try {
            BluetoothDevice::class.java.getMethod("isConnected").invoke(device) as? Boolean
        } catch (e: Exception) {
            null
        }

    fun isTarget(context: Context, device: BluetoothDevice): Boolean {
        val address = BudsState.selectedAddress(context)
        return if (address != null) device.address == address else name(device).contains("buds", ignoreCase = true)
    }
}
