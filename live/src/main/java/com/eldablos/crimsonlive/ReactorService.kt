package com.eldablos.crimsonlive

import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Crimson Reactor: a 3D arc reactor in front of an endless hex tunnel, drawn live on the GPU. It tilts
 * with the phone and reacts to the Crimson Launcher, which sends it the theme's colours, messages (a
 * shockwave in the app's colour), calls, taps and the time-of-day mode. It watches the battery itself
 * (charging fills the tunnel, low battery beats) and powers up on each unlock.
 *
 * Battery: it renders at under half the screen's resolution, runs at a steady 30 fps (20 in power
 * saving) and stops completely whenever it's hidden: apps open or the screen off.
 */
class ReactorService : WallpaperService() {
    override fun onCreateEngine(): Engine = ReactorEngine()

    private inner class ReactorEngine : Engine(), SensorEventListener {
        private val thread = HandlerThread("crimson-reactor").apply { start() }
        private val handler = Handler(thread.looper)
        private val sensors = getSystemService(SensorManager::class.java)
        private val tiltSensor = sensors?.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        private val power = getSystemService(PowerManager::class.java)
        private val prefs = getSharedPreferences("reactor", Context.MODE_PRIVATE)

        // Everything below is only touched on [thread].
        private var renderer: ReactorRenderer? = null
        private var width = 1
        private var height = 1
        private var visible = false
        private val started = SystemClock.uptimeMillis()

        private var primary = prefs.getInt("primary", 0xFFE04358.toInt())
        private var secondary = prefs.getInt("secondary", 0xFFFFD27A.toInt())
        private var centerY = prefs.getFloat("centerY", .43f)
        private var pulseAt = -100f
        private var pulseColor = 0xFF25D366.toInt()
        private var callTarget = 0f
        private var call = 0f
        private var callColor = 0xFF3DDC84.toInt()
        private var charging = false
        private var charge = 0f
        private var level = 1f
        private var low = false
        private var tapX = .5f
        private var tapY = .5f
        private var tapAt = -100f
        private var bootAt = -100f
        private var energyTarget = prefs.getFloat("energy", 1f)
        private var energy = energyTarget

        // Tilt: the resting angle drifts towards however the phone is held, so any angle feels neutral.
        private var hasRest = false
        private var restX = 0f
        private var restY = 0f
        private var targetX = 0f
        private var targetY = 0f
        private var tiltX = 0f
        private var tiltY = 0f

        private val loop = object : Runnable {
            override fun run() {
                draw()
                if (!visible) return
                // One steady pace, so the motion never stutters or pauses: a little slower in power saving.
                val saver = power?.isPowerSaveMode == true
                handler.postDelayed(this, if (saver) SAVER_MS else FRAME_MS)
            }
        }

        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_USER_PRESENT -> handler.post {
                        bootAt = now()
                        wake()
                    }
                    Intent.ACTION_BATTERY_CHANGED -> handler.post { battery(intent) }
                }
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(false)
            registerReceiver(receiver, IntentFilter(Intent.ACTION_USER_PRESENT))
        }

        private fun now() = (SystemClock.uptimeMillis() - started) / 1000f

        /** Something happened: draw it straight away rather than waiting for the next frame. */
        private fun wake() {
            if (visible) {
                handler.removeCallbacks(loop)
                handler.post(loop)
            }
        }

        private fun battery(intent: Intent) {
            val l = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (l >= 0 && scale > 0) level = l / scale.toFloat()
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            if (plugged != charging) {
                charging = plugged
                // Plugged in: a surge of energy rolls out from the core.
                if (plugged) {
                    pulseAt = now()
                    pulseColor = Color.rgb(255, 214, 110)
                }
                wake()
            }
            low = !plugged && level <= .15f
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            handler.post {
                try {
                    val source = resources.openRawResource(R.raw.reactor).bufferedReader().use { it.readText() }
                    renderer = ReactorRenderer(holder.surface, source)
                } catch (e: Exception) {
                    Log.w(TAG, "Couldn't start the reactor", e)
                    renderer?.release()
                    renderer = null
                }
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
            super.onSurfaceChanged(holder, format, w, h)
            handler.post {
                width = w
                height = h
                draw()
            }
        }

        override fun onVisibilityChanged(isVisible: Boolean) {
            handler.post {
                visible = isVisible
                handler.removeCallbacks(loop)
                if (isVisible) {
                    hasRest = false
                    registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { battery(it) }
                    tiltSensor?.let { sensors?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI, handler) }
                    handler.post(loop)
                } else {
                    sensors?.unregisterListener(this)
                    // Keep listening for unlocks; drop the battery updates while hidden.
                    try {
                        unregisterReceiver(receiver)
                    } catch (e: Exception) {
                        // Not registered.
                    }
                    registerReceiver(receiver, IntentFilter(Intent.ACTION_USER_PRESENT))
                }
            }
        }

        /** Messages from the Crimson Launcher, and taps on the home screen. */
        override fun onCommand(action: String?, x: Int, y: Int, z: Int, extras: Bundle?, resultRequested: Boolean): Bundle? {
            handler.post {
                when (action) {
                    THEME -> {
                        primary = extras?.getInt("primary", primary) ?: primary
                        secondary = extras?.getInt("secondary", secondary) ?: secondary
                        centerY = extras?.getFloat("centerY", centerY) ?: centerY
                        prefs.edit().putInt("primary", primary).putInt("secondary", secondary).putFloat("centerY", centerY).apply()
                        wake()
                    }
                    PULSE -> {
                        pulseColor = extras?.getInt("color", primary) ?: primary
                        pulseAt = now()
                        wake()
                    }
                    CALL -> {
                        callTarget = if (extras?.getBoolean("on") == true) 1f else 0f
                        callColor = extras?.getInt("color", callColor) ?: callColor
                        wake()
                    }
                    MODE -> {
                        energyTarget = extras?.getFloat("energy", 1f) ?: 1f
                        prefs.edit().putFloat("energy", energyTarget).apply()
                        wake()
                    }
                    WallpaperManager.COMMAND_TAP -> {
                        tapX = x / width.coerceAtLeast(1).toFloat()
                        tapY = y / height.coerceAtLeast(1).toFloat()
                        tapAt = now()
                        wake()
                    }
                }
            }
            return null
        }

        override fun onSensorChanged(event: SensorEvent) {
            val x = event.values[0] / SensorManager.GRAVITY_EARTH
            val y = event.values[1] / SensorManager.GRAVITY_EARTH
            if (!hasRest) {
                restX = x
                restY = y
                hasRest = true
            }
            restX += (x - restX) * REST_FOLLOW
            restY += (y - restY) * REST_FOLLOW
            targetX = ((x - restX) / TILT_RANGE).coerceIn(-1f, 1f)
            targetY = ((y - restY) / TILT_RANGE).coerceIn(-1f, 1f)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

        private fun draw() {
            val r = renderer ?: return
            val t = now()
            tiltX += (targetX - tiltX) * EASE
            tiltY += (targetY - tiltY) * EASE
            call += (callTarget - call) * .08f
            charge += ((if (charging) 1f else 0f) - charge) * .06f
            energy += (energyTarget - energy) * .05f
            try {
                r.begin(width, height, SCALE)
                // Time wraps every hour to keep the shader's maths precise.
                r.set("uTime", t % 3600f)
                r.set("uPrimary", Color.red(primary) / 255f, Color.green(primary) / 255f, Color.blue(primary) / 255f)
                r.set("uSecondary", Color.red(secondary) / 255f, Color.green(secondary) / 255f, Color.blue(secondary) / 255f)
                r.set("uTilt", -tiltX, tiltY)
                r.set("uCenterY", centerY)
                r.set("uPulseT", (t - pulseAt).coerceAtMost(99f))
                val pc = if (call > .05f) callColor else pulseColor
                r.set("uPulseColor", Color.red(pc) / 255f, Color.green(pc) / 255f, Color.blue(pc) / 255f)
                r.set("uCall", call)
                r.set("uCharge", charge)
                r.set("uLevel", level)
                r.set("uTap", tapX, tapY, (t - tapAt).coerceAtMost(99f))
                r.set("uBoot", (t - bootAt).coerceAtMost(99f))
                r.set("uEnergy", energy)
                r.set("uLow", if (low) 1f else 0f)
                r.end(width, height)
            } catch (e: Exception) {
                Log.w(TAG, "Frame failed", e)
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            val done = CountDownLatch(1)
            handler.post {
                release()
                done.countDown()
            }
            done.await(2, TimeUnit.SECONDS)
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            try {
                unregisterReceiver(receiver)
            } catch (e: Exception) {
                // Not registered.
            }
            handler.post { release() }
            thread.quitSafely()
            super.onDestroy()
        }

        private fun release() {
            visible = false
            handler.removeCallbacks(loop)
            sensors?.unregisterListener(this)
            renderer?.release()
            renderer = null
        }
    }

    companion object {
        private const val TAG = "CrimsonReactor"
        const val THEME = "com.eldablos.crimson.THEME"
        const val PULSE = "com.eldablos.crimson.PULSE"
        const val CALL = "com.eldablos.crimson.CALL"
        const val MODE = "com.eldablos.crimson.MODE"
        private const val FRAME_MS = 33L
        private const val SAVER_MS = 50L
        private const val SCALE = .45f
        private const val REST_FOLLOW = 0.015f
        private const val TILT_RANGE = 0.3f
        private const val EASE = 0.12f
    }
}
