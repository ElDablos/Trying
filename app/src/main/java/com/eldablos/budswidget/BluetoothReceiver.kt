package com.eldablos.budswidget

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
            BluetoothDevice.ACTION_ACL_CONNECTED -> BudsService.start(context, BudsService.ACTION_CONNECT)
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                context.stopService(Intent(context, BudsService::class.java))
                BudsState.setConnection(context, BudsState.Connection.DISCONNECTED)
            }
        }
    }
}
