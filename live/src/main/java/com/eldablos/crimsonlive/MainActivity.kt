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

/** A preview of the wallpaper, the tilt-strength slider, and a button that opens the live wallpaper picker. */
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
        findViewById<Button>(R.id.set_button).setOnClickListener {
            val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                ComponentName(this, CrimsonLiveService::class.java),
            )
            try {
                startActivity(intent)
            } catch (e: Exception) {
                startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
                Toast.makeText(this, "Pick Crimson Live from the list", Toast.LENGTH_LONG).show()
            }
        }
    }
}
