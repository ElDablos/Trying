package com.eldablos.crimson.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.Settings
import com.eldablos.crimson.R
import com.eldablos.crimson.core.SysLog
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Rings an alarm until you stop or snooze it: a foreground service (allowed to start from an exact alarm),
 * holding the CPU awake, playing on the alarm channel at your chosen volume (full by default, put back after),
 * through silent mode and Do Not Disturb, vibrating, with a full-screen alarm over the lock screen.
 * If your sound won't play it falls back to the phone's alarm sound, then its ringtone, then loud beeps:
 * it never rings silently. Left alone, it rings for [Alarms.ringMinutes] and then snoozes (up to three times).
 */
class AlarmService : Service() {
    /** What's ringing now, for the alarm screen. */
    class Ringing(val id: Int, val label: String, val time: LocalTime, val shiftTimes: String?, val index: Int, val of: Int, val shift: LocalDate?, val autoSnoozes: Int,
                  val brief: String?, val snoozed: Boolean)

    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var tone: ToneGenerator? = null
    private var wake: PowerManager.WakeLock? = null
    private var savedVolume = -1
    private val timeout = Runnable { current?.let { r -> if (r.autoSnoozes < 3) snooze(r, auto = true) else stop() } }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Foreground first, whatever happens next: Android stops a service that's late to say so.
        foreground(current?.label ?: "Alarm", current?.shiftTimes)
        when (intent?.action) {
            ACTION_RING -> start(intent.getIntExtra("id", 0), intent.getIntExtra("auto", 0))
            ACTION_STOP -> stop()
            ACTION_SNOOZE -> current?.let { snooze(it, auto = false) } ?: stop()
            ACTION_SKIP_REST -> { current?.let { Alarms.skipRest(this, it.shift) }; stop() }
            else -> if (current == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun start(id: Int, autoSnoozes: Int) {
        val a = Alarms.get(this, id)
        if (a == null || !a.enabled) {
            if (current == null) stopSelf()
            return
        }
        // The morning's details: fresh if the phone's unlocked, otherwise as worked out when the alarm was set.
        val fresh = if (getSystemService(android.os.UserManager::class.java)?.isUserUnlocked == true && a.shift != null)
            runCatching { com.eldablos.crimson.core.Shifts.all(this)[a.shift]?.let { com.eldablos.crimson.assistant.Nudges.headsLine(this, a.shift, it) } }.getOrNull() else null
        val r = Ringing(a.id, a.label, if (a.snoozedUntil > 0) LocalTime.now().withSecond(0) else a.time, a.shiftTimes, a.index, a.of, a.shift, autoSnoozes,
            fresh ?: a.brief, a.snoozedUntil > 0)
        val already = current != null
        current = r
        foreground(r.label, r.shiftTimes)
        Upcoming.clear(this, id)
        // It's rung: the list moves on (and the next one is set) straight away, so a crash can't make it ring forever.
        Alarms.rang(this, id)
        if (!already) {
            wake = getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "crimson:alarm")?.apply { acquire(12 * 60_000L) }
            loud()
            play()
            buzz()
        }
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, Alarms.ringMinutes(this) * 60_000L)
        // Show the alarm screen (the full-screen notification does it on the lock screen; this covers the rest).
        try { startActivity(Intent(this, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)) } catch (_: Exception) {}
        listener?.invoke()
    }

    private fun channel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Alarms ringing", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Shown while an alarm rings"
            setSound(null, null)
            enableVibration(false)
            setBypassDnd(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
    }

    private fun foreground(label: String, times: String?) {
        channel()
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val screen = PendingIntent.getActivity(this, 4501, Intent(this, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION), flags)
        fun act(a: String, code: Int) = PendingIntent.getService(this, code, Intent(this, AlarmService::class.java).setAction(a), flags)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(label)
            .setContentText(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")) + (times?.let { " · shift $it" } ?: ""))
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setFullScreenIntent(screen, true)
            .setContentIntent(screen)
            .addAction(Notification.Action.Builder(null, "Snooze ${Alarms.snoozeMinutes(this)} min", act(ACTION_SNOOZE, 4502)).build())
            .addAction(Notification.Action.Builder(null, "Stop", act(ACTION_STOP, 4503)).build())
            .build()
        try {
            startForeground(NOTIFICATION, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        } catch (e: Exception) {
            try {
                startForeground(NOTIFICATION, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } catch (e2: Exception) {
                SysLog.error("Alarm", "Couldn't go foreground: ${e2.javaClass.simpleName}")
                getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION, n)
            }
        }
    }

    /** The alarm channel turned up to your volume (full by default); put back when it stops. */
    private fun loud() {
        val am = getSystemService(AudioManager::class.java) ?: return
        try {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            savedVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
            val want = Math.ceil(max * Alarms.volume(this) / 100.0).toInt().coerceIn(1, max)
            if (want > savedVolume) am.setStreamVolume(AudioManager.STREAM_ALARM, want, 0)
        } catch (e: Exception) {
            SysLog.warn("Alarm", "Couldn't raise the alarm volume: ${e.javaClass.simpleName}")
        }
    }

    private fun play() {
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        val choices = listOfNotNull(
            Alarms.sound(this),
            runCatching { RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM) }.getOrNull(),
            Settings.System.DEFAULT_ALARM_ALERT_URI,
            runCatching { RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE) }.getOrNull(),
        )
        for (uri in choices) {
            try {
                val p = MediaPlayer()
                p.setAudioAttributes(attrs)
                p.setDataSource(this, uri)
                p.isLooping = true
                p.prepare()
                if (Alarms.ramp(this)) rampUp(p) else p.setVolume(1f, 1f)
                p.start()
                player = p
                return
            } catch (e: Exception) {
                SysLog.warn("Alarm", "Sound $uri wouldn't play: ${e.javaClass.simpleName}")
            }
        }
        // Nothing would play (before unlock the sounds may be out of reach): loud beeps instead.
        beep()
    }

    private fun rampUp(p: MediaPlayer) {
        var v = .15f
        p.setVolume(v, v)
        val step = object : Runnable {
            override fun run() {
                if (player !== p) return
                v = (v + .05f).coerceAtMost(1f)
                runCatching { p.setVolume(v, v) }
                if (v < 1f) main.postDelayed(this, 1500)
            }
        }
        main.postDelayed(step, 1500)
    }

    private fun beep() {
        try {
            val t = ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME)
            tone = t
            val again = object : Runnable {
                override fun run() {
                    if (tone !== t) return
                    t.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 1200)
                    main.postDelayed(this, 1600)
                }
            }
            main.post(again)
        } catch (e: Exception) {
            SysLog.error("Alarm", "Couldn't beep: ${e.javaClass.simpleName}")
        }
    }

    private fun buzz() {
        if (!Alarms.vibrate(this)) return
        try {
            val v = getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 900, 700), 0), VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } catch (e: Exception) {}
    }

    private fun quiet() {
        main.removeCallbacksAndMessages(null)
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        runCatching { tone?.release() }
        tone = null
        runCatching { getSystemService(VibratorManager::class.java)?.defaultVibrator?.cancel() }
        if (savedVolume >= 0) runCatching { getSystemService(AudioManager::class.java)?.setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0) }
        savedVolume = -1
        runCatching { if (wake?.isHeld == true) wake?.release() }
        wake = null
    }

    private fun snooze(r: Ringing, auto: Boolean) {
        val minutes = Alarms.snoozeMinutes(this)
        Alarms.snooze(this, r.id, minutes, r.label, r.shiftTimes, r.brief)
        if (auto) Alarms.store(this).edit().putInt("auto:${r.id}", r.autoSnoozes + 1).apply()
        else Alarms.store(this).edit().remove("auto:${r.id}").apply()
        stop()
    }

    private fun stop() {
        quiet()
        current?.let { Alarms.store(this).edit().putLong("last_rang", System.currentTimeMillis()).apply() }
        current = null
        listener?.invoke()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        quiet()
        current = null
        listener?.invoke()
        super.onDestroy()
    }

    companion object {
        const val ACTION_RING = "com.eldablos.crimson.alarm.RING"
        const val ACTION_STOP = "com.eldablos.crimson.alarm.STOP"
        const val ACTION_SNOOZE = "com.eldablos.crimson.alarm.SNOOZE"
        const val ACTION_SKIP_REST = "com.eldablos.crimson.alarm.SKIP_REST"
        private const val CHANNEL = "alarm_ring"
        private const val NOTIFICATION = 4500

        @Volatile var current: Ringing? = null
            private set

        /** The alarm screen listens for changes (stopped, snoozed, another alarm). */
        @Volatile var listener: (() -> Unit)? = null

        fun ring(context: Context, id: Int) {
            val auto = Alarms.store(context).getInt("auto:$id", 0)
            val i = Intent(context, AlarmService::class.java).setAction(ACTION_RING).putExtra("id", id).putExtra("auto", auto)
            try {
                context.startForegroundService(i)
            } catch (e: Exception) {
                SysLog.error("Alarm", "Couldn't start ringing: ${e.javaClass.simpleName} ${e.message.orEmpty().take(80)}")
            }
        }

        fun send(context: Context, action: String) {
            runCatching { context.startService(Intent(context, AlarmService::class.java).setAction(action)) }
        }
    }
}
