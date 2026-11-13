package com.eldablos.crimson.weather

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import com.eldablos.crimson.CrimsonWidgetProvider
import com.eldablos.crimson.OpenAppActivity
import com.eldablos.crimson.core.Hud
import com.eldablos.crimson.core.SysLog
import com.eldablos.crimson.core.Theme
import java.time.LocalDate

/**
 * Crimson Weather, from the launcher days, as its own screen: opened from the widgets' weather. Now with the
 * day's outlook, 48 hourly cards, the conditions and sun, ten days; tap an hour or a day for its page. Its
 * "Samsung" button opens the phone's weather app.
 */
class WeatherActivity : Activity() {
    private lateinit var view: WeatherView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SysLog.init(this)
        Theme.load(this)
        window.setDecorFitsSystemWindows(false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        val u = resources.displayMetrics.widthPixels / 412f
        Hud.u = u
        view = WeatherView(this, u).apply {
            onClose = { finish() }
            onSamsung = {
                try { startActivity(OpenAppActivity.intent(this@WeatherActivity, "Weather", CrimsonWidgetProvider.WEATHER_APPS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
            }
        }
        setContentView(view)
        view.alpha = 0f
        view.translationY = -60 * u
        view.animate().alpha(1f).translationY(0f).setDuration(240).setInterpolator(android.view.animation.DecelerateInterpolator(1.5f)).start()
        view.load(intent.getStringExtra(EXTRA_DAY)?.let { runCatching { LocalDate.parse(it) }.getOrNull() })
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!view.back()) super.onBackPressed()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) view.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    companion object {
        const val EXTRA_DAY = "day"
    }
}
