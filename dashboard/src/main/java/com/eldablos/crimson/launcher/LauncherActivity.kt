package com.eldablos.crimson.launcher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.content.pm.LauncherApps
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
    private lateinit var anchor: View
    private val handler = Handler(Looper.getMainLooper())
    private var mode = Mode.at(LocalTime.now().hour)

    /** The mode picked by hand, or the one the clock says. */
    private fun currentMode() = LauncherStore.modeOverride(this) ?: Mode.at(LocalTime.now().hour)
    private var games: List<ComponentName> = emptyList()
    private var allGames: List<ComponentName> = emptyList()
    private var gameCount = 0
    private var resumed = false

    private val tick = object : Runnable {
        override fun run() {
            home.setTelemetry(Telemetry.read(this@LauncherActivity))
            val now = currentMode()
            if (now != mode) {
                mode = now
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        root.addView(comms)
        root.addView(apps)
        hub = GameHubView(this, u).apply {
            visibility = View.GONE
            onClose = { closeGames() }
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
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Samsung's panel brings its status bar back; hide it again once we're on top.
        if (hasFocus) window.insetsController?.hide(WindowInsets.Type.statusBars())
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
        resumed = true
        window.insetsController?.hide(WindowInsets.Type.statusBars())
        Comms.addListener(this)
        val prefs = getSharedPreferences("launcher", MODE_PRIVATE)
        home.bootEnabled = prefs.getBoolean(LauncherSettingsActivity.BOOT, true)
        effects.running = prefs.getBoolean(LauncherSettingsActivity.EFFECTS, true) && !overlayOpen()
        onComms()
        showNodes()
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
        // Home pressed while already home: close whatever's open.
        when {
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
            weather.visibility == View.VISIBLE -> closeWeather()
            hub.visibility == View.VISIBLE -> closeGames()
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
        home.setComms(Comms.groups(), Comms.calls())
        comms.refresh()
    }

    override fun onIncoming(sbn: StatusBarNotification) {
        if (resumed && comms.visibility != View.VISIBLE && apps.visibility != View.VISIBLE) home.showIncoming(sbn)
    }

    // --- Overlays ---

    private fun overlayOpen() = comms.visibility == View.VISIBLE || apps.visibility == View.VISIBLE ||
        hub.visibility == View.VISIBLE || weather.visibility == View.VISIBLE

    /** Blurs the home screen behind an overlay, like Samsung's panel does. */
    private fun backdrop(on: Boolean) {
        // The blur eases in and out rather than snapping.
        blurAnim?.cancel()
        blurAnim = android.animation.ValueAnimator.ofFloat(blurLevel, if (on) 1f else 0f).apply {
            duration = if (on) 260 else 220
            addUpdateListener { setBlur(it.animatedValue as Float) }
            start()
        }
        effects.running = !on && getSharedPreferences("launcher", MODE_PRIVATE).getBoolean(LauncherSettingsActivity.EFFECTS, true)
    }

    private var blurAnim: android.animation.ValueAnimator? = null
    private var blurLevel = 0f

    /** Home screen blur behind the overlays, 0 (sharp) to 1 (fully blurred). */
    private fun setBlur(level: Float) {
        blurLevel = level.coerceIn(0f, 1f)
        val radius = BLUR * Hud.u * blurLevel
        home.setRenderEffect(if (radius < .5f) null else RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
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
    override fun openWeather() {
        if (overlayOpen()) return
        backdrop(true)
        weather.visibility = View.VISIBLE
        weather.alpha = 0f
        weather.translationY = -60 * Hud.u
        weather.animate().alpha(1f).translationY(0f).setDuration(240).setInterpolator(android.view.animation.DecelerateInterpolator(1.5f)).start()
        weather.load()
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
        private val SAMSUNG_WEATHER = listOf("com.sec.android.daemonapp", "com.samsung.android.weather")
    }
}
