package com.eldablos.crimson.launcher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.net.Uri
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.ViewAnimationUtils
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.Toast
import com.eldablos.crimson.Arcade
import com.eldablos.crimson.CrimsonWidgetProvider
import com.eldablos.crimson.MainActivity
import com.eldablos.crimson.OpenAppActivity
import com.eldablos.crimson.QueueActivity
import com.eldablos.crimson.RefreshJob
import com.eldablos.crimson.Steps
import java.time.LocalTime

/**
 * Crimson Launcher: the home screen. Hides Android's status bar and draws the HUD instead, with the
 * Comms Centre and the app list as overlays on top.
 */
class LauncherActivity : Activity(), HomeView.Host, Comms.Listener {
    private lateinit var home: HomeView
    private lateinit var effects: EffectsView
    private lateinit var comms: CommsView
    private lateinit var apps: AppsView
    private lateinit var hub: GameHubView
    private lateinit var weather: WeatherView
    private lateinit var deck: DeckView
    private lateinit var jarvis: JarvisView
    private lateinit var orb: JarvisOrb
    private lateinit var anchor: View
    private val handler = Handler(Looper.getMainLooper())
    private var mode = Mode.at(LocalTime.now().hour)

    /** The mode picked by hand, or the one the clock says. */
    private fun currentMode() = LauncherStore.modeOverride(this) ?: Mode.at(LocalTime.now().hour)
    private var games: List<ComponentName> = emptyList()
    private var allGames: List<ComponentName> = emptyList()
    private var gameCount = 0
    private var resumed = false

    private var ticks = 0
    private var lastTelemetry: Telemetry? = null

    private val tick = object : Runnable {
        override fun run() {
            // The clock moves every second, but the phone's readings (battery, signal, network, Buds) are
            // read every 5 s, or every second while music plays so its progress bar moves.
            val playing = lastTelemetry?.track?.playing == true
            val fresh = ticks++ % 5 == 0 || playing || lastTelemetry == null
            val t = if (fresh) Telemetry.read(this@LauncherActivity).also { lastTelemetry = it } else lastTelemetry!!
            if (fresh) {
                DeckData.recordBattery(this@LauncherActivity, t.battery, t.charging)
                t.steps?.let { DeckStore.recordSteps(this@LauncherActivity, it) }
                // A focus session that ran out while the alarm was held back: wrap it up.
                FocusTimer.check(this@LauncherActivity)
            }
            home.setTelemetry(t)
            if (deck.visibility == View.VISIBLE) deck.setTelemetry(t)
            val now = currentMode()
            if (now != mode) {
                mode = now
                WallpaperLink.mode(this@LauncherActivity, now)
                showNodes()
            }
            handler.postDelayed(this, 1000L - System.currentTimeMillis() % 1000L)
        }
    }

    /** Unlocking the phone replays the boot sequence. */
    private val unlocked = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            home.boot()
        }
    }

    private val iconsLoaded = {
        home.invalidate()
        apps.iconsChanged()
    }

    private val packages = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = appsChanged()
        override fun onPackageAdded(packageName: String, user: UserHandle) = appsChanged()
        override fun onPackageChanged(packageName: String, user: UserHandle) = appsChanged()
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = appsChanged()
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = appsChanged()
    }

    /** The theme this screen was built in; another one picked in settings rebuilds it. */
    private var builtIn: Theme? = null
    private var ringing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Theme.load(this)
        builtIn = Theme.now
        window.setDecorFitsSystemWindows(false)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        val u = resources.displayMetrics.widthPixels / 412f
        Hud.u = u
        val root = FrameLayout(this)
        home = HomeView(this).also { it.host = this }
        effects = EffectsView(this, home)
        comms = CommsView(this, u).apply {
            visibility = View.GONE
            onClose = { closeComms() }
            onSettings = { startActivity(Intent(this@LauncherActivity, LauncherSettingsActivity::class.java)) }
            // Dragging the panel away unblurs the home screen in step with the finger.
            onProgress = { p -> blurAnim?.cancel(); setBlur(p) }
            onClosed = { backdrop(false) }
        }
        apps = AppsView(this, u).apply {
            visibility = View.GONE
            onClose = { closeApps() }
            onLaunch = { component ->
                AppCatalog.launch(this@LauncherActivity, component)
                closeApps()
            }
            onMenu = { component, view -> appListMenu(component, view) }
            onMenuAt = { component, x, y -> appListMenu(component, x, y) }
        }
        anchor = View(this)
        root.addView(home)
        root.addView(effects)
        // Jarvis's orb under the music bar: its own view, so it turns without redrawing the home screen.
        orb = JarvisOrb(this, u).apply {
            visibility = View.GONE
            onTap = { openJarvis(listen = true) }
            onHold = { openJarvis(listen = false) }
        }
        root.addView(orb, FrameLayout.LayoutParams(orb.size, orb.size))
        home.onJarvisSlot = { x, y, shown ->
            orb.translationX = x - orb.size / 2f
            orb.translationY = y - orb.size / 2f + 6 * u
            orb.visibility = if (shown) View.VISIBLE else View.GONE
        }
        root.addView(comms)
        root.addView(apps)
        hub = GameHubView(this, u).apply {
            visibility = View.GONE
            onClose = { closeGames() }
            onGameMenu = { component, x, y -> gameMenu(component, x, y) }
            onLaunch = { component ->
                AppCatalog.launch(this@LauncherActivity, component)
                closeGames()
            }
        }
        root.addView(hub)
        weather = WeatherView(this, u).apply {
            visibility = View.GONE
            onClose = { closeWeather() }
            onSamsung = { openSamsungWeather() }
        }
        root.addView(weather)
        deck = DeckView(this, u).apply {
            visibility = View.GONE
            onClose = { closeDeck() }
            // The deck's ⚙ opens the phone's own settings (Launcher settings stay in the home menu).
            onSettings = { safeStart(Intent(android.provider.Settings.ACTION_SETTINGS)) }
            onComms = { closeDeck(); handler.postDelayed({ openComms(null) }, 220) }
            onReply = { pkg -> closeDeck(); handler.postDelayed({ openComms(pkg) }, 220) }
            onWeather = { closeDeck(); handler.postDelayed({ openWeather() }, 220) }
            onWeatherDay = { day -> closeDeck(); handler.postDelayed({ showWeather(day) }, 220) }
            onLaunch = { component -> AppCatalog.launch(this@LauncherActivity, component) }
            onVoice = { askByVoice() }
            onJarvis = { openJarvis(listen = true) }
        }
        root.addView(deck)
        jarvis = JarvisView(this, u, jarvisHost).apply {
            visibility = View.GONE
            onClose = { closeJarvis() }
            onPermission = { asked -> requestPermissions(asked, JARVIS_REQUEST) }
        }
        root.addView(jarvis)
        root.addView(anchor, FrameLayout.LayoutParams(1, 1))
        setContentView(root)

        root.setOnApplyWindowInsetsListener { _, insets ->
            home.bottomInset = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            insets.displayCutout?.boundingRectTop?.takeIf { !it.isEmpty }?.let {
                home.setCamera(it.exactCenterX(), it.exactCenterY())
            }
            insets
        }
        window.insetsController?.apply {
            hide(WindowInsets.Type.statusBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        AppCatalog.addIconListener(iconsLoaded)
        getSystemService(LauncherApps::class.java)?.registerCallback(packages, handler)
        AppCatalog.load(this)
        LauncherStore.seedIfNeeded(this, AppCatalog.apps())
        RefreshJob.schedule(this)
        showNodes()
        loadGames()
        registerReceiver(unlocked, IntentFilter(Intent.ACTION_USER_PRESENT), RECEIVER_NOT_EXPORTED)
        home.boot()
        if (fromJarvisShortcut(intent)) handler.postDelayed({ openJarvis(listen = true) }, 400)
        else if (jarvisExtras(intent)) handler.postDelayed({ handleJarvis(intent) }, 400)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Samsung's panel brings its status bar back; hide it again once we're on top.
        if (hasFocus) {
            window.insetsController?.hide(WindowInsets.Type.statusBars())
            // Back on the home screen: bring the wallpaper up to date.
            WallpaperLink.theme(this, home.ringFraction)
            WallpaperLink.mode(this, mode)
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(unlocked)
        } catch (e: Exception) {
            // Not registered.
        }
        getSystemService(LauncherApps::class.java)?.unregisterCallback(packages)
        AppCatalog.removeIconListener(iconsLoaded)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // A new theme from settings: rebuild everything in its colours, icons too.
        Theme.load(this)
        if (builtIn != Theme.now) {
            AppCatalog.clearIcons()
            recreate()
            return
        }
        resumed = true
        settleOverlays()
        window.insetsController?.hide(WindowInsets.Type.statusBars())
        Comms.addListener(this)
        val prefs = getSharedPreferences("launcher", MODE_PRIVATE)
        home.bootEnabled = prefs.getBoolean(LauncherSettingsActivity.BOOT, true)
        ticks = 0
        lastTelemetry = null
        effects.running = effectsOn() && !overlayOpen()
        orb.running = effects.running
        onComms()
        showNodes()
        refreshJarvisAlerts()
        // "Hey Jarvis": the mic can only be taken up from the foreground, so it starts (or restarts) here.
        if (JarvisEar.wakeEnabled(this) && JarvisEar.modelReady(this)) JarvisEar.start(this)
        handler.removeCallbacks(tick)
        tick.run()
        refreshIfStale()
    }

    override fun onPause() {
        resumed = false
        Comms.removeListener(this)
        handler.removeCallbacks(tick)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Back from Jarvis's hands, or a heads-up's button.
        if (jarvisExtras(intent)) {
            handleJarvis(intent)
            return
        }
        // The Jarvis shortcut (side key, or its icon): straight to him, listening.
        if (fromJarvisShortcut(intent)) {
            if (jarvis.visibility != View.VISIBLE) openJarvis(listen = true) else jarvis.listen()
            return
        }
        // Home pressed while already home: close whatever's open.
        when {
            jarvis.visibility == View.VISIBLE -> closeJarvis()
            deck.visibility == View.VISIBLE -> closeDeck()
            weather.visibility == View.VISIBLE -> closeWeather()
            hub.visibility == View.VISIBLE -> closeGames()
            apps.visibility == View.VISIBLE -> closeApps()
            comms.visibility == View.VISIBLE -> closeComms()
            else -> home.closeNode()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            jarvis.visibility == View.VISIBLE -> if (!jarvis.back()) closeJarvis()
            deck.visibility == View.VISIBLE -> if (!deck.back()) closeDeck()
            weather.visibility == View.VISIBLE -> if (!weather.back()) closeWeather()
            hub.visibility == View.VISIBLE -> if (!hub.back()) closeGames()
            apps.visibility == View.VISIBLE -> closeApps()
            comms.visibility == View.VISIBLE -> closeComms()
            else -> home.closeNode()
        }
    }

    // --- Data ---

    private fun appsChanged() {
        AppCatalog.load(this)
        AppCatalog.clearIcons()
        showNodes()
        loadGames()
        // An uninstalled game leaves the Arcade straight away.
        if (hub.visibility == View.VISIBLE) hub.load()
    }

    /** Puts the saved nodes and dock on the ring, skipping apps that aren't installed. */
    private fun showNodes() {
        val installed = AppCatalog.apps().map { it.component }.toHashSet()
        val nodes = LauncherStore.nodes(this).map { n -> n.copy(apps = n.apps.filter { it in installed }) }
        val dock = LauncherStore.dock(this).filter { it in installed }
        val dim = if (mode == Mode.WORK && LauncherStore.workDimsGames(this)) setOf(LauncherStore.GAMES) else emptySet()
        home.setData(nodes, dock, mode, games, gameCount, LauncherStore.modeTop(this, mode), dim)
    }

    /** The games node shows the Arcade's recommended games: most played and last played first. */
    private fun loadGames() {
        val context = applicationContext
        Thread {
            val all = Arcade.games(context)
            val stats = Arcade.playStats(context, all)
            val picks = Arcade.recommended(all, stats, LauncherStore.MAX_APPS).map { it.component }
            handler.post {
                games = picks
                allGames = all.map { it.component }
                gameCount = all.size
                showNodes()
            }
        }.start()
    }

    private fun refreshIfStale() {
        val prefs = getSharedPreferences("launcher", MODE_PRIVATE)
        val last = prefs.getLong("last_refresh", 0)
        if (System.currentTimeMillis() - last < 15 * 60_000L) return
        prefs.edit().putLong("last_refresh", System.currentTimeMillis()).apply()
        val context = applicationContext
        Thread { RefreshJob.refresh(context) }.start()
    }

    override fun onComms() {
        val calls = Comms.calls()
        val groups = Comms.groups()
        home.setComms(groups, calls)
        if (::deck.isInitialized) deck.setComms(groups)
        // The wallpaper's reactor spins up while a call rings.
        val nowRinging = calls.any { Comms.ringing(it) }
        if (nowRinging != ringing && resumed) {
            ringing = nowRinging
            WallpaperLink.call(this, nowRinging, Hud.tint(0xFF3DDC84.toInt()))
        }
        comms.refresh()
    }

    override fun onIncoming(sbn: StatusBarNotification) {
        effects.wake()
        if (resumed) EdgeLight.withAccent(this, sbn.packageName) { WallpaperLink.pulse(this, it) }
        if (resumed && comms.visibility != View.VISIBLE && apps.visibility != View.VISIBLE) home.showIncoming(sbn)
    }

    /** Idle effects: on in settings, and off by themselves while Android's power saving is on. */
    private fun effectsOn(): Boolean = getSharedPreferences("launcher", MODE_PRIVATE).getBoolean(LauncherSettingsActivity.EFFECTS, true) &&
        getSystemService(android.os.PowerManager::class.java)?.isPowerSaveMode != true

    private var downX = 0f
    private var downY = 0f

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                effects.wake()
                downX = ev.rawX
                downY = ev.rawY
            }
            // A tap on the home screen ripples the wallpaper.
            android.view.MotionEvent.ACTION_UP -> if (!overlayOpen() && kotlin.math.hypot(ev.rawX - downX, ev.rawY - downY) < 12 * Hud.u) {
                WallpaperLink.tap(this, ev.rawX.toInt(), ev.rawY.toInt())
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun nodeOpened() = WallpaperLink.pulse(this, Hud.HOT)

    // --- Overlays ---

    private fun overlayOpen() = comms.visibility == View.VISIBLE || apps.visibility == View.VISIBLE ||
        hub.visibility == View.VISIBLE || weather.visibility == View.VISIBLE || deck.visibility == View.VISIBLE ||
        jarvis.visibility == View.VISIBLE

    /** Blurs the home screen behind an overlay, like Samsung's panel does. */
    private fun backdrop(on: Boolean) {
        // The blur eases in and out rather than snapping.
        blurAnim?.cancel()
        // Only clear the blur once nothing is open, so one overlay closing as another opens can't
        // leave the home screen sharp behind it, or blurred with nothing in front.
        val target = if (on || overlayOpen()) 1f else 0f
        blurAnim = android.animation.ValueAnimator.ofFloat(blurLevel, target).apply {
            duration = if (on) 260 else 220
            addUpdateListener { setBlur(it.animatedValue as Float) }
            start()
        }
        effects.running = target == 0f && effectsOn()
        orb.running = effects.running
    }

    /**
     * Coming back home: finish any overlay that was part-way closing when an app opened, and make sure
     * the home screen isn't left blurred or faded with nothing in front of it.
     */
    private fun settleOverlays() {
        for (v in listOf(deck, hub, weather)) {
            if (v.visibility == View.VISIBLE && v.alpha < 1f && v.animation == null) {
                v.animate().cancel()
                v.visibility = View.GONE
            }
        }
        if (!overlayOpen()) {
            blurAnim?.cancel()
            setBlur(0f)
        }
        home.alpha = 1f
    }

    private var blurAnim: android.animation.ValueAnimator? = null
    private var blurLevel = 0f

    /** Home screen blur behind the overlays, 0 (sharp) to 1 (fully blurred). */
    private fun setBlur(level: Float) {
        blurLevel = level.coerceIn(0f, 1f)
        val radius = BLUR * Hud.u * blurLevel
        home.setRenderEffect(if (radius < .5f) null else RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
    }

    /** The Command Deck slides in from the right. */
    override fun openDeck() {
        if (overlayOpen()) return
        backdrop(true)
        deck.visibility = View.VISIBLE
        deck.translationX = deck.width.toFloat().coerceAtLeast(1f)
        deck.alpha = 0f
        deck.animate().translationX(0f).alpha(1f).setDuration(300).setInterpolator(android.view.animation.DecelerateInterpolator(1.8f)).start()
        lastTelemetry?.let { deck.setTelemetry(it) }
        deck.load()
    }

    private fun closeDeck() {
        deck.dismissKeyboard()
        deck.animate().translationX(deck.width.toFloat()).alpha(0f).setDuration(220).setInterpolator(android.view.animation.AccelerateInterpolator(1.4f)).withEndAction {
            deck.visibility = View.GONE
            backdrop(false)
        }.start()
    }

    /** Speech for Ask Claude: Android's voice typing, dropped into the box. */
    private fun askByVoice() {
        val intent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Ask Claude")
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, VOICE_REQUEST)
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "Voice typing isn't available", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == DeckView.CALENDAR_REQUEST) deck.calendarAccessChanged()
        val mic = permissions.indexOf(android.Manifest.permission.RECORD_AUDIO).let { it >= 0 && grantResults.getOrNull(it) == PackageManager.PERMISSION_GRANTED }
        if (requestCode == JARVIS_REQUEST && mic) {
            // Allowed from the Hey Jarvis switch: start listening for the wake word; from the core: listen now.
            if (JarvisEar.wakeEnabled(this) && JarvisEar.modelReady(this)) JarvisEar.start(this)
            if (jarvis.visibility == View.VISIBLE && !jarvis.sheetOpen) jarvis.listen()
        }
    }

    // --- Jarvis ---

    private fun fromJarvisShortcut(intent: Intent?) = intent?.component?.className?.endsWith(".JarvisShortcut") == true

    private fun jarvisExtras(intent: Intent?) = intent != null &&
        (intent.hasExtra(JarvisHands.EXTRA_NOTE) || intent.hasExtra(JarvisProactive.EXTRA_SAY) || intent.hasExtra(JarvisProactive.EXTRA_INSIGHT) ||
            intent.hasExtra(JarvisEar.EXTRA_BACK))

    /** Jarvis reporting back from another app, or a notification's button: open him and carry on. */
    private fun handleJarvis(intent: Intent) {
        val note = intent.getStringExtra(JarvisHands.EXTRA_NOTE)
        val say = intent.getStringExtra(JarvisProactive.EXTRA_SAY)
        val insight = intent.getStringExtra(JarvisProactive.EXTRA_INSIGHT)
        intent.removeExtra(JarvisHands.EXTRA_NOTE)
        intent.removeExtra(JarvisProactive.EXTRA_SAY)
        intent.removeExtra(JarvisProactive.EXTRA_INSIGHT)
        val back = intent.getBooleanExtra(JarvisEar.EXTRA_BACK, false)
        intent.removeExtra(JarvisEar.EXTRA_BACK)
        if (say != null && insight != null) JarvisProactive.dismiss(this, insight)
        val open = jarvis.visibility == View.VISIBLE
        if (!open) {
            for (v in listOf(weather, hub, apps, comms)) if (v.visibility == View.VISIBLE) { v.animate().cancel(); v.visibility = View.GONE }
            backdrop(true)
        }
        when {
            note != null -> jarvis.note(note)
            say != null -> {
                if (!open) jarvis.show(listen = false, greet = false)
                jarvis.submit(say)
            }
            // Back from an app he was working: Jarvis as you left him, no hello.
            back -> if (!open) jarvis.show(listen = false, greet = false)
            !open -> jarvis.show(listen = false)
        }
    }

    /** The number on the home screen's Jarvis button. */
    private fun refreshJarvisAlerts() {
        Thread {
            val n = try { JarvisProactive.unseen(this) } catch (e: Exception) { 0 }
            runOnUiThread { orb.alerts = n }
        }.start()
    }

    /** Jarvis comes up over whatever's showing; over a briefing being read, he pauses it and listens. */
    override fun openJarvis(listen: Boolean) {
        if (jarvis.visibility == View.VISIBLE) return
        val midBriefing = deck.visibility == View.VISIBLE && deck.readingBriefing
        if (midBriefing) deck.stopBriefing()
        backdrop(true)
        jarvis.show(listen, greet = !midBriefing)
        if (!listen && !midBriefing) handler.postDelayed({ jarvis.type() }, 300)
        orb.alerts = 0
    }

    private fun closeJarvis() {
        jarvis.hide()
        refreshJarvisAlerts()
        handler.postDelayed({ backdrop(false) }, 200)
    }

    private val jarvisHost = object : Jarvis.Host {
        override fun briefing(control: JarvisParser.Control?, chapter: String?): Boolean {
            // Jarvis steps aside and the deck reads it, so you can see where it's up to.
            jarvis.hide(now = true)
            val act = {
                when (control) {
                    null -> deck.readBriefing()
                    JarvisParser.Control.STOP -> deck.stopBriefing()
                    JarvisParser.Control.RESUME -> if (!deck.resumeBriefing()) deck.readBriefing()
                    JarvisParser.Control.BACK -> deck.skipBriefing(-1)
                    JarvisParser.Control.SKIP -> deck.skipBriefing(1)
                    JarvisParser.Control.CHAPTER -> if (chapter == null || !deck.jumpBriefing(chapter)) deck.skipBriefing(1)
                }
            }
            if (deck.visibility == View.VISIBLE) {
                act()
            } else {
                closeOtherOverlays()
                openDeck()
                // Give the deck a moment to load today's agenda before reading it.
                handler.postDelayed({ if (deck.visibility == View.VISIBLE) act() }, 600)
            }
            return true
        }

        override fun launch(component: ComponentName) {
            jarvis.hide(now = true)
            backdrop(false)
            AppCatalog.launch(this@LauncherActivity, component)
        }

        override fun ask(permissions: Array<String>) = requestPermissions(permissions, JARVIS_REQUEST)

        override fun briefingActive() = deck.visibility == View.VISIBLE && (deck.readingBriefing || deck.briefingPaused)
    }

    /** Shuts the other panels at once, so the deck can open for the briefing. */
    private fun closeOtherOverlays() {
        for (v in listOf(weather, hub, apps, comms)) {
            v.animate().cancel()
            v.visibility = View.GONE
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VOICE_REQUEST && resultCode == RESULT_OK) {
            data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { deck.setQuestion(it) }
        }
    }

    override fun openGames() {
        if (overlayOpen()) return
        backdrop(true)
        hub.visibility = View.VISIBLE
        hub.translationX = -hub.width.toFloat().coerceAtLeast(1f)
        hub.alpha = 0f
        hub.animate().translationX(0f).alpha(1f).setDuration(260).setInterpolator(android.view.animation.DecelerateInterpolator(1.5f)).start()
        hub.reset()
        hub.load()
    }

    private fun closeGames() {
        hub.animate().translationX(-hub.width.toFloat()).alpha(0f).setDuration(200).withEndAction {
            hub.visibility = View.GONE
            backdrop(false)
        }.start()
    }

    override fun openComms(replyTo: String?) {
        if (apps.visibility == View.VISIBLE || hub.visibility == View.VISIBLE) return
        backdrop(true)
        comms.show(replyTo)
    }

    private fun closeComms() = comms.hide { backdrop(false) }

    override fun openApps(search: Boolean) {
        if (comms.visibility == View.VISIBLE || hub.visibility == View.VISIBLE) return
        val homes = HashMap<ComponentName, String>()
        LauncherStore.nodes(this).forEach { n -> n.apps.forEach { homes.putIfAbsent(it, n.name.uppercase()) } }
        LauncherStore.dock(this).forEach { homes.putIfAbsent(it, "DOCK") }
        backdrop(true)
        // Hive clusters: the dock in the middle, then each node in ring order.
        val groups = ArrayList<Pair<String, List<ComponentName>>>()
        groups += "DOCK" to LauncherStore.dock(this)
        LauncherStore.nodes(this).forEach { n -> groups += n.name.uppercase() to (if (n.games) allGames else n.apps) }
        apps.show(AppCatalog.apps(), homes, groups, search)
        // Search grows out of the core.
        if (search && apps.isAttachedToWindow) {
            val geometry = home.geometry()
            try {
                ViewAnimationUtils.createCircularReveal(apps, geometry.cx.toInt(), geometry.ringY.toInt(), 30 * geometry.u, window.decorView.height.toFloat())
                    .setDuration(380).start()
            } catch (e: Exception) {
                // Not laid out yet.
            }
        }
    }

    private fun closeApps() = apps.hide { backdrop(false) }

    // --- Menus ---

    private fun popup(x: Float, y: Float): PopupMenu {
        anchor.translationX = x
        anchor.translationY = y
        return PopupMenu(this, anchor)
    }

    override fun editNode(storedIndex: Int) {
        startActivity(Intent(this, NodeEditActivity::class.java).putExtra(NodeEditActivity.EXTRA_INDEX, storedIndex))
    }

    override fun appMenu(component: ComponentName, storedNode: Int, anchorX: Float, anchorY: Float) {
        val nodes = LauncherStore.nodes(this)
        val node = nodes.getOrNull(storedNode) ?: return
        val menu = popup(anchorX, anchorY)
        if (!node.games) {
            menu.menu.add("Remove from ${node.name}").setOnMenuItemClickListener {
                saveNodes(nodes.toMutableList().also { it[storedNode] = node.copy(apps = node.apps - component) })
                true
            }
            val move = menu.menu.addSubMenu("Move to…")
            nodes.forEachIndexed { i, other ->
                if (i != storedNode && !other.games) {
                    move.add(other.name).setOnMenuItemClickListener {
                        moveApp(component, storedNode, i)
                        true
                    }
                }
            }
        }
        menu.menu.add("Edit ${node.name}").setOnMenuItemClickListener { editNode(storedNode); true }
        addAppItems(menu, component)
        menu.show()
    }

    override fun dockMenu(component: ComponentName, anchorX: Float, anchorY: Float) {
        val menu = popup(anchorX, anchorY)
        menu.menu.add("Edit the dock").setOnMenuItemClickListener {
            startActivity(Intent(this, NodeEditActivity::class.java).putExtra(NodeEditActivity.EXTRA_DOCK, true))
            true
        }
        menu.menu.add("Remove from the dock").setOnMenuItemClickListener {
            LauncherStore.setDock(this, LauncherStore.dock(this) - component)
            showNodes()
            true
        }
        addAppItems(menu, component)
        menu.show()
    }

    /** Pick a mode by hand; it holds until the clock's next mode change. */
    override fun modeMenu(anchorX: Float, anchorY: Float) {
        val menu = popup(anchorX, anchorY)
        val manual = LauncherStore.modeOverride(this)
        menu.menu.add((if (manual == null) "● " else "○ ") + "AUTO · BY THE CLOCK").setOnMenuItemClickListener {
            LauncherStore.setModeOverride(this, null)
            tick.run()
            true
        }
        Mode.entries.forEach { m ->
            menu.menu.add((if (manual == m) "● " else "○ ") + m.label).setOnMenuItemClickListener {
                LauncherStore.setModeOverride(this, m)
                tick.run()
                true
            }
        }
        menu.show()
    }

    override fun homeMenu(anchorX: Float, anchorY: Float) {
        val menu = popup(anchorX, anchorY)
        menu.menu.add("New node").setOnMenuItemClickListener {
            if (LauncherStore.nodes(this).size >= LauncherStore.MAX_NODES) {
                Toast.makeText(this, "The ring holds ${LauncherStore.MAX_NODES} nodes", Toast.LENGTH_SHORT).show()
            } else {
                editNode(-1)
            }
            true
        }
        menu.menu.add("Edit the dock").setOnMenuItemClickListener {
            startActivity(Intent(this, NodeEditActivity::class.java).putExtra(NodeEditActivity.EXTRA_DOCK, true))
            true
        }
        menu.menu.add("Wallpaper").setOnMenuItemClickListener {
            safeStart(Intent(Intent.ACTION_SET_WALLPAPER))
            true
        }
        menu.menu.add("Launcher settings").setOnMenuItemClickListener {
            startActivity(Intent(this, LauncherSettingsActivity::class.java))
            true
        }
        menu.menu.add("Widget settings").setOnMenuItemClickListener {
            startActivity(Intent(this, MainActivity::class.java))
            true
        }
        menu.show()
    }

    /** A game in the Arcade: launch it, take it out of the Arcade, or its app info and uninstall. */
    private fun gameMenu(component: ComponentName, x: Float, y: Float) {
        val menu = popup(x, y)
        menu.menu.add("Launch").setOnMenuItemClickListener {
            AppCatalog.launch(this, component)
            closeGames()
            true
        }
        menu.menu.add("Remove from Arcade").setOnMenuItemClickListener {
            val pkg = component.packageName
            com.eldablos.crimson.Arcade.setIncluded(this, pkg, include = false, auto = pkg !in com.eldablos.crimson.Arcade.added(this))
            hub.load()
            loadGames()
            true
        }
        addAppItems(menu, component)
        menu.show()
    }

    private fun appListMenu(component: ComponentName, view: View) {
        val loc = IntArray(2)
        view.getLocationInWindow(loc)
        appListMenu(component, loc[0] + view.width * .7f, loc[1] + view.height / 2f)
    }

    private fun appListMenu(component: ComponentName, x: Float, y: Float) {
        val menu = popup(x, y)
        val nodes = LauncherStore.nodes(this)
        val add = menu.menu.addSubMenu("Add to node…")
        nodes.forEachIndexed { i, n ->
            if (!n.games && component !in n.apps) {
                add.add(n.name).setOnMenuItemClickListener {
                    if (n.apps.size >= LauncherStore.MAX_APPS) {
                        Toast.makeText(this, "${n.name} is full (${LauncherStore.MAX_APPS} apps)", Toast.LENGTH_SHORT).show()
                    } else {
                        saveNodes(nodes.toMutableList().also { it[i] = n.copy(apps = n.apps + component) })
                    }
                    true
                }
            }
        }
        addAppItems(menu, component)
        menu.show()
    }

    private fun addAppItems(menu: PopupMenu, component: ComponentName) {
        menu.menu.add("App info").setOnMenuItemClickListener {
            AppCatalog.appInfo(this, component)
            true
        }
        menu.menu.add("Uninstall").setOnMenuItemClickListener {
            safeStart(Intent(Intent.ACTION_DELETE, Uri.parse("package:${component.packageName}")))
            true
        }
    }

    private fun moveApp(component: ComponentName, from: Int, to: Int) {
        val nodes = LauncherStore.nodes(this).toMutableList()
        val target = nodes[to]
        if (target.apps.size >= LauncherStore.MAX_APPS) {
            Toast.makeText(this, "${target.name} is full (${LauncherStore.MAX_APPS} apps)", Toast.LENGTH_SHORT).show()
            return
        }
        nodes[from] = nodes[from].copy(apps = nodes[from].apps - component)
        nodes[to] = target.copy(apps = target.apps + component)
        saveNodes(nodes)
    }

    private fun saveNodes(nodes: List<Node>) {
        LauncherStore.setNodes(this, nodes)
        showNodes()
    }

    // --- Opening things ---

    override fun openQueue() {
        startActivity(Intent(this, QueueActivity::class.java))
    }

    /** Crimson Weather slides down over the home screen. */
    override fun openWeather() = showWeather(null)

    /** Crimson Weather, on [day]'s page when given. */
    private fun showWeather(day: java.time.LocalDate?) {
        if (overlayOpen()) return
        backdrop(true)
        weather.visibility = View.VISIBLE
        weather.alpha = 0f
        weather.translationY = -60 * Hud.u
        weather.animate().alpha(1f).translationY(0f).setDuration(240).setInterpolator(android.view.animation.DecelerateInterpolator(1.5f)).start()
        weather.load(day)
    }

    private fun closeWeather() {
        weather.animate().alpha(0f).translationY(60 * Hud.u).setDuration(200).withEndAction {
            weather.visibility = View.GONE
            backdrop(false)
        }.start()
    }

    /** Samsung Weather first; any other weather app only if it isn't there. */
    private fun openSamsungWeather() {
        val apps = AppCatalog.apps()
        val samsung = SAMSUNG_WEATHER.firstNotNullOfOrNull { pkg -> apps.firstOrNull { it.component.packageName == pkg } }
            ?: apps.firstOrNull { it.label.equals("Weather", true) && (it.component.packageName.startsWith("com.sec.") || it.component.packageName.startsWith("com.samsung.")) }
        if (samsung != null) {
            AppCatalog.launch(this, samsung.component)
        } else {
            startActivity(OpenAppActivity.intent(this, "Weather", CrimsonWidgetProvider.WEATHER_APPS))
        }
    }

    override fun openCalendar() {
        val uri = CalendarContract.CONTENT_URI.buildUpon().appendPath("time").appendPath(System.currentTimeMillis().toString()).build()
        safeStart(
            Intent(Intent.ACTION_VIEW, uri).setPackage(com.eldablos.crimson.CrimsonWidgetProvider.GOOGLE_CALENDAR),
            Intent(Intent.ACTION_VIEW, uri).setPackage("com.samsung.android.calendar"),
            Intent(Intent.ACTION_VIEW, uri),
        )
    }

    override fun openAlarms() {
        safeStart(
            Intent(AlarmClock.ACTION_SHOW_ALARMS).setPackage("com.sec.android.app.clockpackage"),
            Intent(AlarmClock.ACTION_SHOW_ALARMS),
        )
    }

    override fun openHealth() {
        packageManager.getLaunchIntentForPackage(Steps.SAMSUNG_HEALTH)?.let { safeStart(it) }
            ?: startActivity(Intent(this, MainActivity::class.java))
    }

    /** Starts the first of [intents] that opens. */
    private fun safeStart(vararg intents: Intent) {
        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (e: Exception) {
                // Try the next one.
            }
        }
        Toast.makeText(this, "Nothing on the phone opens that", Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val BLUR = 14f
        private const val VOICE_REQUEST = 41
        private const val JARVIS_REQUEST = 42
        private val SAMSUNG_WEATHER = listOf("com.sec.android.daemonapp", "com.samsung.android.weather")
    }
}
