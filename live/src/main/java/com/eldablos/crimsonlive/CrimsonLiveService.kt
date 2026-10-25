package com.eldablos.crimsonlive

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Plays the pre-rendered Crimson Live loop (res/raw/crimson_live.mp4) as the wallpaper, with depth:
 * tilting the phone shifts the foreground more than the background. The video is decoded by the phone's
 * hardware, and video, drawing and the motion sensor all stop whenever the wallpaper isn't visible.
 */
class CrimsonLiveService : WallpaperService() {
    override fun onCreateEngine(): Engine = ParallaxEngine()

    private inner class ParallaxEngine : Engine(), SensorEventListener {
        private val thread = HandlerThread("crimson-live").apply { start() }
        private val handler = Handler(thread.looper)
        private val sensors = getSystemService(SensorManager::class.java)
        private val tiltSensor = sensors?.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        // Everything below is only touched on [thread].
        private var renderer: LayerRenderer? = null
        private var texture: SurfaceTexture? = null
        private var player: MediaPlayer? = null
        private val texMatrix = FloatArray(16)
        private var frameReady = false
        private var width = 1
        private var height = 1
        private var visible = false
        private var strength = Settings.DEFAULT_TILT / 100f

        // Tilt: the resting angle drifts towards however the phone is held, so any angle feels neutral.
        private var hasRest = false
        private var restX = 0f
        private var restY = 0f
        private var targetX = 0f
        private var targetY = 0f
        private var tiltX = 0f
        private var tiltY = 0f
        private var page = 0f
        private var drawnPage = 0f

        private val loop = object : Runnable {
            override fun run() {
                drawIfNeeded()
                if (visible) handler.postDelayed(this, FRAME_MS)
            }
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            handler.post { start(holder.surface) }
        }

        private fun start(surface: Surface) {
            try {
                val r = LayerRenderer(surface)
                renderer = r
                val st = SurfaceTexture(r.textureId).apply {
                    setOnFrameAvailableListener({ frameReady = true }, handler)
                }
                texture = st
                player = MediaPlayer().apply {
                    setDataSource(this@CrimsonLiveService, Uri.parse("android.resource://$packageName/${R.raw.crimson_live}"))
                    setSurface(Surface(st))
                    isLooping = true
                    setVolume(0f, 0f)
                    prepare()
                    if (visible) start()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't start the wallpaper", e)
                release()
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
            super.onSurfaceChanged(holder, format, w, h)
            handler.post {
                width = w
                height = h
                frameReady = true // redraw at the new size
            }
        }

        override fun onVisibilityChanged(isVisible: Boolean) {
            handler.post {
                visible = isVisible
                handler.removeCallbacks(loop)
                if (isVisible) {
                    strength = Settings.tilt(this@CrimsonLiveService) / 100f
                    hasRest = false
                    player?.start()
                    if (strength > 0f) tiltSensor?.let { sensors?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, handler) }
                    handler.post(loop)
                } else {
                    player?.pause()
                    sensors?.unregisterListener(this)
                }
            }
        }

        override fun onOffsetsChanged(xOffset: Float, yOffset: Float, xStep: Float, yStep: Float, xPixels: Int, yPixels: Int) {
            handler.post { page = xOffset - 0.5f }
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

        /** Draws when the video has a new frame or the layers are still moving; otherwise saves the GPU. */
        private fun drawIfNeeded() {
            val r = renderer ?: return
            val st = texture ?: return
            tiltX += (targetX - tiltX) * EASE
            tiltY += (targetY - tiltY) * EASE
            val moving = abs(targetX - tiltX) > 0.002f || abs(targetY - tiltY) > 0.002f || page != drawnPage
            if (!frameReady && !moving) return
            if (frameReady) {
                frameReady = false
                st.updateTexImage()
                st.getTransformMatrix(texMatrix)
            }
            drawnPage = page
            // The foreground moves the most; the background, being far away, a third as much.
            val fgX = tiltX * FG_SHIFT * strength + page * PAGE_SHIFT
            val fgY = -tiltY * FG_SHIFT * strength
            r.draw(texMatrix, fgX * BG_RATIO, fgY * BG_RATIO, fgX, fgY, width, height)
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
            handler.post { release() }
            thread.quitSafely()
            super.onDestroy()
        }

        private fun release() {
            visible = false
            handler.removeCallbacks(loop)
            sensors?.unregisterListener(this)
            player?.release()
            player = null
            texture?.release()
            texture = null
            renderer?.release()
            renderer = null
        }
    }

    companion object {
        private const val TAG = "CrimsonLive"
        private const val FRAME_MS = 16L
        private const val REST_FOLLOW = 0.015f // how quickly "level" follows the way you hold the phone
        private const val TILT_RANGE = 0.3f // tilt (in g) for the full effect
        private const val EASE = 0.12f
        private const val FG_SHIFT = 0.03f // foreground shift at full tilt and strength, as a fraction of the picture
        private const val BG_RATIO = 0.35f
        private const val PAGE_SHIFT = 0.016f
    }
}

/** Settings shared between the app screen and the wallpaper. */
object Settings {
    const val DEFAULT_TILT = 60
    private fun prefs(context: Context) = context.getSharedPreferences("crimson_live", Context.MODE_PRIVATE)
    fun tilt(context: Context): Int = prefs(context).getInt("tilt", DEFAULT_TILT)
    fun setTilt(context: Context, value: Int) = prefs(context).edit().putInt("tilt", value).apply()
}
