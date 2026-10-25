package com.eldablos.crimson

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/** Connects automatically when the earbuds connect to the phone, and lets go when they disconnect. */
class BluetoothReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BudsDevices.hasPermission(context)) return
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
        } ?: return
        if (!BudsDevices.isTarget(context, device)) return

        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                BudsState.setPresent(context, true, BudsDevices.name(device))
                // If Android blocks the start, the widget's Buds row shows "tap to connect" instead.
                BudsService.start(context, BudsService.ACTION_CONNECT)
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                context.stopService(Intent(context, BudsService::class.java))
                BudsState.setPresent(context, false)
                BudsState.setConnection(context, BudsState.Connection.DISCONNECTED)
            }
        }
    }
}
