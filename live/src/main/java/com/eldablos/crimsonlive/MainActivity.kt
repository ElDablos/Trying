package com.eldablos.crimsonlive

import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

/** A preview, the tilt-strength slider, and buttons that set either wallpaper: the rose window or Crimson Reactor. */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val tiltLabel = findViewById<TextView>(R.id.tilt_label)
        fun label(value: Int) {
            tiltLabel.text = if (value == 0) "Tilt effect: off" else "Tilt effect: $value%"
        }
        findViewById<SeekBar>(R.id.tilt_slider).apply {
            progress = Settings.tilt(this@MainActivity)
            label(progress)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) = label(value)
                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) = Settings.setTilt(this@MainActivity, bar.progress)
            })
        }
        findViewById<Button>(R.id.set_button).setOnClickListener { pick(CrimsonLiveService::class.java, "Crimson Live") }
        findViewById<Button>(R.id.reactor_button).setOnClickListener { pick(ReactorService::class.java, "Crimson Reactor") }
    }

    /** Opens the live wallpaper preview for [service], or the picker if that isn't available. */
    private fun pick(service: Class<*>, name: String) {
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
            WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
            ComponentName(this, service),
        )
        try {
            startActivity(intent)
        } catch (e: Exception) {
            startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
            Toast.makeText(this, "Pick $name from the list", Toast.LENGTH_LONG).show()
        }
    }
}
