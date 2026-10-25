package com.eldablos.crimson

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.concurrent.thread

/**
 * Holds the RFCOMM connection to the earbuds, keeps [BudsState] up to date from the
 * status messages they push, and sends listening-mode changes.
 */
@SuppressLint("MissingPermission") // started only after BudsDevices.hasPermission()
class BudsService : Service() {
    companion object {
        const val ACTION_CONNECT = "com.eldablos.crimson.BUDS_CONNECT"
        const val ACTION_SET_MODE = "com.eldablos.crimson.BUDS_SET_MODE"
        const val ACTION_STOP = "com.eldablos.crimson.BUDS_STOP"
        const val EXTRA_MODE = "mode"

        private const val TAG = "CrimsonBuds"
        private const val CHANNEL_ID = "buds_status"
        private const val NOTIFICATION_ID = 7
        private const val MAX_FAILURES = 3
        private const val RETRY_DELAY_MS = 4000L

        // Newer Buds expose a vendor UUID; older ones use the standard serial port profiles.
        private val CANDIDATE_UUIDS = listOf(
            UUID.fromString("2e73a4ad-332d-41fc-90e2-16bef06523f2"),
            UUID.fromString("00001101-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("00001102-0000-1000-8000-00805f9b34fd"),
        )

        fun intent(context: Context, action: String, mode: NoiseMode? = null): Intent =
            Intent(context, BudsService::class.java).setAction(action).apply {
                mode?.let { putExtra(EXTRA_MODE, it.name) }
            }

        /** Returns false if Android refused to start the service (it may, from the background). */
        fun start(context: Context, action: String, mode: NoiseMode? = null): Boolean {
            if (!BudsDevices.hasPermission(context)) return false
            return try {
                context.startForegroundService(intent(context, action, mode))
                true
            } catch (e: Exception) {
                // Android 12+ may refuse to start from the background; the widget falls back to "tap to connect".
                Log.w(TAG, "Could not start service", e)
                false
            }
        }
    }

    @Volatile private var running = false
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var output: OutputStream? = null
    @Volatile private var model = BudsModel.MODERN
    @Volatile private var pendingMode: NoiseMode? = null
    private var worker: Thread? = null
    private val writer = Executors.newSingleThreadExecutor()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!goForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SET_MODE -> {
                intent.getStringExtra(EXTRA_MODE)
                    ?.let { runCatching { NoiseMode.valueOf(it) }.getOrNull() }
                    ?.let { pendingMode = it }
                ensureWorker()
                queueSendMode()
            }
            else -> ensureWorker()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        closeSocket()
        writer.shutdownNow()
        // Keep any reason the connection loop already recorded.
        if (BudsState.read(this).connection != BudsState.Connection.DISCONNECTED) {
            BudsState.setConnection(this, BudsState.Connection.DISCONNECTED)
        }
        super.onDestroy()
    }

    private fun goForeground(): Boolean {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.buds_channel_name), NotificationManager.IMPORTANCE_LOW)
        )
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not enter foreground", e)
            false
        }
    }

    private fun buildNotification(): Notification {
        val state = BudsState.read(this)
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, intent(this, ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = when (state.connection) {
            BudsState.Connection.CONNECTED ->
                state.batterySummary() + (state.mode?.let { "  ·  ${it.label}" } ?: "")
            BudsState.Connection.CONNECTING -> getString(R.string.buds_connecting)
            BudsState.Connection.DISCONNECTED -> state.message ?: getString(R.string.buds_disconnected)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_buds)
            .setContentTitle(state.deviceName ?: getString(R.string.buds_default_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_buds), getString(R.string.buds_disconnect), stop
                ).build()
            )
            .build()
    }

    private fun refreshNotification() {
        if (!running) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun ensureWorker() {
        if (worker?.isAlive == true) return
        running = true
        worker = thread(name = "buds-connection") { connectionLoop() }
    }

    private fun connectionLoop() {
        var failures = 0
        var message: String? = null
        while (running && failures < MAX_FAILURES) {
            val device = BudsDevices.selected(this)
            if (device == null) {
                message = getString(
                    if (BudsDevices.adapter(this)?.isEnabled == true) R.string.buds_none_paired else R.string.buds_bluetooth_off
                )
                break
            }
            val name = BudsDevices.name(device)
            model = BudsModel.fromName(name)
            BudsState.setDevice(this, name)
            BudsState.setConnection(this, BudsState.Connection.CONNECTING)
            refreshNotification()

            val sock = openSocket(device)
            if (sock == null) {
                failures++
                message = getString(R.string.buds_connect_failed)
                if (!sleepQuietly(RETRY_DELAY_MS)) break
                continue
            }
            failures = 0
            message = null
            socket = sock
            output = sock.outputStream
            BudsState.setConnection(this, BudsState.Connection.CONNECTED)
            refreshNotification()
            queueSendMode()

            readLoop(sock)
            closeSocket()
            if (running) {
                BudsState.setConnection(this, BudsState.Connection.CONNECTING)
                refreshNotification()
                if (!sleepQuietly(RETRY_DELAY_MS)) break
            }
        }
        if (running) {
            running = false
            BudsState.setConnection(this, BudsState.Connection.DISCONNECTED, message)
            stopSelf()
        }
    }

    private fun openSocket(device: BluetoothDevice): BluetoothSocket? {
        val advertised = try {
            device.uuids?.map { it.uuid }.orEmpty()
        } catch (e: SecurityException) {
            emptyList()
        }
        for (uuid in CANDIDATE_UUIDS.sortedByDescending { it in advertised }) {
            if (!running) return null
            val sock = try {
                device.createRfcommSocketToServiceRecord(uuid)
            } catch (e: Exception) {
                continue
            }
            try {
                sock.connect()
                return sock
            } catch (e: Exception) {
                Log.d(TAG, "RFCOMM $uuid failed: ${e.message}")
                try {
                    sock.close()
                } catch (ignored: IOException) {
                }
            }
        }
        return null
    }

    private fun readLoop(sock: BluetoothSocket) {
        val decoder = BudsProtocol.Decoder()
        val buffer = ByteArray(1024)
        try {
            val input = sock.inputStream
            while (running) {
                val count = input.read(buffer)
                if (count < 0) break
                decoder.feed(buffer, count).forEach(::handleFrame)
            }
        } catch (e: IOException) {
            Log.d(TAG, "Connection closed: ${e.message}")
        }
    }

    private fun handleFrame(frame: BudsProtocol.Frame) {
        val p = frame.payload
        fun battery(index: Int): Int =
            if (index < p.size) (p[index].toInt() and 0xFF).takeIf { it in 0..100 } ?: -1 else -1
        fun flag(): Boolean = p.isNotEmpty() && p[0].toInt() != 0

        when (frame.id) {
            BudsProtocol.MSG_EXTENDED_STATUS_UPDATED -> if (p.size >= 4) {
                BudsState.setBattery(this, battery(2), battery(3), if (model.legacyFraming) -1 else battery(7))
            }
            BudsProtocol.MSG_STATUS_UPDATED -> if (p.size >= 3) {
                BudsState.setBattery(this, battery(1), battery(2), if (model.legacyFraming) -1 else battery(6))
            }
            BudsProtocol.MSG_NOISE_CONTROLS_UPDATE ->
                p.firstOrNull()?.let { NoiseMode.fromWire(it.toInt() and 0xFF) }?.let { BudsState.setMode(this, it) }
            BudsProtocol.MSG_AMBIENT_MODE_UPDATED ->
                BudsState.setMode(this, if (flag()) NoiseMode.AMBIENT else NoiseMode.OFF)
            BudsProtocol.MSG_NOISE_REDUCTION_MODE_UPDATE ->
                BudsState.setMode(this, if (flag()) NoiseMode.ANC else NoiseMode.OFF)
            else -> return
        }
        refreshNotification()
    }

    private fun queueSendMode() {
        try {
            writer.execute { sendMode() }
        } catch (e: RejectedExecutionException) {
            // service is shutting down
        }
    }

    private fun sendMode() {
        val mode = pendingMode ?: return
        val out = output ?: return // sent once connected
        val currentModel = model
        if (mode !in currentModel.modes) {
            pendingMode = null
            return
        }
        val (id, value) = when (currentModel) {
            BudsModel.LIVE -> BudsProtocol.MSG_SET_NOISE_REDUCTION to (if (mode == NoiseMode.ANC) 1 else 0)
            BudsModel.PLUS, BudsModel.ORIGINAL ->
                BudsProtocol.MSG_SET_AMBIENT_MODE to (if (mode == NoiseMode.AMBIENT) 1 else 0)
            BudsModel.MODERN -> BudsProtocol.MSG_NOISE_CONTROLS to mode.wire
        }
        try {
            out.write(BudsProtocol.encode(id, byteArrayOf(value.toByte()), currentModel.legacyFraming))
            out.flush()
            pendingMode = null
            BudsState.setMode(this, mode)
            refreshNotification()
        } catch (e: IOException) {
            Log.w(TAG, "Failed to send mode", e)
            closeSocket()
        }
    }

    private fun closeSocket() {
        output = null
        try {
            socket?.close()
        } catch (ignored: IOException) {
        }
        socket = null
    }

    /** Returns false if interrupted (service stopping). */
    private fun sleepQuietly(ms: Long): Boolean =
        try {
            Thread.sleep(ms)
            true
        } catch (e: InterruptedException) {
            false
        }
}
