package com.eldablos.crimson.launcher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.service.notification.StatusBarNotification
import android.view.WindowInsets

/**
 * Crimson Comms on its own, over whatever app is open: add it to Samsung's Edge Panel and it's one
 * swipe away anywhere. Closes back to the app with a swipe up, Back, or once something is opened.
 */
class CommsActivity : Activity(), Comms.Listener {
    private lateinit var comms: CommsView
    private var closing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Theme.load(this)
        val u = resources.displayMetrics.widthPixels / 412f
        Hud.u = u
        window.setDecorFitsSystemWindows(false)
        comms = CommsView(this, u).apply {
            onClose = { close() }
            onSettings = { startActivity(Intent(this@CommsActivity, LauncherSettingsActivity::class.java)) }
            onClosed = { finish() }
        }
        setContentView(comms)
        comms.setOnApplyWindowInsetsListener { _, insets ->
            comms.setTopSpace(insets.getInsets(WindowInsets.Type.statusBars()).top + (10 * u).toInt())
            insets
        }
        comms.show(null)
    }

    override fun onResume() {
        super.onResume()
        Comms.addListener(this)
        comms.refresh()
    }

    override fun onPause() {
        Comms.removeListener(this)
        super.onPause()
    }

    override fun onComms() = comms.refresh()

    override fun onIncoming(sbn: StatusBarNotification) {}

    private fun close() {
        if (closing) return
        closing = true
        comms.hide { finish() }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = close()

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
