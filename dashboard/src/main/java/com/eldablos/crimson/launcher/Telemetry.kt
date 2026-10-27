package com.eldablos.crimson.launcher

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.media.session.PlaybackState
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.SystemClock
import android.telephony.TelephonyManager
import com.eldablos.crimson.BudsState
import com.eldablos.crimson.DashboardStore
import com.eldablos.crimson.NowPlaying
import com.eldablos.crimson.WeatherNow

/** Everything the HUD shows about the phone, read fresh each second from the system and the widget's data. */
class Telemetry(
    val battery: Int,
    val charging: Boolean,
    /** Minutes until full while charging, if Android can tell. */
    val chargeMinutes: Long?,
    val signal: Int,
    val network: String,
    val budsLeft: Int,
    val budsRight: Int,
    val budsCase: Int,
    val weather: WeatherNow?,
    val place: String?,
    val steps: Long?,
    val goal: Int,
    val alarm: Long?,
    val track: NowPlaying.Track?,
    val art: Bitmap?,
    /** Song position and length in ms, when the player reports them. */
    val position: Long,
    val duration: Long,
    /** A VPN (like NordVPN) is connected. */
    val vpn: Boolean = false,
) {
    val budsPresent: Boolean get() = budsLeft > 0 || budsRight > 0

    companion object {
        private var artFor: String? = null
        private var artCache: Bitmap? = null

        fun read(context: Context): Telemetry {
            val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val plugged = (batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
            val percent = if (level >= 0 && scale > 0) level * 100 / scale else 0

            val signal = try {
                context.getSystemService(TelephonyManager::class.java)?.signalStrength?.level ?: 0
            } catch (e: Exception) {
                0
            }
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            val caps = connectivity?.getNetworkCapabilities(connectivity.activeNetwork)
            // With a VPN on, the active network is the VPN: look at the real one under it too.
            @Suppress("DEPRECATION")
            val all = try {
                connectivity?.allNetworks.orEmpty().mapNotNull { connectivity?.getNetworkCapabilities(it) }
            } catch (e: Exception) {
                emptyList()
            }
            val vpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true ||
                all.any { it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
            val real = listOfNotNull(caps) + all.filter { !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
            val network = when {
                caps == null -> "NO NET"
                real.any { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } -> "WIFI"
                real.any { it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) } -> "DATA"
                else -> "NET"
            }

            val buds = BudsState.read(context)
            val track = NowPlaying.read(context)
            val key = track?.let { "${it.title}\u0000${it.artist}" }
            if (key != artFor) {
                artFor = key
                artCache = if (track != null) NowPlaying.art(context) else null
            }
            val controller = if (track != null) NowPlaying.activeController(context) else null
            val state = controller?.playbackState
            val duration = controller?.metadata?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) ?: 0L
            val position = state?.let {
                if (it.state == PlaybackState.STATE_PLAYING) {
                    it.position + ((SystemClock.elapsedRealtime() - it.lastPositionUpdateTime) * it.playbackSpeed).toLong()
                } else {
                    it.position
                }
            } ?: 0L

            return Telemetry(
                battery = percent,
                charging = plugged,
                chargeMinutes = if (plugged) {
                    context.getSystemService(BatteryManager::class.java)?.computeChargeTimeRemaining()
                        ?.takeIf { it > 0 }?.let { it / 60_000 }
                } else {
                    null
                },
                signal = signal,
                network = network,
                vpn = vpn,
                budsLeft = if (buds.present) buds.left else -1,
                budsRight = if (buds.present) buds.right else -1,
                budsCase = if (buds.present) buds.case else -1,
                weather = DashboardStore.weather(context),
                place = DashboardStore.place(context)?.name?.substringBefore(','),
                steps = DashboardStore.steps(context),
                goal = DashboardStore.goal(context),
                alarm = context.getSystemService(AlarmManager::class.java)?.nextAlarmClock?.triggerTime,
                track = track,
                art = artCache,
                position = position.coerceAtLeast(0),
                duration = duration,
            )
        }
    }
}
